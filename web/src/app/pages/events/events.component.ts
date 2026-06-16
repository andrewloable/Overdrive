import { Component, computed, inject, signal, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { ConnectClients } from '../../core/connect/connect-clients';
import {
  VideoPlayerComponent,
  type PlayerItem,
} from '../../shared/video-player/video-player.component';
import type { RecordingEntry } from '../../../gen/bladewatch/v1/recordings_pb';

@Component({
  selector: 'app-events',
  standalone: true,
  imports: [DatePipe, VideoPlayerComponent, TranslateModule],
  templateUrl: './events.component.html',
  styleUrl: './events.component.scss',
})
export default class EventsComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  readonly events = signal<readonly RecordingEntry[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly activeFile = signal<PlayerItem | null>(null);

  /** Playlist for the shared player — the full ordered events list. */
  readonly playlist = computed<PlayerItem[]>(() =>
    this.events().map((e) => ({
      filename: e.filename,
      title: e.timeLabel || e.dateLabel || e.filename,
      hasEvents: e.hasEvents,
    })),
  );

  ngOnInit(): void {
    this.loadEvents();
  }

  private async loadEvents(): Promise<void> {
    try {
      const resp = await this.clients.recordings.listRecordings({
        type: 'sentry',
        pageSize: 100,
      });
      this.events.set(resp.recordings ?? []);
    } catch {
      this.error.set('Failed to load events');
    } finally {
      this.loading.set(false);
    }
  }

  playVideo(rec: RecordingEntry): void {
    this.activeFile.set({
      filename: rec.filename,
      title: rec.timeLabel || rec.dateLabel || rec.filename,
      hasEvents: rec.hasEvents,
    });
  }

  onPlayerFileChange(item: PlayerItem): void {
    this.activeFile.set(item);
  }

  closeVideo(): void {
    this.activeFile.set(null);
  }

  /** int64 timestamp → number for DatePipe (0 when unset). */
  tsMs(ev: RecordingEntry): number {
    return Number(ev.timestampMs);
  }

  typeBadge(type: string | number): string {
    const map: Record<string, string> = { SENTRY: 'Sentry', PROXIMITY: 'Proximity', '2': 'Sentry', '3': 'Proximity' };
    return map[String(type)] ?? String(type);
  }
}
