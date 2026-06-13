import {
  Component,
  inject,
  signal,
  computed,
  OnInit,
  OnDestroy,
  ElementRef,
  viewChild,
  effect,
} from '@angular/core';
import { DecimalPipe, UpperCasePipe } from '@angular/common';
import * as L from 'leaflet';
// Leaflet's stylesheet. Imported here as a Vite side-effect import (Vite
// resolves the bare node_modules specifier and injects it globally) rather than
// via a SCSS `@import`, which Sass cannot resolve from node_modules under this
// Vite + @analogjs build — see location.component.ts. Without this the route
// map's tiles are mispositioned unless the Location page was opened first.
import 'leaflet/dist/leaflet.css';
import { ConnectClients } from '../../core/connect/connect-clients';
import { toast } from '../../shared/page-utils';
import type {
  TripSummary,
  TripDetail,
  DnaScores,
  TripConfig,
  TripStorageInfo,
} from '../../../gen/bladewatch/v1/trips_pb';

/**
 * Trips — 1:1 parity with the native Trips screen
 * (TripsController.kt / TripDetailController.kt / TripsClient.kt / TripsModels.kt).
 *
 * Three tabs:
 *  - Trips:   days filter (7/14/30), Period Summary card (rolled up from
 *             GetSummary WeeklyRollup JSON), trip rows with score, and a trip
 *             detail overlay (in-app Leaflet OSM route map from GetGpsTrace,
 *             summary grid, 5 driving-score bars). Delete retained.
 *  - Stats:   Driver Score (sum of 5 DNA axes / 500), Personalized Range
 *             (GetRange rangeJson), Driving DNA bars (GetDna).
 *  - Storage: Trip Analytics toggle + Electricity Rate (currency + rate) +
 *             Distance Unit (km/mi) via SetConfig; Storage Location via
 *             SetStorage; Apply; and a Sync Database card (SyncTrips).
 *
 * RPCs used: ListTrips, GetTrip, DeleteTrip, GetSummary, GetDna, GetRange,
 * GetConfig, SetConfig, GetStorage, SetStorage, SyncTrips, GetGpsTrace.
 */

type Tab = 'trips' | 'stats' | 'storage';
type DaysFilter = 7 | 14 | 30;
type DistanceUnit = 'km' | 'mi';

interface PeriodSummary {
  tripCount: number;
  totalDistanceKm: number;
  totalDurationSeconds: number;
  totalEnergyKwh: number;
  avgEnergyPerKm: number;
  avgEfficiency: number;
}

interface RangeEstimate {
  estimatedKm: number;
  builtInKm: number;
}

const KM_PER_MI = 0.621371;

@Component({
  selector: 'app-trips',
  standalone: true,
  imports: [DecimalPipe, UpperCasePipe],
  templateUrl: './trips.component.html',
  styleUrl: './trips.component.scss',
})
export default class TripsComponent implements OnInit, OnDestroy {
  private readonly clients = inject(ConnectClients);

  private readonly mapEl = viewChild<ElementRef<HTMLDivElement>>('mapEl');

  // ---- tab + filter state -------------------------------------------------
  readonly activeTab = signal<Tab>('trips');
  readonly daysFilter = signal<DaysFilter>(7);
  readonly daysOptions: readonly DaysFilter[] = [7, 14, 30];

  // ---- loaded data --------------------------------------------------------
  readonly trips = signal<readonly TripSummary[]>([]);
  readonly summary = signal<PeriodSummary | null>(null);
  readonly dna = signal<DnaScores | null>(null);
  readonly range = signal<RangeEstimate | null>(null);
  readonly rangeMessage = signal('');
  readonly config = signal<TripConfig | null>(null);
  readonly storage = signal<TripStorageInfo | null>(null);

  readonly loading = signal(true);
  readonly error = signal('');
  readonly statusMsg = signal('');

  // ---- trip detail overlay ------------------------------------------------
  readonly detail = signal<TripDetail | null>(null);
  readonly detailLoading = signal(false);
  readonly detailError = signal('');
  /** True once a GPS trace with >= 2 points has been drawn for the open trip. */
  readonly hasRoute = signal(false);

