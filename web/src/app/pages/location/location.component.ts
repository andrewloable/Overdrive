import {
  Component,
  inject,
  signal,
  computed,
  OnInit,
  OnDestroy,
  AfterViewInit,
  ElementRef,
  viewChild,
} from '@angular/core';
import * as L from 'leaflet';
// Leaflet's stylesheet. Imported here as a Vite side-effect import (Vite
// resolves the bare node_modules specifier and injects it) rather than via a
// SCSS `@import`, which Sass cannot resolve from node_modules under this
// Vite + @analogjs build. Leaflet classes are all `.leaflet-*`-namespaced, so
// global injection is safe.
import 'leaflet/dist/leaflet.css';
import { ConnectClients } from '../../core/connect/connect-clients';

/**
 * Location — 1:1 parity with the native Location screen
 * (LocationMapController.kt / LocationModels.kt).
 *
 * Native split mirrored here:
 *  - Full-screen Leaflet map with a heading-rotated car marker.
 *  - Follow mode: recenter on every fix until the user pans, then show a
 *    "Recenter" button (LocationMapReducer.onUserPan / onFollowRequested).
 *  - Status banner reflecting the LocationUiState set: loading / waiting for
 *    fix / "lat, lon" (fresh) / stale / unavailable
 *    (LocationUiStateMapper.panelModel).
 *  - Map Appearance Auto/Light/Dark, persisted (LocationSettingsStore). Dark
 *    applies a CSS invert to the tile layer, matching osmdroid INVERT_COLORS.
 *
 * Data source: VehicleService.getGpsLocation() returns locationJson (from
 * GpsMonitor.getLocationJson(): lat, lng, heading, accuracy, isStale, ageMs,
 * hasLocation, …) plus googleMapsUrl. Polled every 4s.
 */

type MapTheme = 'auto' | 'light' | 'dark';

interface CarGps {
  lat: number;
  lng: number;
  heading: number | null;
  accuracy: number | null;
  isStale: boolean;
  hasLocation: boolean;
}

type BannerStatus = 'loading' | 'waiting' | 'fresh' | 'stale' | 'unavailable';

const THEME_KEY = 'bw_map_theme';
const POLL_MS = 4000;
const FOLLOW_ZOOM = 17;
// Mirrors LocationStateReducer.STALE_THRESHOLD_MS (30s) for the banner subtitle.
const STALE_MS = 30_000;

