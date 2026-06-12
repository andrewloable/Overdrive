import { Component, inject, signal, computed, OnInit } from '@angular/core';
import { TitleCasePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateService } from '@ngx-translate/core';
import { ConnectClients } from '../../core/connect/connect-clients';
import type { CheckUpdateResponse } from '../../../gen/bladewatch/v1/update_pb';
import type { GetStorageSettingsResponse } from '../../../gen/bladewatch/v1/storage_pb';
import type { GetStatusResponse } from '../../../gen/bladewatch/v1/system_pb';
import type { SafeZone } from '../../../gen/bladewatch/v1/safe_locations_pb';
import type { PreviewCleanupResponse } from '../../../gen/bladewatch/v1/storage_pb';

type SectionId = 'appearance' | 'recording' | 'surveillance' | 'overlay' | 'daemons' | 'privacy';
type ThemeMode = 'auto' | 'light' | 'dark';
type RecordingTab = 'capture' | 'quality' | 'storage';

interface RecordingModeOption {
  value: string;
  label: string;
  description: string;
}

interface DaemonStatusRow {
  label: string;
  running: boolean;
  detail: string;
}

/**
 * Settings shell — parity with native SettingsFragment's two-pane sub-rail.
 * A section list (Appearance, Recording, Surveillance, Status overlay, Daemons,
 * Privacy & data) swaps the detail pane. Every section maps to its native
 * counterpart; sections without a backing RPC render a clearly-noted,
 * device-only state instead of inventing a fake control.
 */
@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [FormsModule, TitleCasePipe],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.scss',
})
export default class SettingsComponent implements OnInit {
  private readonly clients = inject(ConnectClients);
  private readonly translate = inject(TranslateService);

  readonly sections: { id: SectionId; label: string; ready: boolean }[] = [
    { id: 'appearance', label: 'Appearance', ready: true },
    { id: 'recording', label: 'Recording', ready: true },
    { id: 'surveillance', label: 'Surveillance', ready: true },
    { id: 'overlay', label: 'Status overlay', ready: true },
    { id: 'daemons', label: 'Daemons', ready: true },
    { id: 'privacy', label: 'Privacy & data', ready: true },
  ];
  readonly active = signal<SectionId>('appearance');

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly statusMsg = signal('');

  // Appearance
  readonly theme = signal<ThemeMode>('auto');
  readonly driveSide = signal<'left' | 'right' | 'auto'>('auto');

  // Recording
  readonly recordingTab = signal<RecordingTab>('capture');
  readonly recordingQuality = signal('STANDARD');
  readonly codec = signal('H264');
  // Mirrors native RecordingMode (NONE / CONTINUOUS / DRIVE_MODE / PROXIMITY_GUARD).
  readonly recordingMode = signal('NONE');
  readonly recordingModes: RecordingModeOption[] = [
    { value: 'NONE', label: 'None (Default)', description: 'No recording — surveillance still works' },
    { value: 'CONTINUOUS', label: 'Continuous', description: 'Record all the time while driving' },
    { value: 'DRIVE_MODE', label: 'Drive Mode', description: 'Record only when the vehicle is moving' },
    { value: 'PROXIMITY_GUARD', label: 'Proximity Guard', description: 'Record when motion is detected' },
  ];
  // Per-file segment length (segment rotation), mirrors native RecordingLimit.
  readonly recordingLimit = signal(5);
  readonly recordingLimits = [1, 5, 10];

  // Recording storage (shared GetStorageSettings payload).
  readonly storage = signal<GetStorageSettingsResponse | null>(null);
  readonly recStorageType = signal<'INTERNAL' | 'SD_CARD'>('INTERNAL');
  readonly recLimitMb = signal(500);
  readonly formatBusy = signal(false);
  readonly formatConfirm = signal(false);

  // Language
  readonly lang = signal('en');
  readonly supportedLangs = signal<{ code: string; label: string }[]>([]);

  // Updates
  readonly updateInfo = signal<CheckUpdateResponse | null>(null);
  readonly checkingUpdate = signal(false);
  readonly installing = signal(false);

