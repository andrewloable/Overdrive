import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  Output,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ConnectClients } from '../../core/connect/connect-clients';

/**
 * One detection-event span on the timeline. Mirrors the native
 * EventTimelineView.TimelineEvent shape: a start/end window (ms relative to
 * the clip start), a class/type label, and an optional confidence.
 */
export interface TimelineSpan {
  startMs: number;
  endMs: number;
  /** "person" | "car" | "vehicle" | "bike" | "motion" | … */
  type: string;
  confidence?: number;
}

/**
 * One playlist entry. Only the filename is required (it maps to
 * `/video/<filename>` + `/thumb/<filename>`); an optional human title is used
 * for the player header.
 */
export interface PlayerItem {
  filename: string;
  title?: string;
  /** Whether a JSON sidecar with an event timeline exists for this clip. */
  hasEvents?: boolean;
}

/**
 * Reusable video player with a detection-event timeline overlay.
 *
 * Web parity for the native VideoPlayerFragment + EventTimelineView:
 *   - HTML5 <video src="/video/<file>"> with native controls.
 *   - A timeline strip below the video painting colored detection spans
 *     (person=red, car/vehicle=blue, bike=green, motion=gray) proportional to
 *     the clip duration, plus a live white playhead. Click-to-seek.
 *   - Prev/Next transport that walks the supplied playlist in place (no modal
 *     teardown), auto-advancing to the next clip when one ends.
 *   - A close button surfacing a (close) output for the host modal.
 *
 * Detection spans can be passed in via [spans]; otherwise the player fetches
 * them lazily per clip via RecordingsService.GetEventTimeline. When neither
 * source yields spans, the timeline gracefully degrades to just the clip
 * duration track + playhead (no fabricated data).
 */
@Component({
  selector: 'app-video-player',
  standalone: true,
  imports: [DecimalPipe],
  templateUrl: './video-player.component.html',
  styleUrl: './video-player.component.scss',
})
export class VideoPlayerComponent {
  private readonly clients = inject(ConnectClients);

  /** Ordered list of clips the prev/next transport walks. */
  @Input({ required: true }) set playlist(items: readonly PlayerItem[]) {
    this._playlist.set(items ?? []);
    this.syncIndexToCurrent();
  }
  private readonly _playlist = signal<readonly PlayerItem[]>([]);

  /** The currently playing clip. Must be a member of [playlist]. */
  @Input({ required: true }) set file(item: PlayerItem | null) {
    this._file.set(item);
    this.syncIndexToCurrent();
    if (item) this.loadSpansFor(item);
  }
  private readonly _file = signal<PlayerItem | null>(null);

  /**
   * Optional pre-supplied detection spans for the current clip. When omitted
   * (or empty) the player fetches them itself via GetEventTimeline.
   */
  @Input() set spans(value: readonly TimelineSpan[] | null | undefined) {
    if (value && value.length) {
      this.spansExternal = true;
      this._spans.set([...value]);
    } else {
      this.spansExternal = false;
    }
  }
  private spansExternal = false;
  private readonly _spans = signal<readonly TimelineSpan[]>([]);

  /** Emitted when the user dismisses the player. */
  @Output() readonly close = new EventEmitter<void>();
  /** Emitted whenever the active clip changes (prev/next/auto-advance). */
  @Output() readonly fileChange = new EventEmitter<PlayerItem>();

  @ViewChild('video') private videoRef?: ElementRef<HTMLVideoElement>;

  readonly index = signal(-1);
  readonly currentMs = signal(0);
  readonly durationMs = signal(0);
  /** Legend summary (e.g. "2 person · 1 car") from the sidecar stats. */
  readonly legend = signal('');

  readonly spansView = computed(() => this._spans());

  readonly currentItem = computed<PlayerItem | null>(() => this._file());
  readonly title = computed(() => {
    const f = this._file();
    if (!f) return '';
    return f.title || f.filename;
  });
  readonly src = computed(() => {
    const f = this._file();
    return f ? '/video/' + f.filename : '';
  });

  readonly playlistCount = computed(() => this._playlist().length);
  readonly hasPlaylist = computed(() => this._playlist().length >= 2);
  readonly canPrev = computed(() => this.index() > 0);
  readonly canNext = computed(() => {
    const list = this._playlist();
    const i = this.index();
    return i >= 0 && i < list.length - 1;
  });

  private syncIndexToCurrent(): void {
    const f = this._file();
    const list = this._playlist();
    if (!f) {
      this.index.set(-1);
      return;
    }
    this.index.set(list.findIndex((p) => p.filename === f.filename));
  }

  // -------- Transport --------

  onClose(): void {
    this.close.emit();
  }

  prev(): void {
    if (this.canPrev()) this.jumpTo(this.index() - 1);
  }

  next(): void {
    if (this.canNext()) this.jumpTo(this.index() + 1);
  }

