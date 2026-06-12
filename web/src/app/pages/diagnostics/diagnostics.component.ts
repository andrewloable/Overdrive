import { Component, inject, signal, computed, OnInit, OnDestroy } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ConnectClients } from '../../core/connect/connect-clients';

/**
 * Diagnostics page — 1:1 parity with the native Android DiagnosticsFragment.
 *
 * HEALTH grid (2x2): Network, Storage, Camera, Battery.
 *   - Network : type/SSID + IP from SystemService.GetStatus().network, plus a
 *     tunnel state line (online/connecting/offline) inferred from the network
 *     bind/mode. (The native reads daemon tunnel URL; over the web client we
 *     surface the LAN/tunnel HTTP state available in NetworkInfo.)
 *   - Storage : clip count + bytes used + free, from StorageService.GetStorageSettings.
 *   - Camera  : daemon camera state + saved/probed camera id. Clickable -> Camera Probe.
 *   - Battery : SOH % + coloured dot, from SystemService.GetSohStatus. Clickable -> Battery Health.
 *
 * TOOLS grid: Camera probe, Battery health, Performance (route), Settings (route).
 * (No Traffic monitor and no ADB Console — ADB is excluded from the web build.)
 *
 * Camera Probe dialog: radio Auto + Camera 0-5, saves via
 *   SurveillanceService.SetConfig({ manualCameraId }) / ({ clearManualCameraId: true }),
 * matching the native onReconfigureCameraClicked() flow.
 *
 * Battery Health dialog: shows GetSohStatus fields and a Reset SOH button
 *   (SystemService.ResetSoh).
 */

type DotState = 'online' | 'starting' | 'offline' | 'neutral';

interface NetworkTile {
  topLine: string;
  ip: string;
  tunnelLabel: string;
  tunnelDot: DotState;
}

interface StorageTile {
  usedLine: string;
  freeLine: string;
}

interface CameraTile {
  value: string;
  dot: DotState;
  /** Saved/probed camera id, -1 when unknown/auto. */
  cameraId: number;
}

interface BatteryTile {
  value: string;
  dot: DotState;
}

interface SohStatus {
  displaySoh: number;
  displaySource: string;
  nominalCapacityKwh: number;
  nominalSource: string;
  loaded: boolean;
}

@Component({
  selector: 'app-diagnostics',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './diagnostics.component.html',
  styleUrl: './diagnostics.component.scss',
})
export default class DiagnosticsComponent implements OnInit, OnDestroy {
  private readonly clients = inject(ConnectClients);

  readonly loading = signal(true);

  readonly network = signal<NetworkTile>({
    topLine: 'Offline',
    ip: '',
    tunnelLabel: 'Tunnel · Offline',
    tunnelDot: 'offline',
  });
  readonly storage = signal<StorageTile>({ usedLine: '—', freeLine: '—' });
  readonly camera = signal<CameraTile>({ value: 'Pending', dot: 'neutral', cameraId: -1 });
  readonly battery = signal<BatteryTile>({ value: 'Pending data', dot: 'neutral' });

  // ===== Camera Probe dialog =====
  readonly cameraDialogOpen = signal(false);
  readonly cameraSaving = signal(false);
  readonly cameraDialogMsg = signal('');
  /** -1 = Auto, 0..5 = manual camera id. */
  readonly cameraSelection = signal(-1);
  readonly cameraOptions = [0, 1, 2, 3, 4, 5];

  // ===== Battery Health dialog =====
  readonly batteryDialogOpen = signal(false);
  readonly sohResetting = signal(false);
  readonly batteryDialogMsg = signal('');
  readonly soh = signal<SohStatus>({
    displaySoh: 0,
    displaySource: '',
    nominalCapacityKwh: 0,
    nominalSource: '',
    loaded: false,
  });

  readonly sohPercentLabel = computed(() => {
    const s = this.soh();
    if (!s.loaded || s.displaySoh <= 0) return 'Pending data';
    return `${s.displaySoh.toFixed(1)}%`;
  });

