import { Component, inject, signal, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ConnectClients } from '../../core/connect/connect-clients';
import type { SurveillanceConfig } from '../../../../gen/bladewatch/v1/surveillance_pb';
import type { SafeZone } from '../../../../gen/bladewatch/v1/safe_locations_pb';

@Component({
  selector: 'app-surveillance',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  templateUrl: './surveillance.component.html',
  styleUrl: './surveillance.component.scss',
})
export default class SurveillanceComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  readonly loading = signal(true);
  readonly error = signal('');
  readonly statusMsg = signal('');
  readonly saving = signal(false);

  readonly active = signal(false);
  readonly pipelineRunning = signal(false);
  readonly toggling = signal(false);

  readonly sensitivity = signal(3);
  readonly distancePreset = signal('BALANCED');
  readonly aiEnabled = signal(false);
  readonly aiConfidence = signal(0.5);
  readonly minObjectSize = signal(50);
  readonly preRecordSeconds = signal(5);
  readonly postRecordSeconds = signal(10);
  readonly detectPerson = signal(true);
  readonly detectCar = signal(true);
  readonly detectBike = signal(true);
  readonly nightMode = signal(false);

  readonly snapshots = signal<Record<number, string>>({});
  readonly snapshotLoading = signal<Record<number, boolean>>({});

  readonly safeZones = signal<readonly SafeZone[]>([]);
  readonly safeLocationsEnabled = signal(false);

  readonly quadrants = [1, 2, 3, 4];

  ngOnInit(): void {
    this.loadAll();
  }

  private async loadAll(): Promise<void> {
    try {
      const [config, status, zones] = await Promise.all([
        this.clients.surveillance.getConfig({}),
        this.clients.surveillance.getStatus({}),
        this.clients.safeLocations.listZones({}),
      ]);

      const cfg = config.config;
      if (cfg) {
        this.sensitivity.set(cfg.sensitivity ?? 3);
        this.distancePreset.set(cfg.distancePreset ?? 'BALANCED');
        this.aiEnabled.set(cfg.aiEnabled ?? false);
        this.aiConfidence.set(cfg.aiConfidence ?? 0.5);
        this.minObjectSize.set(cfg.minObjectSize ?? 50);
        this.preRecordSeconds.set(cfg.preRecordSeconds ?? 5);
        this.postRecordSeconds.set(cfg.postRecordSeconds ?? 10);
        this.detectPerson.set(cfg.detectPerson ?? true);
        this.detectCar.set(cfg.detectCar ?? true);
        this.detectBike.set(cfg.detectBike ?? true);
        this.nightMode.set(cfg.nightMode ?? false);
      }

      this.active.set(status.surveillanceActive ?? false);
      this.pipelineRunning.set(status.pipelineRunning ?? false);

      this.safeZones.set(zones.zones ?? []);
      this.safeLocationsEnabled.set(zones.featureEnabled ?? false);

      this.loadAllSnapshots();
    } catch {
      this.error.set('Failed to load surveillance settings');
    } finally {
      this.loading.set(false);
    }
  }

  async toggle(): Promise<void> {
    this.toggling.set(true);
    try {
      if (this.active()) {
        await this.clients.surveillance.disable({});
        this.active.set(false);
      } else {
        await this.clients.surveillance.enable({});
        this.active.set(true);
      }
    } catch {
      this.statusMsg.set('Toggle failed');
    } finally {
      this.toggling.set(false);
    }
  }

  async saveConfig(): Promise<void> {
    this.saving.set(true);
    this.statusMsg.set('');
    try {
      await this.clients.surveillance.setConfig({
        config: {
          sensitivity: this.sensitivity(),
          distancePreset: this.distancePreset() as any,
          aiEnabled: this.aiEnabled(),
          aiConfidence: this.aiConfidence(),
          minObjectSize: this.minObjectSize(),
          preRecordSeconds: this.preRecordSeconds(),
          postRecordSeconds: this.postRecordSeconds(),
          detectPerson: this.detectPerson(),
          detectCar: this.detectCar(),
          detectBike: this.detectBike(),
          nightMode: this.nightMode(),
        } as Partial<SurveillanceConfig>,
      });
      this.statusMsg.set('Settings saved');
    } catch {
      this.statusMsg.set('Failed to save settings');
    } finally {
      this.saving.set(false);
    }
  }

  async loadAllSnapshots(): Promise<void> {
    for (const q of this.quadrants) {
      this.loadSnapshot(q);
    }
  }

  async loadSnapshot(quadrant: number): Promise<void> {
    this.snapshotLoading.update(s => ({ ...s, [quadrant]: true }));
    try {
      const resp = await this.clients.surveillance.getSnapshot({ quadrant });
      if (resp.imageJpeg && resp.imageJpeg.length > 0) {
        const b64 = btoa(String.fromCharCode(...new Uint8Array(resp.imageJpeg as any)));
        this.snapshots.update(s => ({ ...s, [quadrant]: `data:image/jpeg;base64,${b64}` }));
      }
    } catch { /* ignore */ } finally {
      this.snapshotLoading.update(s => ({ ...s, [quadrant]: false }));
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

  async toggleSafeLocations(): Promise<void> {
    const enabled = !this.safeLocationsEnabled();
    try {
      await this.clients.safeLocations.toggle({ enabled });
      this.safeLocationsEnabled.set(enabled);
    } catch {
      this.statusMsg.set('Failed to toggle safe locations');
    }
  }
}