  // ---- storage tab editable form ------------------------------------------
  readonly formEnabled = signal(false);
  readonly formCurrency = signal('USD');
  readonly formRate = signal('0');
  readonly formDistanceUnit = signal<DistanceUnit>('km');
  readonly formStorageType = signal('INTERNAL');
  readonly saving = signal(false);

  // ---- sync card ----------------------------------------------------------
  readonly syncRunning = signal(false);
  readonly syncResult = signal('');
  readonly syncIsError = signal(false);

  // ---- derived ------------------------------------------------------------
  /** Distance unit to display trips/summary/detail in (from saved config). */
  readonly unit = computed<DistanceUnit>(() =>
    this.config()?.distanceUnit === 'mi' ? 'mi' : 'km',
  );

  /** Driver Score = sum of the 5 DNA axes, out of 500 (native parity). */
  readonly driverScore = computed(() => {
    const d = this.dna();
    if (!d) return 0;
    return d.anticipation + d.smoothness + d.speedDiscipline + d.efficiency + d.consistency;
  });

  readonly dnaBars = computed(() => {
    const d = this.dna();
    if (!d) return [] as { name: string; score: number }[];
    return [
      { name: 'Anticipation', score: d.anticipation },
      { name: 'Smoothness', score: d.smoothness },
      { name: 'Speed Discipline', score: d.speedDiscipline },
      { name: 'Efficiency', score: d.efficiency },
      { name: 'Consistency', score: d.consistency },
    ];
  });

  readonly detailScoreBars = computed(() => {
    const t = this.detail();
    if (!t) return [] as { name: string; score: number }[];
    return [
      { name: 'Anticipation', score: t.anticipationScore },
      { name: 'Smoothness', score: t.smoothnessScore },
      { name: 'Speed Discipline', score: t.speedDisciplineScore },
      { name: 'Efficiency', score: t.efficiencyScore },
      { name: 'Consistency', score: t.consistencyScore },
    ];
  });

  private map?: L.Map;
  private route?: L.Polyline;
  private startDot?: L.Marker;
  private endDot?: L.Marker;
  /** Watches the route-map host so a late layout pass recomputes the tile grid. */
  private resizeObs?: ResizeObserver;
  /** Points of the currently drawn route, kept so we can re-fit after a resize. */
  private currentPoints?: L.LatLngExpression[];

  constructor() {
    // Build / tear down the route map as the detail overlay opens and closes.
    // The map element only exists in the DOM while a detail is shown.
    effect(() => {
      const t = this.detail();
      if (t) {
        // Defer to next frame so the @if-rendered map element is in the DOM.
        queueMicrotask(() => this.ensureMap());
      } else {
        this.destroyMap();
      }
    });
  }

  ngOnInit(): void {
    this.loadAll();
  }

  ngOnDestroy(): void {
    this.destroyMap();
  }

  // ---- loading ------------------------------------------------------------
  private async loadAll(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    const days = this.daysFilter();
    try {
      const [tripsR, summaryR, dnaR, rangeR, configR, storageR] = await Promise.allSettled([
        this.clients.trips.listTrips({ days, limit: 100, offset: 0 }),
        this.clients.trips.getSummary({ days }),
        this.clients.trips.getDna({ days: 30 }),
        this.clients.trips.getRange({}),
        this.clients.trips.getConfig({}),
        this.clients.trips.getStorage({}),
      ]);

      if (tripsR.status === 'fulfilled') {
        this.trips.set(tripsR.value.trips ?? []);
      } else {
        this.trips.set([]);
      }

      this.summary.set(
        summaryR.status === 'fulfilled' ? this.rollupSummary(summaryR.value.summary ?? []) : null,
      );

      this.dna.set(dnaR.status === 'fulfilled' ? (dnaR.value.dna ?? null) : null);

      if (rangeR.status === 'fulfilled') {
        this.range.set(this.parseRange(rangeR.value.rangeJson));
        this.rangeMessage.set(rangeR.value.message ?? '');
      } else {
        this.range.set(null);
      }

      const cfg = configR.status === 'fulfilled' ? (configR.value.config ?? null) : null;
      this.config.set(cfg);
      if (cfg) this.seedConfigForm(cfg);

      const st = storageR.status === 'fulfilled' ? (storageR.value.storage ?? null) : null;
      this.storage.set(st);
      if (st) this.formStorageType.set(st.storageType || 'INTERNAL');

      // Only surface an error if literally nothing came back.
      if (tripsR.status === 'rejected' && configR.status === 'rejected') {
        this.error.set('Failed to load trips data');
      }
    } catch {
      this.error.set('Failed to load trips data');
    } finally {
      this.loading.set(false);
    }
  }