  private pollTimer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.poll();
    this.pollTimer = setInterval(() => this.poll(), 5000);
  }

  ngOnDestroy(): void {
    clearInterval(this.pollTimer);
  }

  // ============================ Polling ============================

  private async poll(): Promise<void> {
    await Promise.all([this.refreshStatus(), this.refreshStorage(), this.refreshSoh()]);
    this.loading.set(false);
  }

  /** Network + Camera tiles come from SystemService.GetStatus. */
  private async refreshStatus(): Promise<void> {
    try {
      const resp = await this.clients.system.getStatus({});

      // ----- Network tile -----
      const net = resp.network;
      const type = (net?.type ?? '').trim();
      const ssid = (net?.ssid ?? '').trim();
      const ip = (net?.ip ?? '').trim();

      // The connection itself is derived from the client's own reachability
      // (this page loaded through the daemon), NOT from the device network block
      // (which is often empty over the web client). getStatus succeeding here
      // means the daemon is reachable, so the connection is online.
      const conn = this.connectionState();
      let topLine: string;
      if (ssid && ssid !== '<unknown ssid>' && ssid !== '0x') {
        topLine = ssid;
      } else if (type && type.toLowerCase() !== 'none' && type.toLowerCase() !== 'offline') {
        topLine = this.titleCase(type);
      } else {
        topLine = conn.network;
      }

      this.network.set({ topLine, ip, tunnelLabel: conn.label, tunnelDot: conn.dot });

      // ----- Camera tile -----
      // Active = recording or view-only camera ids; available = device camera ids.
      const active = resp.active ?? [];
      const available = resp.available ?? [];
      if (active.length > 0) {
        const id = active[0];
        this.camera.set({ value: `Camera ${id}`, dot: 'online', cameraId: id });
      } else if (available.length > 0) {
        this.camera.set({ value: 'Probing…', dot: 'starting', cameraId: -1 });
      } else {
        this.camera.set({ value: 'Offline', dot: 'offline', cameraId: -1 });
      }
    } catch {
      // The page is loaded via this connection, so it is reachable; only the
      // device status query failed. Keep the connection state honest and mark
      // the device details as unknown rather than claiming "Offline".
      const conn = this.connectionState();
      this.network.set({ topLine: conn.network, ip: '', tunnelLabel: conn.label, tunnelDot: conn.dot });
      this.camera.set({ value: 'Unknown', dot: 'neutral', cameraId: -1 });
    }
  }

  /**
   * Connection state inferred from the URL the client actually reached the
   * daemon through — reliable regardless of what the device network block says.
   */
  private connectionState(): { label: string; dot: DotState; network: string } {
    const host = (typeof window !== 'undefined' && window.location?.hostname) || '';
    const isLocal = host === 'localhost' || host === '127.0.0.1' || host === '';
    const isLan = /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host);
    if (isLocal) return { label: 'Local · Online', dot: 'online', network: 'Local' };
    if (isLan) return { label: 'LAN · Online', dot: 'online', network: host };
    return { label: 'Tunnel · Online', dot: 'online', network: 'Connected' };
  }

  /** Storage tile from StorageService.GetStorageSettings (clips + used + free). */
  private async refreshStorage(): Promise<void> {
    try {
      const resp = await this.clients.storage.getStorageSettings({});
      const clipCount = (resp.recordingsCount ?? 0) + (resp.surveillanceCount ?? 0);
      const usedBytes = Number(resp.recordingsSizeBytes ?? 0n) + Number(resp.surveillanceSizeBytes ?? 0n);
      const freeFormatted =
        (resp.internalFreeFormatted ?? '').trim() || this.humanBytes(Number(resp.internalFreeBytes ?? 0n));

      this.storage.set({
        usedLine: `${clipCount} clips · ${this.humanBytes(usedBytes)}`,
        freeLine: `${freeFormatted} free`,
      });
    } catch {
      this.storage.set({ usedLine: '—', freeLine: '—' });
    }
  }

  /** Battery tile + dialog data from SystemService.GetSohStatus. */
  private async refreshSoh(): Promise<void> {
    try {
      const resp = await this.clients.system.getSohStatus({});
      const displaySoh = resp.displaySoh ?? 0;
      const displaySource = resp.displaySource ?? '';
      const inBand = resp.success && displaySoh >= 60 && displaySoh <= 110;

      this.soh.set({
        displaySoh: inBand ? displaySoh : 0,
        displaySource,
        nominalCapacityKwh: resp.nominalCapacityKwh ?? 0,
        nominalSource: resp.nominalSource ?? '',
        loaded: true,
      });

      if (!inBand) {
        this.battery.set({ value: 'Pending data', dot: 'neutral' });
      } else {
        let dot: DotState;
        if (displaySource === 'nominal') dot = 'neutral';
        else if (displaySoh >= 80) dot = 'online';
        else if (displaySoh >= 50) dot = 'starting';
        else dot = 'offline';
        this.battery.set({ value: `SOH ${displaySoh.toFixed(1)}%`, dot });
      }
    } catch {
      this.soh.set({
        displaySoh: 0,
        displaySource: '',
        nominalCapacityKwh: 0,
        nominalSource: '',
        loaded: true,
      });
      this.battery.set({ value: 'Pending data', dot: 'neutral' });
    }
  }

  // ===================== Camera Probe dialog =====================

  openCameraDialog(): void {
    // Seed selection from the current camera tile (-1 = Auto).
    this.cameraSelection.set(this.camera().cameraId >= 0 ? this.camera().cameraId : -1);
    this.cameraDialogMsg.set('');
    this.cameraDialogOpen.set(true);
  }

  closeCameraDialog(): void {
    this.cameraDialogOpen.set(false);
  }

  selectCamera(id: number): void {
    this.cameraSelection.set(id);
  }

  async saveCamera(): Promise<void> {
    this.cameraSaving.set(true);
    this.cameraDialogMsg.set('');
    try {
      const sel = this.cameraSelection();
      if (sel < 0) {
        // Auto: clear the manual pin and force re-discovery.
        await this.clients.surveillance.setConfig({ clearManualCameraId: true });
      } else {
        // Manual: pin the probe to camera id 0-5.
        await this.clients.surveillance.setConfig({ manualCameraId: sel });
      }
      this.cameraDialogMsg.set('Saved · applies on next camera restart');
      this.refreshStatus();
    } catch {
      this.cameraDialogMsg.set('Failed to save camera selection');
    } finally {
      this.cameraSaving.set(false);
    }
  }

  // ===================== Battery Health dialog =====================

  openBatteryDialog(): void {
    this.batteryDialogMsg.set('');
    this.batteryDialogOpen.set(true);
    this.refreshSoh();
  }

  closeBatteryDialog(): void {
    this.batteryDialogOpen.set(false);
  }

  async resetSoh(): Promise<void> {
    this.sohResetting.set(true);
    this.batteryDialogMsg.set('');
    try {
      const resp = await this.clients.system.resetSoh({});
      if (resp.success) {
        this.batteryDialogMsg.set('State of Health reset');
        this.refreshSoh();
      } else {
        this.batteryDialogMsg.set(resp.error || 'Reset failed');
      }
    } catch {
      this.batteryDialogMsg.set('Reset failed');
    } finally {
      this.sohResetting.set(false);
    }
  }

  // ============================ Helpers ============================

  private titleCase(s: string): string {
    return s.length === 0 ? s : s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
  }

  private humanBytes(bytes: number): string {
    if (!bytes || bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let v = bytes;
    let i = 0;
    while (v >= 1024 && i < units.length - 1) {
      v /= 1024;
      i++;
    }
    const decimals = v >= 100 || i === 0 ? 0 : 1;
    return `${v.toFixed(decimals)} ${units[i]}`;
  }
}
