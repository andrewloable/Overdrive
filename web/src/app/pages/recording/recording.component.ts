import { Component, computed, inject, signal, OnInit } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { ConnectClients } from '../../core/connect/connect-clients';
import { toast } from '../../shared/page-utils';
import {
  VideoPlayerComponent,
  type PlayerItem,
} from '../../shared/video-player/video-player.component';
import { RecordingType, type RecordingEntry } from '../../../gen/bladewatch/v1/recordings_pb';

/** Top-level segment: dashcam clips vs the surveillance pipeline. */
type Segment = 'DASHCAM' | 'SURVEILLANCE';
/** Date narrowing modes. ALL = no date filter. */
type DateMode = 'ALL' | 'DAY';

@Component({
  selector: 'app-recording',
  standalone: true,
  imports: [DecimalPipe, TranslateModule, VideoPlayerComponent],
  templateUrl: './recording.component.html',
  styleUrl: './recording.component.scss',
})
export default class RecordingComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  // Full catalog (both dashcam + surveillance) loaded once, filtered client-side.
  readonly all = signal<readonly RecordingEntry[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly statusMsg = signal('');

  readonly usedBytes = signal(0);
  readonly totalCount = signal(0);

  // -------- Segment --------
  readonly segment = signal<Segment>('DASHCAM');

  // -------- Date navigation --------
  // selectedDay is local midnight epoch ms of the chosen day.
  readonly dateMode = signal<DateMode>('DAY');
  readonly selectedDay = signal<number>(startOfDay(Date.now()));

  // -------- Filter chips --------
  // Surveillance: actor classes (lowercase) + severity (upper).
  readonly actorFilter = signal<ReadonlySet<string>>(new Set());
  readonly severityFilter = signal<ReadonlySet<string>>(new Set());
  // Dashcam: type chips. Empty OR both = show NORMAL + PROXIMITY.
  readonly dashcamTypes = signal<ReadonlySet<string>>(new Set());

  readonly actorChips = [
    { key: 'person', label: 'Person' },
    { key: 'vehicle', label: 'Vehicle' },
    { key: 'bike', label: 'Bike' },
    { key: 'animal', label: 'Animal' },
  ];
  readonly severityChips = [
    { key: 'ALERT', label: 'Alert' },
    { key: 'CRITICAL', label: 'Critical' },
  ];
  readonly typeChips = [
    { key: 'NORMAL', label: 'Normal' },
    { key: 'PROXIMITY', label: 'Proximity' },
  ];

  // -------- Multi-select --------
  readonly selectMode = signal(false);
  readonly selected = signal<ReadonlySet<string>>(new Set());

  // -------- Player --------
  readonly activeFile = signal<PlayerItem | null>(null);

  // -------- Derived --------

  /** Dashcam = NORMAL + PROXIMITY; Surveillance = SENTRY. */
  private readonly dashcamAll = computed(() =>
    this.all().filter(
      (r) => r.type === RecordingType.NORMAL || r.type === RecordingType.PROXIMITY,
    ),
  );
  private readonly surveillanceAll = computed(() =>
    this.all().filter((r) => r.type === RecordingType.SENTRY),
  );

  readonly dashcamCount = computed(() => this.dashcamAll().length);
  readonly surveillanceCount = computed(() => this.surveillanceAll().length);

  /** The post-segment, post-date, post-chip list shown in the grid. */
  readonly visible = computed<readonly RecordingEntry[]>(() => {
    const seg = this.segment();
    let list = seg === 'DASHCAM' ? this.dashcamAll() : this.surveillanceAll();

    // Date narrowing (client-side, by timestamp).
    if (this.dateMode() === 'DAY') {
      const start = this.selectedDay();
      const end = start + 86_400_000;
      list = list.filter((r) => {
        const ts = Number(r.timestampMs);
        return ts >= start && ts < end;
      });
    }

    if (seg === 'DASHCAM') {
      const types = this.dashcamTypes();
      // Empty or both => no narrowing.
      if (types.size === 1) {
        const wantNormal = types.has('NORMAL');
        list = list.filter((r) =>
          wantNormal ? r.type === RecordingType.NORMAL : r.type === RecordingType.PROXIMITY,
        );
      }
    } else {
      const actors = this.actorFilter();
      const sevs = this.severityFilter();
      if (actors.size || sevs.size) {
        list = list.filter((r) => {
          const classOk =
            actors.size === 0 ||
            (r.detectedClasses ?? []).some((c) => actors.has(c.toLowerCase()));
          const sevOk = sevs.size === 0 || (r.severity && sevs.has(r.severity.toUpperCase()));
          return classOk && sevOk;
        });
      }
    }
    return list;
  });

  readonly visibleCount = computed(() => this.visible().length);
  readonly selectedCount = computed(() => this.selected().size);
  readonly allVisibleSelected = computed(() => {
    const v = this.visible();
    const sel = this.selected();
    return v.length > 0 && v.every((r) => sel.has(r.filename));
  });

  /** Playlist for the shared player — the currently visible (filtered) list. */
  readonly playlist = computed<PlayerItem[]>(() =>
    this.visible().map((r) => ({
      filename: r.filename,
      title: r.timeLabel || r.dateLabel || r.filename,
      hasEvents: r.hasEvents,
    })),
  );

  readonly dateLabel = computed(() => {
    if (this.dateMode() === 'ALL') return 'All days';
    const d = this.selectedDay();
    const today = startOfDay(Date.now());
    if (d === today) return 'Today';
    if (d === today - 86_400_000) return 'Yesterday';
    return new Date(d).toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
  });

  /** Next-day disabled once we reach today. */
  readonly canGoNextDay = computed(
    () => this.dateMode() === 'DAY' && this.selectedDay() < startOfDay(Date.now()),
  );

  readonly chipsActive = computed(() => {
    if (this.segment() === 'DASHCAM') return this.dashcamTypes().size > 0;
    return this.actorFilter().size > 0 || this.severityFilter().size > 0;
  });

  ngOnInit(): void {
    this.loadRecordings();
    this.loadStats();
  }

  private async loadRecordings(): Promise<void> {
    this.loading.set(true);
    try {
      // Load the whole catalog once; segment/date/chip narrowing is client-side
      // so the segment counts and the grid always agree.
      const resp = await this.clients.recordings.listRecordings({ type: '', pageSize: 1000 });
      this.all.set(resp.recordings ?? []);
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
    } catch {
      /* ignore */
    }
  }

  // -------- Segment --------

  setSegment(s: Segment): void {
    if (this.segment() === s) return;
    this.segment.set(s);
    // Chip dimensions are per-segment; clear the other segment's residue.
    this.exitSelect();
  }

  // -------- Date --------

  setDateMode(mode: DateMode): void {
    this.dateMode.set(mode);
    if (mode === 'DAY') this.selectedDay.set(startOfDay(Date.now()));
  }

  goToday(): void {
    this.dateMode.set('DAY');
    this.selectedDay.set(startOfDay(Date.now()));
  }

  goYesterday(): void {
    this.dateMode.set('DAY');
    this.selectedDay.set(startOfDay(Date.now()) - 86_400_000);
  }

  shiftDay(delta: number): void {
    if (this.dateMode() !== 'DAY') return;
    const next = this.selectedDay() + delta * 86_400_000;
    if (next > startOfDay(Date.now())) return; // clamp at today
    this.selectedDay.set(next);
  }

  // -------- Chips --------

  toggleActor(key: string): void {
    this.actorFilter.update((s) => toggle(s, key));
  }
  toggleSeverity(key: string): void {
    this.severityFilter.update((s) => toggle(s, key));
  }
  toggleType(key: string): void {
    this.dashcamTypes.update((s) => toggle(s, key));
  }
  isActor(key: string): boolean {
    return this.actorFilter().has(key);
  }
  isSeverity(key: string): boolean {
    return this.severityFilter().has(key);
  }
  isType(key: string): boolean {
    return this.dashcamTypes().has(key);
  }

  resetChips(): void {
    this.actorFilter.set(new Set());
    this.severityFilter.set(new Set());
    this.dashcamTypes.set(new Set());
  }

  // -------- Multi-select --------

  enterSelect(): void {
    this.selectMode.set(true);
  }

  exitSelect(): void {
    this.selectMode.set(false);
    this.selected.set(new Set());
  }

  toggleSelected(filename: string): void {
    this.selected.update((s) => toggle(s, filename));
  }

  isSelected(filename: string): boolean {
    return this.selected().has(filename);
  }

  selectAllVisible(): void {
    if (this.allVisibleSelected()) {
      this.selected.set(new Set());
    } else {
      this.selected.set(new Set(this.visible().map((r) => r.filename)));
    }
  }

  async deleteSelected(): Promise<void> {
    const names = [...this.selected()];
    if (names.length === 0) return;
    if (!confirm(`Delete ${names.length} recording(s)?`)) return;

    let deleted = 0;
    let failed = 0;
    for (const filename of names) {
      try {
        const resp = await this.clients.recordings.deleteRecording({ filename });
        if (resp.success) deleted++;
        else failed++;
      } catch {
        failed++;
      }
    }
    const set = new Set(names);
    this.all.update((list) => list.filter((r) => !set.has(r.filename)));
    if (this.activeFile() && set.has(this.activeFile()!.filename)) this.activeFile.set(null);
    this.exitSelect();
    toast(this.statusMsg, failed ? `Deleted ${deleted}, ${failed} failed` : `Deleted ${deleted}`);
  }

  // -------- Player --------

  playVideo(rec: RecordingEntry): void {
    if (this.selectMode()) {
      this.toggleSelected(rec.filename);
      return;
    }
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

  // -------- Single delete (grid card button) --------

  async deleteRecording(rec: RecordingEntry, ev: Event): Promise<void> {
    ev.stopPropagation();
    if (!confirm(`Delete ${rec.filename}?`)) return;
    try {
      await this.clients.recordings.deleteRecording({ filename: rec.filename });
      this.all.update((list) => list.filter((r) => r.filename !== rec.filename));
      if (this.activeFile()?.filename === rec.filename) this.activeFile.set(null);
      toast(this.statusMsg, 'Deleted');
    } catch {
      this.statusMsg.set('Delete failed');
    }
  }

  // -------- Helpers --------

  durationSec(rec: RecordingEntry): number {
    return Number(rec.durationSeconds);
  }

  typeName(type: RecordingType): string {
    switch (type) {
      case RecordingType.SENTRY:
        return 'SENTRY';
      case RecordingType.PROXIMITY:
        return 'PROXIMITY';
      case RecordingType.NORMAL:
        return 'NORMAL';
      default:
        return 'NORMAL';
    }
  }

  usedMb(): number {
    return Math.round(this.usedBytes() / 1024 / 1024);
  }
}

/** Local-midnight epoch ms for the day containing `ms`. */
function startOfDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** Return a new Set with `key` toggled. */
function toggle(set: ReadonlySet<string>, key: string): Set<string> {
  const next = new Set(set);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  return next;
}