  /** Reloads list + summary for the chosen days window (native loadData()). */
  selectDays(days: DaysFilter): void {
    if (this.daysFilter() === days) return;
    this.daysFilter.set(days);
    this.loadAll();
  }

  selectTab(tab: Tab): void {
    this.activeTab.set(tab);
    if (tab !== 'storage') {
      this.syncResult.set('');
      this.syncIsError.set(false);
    }
  }

  private seedConfigForm(cfg: TripConfig): void {
    this.formEnabled.set(cfg.enabled);
    this.formCurrency.set(cfg.currency || 'USD');
    this.formRate.set((cfg.electricityRate ?? 0).toFixed(4));
    this.formDistanceUnit.set(cfg.distanceUnit === 'mi' ? 'mi' : 'km');
  }

  /**
   * Rolls the weekly rollup JSON blobs up into one period total, mirroring
   * TripsClient.fetchSummary(). Keys: tripCount, totalDistanceKm,
   * totalDurationSeconds, totalEnergyKwh, avgEfficiency, avgEnergyPerKm.
   */
  private rollupSummary(entries: readonly { rollupJson: string }[]): PeriodSummary | null {
    if (entries.length === 0) return null;
    let tripCount = 0;
    let totalDistanceKm = 0;
    let totalDurationSeconds = 0;
    let totalEnergyKwh = 0;
    let efficiencySum = 0;
    let energyPerKmSum = 0;
    let count = 0;
    for (const e of entries) {
      let r: Record<string, any>;
      try {
        r = JSON.parse(e.rollupJson) as Record<string, any>;
      } catch {
        continue;
      }
      count++;
      tripCount += Number(r['tripCount'] ?? 0);
      totalDistanceKm += Number(r['totalDistanceKm'] ?? 0);
      totalDurationSeconds += Number(r['totalDurationSeconds'] ?? 0);
      totalEnergyKwh += Number(r['totalEnergyKwh'] ?? 0);
      efficiencySum += Number(r['avgEfficiency'] ?? 0);
      energyPerKmSum += Number(r['avgEnergyPerKm'] ?? 0);
    }
    if (count === 0) return null;
    return {
      tripCount,
      totalDistanceKm,
      totalDurationSeconds,
      totalEnergyKwh,
      avgEnergyPerKm: energyPerKmSum / count,
      avgEfficiency: efficiencySum / count,
    };
  }

  /**
   * Parses GetRange.rangeJson. The daemon's RangeEstimate.toJson() emits
   * predictedRangeKm / builtInRangeKm (optionally nested under "range"),
   * matching TripsClient.fetchRange().
   */
  private parseRange(rangeJson: string | undefined): RangeEstimate | null {
    if (!rangeJson) return null;
    let parsed: Record<string, any>;
    try {
      parsed = JSON.parse(rangeJson) as Record<string, any>;
    } catch {
      return null;
    }
    const r = (parsed['range'] as Record<string, any>) ?? parsed;
    const estimatedKm = Number(r['predictedRangeKm'] ?? 0);
    const builtInKm = Number(r['builtInRangeKm'] ?? 0);
    if (!Number.isFinite(estimatedKm) || estimatedKm <= 0) {
      return builtInKm > 0 ? { estimatedKm: 0, builtInKm } : null;
    }
    return { estimatedKm, builtInKm: Number.isFinite(builtInKm) ? builtInKm : 0 };
  }

  // ---- trip detail --------------------------------------------------------
  async openTrip(trip: TripSummary): Promise<void> {
    this.detailLoading.set(true);
    this.detailError.set('');
    this.detail.set(null);
    this.hasRoute.set(false);
    try {
      const resp = await this.clients.trips.getTrip({ id: trip.id });
      const t = resp.trip;
      if (!t || !t.summary) {
        this.detailError.set(resp.error || 'Trip details unavailable');
        return;
      }
      this.detail.set(t);
      // Draw the route once the overlay + map element exist (handled by effect).
      void this.loadRoute(trip.id);
    } catch {
      this.detailError.set('Trip details unavailable');
    } finally {
      this.detailLoading.set(false);
    }
  }

