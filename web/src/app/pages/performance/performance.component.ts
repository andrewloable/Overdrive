import { Component, inject, signal, OnInit, OnDestroy } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { ConnectClients } from '../../core/connect/connect-clients';

interface PerfMetrics {
  cpuPercent: number;
  memPercent: number;
  tempC: number;
  fps: number;
  memUsedMb: number;
  memTotalMb: number;
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
        const data = JSON.parse(resp.performanceJson);
        this.metrics.set({
          cpuPercent: data.cpuPercent ?? data.cpu ?? 0,
          memPercent: data.memPercent ?? data.memoryPercent ?? 0,
          tempC: data.tempC ?? data.temperature ?? 0,
          fps: data.fps ?? 0,
          memUsedMb: data.memUsedMb ?? 0,
          memTotalMb: data.memTotalMb ?? 0,
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
