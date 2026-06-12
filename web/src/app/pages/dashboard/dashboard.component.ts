import { Component, inject, signal, computed, OnInit, OnDestroy } from '@angular/core';
import { RouterLink } from '@angular/router';
import QRCode from 'qrcode';
import { ConnectClients } from '../../core/connect/connect-clients';
import { toast } from '../../shared/page-utils';
import type { GetStatusResponse } from '../../../gen/bladewatch/v1/system_pb';

/**
 * Dashboard — stats / connect hub, parity with the native DashboardFragment.
 *
 * Layout (top to bottom):
 *   - Trip-stats hero ("This Week"): trip count, distance, drive time rolled
 *     up from TripsService.GetSummary, with a "View all trips" button to /trips.
 *   - Status chips: Tunnel / Services / Recording derived from
 *     SystemService.GetStatus.
 *   - Metric tiles: clickable cards that route to /recording, /diagnostics,
 *     /live, /vehicle.
 *   - Connect card: device ID (AuthService.GetAuthStatus.deviceId) + the
 *     current tunnel URL (GetStatus, if present) + a QR of the share URL.
 *   - Battery-capacity dialog: GetSohStatus + SetSohNominal.
 *
 * The live camera stream that used to live here has moved to the dedicated
 * /live page (app-live).
 *
 * RPC reality: AuthService only exposes Login/Logout/GetAuthStatus/
 * InvalidateAuthCache — there is NO web RPC to read the device secret access
 * code, set a password, or regenerate the token (by design — the web client
 * must never be able to read the device secret). So the Connect card shows
 * device ID + share URL + QR only, with a note that the access code / password
 * is managed on the device.
 */

interface WeekStats {
  tripCount: number;
  totalDistanceKm: number;
  totalDurationSeconds: number;
  /** True once at least one rollup row parsed (so we can tell "0 trips" from "no data"). */
  loaded: boolean;
}

type ChipState = 'on' | 'warn' | 'off';

interface SohStatus {
  displaySoh: number;
  displaySource: string;
  nominalCapacityKwh: number;
  nominalSource: string;
  loaded: boolean;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export default class DashboardComponent implements OnInit, OnDestroy {
  private readonly clients = inject(ConnectClients);

  // ---- status + week stats -------------------------------------------------
  readonly status = signal<GetStatusResponse | null>(null);
  readonly loading = signal(true);
  readonly week = signal<WeekStats>({
    tripCount: 0,
    totalDistanceKm: 0,
    totalDurationSeconds: 0,
    loaded: false,
  });

  // ---- connect card --------------------------------------------------------
  readonly deviceId = signal('');
  readonly tunnelUrl = signal('');
  readonly qrDataUrl = signal('');
  readonly statusMsg = signal('');

  // ---- status chips (derived from GetStatus) -------------------------------
  readonly tunnelChip = computed<ChipState>(() => (this.tunnelUrl() ? 'on' : 'off'));

  readonly servicesChip = computed<ChipState>(() => {
    const s = this.status();
    // The daemon is serving this very page, so services are never fully "Down"
    // from the web client's vantage point. Before the first status arrives, or
    // when the camera pipeline is idle, show "Partial"; "Up" when it's running.
    if (!s) return 'warn';
    return s.recordingStatus?.pipelineRunning ? 'on' : 'warn';
  });

  readonly recordingChip = computed<ChipState>(() =>
    this.status()?.recordingStatus?.isRecording ? 'on' : 'off',
  );

  // ---- week stats labels ---------------------------------------------------
  readonly weekHeadline = computed(() => {
    const w = this.week();
    if (!w.loaded) return 'This Week';
    if (w.tripCount === 0) return 'No trips yet this week';
    const distance =
      w.totalDistanceKm >= 1000
        ? `${Math.round(w.totalDistanceKm)} km`
        : `${w.totalDistanceKm.toFixed(1)} km`;
    const tripWord = w.tripCount === 1 ? 'trip' : 'trips';
    return `${w.tripCount} ${tripWord} · ${distance}`;
  });

  readonly tripsLabel = computed(() => (this.week().loaded ? String(this.week().tripCount) : '—'));

  readonly distanceLabel = computed(() => {
    const w = this.week();
    if (!w.loaded) return '—';
    return w.totalDistanceKm >= 1000
      ? `${Math.round(w.totalDistanceKm)} km`
      : `${w.totalDistanceKm.toFixed(1)} km`;
  });

  readonly driveTimeLabel = computed(() => {
    const w = this.week();
    if (!w.loaded) return '—';
    return this.formatDuration(w.totalDurationSeconds);
  });

  // ---- battery-capacity dialog ---------------------------------------------
  readonly batteryDialogOpen = signal(false);
  readonly sohSaving = signal(false);
  readonly batteryDialogMsg = signal('');
  readonly capacityInput = signal('');
  readonly soh = signal<SohStatus>({
    displaySoh: 0,
    displaySource: '',
    nominalCapacityKwh: 0,
    nominalSource: '',
    loaded: false,
  });

  readonly vehicleTileLabel = computed(() => {
    const s = this.soh();
    if (!s.loaded) return 'Pending';
    if (s.nominalCapacityKwh > 0) return `${s.nominalCapacityKwh.toFixed(1)} kWh`;
    return 'Tap to set';
  });

  readonly sohPercentLabel = computed(() => {
    const s = this.soh();
    if (!s.loaded || s.displaySoh <= 0) return 'Pending data';
    return `${s.displaySoh.toFixed(1)}%`;
  });

  private pollTimer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.refreshAll();
    this.loadAuth();
    this.refreshWeek();
    this.refreshSoh();
    // Poll status only — auth/week/soh change slowly and a fast poll adds noise.
    this.pollTimer = setInterval(() => this.refreshAll(), 5000);
  }