  private jumpTo(newIndex: number): void {
    const list = this._playlist();
    if (newIndex < 0 || newIndex >= list.length) return;
    const item = list[newIndex];
    this.index.set(newIndex);
    this._file.set(item);
    this.currentMs.set(0);
    this.durationMs.set(0);
    // External spans only describe the clip the host passed in; once we move
    // off it, fall back to self-fetching for the new clip.
    this.spansExternal = false;
    this.loadSpansFor(item);
    this.fileChange.emit(item);
  }

  // -------- <video> events --------

  onLoadedMetadata(): void {
    const v = this.videoRef?.nativeElement;
    if (v && isFinite(v.duration)) {
      this.durationMs.set(Math.round(v.duration * 1000));
    }
  }

  onTimeUpdate(): void {
    const v = this.videoRef?.nativeElement;
    if (v) this.currentMs.set(Math.round(v.currentTime * 1000));
  }

  onEnded(): void {
    // Auto-advance through the playlist, mirroring the native player.
    if (this.canNext()) this.next();
  }

  /** Click-to-seek on the timeline strip. */
  seekFromEvent(ev: MouseEvent): void {
    const v = this.videoRef?.nativeElement;
    const dur = this.durationMs();
    if (!v || dur <= 0) return;
    const el = ev.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (ev.clientX - rect.left) / rect.width));
    v.currentTime = (ratio * dur) / 1000;
    this.currentMs.set(Math.round(ratio * dur));
  }

  // -------- Timeline geometry / styling --------

  spanLeft(s: TimelineSpan): string {
    const dur = this.durationMs();
    if (dur <= 0) return '0%';
    return Math.max(0, Math.min(100, (s.startMs / dur) * 100)) + '%';
  }

  spanWidth(s: TimelineSpan): string {
    const dur = this.durationMs();
    if (dur <= 0) return '0%';
    const w = ((s.endMs - s.startMs) / dur) * 100;
    return Math.max(0.5, Math.min(100, w)) + '%';
  }

  spanClass(s: TimelineSpan): string {
    switch ((s.type || '').toLowerCase()) {
      case 'person':
        return 'span-person';
      case 'car':
      case 'vehicle':
        return 'span-car';
      case 'bike':
      case 'bicycle':
      case 'motorcycle':
        return 'span-bike';
      default:
        return 'span-motion';
    }
  }

  playheadLeft(): string {
    const dur = this.durationMs();
    if (dur <= 0) return '0%';
    return Math.min(100, (this.currentMs() / dur) * 100) + '%';
  }

  // -------- Span loading --------

  private loadSpansFor(item: PlayerItem): void {
    // Host already supplied spans for this clip — respect them.
    if (this.spansExternal) return;

    this._spans.set([]);
    this.legend.set('');

    // No sidecar => nothing to fetch; graceful empty timeline.
    if (item.hasEvents === false) return;

    this.fetchSpans(item.filename);
  }

  private async fetchSpans(filename: string): Promise<void> {
    try {
      const resp = await this.clients.recordings.getEventTimeline({ filename });
      // Guard against a stale response landing after the user moved on.
      if (this._file()?.filename !== filename) return;
      const raw = resp.timelineJson;
      if (!raw) return;
      const json = JSON.parse(raw) as {
        durationMs?: number;
        events?: Array<{ start?: number; end?: number; type?: string; maxConf?: number }>;
        stats?: Record<string, number>;
      };

      const spans: TimelineSpan[] = (json.events ?? []).map((e) => ({
        startMs: e.start ?? 0,
        endMs: e.end ?? (e.start ?? 0),
        type: e.type ?? 'motion',
        confidence: e.maxConf ?? 0,
      }));
      this._spans.set(spans);

      // Use sidecar duration if the <video> hasn't reported one yet.
      if (this.durationMs() <= 0 && json.durationMs) {
        this.durationMs.set(json.durationMs);
      }

      this.legend.set(this.buildLegend(json.stats));
    } catch {
      // Sidecar missing or unparsable — leave the timeline empty. The clip
      // duration track + playhead still render once metadata loads.
    }
  }

  private buildLegend(stats?: Record<string, number>): string {
    if (!stats) return '';
    const parts: string[] = [];
    const p = stats['person'] ?? 0;
    const c = stats['car'] ?? 0;
    const b = stats['bike'] ?? 0;
    const m = stats['motion'] ?? 0;
    if (p > 0) parts.push(`${p} person`);
    if (c > 0) parts.push(`${c} car`);
    if (b > 0) parts.push(`${b} bike`);
    if (m > 0) parts.push(`${m} motion`);
    return parts.join(' · ');
  }

  // -------- Time formatting (m:ss) --------

  fmt(ms: number): string {
    const totalSec = Math.floor(ms / 1000);
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    return `${min}:${sec.toString().padStart(2, '0')}`;
  }
}