  // Surveillance
  readonly survSensitivity = signal(3);
  readonly survPreset = signal('OUTDOOR'); // distance_preset field (OUTDOOR/GARAGE/STREET/CUSTOM)
  readonly survDetectPerson = signal(true);
  readonly survDetectCar = signal(true);
  readonly survDetectBike = signal(true);
  readonly survAiEnabled = signal(true);
  readonly survNightMode = signal(false);
  readonly survDeterrent = signal('silent'); // silent / horn / flash
  readonly survPreRecord = signal(5);
  readonly survPostRecord = signal(10);
  readonly survCameraFront = signal(true);
  readonly survCameraRight = signal(true);
  readonly survCameraRear = signal(true);
  readonly survCameraLeft = signal(true);
  readonly survPresets = ['OUTDOOR', 'GARAGE', 'STREET', 'CUSTOM'];
  readonly survDeterrents = ['silent', 'horn', 'flash'];
  readonly survPreRecordOptions = [2, 5, 10, 15];
  readonly survPostRecordOptions = [5, 10, 15, 20, 30];
  readonly survSensitivityLevels = [1, 2, 3, 4, 5];

  // Safe zones
  readonly safeZones = signal<readonly SafeZone[]>([]);
  readonly safeLocationsEnabled = signal(false);

  // Overlay (device-only — no web RPC; flags live in UnifiedConfigManager on device).
  readonly overlayCamera = signal(true);
  readonly overlayTrip = signal(true);

  // Daemons (read-only, derived from system.getStatus).
  readonly systemStatus = signal<GetStatusResponse | null>(null);
  readonly daemonRows = computed<DaemonStatusRow[]>(() => {
    const s = this.systemStatus();
    if (!s) return [];
    const rec = s.recordingStatus;
    const trip = s.tripStatus;
    const net = s.network;
    const cameraRunning = rec?.pipelineRunning ?? false;
    return [
      {
        label: 'Camera Daemon',
        running: cameraRunning,
        detail: cameraRunning
          ? (rec?.isRecording ? `Recording (${rec?.configuredMode || 'NONE'})` : 'Pipeline running')
          : 'Not running',
      },
      {
        label: 'Surveillance Pipeline',
        running: s.gpuSurveillance ?? false,
        detail: s.gpuSurveillance ? 'GPU sentry active' : 'Idle',
      },
      {
        label: 'Trip Analytics',
        running: trip?.tripActive ?? false,
        detail: trip?.enabled
          ? (trip?.tripActive ? 'Trip in progress' : 'Enabled — no active trip')
          : 'Disabled',
      },
      {
        label: 'HTTP API Server',
        running: true,
        detail: net?.lanHttpEnabled
          ? `LAN HTTP on ${net?.httpBind || 'unknown'}`
          : `Loopback only (${net?.httpBind || '127.0.0.1:8080'})`,
      },
    ];
  });

  // Privacy
  readonly cleanupPreview = signal<PreviewCleanupResponse | null>(null);
  readonly cleanupBusy = signal(false);
  readonly cleanupConfirm = signal(false);

  ngOnInit(): void {
    this.theme.set((localStorage.getItem('bw_theme') as ThemeMode) || 'auto');
    this.driveSide.set((localStorage.getItem('bw_drive_side') as any) || 'auto');
    this.overlayCamera.set(localStorage.getItem('bw_overlay_camera') !== 'false');
    this.overlayTrip.set(localStorage.getItem('bw_overlay_trip') !== 'false');
    this.loadSettings();
  }

  select(id: SectionId): void {
    this.active.set(id);
    this.statusMsg.set('');
    this.formatConfirm.set(false);
    this.cleanupConfirm.set(false);
    if (id === 'surveillance' && this.safeZones().length === 0) this.loadSurveillance();
    if (id === 'daemons') this.loadStatus();
    if (id === 'privacy' && !this.storage()) this.loadStorage();
    if (id === 'recording' && !this.storage()) this.loadStorage();
  }

  setRecordingTab(tab: RecordingTab): void {
    this.recordingTab.set(tab);
    this.statusMsg.set('');
    this.formatConfirm.set(false);
    if (tab === 'storage' && !this.storage()) this.loadStorage();
  }