  closeDetail(): void {
    this.detail.set(null);
    this.detailError.set('');
    this.hasRoute.set(false);
  }

  async deleteTrip(trip: TripSummary): Promise<void> {
    if (!confirm('Delete this trip?')) return;
    try {
      await this.clients.trips.deleteTrip({ id: trip.id });
      this.trips.update(list => list.filter(t => t.id !== trip.id));
      if (this.detail()?.summary?.id === trip.id) this.closeDetail();
      toast(this.statusMsg, 'Trip deleted');
    } catch {
      toast(this.statusMsg, 'Delete failed');
    }
  }

  // ---- route map (Leaflet + OSM) ------------------------------------------
  private ensureMap(): void {
    const host = this.mapEl()?.nativeElement;
    if (!host || this.map) return;
    const map = L.map(host, {
      center: [0, 0],
      zoom: 3,
      minZoom: 3,
      maxZoom: 19,
      zoomControl: true,
      attributionControl: true,
    });
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(map);
    map.getContainer().classList.toggle('bw-map-dark', this.isDark());
    this.map = map;

    // The map element is created from an effect()→microtask, which fires before
    // the @if-rendered detail overlay is laid out. At init Leaflet reads a
    // not-yet-sized container and builds a too-small tile grid, leaving black
    // gaps. Recompute the size once layout settles (rAF) and on any later
    // resize, then re-fit the route so the whole trace stays in view.
    const resync = () => {
      if (this.map !== map) return;
      map.invalidateSize(false);
      this.fitToRoute();
    };
    requestAnimationFrame(resync);
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObs = new ResizeObserver(() => resync());
      this.resizeObs.observe(host);
    }

