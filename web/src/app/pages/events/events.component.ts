import { Component, inject, signal, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ConnectClients } from '../../core/connect/connect-clients';
import type { RecordingEntry } from '../../../../gen/bladewatch/v1/recordings_pb';

@Component({
  selector: 'app-events',
  standalone: true,
  imports: [DatePipe],
  templateUrl: './events.component.html',
  styleUrl: './events.component.scss',
})
export default class EventsComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  readonly events = signal<readonly RecordingEntry[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly activeVideo = signal<RecordingEntry | null>(null);

  ngOnInit(): void {
    this.loadEvents();
  }

  private async loadEvents(): Promise<void> {
    try {
      const resp = await this.clients.recordings.listRecordings({
        type: 2 as any,
        pageSize: 100,
      });
      this.events.set(resp.recordings ?? []);
    } catch {
      this.error.set('Failed to load events');
    } finally {
      this.loading.set(false);
    }
  }

  playVideo(rec: RecordingEntry): void { this.activeVideo.set(rec); }
  closeVideo(): void { this.activeVideo.set(null); }

  typeBadge(type: string | number): string {
    const map: Record<string, string> = { SENTRY: 'Sentry', PROXIMITY: 'Proximity', '2': 'Sentry', '3': 'Proximity' };
    return map[String(type)] ?? String(type);
  }
}