  private async loadSettings(): Promise<void> {
    try {
      const [quality, locale] = await Promise.all([
        this.clients.settings.getQuality({}),
        this.clients.settings.getLocale({}),
      ]);
      this.recordingQuality.set(quality.recordingQuality || 'STANDARD');
      this.codec.set(quality.codec || 'H264');
      if (quality.recordingSegmentMinutes) this.recordingLimit.set(quality.recordingSegmentMinutes);
      this.lang.set(locale.lang || 'en');
      const supported = locale.supported ?? {};
      this.supportedLangs.set(
        Object.entries(supported).map(([code, label]) => ({ code, label: String(label) })),
      );
    } catch {
      this.statusMsg.set('Failed to load settings');
    } finally {
      this.loading.set(false);
    }
    // Pull current recording mode from the daemon status alongside settings.
    this.loadStatus();
    this.loadSurveillance();
  }

  // ---- Appearance ----
  setTheme(mode: ThemeMode): void {
    this.theme.set(mode);
    localStorage.setItem('bw_theme', mode);
    this.applyTheme(mode);
  }

  private applyTheme(mode: ThemeMode): void {
    const resolved =
      mode === 'auto'
        ? (window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark')
        : mode;
    document.documentElement.setAttribute('data-theme', resolved);
  }

  setDriveSide(side: 'left' | 'right' | 'auto'): void {
    this.driveSide.set(side);
    localStorage.setItem('bw_drive_side', side);
    this.statusMsg.set('Navigation side updated');
  }

  async saveLang(): Promise<void> {
    this.saving.set(true);
    try {
      await this.clients.settings.setLocale({ lang: this.lang() });
      this.translate.use(this.lang());
      localStorage.setItem('bw_lang', this.lang());
      this.statusMsg.set('Language updated');
    } catch {
      this.statusMsg.set('Failed to save language');
    } finally {
      this.saving.set(false);
    }
  }

  // ---- Recording: Capture ----
  async saveCapture(): Promise<void> {
    this.saving.set(true);
    this.statusMsg.set('');
    try {
      await this.clients.settings.setRecordingMode({ mode: this.recordingMode() });
      // Segment length rides on the quality endpoint (recording_segment_minutes).
      await this.clients.settings.setQuality({ recordingSegmentMinutes: this.recordingLimit() });
      this.statusMsg.set('Recording mode saved');
    } catch {
      this.statusMsg.set('Failed to save recording mode');
    } finally {
      this.saving.set(false);
    }
  }

  // ---- Recording: Quality ----
  async saveQuality(): Promise<void> {
    this.saving.set(true);
    this.statusMsg.set('');
    try {
      await this.clients.settings.setQuality({
        recordingQuality: this.recordingQuality(),
        codec: this.codec(),
      });
      this.statusMsg.set('Quality settings saved');
    } catch {
      this.statusMsg.set('Failed to save quality settings');
    } finally {
      this.saving.set(false);
    }
  }

  // ---- Recording / Privacy: Storage ----
  private async loadStorage(): Promise<void> {
    try {
      const resp = await this.clients.storage.getStorageSettings({});
      this.storage.set(resp);
      this.recStorageType.set((resp.recordingsStorageType as any) === 'SD_CARD' ? 'SD_CARD' : 'INTERNAL');
      this.recLimitMb.set(Number(resp.recordingsLimitMb) || 500);
    } catch {
      this.statusMsg.set('Failed to load storage info');
    }
  }

  setRecStorageType(type: 'INTERNAL' | 'SD_CARD'): void {
    if (type === 'SD_CARD' && !(this.storage()?.sdCardAvailable)) return;
    this.recStorageType.set(type);
  }

  recLimitMin = (): number => Math.max(100, Number(this.storage()?.minLimitMb ?? 100n));
  recLimitMax = (): number => {
    const s = this.storage();
    if (!s) return 100000;
    const totalBytes = this.recStorageType() === 'SD_CARD' ? Number(s.sdCardTotalBytes) : Number(s.internalTotalBytes);
    const totalMb = totalBytes > 0 ? Math.floor(totalBytes / (1024 * 1024)) : 0;
    const daemonMax = this.recStorageType() === 'SD_CARD' ? Number(s.maxLimitMbSdCard) : Number(s.maxLimitMb);
    return Math.max(totalMb > 0 ? totalMb : daemonMax, this.recLimitMin() + 100);
  };

  formatMb(mb: number): string {
    return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb} MB`;
  }

  async saveStorage(): Promise<void> {
    this.saving.set(true);
    this.statusMsg.set('');
    try {
      await this.clients.storage.setStorageSettings({
        recordingsStorageType: this.recStorageType(),
        recordingsLimitMb: BigInt(this.recLimitMb()),
      });
      this.statusMsg.set('Storage settings saved');
      await this.loadStorage();
    } catch {
      this.statusMsg.set('Failed to save storage settings');
    } finally {
      this.saving.set(false);
    }
  }

  async formatVolume(): Promise<void> {
    if (!this.formatConfirm()) {
      this.formatConfirm.set(true);
      return;
    }
    this.formatConfirm.set(false);
    this.formatBusy.set(true);
    this.statusMsg.set('');
    try {
      const list = await this.clients.storage.listFormatVolumes({});
      const first = list.volumes?.[0];
      if (!first) {
        this.statusMsg.set('No removable drive found');
        return;
      }
      const res = await this.clients.storage.formatVolume({ volumeId: first.volumeId });
      this.statusMsg.set(res.success ? 'Drive formatted' : `Format failed: ${res.error || res.message}`);
      await this.loadStorage();
    } catch {
      this.statusMsg.set('Format failed');
    } finally {
      this.formatBusy.set(false);
    }
  }

  async syncStorageCatalog(): Promise<void> {
    this.saving.set(true);
    this.statusMsg.set('');
    try {
      // Storage has no catalog RPC; the recordings sync lives on RecordingsService.
      await this.clients.recordings.syncCatalog({});
      this.statusMsg.set('Catalog synced');
    } catch {
      this.statusMsg.set('Sync failed');
    } finally {
      this.saving.set(false);
    }
  }

  // ---- Surveillance ----
  private async loadSurveillance(): Promise<void> {
    try {
      const [config, zones] = await Promise.all([
        this.clients.surveillance.getConfig({}),
        this.clients.safeLocations.listZones({}),
      ]);
      const cfg = config.config;
      if (cfg) {
        this.survSensitivity.set(cfg.sensitivity || 3);
        this.survPreset.set(cfg.distancePreset || 'OUTDOOR');
        this.survDetectPerson.set(cfg.detectPerson ?? true);
        this.survDetectCar.set(cfg.detectCar ?? true);
        this.survDetectBike.set(cfg.detectBike ?? true);
        this.survAiEnabled.set(cfg.aiEnabled ?? true);
        this.survNightMode.set(cfg.nightMode ?? false);
        this.survDeterrent.set(cfg.deterrentAction || 'silent');
        this.survPreRecord.set(cfg.preRecordSeconds || 5);
        this.survPostRecord.set(cfg.postRecordSeconds || 10);
        this.survCameraFront.set(cfg.cameraFront ?? true);
        this.survCameraRight.set(cfg.cameraRight ?? true);
        this.survCameraRear.set(cfg.cameraRear ?? true);
        this.survCameraLeft.set(cfg.cameraLeft ?? true);
      }
      this.safeZones.set(zones.zones ?? []);
      this.safeLocationsEnabled.set(zones.featureEnabled ?? false);
    } catch {
      this.statusMsg.set('Failed to load surveillance config');
    }
  }

  async saveSurveillance(): Promise<void> {
    this.saving.set(true);
    this.statusMsg.set('');
    try {
      await this.clients.surveillance.setConfig({
        config: {
          sensitivity: this.survSensitivity(),
          distancePreset: this.survPreset(),
          detectPerson: this.survDetectPerson(),
          detectCar: this.survDetectCar(),
          detectBike: this.survDetectBike(),
          aiEnabled: this.survAiEnabled(),
          nightMode: this.survNightMode(),
          deterrentAction: this.survDeterrent(),
          preRecordSeconds: this.survPreRecord(),
          postRecordSeconds: this.survPostRecord(),
          cameraFront: this.survCameraFront(),
          cameraRight: this.survCameraRight(),
          cameraRear: this.survCameraRear(),
          cameraLeft: this.survCameraLeft(),
        } as any,
      });
      this.statusMsg.set('Surveillance settings saved');
    } catch {
      this.statusMsg.set('Failed to save surveillance settings');
    } finally {
      this.saving.set(false);
    }
  }

  async toggleSafeLocations(): Promise<void> {
    const enabled = !this.safeLocationsEnabled();
    try {
      await this.clients.safeLocations.toggle({ enabled, enabledSet: true });
      this.safeLocationsEnabled.set(enabled);
    } catch {
      this.statusMsg.set('Failed to toggle safe locations');
    }
  }

  async deleteZone(id: string): Promise<void> {
    if (!confirm('Delete this safe zone?')) return;
    try {
      await this.clients.safeLocations.deleteZone({ id });
      this.safeZones.update(z => z.filter(zone => zone.id !== id));
    } catch {
      this.statusMsg.set('Failed to delete zone');
    }
  }

  // ---- Overlay (device-only, no RPC) ----
  setOverlayCamera(on: boolean): void {
    this.overlayCamera.set(on);
    localStorage.setItem('bw_overlay_camera', String(on));
  }

  setOverlayTrip(on: boolean): void {
    this.overlayTrip.set(on);
    localStorage.setItem('bw_overlay_trip', String(on));
  }

  // ---- Daemons (read-only) ----
  private async loadStatus(): Promise<void> {
    try {
      const resp = await this.clients.system.getStatus({});
      this.systemStatus.set(resp);
      const mode = resp.recordingStatus?.configuredMode;
      if (mode) this.recordingMode.set(mode);
    } catch {
      this.statusMsg.set('Failed to load daemon status');
    }
  }

  refreshDaemons(): void {
    this.loadStatus();
  }

  // ---- Updates ----
  async checkUpdate(): Promise<void> {
    this.checkingUpdate.set(true);
    this.statusMsg.set('');
    try {
      const resp = await this.clients.updates.checkUpdate({});
      this.updateInfo.set(resp);
      if (!resp.available) this.statusMsg.set('Already up to date');
    } catch {
      this.statusMsg.set('Failed to check for updates');
    } finally {
      this.checkingUpdate.set(false);
    }
  }

  async installUpdate(): Promise<void> {
    if (!confirm('Install update now? The device will reboot.')) return;
    this.installing.set(true);
    try {
      await this.clients.updates.installUpdate({ confirm: true });
      this.statusMsg.set('Update started. Device will reboot shortly.');
    } catch {
      this.statusMsg.set('Failed to start update');
      this.installing.set(false);
    }
  }

  // ---- Privacy: reset data ----
  async previewCleanup(): Promise<void> {
    this.cleanupBusy.set(true);
    this.statusMsg.set('');
    try {
      const resp = await this.clients.storage.previewCleanup({});
      this.cleanupPreview.set(resp);
      if (!resp.files?.length) this.statusMsg.set('Nothing to clean up');
    } catch {
      this.statusMsg.set('Failed to preview cleanup');
    } finally {
      this.cleanupBusy.set(false);
    }
  }

  async triggerCleanup(): Promise<void> {
    if (!this.cleanupConfirm()) {
      this.cleanupConfirm.set(true);
      return;
    }
    this.cleanupConfirm.set(false);
    this.cleanupBusy.set(true);
    this.statusMsg.set('');
    try {
      const resp = await this.clients.storage.triggerCleanup({});
      this.statusMsg.set(
        resp.success
          ? `Freed ${this.formatBytes(Number(resp.bytesFreed))} (${resp.filesDeleted} files)`
          : `Cleanup failed: ${resp.error || resp.message}`,
      );
      this.cleanupPreview.set(null);
      await this.loadStorage();
    } catch {
      this.statusMsg.set('Cleanup failed');
    } finally {
      this.cleanupBusy.set(false);
    }
  }

  formatBytes(value: number | bigint): string {
    const bytes = Number(value);
    if (!bytes || bytes < 0) return '0 B';
    if (bytes < 1024) return `${bytes} B`;
    const kb = bytes / 1024;
    if (kb < 1024) return `${kb.toFixed(1)} KB`;
    const mb = kb / 1024;
    if (mb < 1024) return `${mb.toFixed(1)} MB`;
    return `${(mb / 1024).toFixed(2)} GB`;
  }
}
