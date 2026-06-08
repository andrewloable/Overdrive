import { Component, inject, signal, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ConnectClients } from '../../core/connect/connect-clients';
import { toast } from '../../shared/page-utils';
import type { RecordingEntry } from '../../../../gen/bladewatch/v1/recordings_pb';

type FilterType = 'ALL' | 'NORMAL' | 'SENTRY' | 'PROXIMITY';

@Component({
  selector: 'app-recording',
  standalone: true,
  imports: [DecimalPipe],
  templateUrl: './recording.component.html',
  styleUrl: './recording.component.scss',
})
export default class RecordingComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  readonly recordings = signal<readonly RecordingEntry[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly activeFilter = signal<FilterType>('ALL');
  readonly activeVideo = signal<RecordingEntry | null>(null);
  readonly statusMsg = signal('');
  readonly usedBytes = signal(0);
  readonly totalCount = signal(0);

  readonly filters: { key: FilterType; label: string }[] = [
    { key: 'ALL', label: 'All' },
    { key: 'NORMAL', label: 'Recordings' },
    { key: 'SENTRY', label: 'Sentry' },
    { key: 'PROXIMITY', label: 'Proximity' },
  ];

  ngOnInit(): void {
    this.loadRecordings();
    this.loadStats();
  }

  private async loadRecordings(): Promise<void> {
    this.loading.set(true);
    try {
      const filter = this.activeFilter();
      const resp = await this.clients.recordings.listRecordings({
        type: filter === 'ALL' ? 0 : (filter === 'NORMAL' ? 1 : filter === 'SENTRY' ? 2 : 3) as any,
        pageSize: 100,
      });
      this.recordings.set(resp.recordings ?? []);
    } catch {
      this.error.set('Failed to load recordings');
    } finally {
      this.loading.set(false);
    }
  }

  private async loadStats(): Promise<void> {
    try {
      const resp = await this.clients.recordings.getStats({});
      this.usedBytes.set(Number(resp.stats?.totalSizeBytes ?? 0));
      this.totalCount.set(resp.stats?.totalCount ?? 0);
    } catch { /* ignore */ }
  }

  setFilter(f: FilterType): void {
    this.activeFilter.set(f);
    this.loadRecordings();
  }

  playVideo(rec: RecordingEntry): void {
    this.activeVideo.set(rec);
  }

  closeVideo(): void {
    this.activeVideo.set(null);
  }

  async deleteRecording(rec: RecordingEntry): Promise<void> {
    if (!confirm(`Delete ${rec.filename}?`)) return;
    try {
      await this.clients.recordings.deleteRecording({ filename: rec.filename });
      this.recordings.update(list => list.filter(r => r.filename !== rec.filename));
      if (this.activeVideo()?.filename === rec.filename) this.activeVideo.set(null);
      toast(this.statusMsg, 'Deleted');
    } catch {
      this.statusMsg.set('Delete failed');
    }
  }

  usedMb(): number { return Math.round(this.usedBytes() / 1024 / 1024); }
}