  ngOnDestroy(): void {
    clearInterval(this.pollTimer);
  }

  // ============================ Status ============================

  private async refreshAll(): Promise<void> {
    try {
      const resp = await this.clients.system.getStatus({});
      this.status.set(resp);
      // The /status payload may carry a network IP but no tunnel URL field;
      // only update the tunnel URL if GetStatus surfaces one we can trust.
      // GetStatusResponse has no dedicated tunnel-url field, so we keep the
      // tunnel URL sourced from auth/window origin (see loadAuth + QR note).
    } catch {
      /* keep last good status on a transient poll failure */
    } finally {
      this.loading.set(false);
    }
  }

  // ============================ Connect (auth) ============================

  /**
   * Device ID comes from AuthService.GetAuthStatus. There is no web RPC that
   * returns the tunnel URL (GetStatus has no tunnel-url field) and none that
   * returns the device secret. We therefore share the current window origin —
   * which is the reachable URL the user is currently viewing (LAN IP or tunnel
   * host) — and render a QR of it so a phone can scan to open the same UI.
   */
  private async loadAuth(): Promise<void> {
    try {
      const resp = await this.clients.auth.getAuthStatus({});
      this.deviceId.set(resp.deviceId || '');
    } catch {
      this.deviceId.set('');
    }
    // Share the origin the user is currently connected through. On a tunnel
    // host this is the tunnel URL; on LAN it is http://<car-ip>:8080.
    const shareUrl =
      typeof window !== 'undefined' && window.location?.origin ? window.location.origin : '';
    this.tunnelUrl.set(shareUrl);
    await this.renderQr(shareUrl);
  }

  private async renderQr(url: string): Promise<void> {
    if (!url) {
      this.qrDataUrl.set('');
      return;
    }
    try {
      const dataUrl = await QRCode.toDataURL(url, {
        margin: 1,
        width: 220,
        color: { dark: '#000000', light: '#ffffff' },
      });
      this.qrDataUrl.set(dataUrl);
    } catch {
      this.qrDataUrl.set('');
    }
  }

  async copyUrl(): Promise<void> {
    const url = this.tunnelUrl();
    if (!url) return;
    try {
      await navigator.clipboard.writeText(url);
      toast(this.statusMsg, 'Link copied');
    } catch {
      toast(this.statusMsg, 'Copy failed');
    }
  }

  async copyDeviceId(): Promise<void> {
    const id = this.deviceId();
    if (!id) return;
    try {
      await navigator.clipboard.writeText(id);
      toast(this.statusMsg, 'Device ID copied');
    } catch {
      toast(this.statusMsg, 'Copy failed');
    }
  }

  // ============================ Week stats ============================

