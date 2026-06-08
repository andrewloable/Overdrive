import { Component, inject, signal, OnInit } from '@angular/core';
import { DecimalPipe, DatePipe } from '@angular/common';
import { ConnectClients } from '../../core/connect/connect-clients';
import type { TripSummary } from '../../../../gen/bladewatch/v1/trips_pb';

@Component({
  selector: 'app-trips',
  standalone: true,
  imports: [DecimalPipe, DatePipe],
  templateUrl: './trips.component.html',
  styleUrl: './trips.component.scss',
})
export default class TripsComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  readonly trips = signal<readonly TripSummary[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly selectedTrip = signal<TripSummary | null>(null);
  readonly statusMsg = signal('');

  ngOnInit(): void {
    this.loadTrips();
  }

  private async loadTrips(): Promise<void> {
    try {
      const resp = await this.clients.trips.listTrips({ limit: 50, offset: 0 });
      this.trips.set(resp.trips ?? []);
    } catch {
      this.error.set('Failed to load trips');
    } finally {
      this.loading.set(false);
    }
  }

  selectTrip(trip: TripSummary): void {
    this.selectedTrip.set(trip);
  }

  closeDetail(): void {
    this.selectedTrip.set(null);
  }

  async deleteTrip(trip: TripSummary): Promise<void> {
    if (!confirm('Delete this trip?')) return;
    try {
      await this.clients.trips.deleteTrip({ id: trip.id });
      this.trips.update(list => list.filter(t => t.id !== trip.id));
      if (this.selectedTrip()?.id === trip.id) this.selectedTrip.set(null);
      this.statusMsg.set('Trip deleted');
      setTimeout(() => this.statusMsg.set(''), 2000);
    } catch {
      this.statusMsg.set('Delete failed');
    }
  }

  durationLabel(sec: number): string {
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    return h > 0 ? `${h}h ${m}m` : `${m}m`;
  }

  mapsUrl(trip: TripSummary): string {
    if (!trip.startLat && !trip.startLon) return '';
    return `https://www.google.com/maps/dir/${trip.startLat},${trip.startLon}/${trip.endLat},${trip.endLon}`;
  }
}