@Component({
  selector: 'app-location',
  standalone: true,
  imports: [],
  templateUrl: './location.component.html',
  styleUrl: './location.component.scss',
})
export default class LocationComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly clients = inject(ConnectClients);

  private readonly mapEl = viewChild.required<ElementRef<HTMLDivElement>>('mapEl');

  // ---- reactive view state ----
  readonly location = signal<CarGps | null>(null);
  readonly status = signal<BannerStatus>('loading');
  readonly googleMapsUrl = signal('');
  /** Follow the car until the user pans; toggles the Recenter button. */
  readonly following = signal(true);
  readonly theme = signal<MapTheme>(this.readTheme());

  /** Recenter button is visible once we have a fix and the user has panned. */
  readonly showRecenter = computed(() => !this.following() && this.location() != null);
  /** Map is shown whenever we have a usable location (parity: panel.showMap). */
  readonly showMap = computed(() => this.location() != null);

  readonly bannerTitle = computed(() => {
    switch (this.status()) {
      case 'loading': return 'Loading map';
      case 'waiting': return 'Waiting for GPS fix';
      case 'fresh': return 'Car location';
      case 'stale': return 'Location stale';
      case 'unavailable': return 'Location unavailable';
    }
  });

  readonly bannerSubtitle = computed(() => {
    const loc = this.location();
    if (this.status() === 'unavailable') return 'GPS sidecar is not reporting a position.';
    if (loc && (this.status() === 'fresh' || this.status() === 'stale')) {
      return `${loc.lat.toFixed(5)}, ${loc.lng.toFixed(5)}`;
    }
    return '';
  });

  private map?: L.Map;
  private marker?: L.Marker;
  private lightTiles?: L.TileLayer;
  private viewInit = false;
  private firstFixCentered = false;
  private suppressMoveEvent = false;
  private pollTimer?: ReturnType<typeof setInterval>;
  private themeMql?: MediaQueryList;
  private readonly themeMqlListener = () => {
    if (this.theme() === 'auto') this.applyTheme();
  };

  ngOnInit(): void {
    this.loadGps();
    this.pollTimer = setInterval(() => this.loadGps(), POLL_MS);
    if (typeof window !== 'undefined' && window.matchMedia) {
      this.themeMql = window.matchMedia('(prefers-color-scheme: dark)');
      this.themeMql.addEventListener('change', this.themeMqlListener);
    }
  }

  ngAfterViewInit(): void {
    this.initMap();
    this.viewInit = true;
    // Render whatever arrived before the map existed.
    const loc = this.location();
    if (loc) this.renderLocation(loc);
    this.applyTheme();
  }

  ngOnDestroy(): void {
    clearInterval(this.pollTimer);
    this.themeMql?.removeEventListener('change', this.themeMqlListener);
    this.map?.remove();
    this.map = undefined;
  }

  // ---- map setup ----------------------------------------------------------
  private initMap(): void {
    const map = L.map(this.mapEl().nativeElement, {
      center: [0, 0],
      zoom: 3,
      minZoom: 3,
      maxZoom: 19,
      zoomControl: true,
      attributionControl: true,
    });

    this.lightTiles = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors',
    });
    this.lightTiles.addTo(map);

    // A user-initiated drag/zoom breaks follow mode (parity: onUserPan).
    map.on('dragstart', () => this.onUserInteract());
    map.on('zoomstart', () => {
      if (!this.suppressMoveEvent) this.onUserInteract();
    });

    this.map = map;
  }

  private onUserInteract(): void {
    if (this.suppressMoveEvent) return;
    if (this.following()) this.following.set(false);
  }

  /**
   * Heading-rotated car marker as a DivIcon (avoids Leaflet's broken default
   * marker image paths under bundlers — see rule 5). The arrow points up at
   * heading 0 and rotates clockwise with the GPS heading.
   */
  private carIcon(heading: number | null): L.DivIcon {
    const rot = heading == null ? 0 : ((heading % 360) + 360) % 360;
    const html = `
      <div class="bw-car-marker" style="transform: rotate(${rot}deg)">
        <svg viewBox="0 0 48 48" width="48" height="48" aria-hidden="true">
          <circle cx="24" cy="24" r="21" fill="rgba(0,0,0,0.45)"/>
          <circle cx="24" cy="24" r="15" fill="#00D4AA" stroke="#fff" stroke-width="3"/>
          <path d="M24 9 L31 27 L24 23 L17 27 Z" fill="#fff"/>
          <circle cx="24" cy="24" r="3.4" fill="#fff"/>
        </svg>
      </div>`;
    return L.divIcon({
      html,
      className: 'bw-car-divicon',
      iconSize: [48, 48],
      iconAnchor: [24, 24],
    });
  }

  // ---- GPS poll -----------------------------------------------------------
  private async loadGps(): Promise<void> {
    try {
      const resp = await this.clients.vehicle.getGpsLocation({});
      this.googleMapsUrl.set(resp.googleMapsUrl ?? '');

      if (!resp.locationJson) {
        this.applyNoFix();
        return;
      }
      const d = JSON.parse(resp.locationJson) as Record<string, any>;
      const lat = Number(d.lat ?? d.latitude ?? 0);
      const lng = Number(d.lng ?? d.lon ?? d.longitude ?? 0);
      const hasLocation = d.hasLocation === true || (lat !== 0 || lng !== 0);

      if (!this.usable(lat, lng) || !hasLocation) {
        this.applyNoFix();
        return;
      }

      const loc: CarGps = {
        lat,
        lng,
        heading: this.numOrNull(d.heading),
        accuracy: this.numOrNull(d.accuracy),
        isStale: d.isStale === true || this.ageStale(d),
        hasLocation: true,
      };
      this.location.set(loc);
      this.status.set(loc.isStale ? 'stale' : 'fresh');
      if (this.viewInit) this.renderLocation(loc);
    } catch {
      this.applyNoFix();
    }
  }

  /** No usable fix this poll: "waiting" if we never had one, else "unavailable". */
  private applyNoFix(): void {
    this.status.set(this.location() == null ? 'waiting' : 'unavailable');
  }

  private renderLocation(loc: CarGps): void {
    const map = this.map;
    if (!map) return;
    const ll: L.LatLngExpression = [loc.lat, loc.lng];

    if (!this.marker) {
      this.marker = L.marker(ll, { icon: this.carIcon(loc.heading) }).addTo(map);
    } else {
      this.marker.setLatLng(ll);
      this.marker.setIcon(this.carIcon(loc.heading));
    }

    if (this.following()) this.center(ll, !this.firstFixCentered);
  }

  private center(ll: L.LatLngExpression, zoom: boolean): void {
    const map = this.map;
    if (!map) return;
    this.suppressMoveEvent = true;
    if (zoom) {
      map.setView(ll, FOLLOW_ZOOM, { animate: false });
      this.firstFixCentered = true;
    } else {
      map.panTo(ll, { animate: true });
    }
    // Release the guard after the (possibly animated) move settles.
    setTimeout(() => { this.suppressMoveEvent = false; }, 450);
  }

  recenter(): void {
    this.following.set(true);
    const loc = this.location();
    if (loc) this.center([loc.lat, loc.lng], false);
  }

  // ---- map appearance (Auto / Light / Dark) -------------------------------
  setTheme(theme: MapTheme): void {
    this.theme.set(theme);
    try {
      localStorage.setItem(THEME_KEY, theme);
    } catch { /* private mode — non-fatal */ }
    this.applyTheme();
  }

  /** True when the dark (inverted) tile rendering should be used right now. */
  private isDark(): boolean {
    const t = this.theme();
    if (t === 'dark') return true;
    if (t === 'light') return false;
    // auto — follow the OS / page color scheme.
    if (typeof window !== 'undefined' && window.matchMedia) {
      return window.matchMedia('(prefers-color-scheme: dark)').matches;
    }
    return true;
  }

  /** Toggle the invert filter on the map container (CSS handles the rest). */
  private applyTheme(): void {
    const el = this.map?.getContainer();
    if (!el) return;
    el.classList.toggle('bw-map-dark', this.isDark());
  }

  // ---- helpers ------------------------------------------------------------
  private usable(lat: number, lng: number): boolean {
    if (Number.isNaN(lat) || Number.isNaN(lng)) return false;
    if (lat < -90 || lat > 90) return false;
    if (lng < -180 || lng > 180) return false;
    return !(lat === 0 && lng === 0);
  }

  private numOrNull(v: any): number | null {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }

  private ageStale(d: Record<string, any>): boolean {
    const age = Number(d.ageMs);
    return Number.isFinite(age) && age >= STALE_MS;
  }

  private readTheme(): MapTheme {
    try {
      const v = localStorage.getItem(THEME_KEY);
      if (v === 'auto' || v === 'light' || v === 'dark') return v;
    } catch { /* ignore */ }
    return 'auto';
  }
}
