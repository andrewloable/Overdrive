import { Component, inject, signal, computed, OnInit, OnDestroy } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { ConnectClients } from '../../core/connect/connect-clients';

type Tab = 'climate' | 'seats' | 'windows';
interface GpsLocation { lat: number; lng: number; accuracy?: number; }

/**
 * Vehicle — parity with native VehicleFragment (VehicleController/VehiclePanels).
 * Lock/Unlock/Flash were REMOVED by decision (native has no such control).
 * Polls GetState every 3s and exposes Climate / Seats / Windows control tabs,
 * read-only lock + charge/range pills, and TPMS cards. GPS card retained until
 * the dedicated Location screen ships.
 */
@Component({
  selector: 'app-vehicle',
  standalone: true,
  imports: [DecimalPipe, TranslateModule],
  templateUrl: './vehicle.component.html',
  styleUrl: './vehicle.component.scss',
})
export default class VehicleComponent implements OnInit, OnDestroy {
  private readonly clients = inject(ConnectClients);

  readonly state = signal<any | null>(null);
  readonly tab = signal<Tab>('climate');
  readonly busy = signal<string | null>(null);
  readonly statusMsg = signal('');

  readonly gpsLocation = signal<GpsLocation | null>(null);
  readonly gpsLoading = signal(true);
  readonly googleMapsUrl = signal('');

  private pollTimer?: ReturnType<typeof setInterval>;

  // ---- derived view state ----
  readonly climate = computed(() => this.state()?.climate ?? null);
  readonly battery = computed(() => this.state()?.battery ?? null);
  readonly seats = computed(() => this.state()?.seats ?? null);
  readonly tyres = computed(() => {
    const t = this.state()?.tyres;
    if (!t) return [];
    return [
      { pos: 'FL', ...this.tyre(t.fl) },
      { pos: 'FR', ...this.tyre(t.fr) },
      { pos: 'RL', ...this.tyre(t.rl) },
      { pos: 'RR', ...this.tyre(t.rr) },
    ];
  });

  private tyre(p: any) {
    return {
      psi: Number(p?.psi ?? 0),
      tempC: Number(p?.temperatureC ?? p?.tempC ?? 0),
      leak: Number(p?.airLeakState ?? p?.leakState ?? 0) > 0,
      warn: Number(p?.pressureState ?? 0) > 0,
    };
  }

  lockLabel(): string {
    const o = this.state()?.doors?.overall;
    if (o == null) return '—';
    return o === 0 ? 'Unlocked' : 'Locked';
  }

  ngOnInit(): void {
    this.loadState();
    this.pollTimer = setInterval(() => this.loadState(), 3000);
    this.loadGps();
  }

  ngOnDestroy(): void {
    clearInterval(this.pollTimer);
  }

  private async loadState(): Promise<void> {
    try {
      const r = await this.clients.vehicle.getState({});
      if (r.success) this.state.set(r);
    } catch { /* keep last good state */ }
  }

  setTab(t: Tab): void { this.tab.set(t); }

  /** Run a control RPC, then re-poll truth from the device. */
  private async control(key: string, fn: () => Promise<{ success?: boolean; error?: string; message?: string }>): Promise<void> {
    this.busy.set(key);
    this.statusMsg.set('');
    try {
      const resp = await fn();
      if (resp && resp.success === false) this.statusMsg.set(resp.error || 'Command failed');
      await this.loadState();
    } catch (e) {
      this.statusMsg.set(e instanceof Error ? e.message : 'Command failed');
    } finally {
      this.busy.set(null);
    }
  }