    if (this.pendingPoints) {
      this.drawRoute(this.pendingPoints);
      this.pendingPoints = undefined;
    }
  }

  private destroyMap(): void {
    this.resizeObs?.disconnect();
    this.resizeObs = undefined;
    this.map?.remove();
    this.map = undefined;
    this.route = undefined;
    this.startDot = undefined;
    this.endDot = undefined;
    this.pendingPoints = undefined;
    this.currentPoints = undefined;
  }

  /** Buffered GPS points when the trace arrives before the map is ready. */
  private pendingPoints?: L.LatLngExpression[];

  private async loadRoute(tripId: bigint): Promise<void> {
    try {
      const resp = await this.clients.trips.getGpsTrace({ tripId });
      const points = (resp.gps ?? [])
        .filter(p => p.lat !== 0 || p.lon !== 0)
        .map(p => [p.lat, p.lon] as L.LatLngExpression);
      if (points.length < 2) {
        this.hasRoute.set(false);
        return;
      }
      this.hasRoute.set(true);
      if (this.map) {
        this.drawRoute(points);
      } else {
        this.pendingPoints = points;
      }
    } catch {
      this.hasRoute.set(false);
    }
  }

  private drawRoute(points: L.LatLngExpression[]): void {
    const map = this.map;
    if (!map || points.length < 2) return;

    this.route?.remove();
    this.startDot?.remove();
    this.endDot?.remove();

    this.route = L.polyline(points, {
      color: '#00D4AA',
      weight: 4,
      opacity: 0.9,
    }).addTo(map);

    this.startDot = L.marker(points[0], { icon: this.dotIcon('#00D4AA') }).addTo(map);
    this.endDot = L.marker(points[points.length - 1], { icon: this.dotIcon('#EF4444') }).addTo(map);

    this.currentPoints = points;
    // Sync to the real container size before fitting, so the bounds zoom is
    // computed against the laid-out map rather than the init-time (often
    // smaller) size that would clip the tile grid.
    map.invalidateSize(false);
    this.fitToRoute();
  }

  /** Re-fits the map to the drawn route's bounds (no-op if nothing is drawn). */
  private fitToRoute(): void {
    const map = this.map;
    const route = this.route;
    if (!map || !route || !this.currentPoints || this.currentPoints.length < 2) return;
    map.fitBounds(route.getBounds(), { padding: [28, 28] });
  }

  /** DivIcon dot (avoids Leaflet's broken default marker image under bundlers). */
  private dotIcon(color: string): L.DivIcon {
    return L.divIcon({
      html: `<div class="bw-trip-dot" style="background:${color}"></div>`,
      className: 'bw-trip-divicon',
      iconSize: [16, 16],
      iconAnchor: [8, 8],
    });
  }

  private isDark(): boolean {
    if (typeof window !== 'undefined' && window.matchMedia) {
      return window.matchMedia('(prefers-color-scheme: dark)').matches;
    }
    return true;
  }

  // ---- storage tab actions ------------------------------------------------
  async applyStorageSettings(): Promise<void> {
    this.saving.set(true);
    const rate = Number.parseFloat(this.formRate()) || 0;
    const currency = this.formCurrency().trim() || 'USD';
    const distanceUnit = this.formDistanceUnit();
    const enabled = this.formEnabled();
    const storageType = this.formStorageType();
    const limitMb = this.storage()?.limitMb ?? 0n;
    try {
      const cfgResp = await this.clients.trips.setConfig({
        enabled,
        hasEnabled: true,
        electricityRate: rate,
        hasElectricityRate: true,
        currency,
        distanceUnit,
      });
      let ok = cfgResp.success;
      let err = cfgResp.error;

      if (ok && this.storage()) {
        const stResp = await this.clients.trips.setStorage({
          storageType,
          storageLimitMb: limitMb,
          hasStorageLimitMb: true,
        });
        ok = stResp.success;
        if (!ok) err = stResp.error;
      }

      if (ok) {
        toast(this.statusMsg, 'Settings saved');
      } else {
        toast(this.statusMsg, err || 'Save failed');
      }
      await this.loadAll();
    } catch {
      toast(this.statusMsg, 'Save failed');
    } finally {
      this.saving.set(false);
    }
  }

  async syncDatabase(): Promise<void> {
    this.syncRunning.set(true);
    this.syncResult.set('');
    this.syncIsError.set(false);
    try {
      const resp = await this.clients.trips.syncTrips({});
      if (resp.success) {
        this.syncIsError.set(false);
        this.syncResult.set(`Synced successfully: +${resp.added} -${resp.removed} (${resp.total} total)`);
        await this.loadAll();
      } else {
        this.syncIsError.set(true);
        this.syncResult.set(resp.error || 'Sync failed');
      }
    } catch {
      this.syncIsError.set(true);
      this.syncResult.set('Network error');
    } finally {
      this.syncRunning.set(false);
    }
  }

  dismissSync(): void {
    this.syncResult.set('');
    this.syncIsError.set(false);
  }

  // ---- formatting helpers (used by template) ------------------------------
  durationLabel(sec: number): string {
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    return h > 0 ? `${h}h ${m}m` : `${m}m`;
  }

  /** Distance in the active display unit, e.g. "12.4 km" / "7.7 mi". */
  distanceLabel(km: number, unit: DistanceUnit = this.unit()): string {
    return unit === 'mi' ? `${(km * KM_PER_MI).toFixed(1)} mi` : `${km.toFixed(1)} km`;
  }

  speedLabel(kmh: number, unit: DistanceUnit = this.unit()): string {
    return unit === 'mi'
      ? `${Math.round(kmh * KM_PER_MI)} mph`
      : `${Math.round(kmh)} km/h`;
  }

  /** Sum of the per-period hours label, e.g. "5h 12m". */
  hoursLabel(sec: number): string {
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    return `${h}h ${m}m`;
  }

  tripDate(ms: bigint): string {
    const n = Number(ms);
    if (!n) return '—';
    return new Date(n).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  }

  detailTitle(ms: bigint): string {
    const n = Number(ms);
    if (!n) return '—';
    return new Date(n).toLocaleDateString(undefined, {
      weekday: 'long',
      month: 'long',
      day: 'numeric',
    });
  }

  timeRange(start: bigint, end: bigint): string {
    const fmt = (ms: bigint) => {
      const n = Number(ms);
      if (!n) return '—';
      return new Date(n).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
    };
    return `${fmt(start)} – ${fmt(end)}`;
  }

  /** Clamp a 0..100 score to a CSS width percentage. */
  scorePct(score: number): number {
    return Math.max(0, Math.min(100, score));
  }

  scoreClass(score: number): string {
    if (score >= 70) return 'good';
    if (score >= 40) return 'warn';
    return 'bad';
  }
}
