import { Component, inject, signal, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ConnectClients } from '../../core/connect/connect-clients';

interface GpsLocation {
  lat: number;
  lng: number;
  accuracy?: number;
}

@Component({
  selector: 'app-vehicle',
  standalone: true,
  imports: [DecimalPipe],
  templateUrl: './vehicle.component.html',
  styleUrl: './vehicle.component.scss',
})
export default class VehicleComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  readonly gpsLocation = signal<GpsLocation | null>(null);
  readonly gpsLoading = signal(true);
  readonly statusMsg = signal('');
  readonly actionBusy = signal<string | null>(null);
  readonly googleMapsUrl = signal('');

  ngOnInit(): void {
    this.loadGps();
  }

  private async loadGps(): Promise<void> {
    try {
      const resp = await this.clients.vehicle.getGpsLocation({});
      this.googleMapsUrl.set(resp.googleMapsUrl ?? '');
      if (resp.locationJson) {
        const loc = JSON.parse(resp.locationJson);
        this.gpsLocation.set({
          lat: loc.lat ?? loc.latitude ?? 0,
          lng: loc.lng ?? loc.lon ?? loc.longitude ?? 0,
          accuracy: loc.accuracy,
        });
      }
    } catch {
      this.statusMsg.set('GPS data unavailable');
    } finally {
      this.gpsLoading.set(false);
    }
  }

  async doAction(action: 'lock' | 'unlock' | 'flash'): Promise<void> {
    if (action === 'unlock' || action === 'flash') {
      if (!confirm(`Confirm: ${action} the vehicle?`)) return;
    }
    this.actionBusy.set(action);
    this.statusMsg.set('');
    try {
      let resp: { success: boolean; message?: string; error?: string };
      if (action === 'lock') resp = await this.clients.vehicle.lock({});
      else if (action === 'unlock') resp = await this.clients.vehicle.unlock({});
      else resp = await this.clients.vehicle.flash({});
      this.statusMsg.set(resp.success ? (resp.message || `${action} successful`) : (resp.error || `${action} failed`));
    } catch (e: unknown) {
      this.statusMsg.set(e instanceof Error ? e.message : `${action} failed`);
    } finally {
      this.actionBusy.set(null);
    }
  }

  refreshGps(): void {
    this.gpsLoading.set(true);
    this.loadGps();
  }
}
