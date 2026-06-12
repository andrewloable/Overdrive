import {
  Component,
  inject,
  signal,
  OnInit,
  OnDestroy,
  AfterViewInit,
  ElementRef,
  ViewChild,
} from '@angular/core';
import { ConnectClients } from '../../core/connect/connect-clients';

// SotaPlayer is a global loaded from public/vendor/SotaPlayer.js (no module).
declare var SotaPlayer: any;

/**
 * Live — full-bleed camera view, parity with the native LiveViewFragment.
 *
 * Owns the WebSocket live stream (SotaPlayer over /ws) plus the
 * All / Front / Right / Rear / Left camera selector. Selecting a camera
 * switches the daemon's mosaic/single view via StreamService.SetViewMode
 * (viewMode 0=All/Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left).
 *
 * This was moved out of the dashboard so the dashboard can be a stats/connect
 * hub; the dashboard now routerLinks here for the live feed.
 */
@Component({
  selector: 'app-live',
  standalone: true,
  templateUrl: './live.component.html',
  styleUrl: './live.component.scss',
})
export default class LiveComponent implements OnInit, AfterViewInit, OnDestroy {
  private readonly clients = inject(ConnectClients);

  @ViewChild('streamCanvas') streamCanvasRef!: ElementRef<HTMLCanvasElement>;

  readonly streamConnected = signal(false);
  readonly selectedCam = signal(0);

  private player: any = null;
  /** Set once the player object exists so the banner can show a retry hint. */
  private playerStarted = false;

  readonly cameras = [
    { id: 0, label: 'All' },
    { id: 1, label: 'Front' },
    { id: 2, label: 'Right' },
    { id: 3, label: 'Rear' },
    { id: 4, label: 'Left' },
  ];

  ngOnInit(): void {
    // Nothing async to load here — the stream wires up in ngAfterViewInit once
    // the canvas element exists.
  }

  ngAfterViewInit(): void {
    this.initStream();
  }

  ngOnDestroy(): void {
    this.player?.stop?.();
    this.player = null;
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
    this.playerStarted = true;
  }

  /** Tear down and re-create the player — used by the retry banner. */
  retryStream(): void {
    this.streamConnected.set(false);
    try {
      this.player?.stop?.();
    } catch {
      /* ignore */
    }
    this.player = null;
    this.playerStarted = false;
    this.initStream();
  }

  selectCamera(camId: number): void {
    this.selectedCam.set(camId);
    this.clients.stream.setViewMode({ viewMode: camId } as any).catch(() => {});
  }
}