  /**
   * Rolls up TripsService.GetSummary({days:7}) WeeklyRollup JSON blobs into one
   * "This Week" total. Mirrors the native dashboard + the Trips screen so the
   * numbers never disagree. Keys: tripCount, totalDistanceKm,
   * totalDurationSeconds.
   */
  private async refreshWeek(): Promise<void> {
    try {
      const resp = await this.clients.trips.getSummary({ days: 7 });
      const entries = resp.summary ?? [];
      let tripCount = 0;
      let totalDistanceKm = 0;
      let totalDurationSeconds = 0;
      let parsed = 0;
      for (const e of entries) {
        let r: Record<string, any>;
        try {
          r = JSON.parse(e.rollupJson) as Record<string, any>;
        } catch {
          continue;
        }
        parsed++;
        tripCount += Number(r['tripCount'] ?? 0);
        totalDistanceKm += Number(r['totalDistanceKm'] ?? 0);
        totalDurationSeconds += Number(r['totalDurationSeconds'] ?? 0);
      }
      this.week.set({
        tripCount,
        totalDistanceKm,
        totalDurationSeconds,
        loaded: parsed > 0 || resp.success,
      });
    } catch {
      this.week.set({ tripCount: 0, totalDistanceKm: 0, totalDurationSeconds: 0, loaded: false });
    }
  }

  // ============================ Battery dialog ============================

  openBatteryDialog(): void {
    this.batteryDialogMsg.set('');
    this.batteryDialogOpen.set(true);
    this.refreshSoh();
  }

  closeBatteryDialog(): void {
    this.batteryDialogOpen.set(false);
  }

  private async refreshSoh(): Promise<void> {
    try {
      const resp = await this.clients.system.getSohStatus({});
      const nominal = resp.nominalCapacityKwh ?? 0;
      this.soh.set({
        displaySoh: resp.displaySoh ?? 0,
        displaySource: resp.displaySource ?? '',
        nominalCapacityKwh: nominal,
        nominalSource: resp.nominalSource ?? '',
        loaded: true,
      });
      // Seed the input with the current nominal so editing starts from truth.
      if (nominal > 0 && !this.capacityInput()) {
        this.capacityInput.set(nominal.toFixed(1));
      }
    } catch {
      this.soh.set({
        displaySoh: 0,
        displaySource: '',
        nominalCapacityKwh: 0,
        nominalSource: '',
        loaded: true,
      });
    }
  }

  async saveCapacity(): Promise<void> {
    const kwh = Number.parseFloat(this.capacityInput());
    if (!Number.isFinite(kwh) || kwh < 8 || kwh > 120) {
      this.batteryDialogMsg.set('Enter a capacity between 8 and 120 kWh');
      return;
    }
    this.sohSaving.set(true);
    this.batteryDialogMsg.set('');
    try {
      const resp = await this.clients.system.setSohNominal({ nominalKwh: kwh });
      if (resp.success) {
        this.batteryDialogMsg.set('Capacity saved');
        await this.refreshSoh();
      } else {
        this.batteryDialogMsg.set(resp.error || 'Save failed');
      }
    } catch {
      this.batteryDialogMsg.set('Save failed');
    } finally {
      this.sohSaving.set(false);
    }
  }

  /** Clear the user override (SetSohNominal with nominalKwh unset). */
  async resetCapacity(): Promise<void> {
    this.sohSaving.set(true);
    this.batteryDialogMsg.set('');
    try {
      // Omit nominalKwh entirely to clear the override (proto: optional).
      const resp = await this.clients.system.setSohNominal({});
      if (resp.success) {
        this.capacityInput.set('');
        this.batteryDialogMsg.set('Capacity reset');
        await this.refreshSoh();
      } else {
        this.batteryDialogMsg.set(resp.error || 'Reset failed');
      }
    } catch {
      this.batteryDialogMsg.set('Reset failed');
    } finally {
      this.sohSaving.set(false);
    }
  }

  onCapacityInput(value: string): void {
    this.capacityInput.set(value);
  }

  // ============================ Helpers ============================

  private formatDuration(seconds: number): string {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return h > 0 ? `${h}h ${m}m` : `${m}m`;
  }

  chipText(state: ChipState, on: string, warn: string, off: string): string {
    if (state === 'on') return on;
    if (state === 'warn') return warn;
    return off;
  }
}
