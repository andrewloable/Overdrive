import { Component, inject, signal, OnInit, OnDestroy, AfterViewInit, ElementRef, ViewChild } from '@angular/core';
import { ConnectClients } from '../../core/connect/connect-clients';
import type { GetStatusResponse } from '../../../../gen/bladewatch/v1/system_pb';

declare var SotaPlayer: any;

@Component({
  selector: 'app-dashboard',
  standalone: true,
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export default class DashboardComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly clients = inject(ConnectClients);

  @ViewChild('streamCanvas') streamCanvasRef!: ElementRef<HTMLCanvasElement>;

  readonly status = signal<GetStatusResponse | null>(null);
  readonly loading = signal(true);
  readonly streamConnected = signal(false);
  readonly selectedCam = signal(0);

  private pollTimer?: ReturnType<typeof setInterval>;
  private player: any = null;

  readonly cameras = [
    { id: 0, label: 'All' },
    { id: 1, label: 'Front' },
    { id: 2, label: 'Right' },
    { id: 3, label: 'Rear' },
    { id: 4, label: 'Left' },
  ];

  ngOnInit(): void {
    this.pollStatus();
    this.pollTimer = setInterval(() => this.pollStatus(), 5000);
  }

  ngAfterViewInit(): void {
    this.initStream();
  }

  ngOnDestroy(): void {
    clearInterval(this.pollTimer);
    this.player?.stop?.();
  }

  private async pollStatus(): Promise<void> {
    try {
      const resp = await this.clients.system.getStatus({});
      this.status.set(resp);
    } catch { /* ignore on poll */ } finally {
      this.loading.set(false);
    }
  }

  private initStream(): void {
    if (typeof SotaPlayer === 'undefined') return;
    const canvas = this.streamCanvasRef?.nativeElement;
    if (!canvas) return;

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws`;

    this.player = new SotaPlayer(canvas, wsUrl);
    this.player.onConnected = () => this.streamConnected.set(true);
    this.player.onDisconnected = () => this.streamConnected.set(false);
    this.player.start();
  }

  selectCamera(camId: number): void {
    this.selectedCam.set(camId);
    this.clients.stream.setViewMode({ viewMode: camId } as any).catch(() => {});
  }

  batteryLabel(): string {
    const s = this.status();
    if (!s) return '—';
    if (s.soc?.percent != null) return `${s.soc.percent}%`;
    if (s.battery?.level != null) return `${s.battery.level}%`;
    return '—';
  }

  rangeLabel(): string {
    const r = this.status()?.range;
    if (!r) return '—';
    const km = r.elecRangeKm ?? r.totalRangeKm ?? 0;
    return km > 0 ? `${Math.round(km)} km` : '—';
  }

  chargingLabel(): string {
    const c = this.status()?.charging;
    if (!c) return '';
    return c.stateName || '';
  }
}
