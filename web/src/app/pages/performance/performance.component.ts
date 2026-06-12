import { Component, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ConnectClients } from '../../core/connect/connect-clients';

/**
 * Parity with native Performance (PerformanceController.kt). The daemon's
 * getPerformance returns performanceJson with nested cpu/memory/gpu/app objects
 * (see PerformanceMonitor.Snapshot.toJson). We render CPU, Memory, GPU and
 * App-process cards to match.
 */
interface PerfMetrics {
  cpu: { system: number; app: number; freqMhz: number; tempC: number };
  memory: { totalMb: number; usedMb: number; usagePercent: number; appTotalMb: number };
  gpu: { usage: number; freqMhz: number; tempC: number };
  app: { threads: number; gcCount: number; openFds: number };
}

@Component({
  selector: 'app-performance',
  standalone: true,
  imports: [DecimalPipe],
  templateUrl: './performance.component.html',
  styleUrl: './performance.component.scss',
})
export default class PerformanceComponent implements OnInit, OnDestroy {
  private readonly clients = inject(ConnectClients);

  readonly metrics = signal<PerfMetrics | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly audioTesting = signal(false);

  private pollTimer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.poll();
    this.pollTimer = setInterval(() => this.poll(), 3000);
  }

  ngOnDestroy(): void {
    clearInterval(this.pollTimer);
  }

  private async poll(): Promise<void> {
    try {
      const resp = await this.clients.system.getPerformance({});
      if (resp.performanceJson) {
        const d = JSON.parse(resp.performanceJson) as Record<string, any>;
        const num = (v: any) => (typeof v === 'number' ? v : 0);
        this.metrics.set({
          cpu: {
            system: num(d.cpu?.system),
            app: num(d.cpu?.app),
            freqMhz: num(d.cpu?.freqMhz),
            tempC: num(d.cpu?.tempC),
          },
          memory: {
            totalMb: num(d.memory?.totalMb),
            usedMb: num(d.memory?.usedMb),
            usagePercent: num(d.memory?.usagePercent),
            appTotalMb: num(d.memory?.appTotalMb),
          },
          gpu: {
            usage: num(d.gpu?.usage),
            freqMhz: num(d.gpu?.freqMhz),
            tempC: num(d.gpu?.tempC),
          },
          app: {
            threads: num(d.app?.threads),
            gcCount: num(d.app?.gcCount),
            openFds: num(d.app?.openFds),
          },
        });
      }
      this.loading.set(false);
    } catch {
      if (this.loading()) {
        this.error.set('Failed to load performance data');
        this.loading.set(false);
      }
    }
  }

  async playAudioTest(): Promise<void> {
    this.audioTesting.set(true);
    try {
      await this.clients.system.playAudioTest({ durationMs: 3000 });
    } finally {
      setTimeout(() => this.audioTesting.set(false), 3500);
    }
  }
}