  // ---- Climate (action: power_on/power_off/set_temp/set_fan/max_cooling) ----
  toggleAc(): void {
    const on = !!this.climate()?.acOn;
    const t = Math.round(this.climate()?.setpointC ?? 22);
    this.control('ac', () => this.clients.vehicle.setClimate({ action: on ? 'power_off' : 'power_on', setpointC: t } as any));
  }
  toggleMaxCooling(): void {
    const on = !!this.climate()?.maxCooling;
    this.control('max', () => this.clients.vehicle.setClimate({ action: 'max_cooling', on: !on } as any));
  }
  changeTemp(delta: number): void {
    const t = Math.min(33, Math.max(17, Math.round((this.climate()?.setpointC ?? 22) + delta)));
    this.control('temp', () => this.clients.vehicle.setClimate({ action: 'set_temp', setpointC: t } as any));
  }
  changeFan(delta: number): void {
    const f = Math.min(7, Math.max(1, Math.round((this.climate()?.fanLevel ?? 1) + delta)));
    this.control('fan', () => this.clients.vehicle.setClimate({ action: 'set_fan', fanLevel: f } as any));
  }

  // ---- Seats (stateful: send full driver/passenger heat+vent every call) ----
  private seatLevels() {
    const s = this.seats();
    const heat: number[] = s?.heat ?? [];
    const cool: number[] = s?.cool ?? [];
    return {
      driverHeat: heat[0] ?? 0, passengerHeat: heat[1] ?? 0,
      driverVent: cool[0] ?? 0, passengerVent: cool[1] ?? 0,
    };
  }
  seatHeat(seat: 'driver' | 'passenger'): number {
    const l = this.seatLevels();
    return seat === 'driver' ? l.driverHeat : l.passengerHeat;
  }
  seatVent(seat: 'driver' | 'passenger'): number {
    const l = this.seatLevels();
    return seat === 'driver' ? l.driverVent : l.passengerVent;
  }
  cycleSeat(seat: 'driver' | 'passenger', kind: 'heat' | 'vent'): void {
    const cur = kind === 'heat' ? this.seatHeat(seat) : this.seatVent(seat);
    const next = (cur + 1) % 3; // Off -> Low -> High -> Off
    const lv = { ...this.seatLevels() };
    const seatIndex = seat === 'driver' ? 1 : 2;
    if (kind === 'heat') lv[seat === 'driver' ? 'driverHeat' : 'passengerHeat'] = next;
    else lv[seat === 'driver' ? 'driverVent' : 'passengerVent'] = next;
    this.control(`${seat}-${kind}`, () => this.clients.vehicle.setSeat({
      seatIndex, action: kind === 'heat' ? 'heating' : 'ventilation', level: next, ...lv,
    } as any));
  }
  seatLabel(level: number): string { return ['Off', 'Low', 'High'][level] ?? 'Off'; }

  // ---- Windows (windowIndex 0=all,1=LF,2=RF,3=LR,4=RR; targetPercent or direction) ----
  readonly windowDefs = [
    { idx: 1, label: 'Front Left' },
    { idx: 2, label: 'Front Right' },
    { idx: 3, label: 'Rear Left' },
    { idx: 4, label: 'Rear Right' },
  ];
  readonly windowPresets = [0, 25, 50, 75, 100];

  setWindow(idx: number, percent: number): void {
    this.control(`win-${idx}-${percent}`, () => this.clients.vehicle.moveWindow({ windowIndex: idx, targetPercent: percent } as any));
  }
  allWindows(kind: 'close' | 'vent' | 'open'): void {
    const req = kind === 'vent'
      ? { windowIndex: 0, targetPercent: 12 }
      : { windowIndex: 0, direction: kind };
    this.control(`all-${kind}`, () => this.clients.vehicle.moveWindow(req as any));
  }
  windowPos(idx: number): number {
    const w = this.state()?.windows;
    if (!w) return 0;
    return [0, w.lf, w.rf, w.lr, w.rr][idx] ?? 0;
  }

  // ---- GPS (retained) ----
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
    } catch { /* gps unavailable */ } finally {
      this.gpsLoading.set(false);
    }
  }
  refreshGps(): void { this.gpsLoading.set(true); this.loadGps(); }
}
