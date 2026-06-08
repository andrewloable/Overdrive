import { Component, inject, signal, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateService } from '@ngx-translate/core';
import { ConnectClients } from '../../core/connect/connect-clients';
import type { CheckUpdateResponse } from '../../../../gen/bladewatch/v1/update_pb';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.scss',
})
export default class SettingsComponent implements OnInit {
  private readonly clients = inject(ConnectClients);
  private readonly translate = inject(TranslateService);

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly statusMsg = signal('');

  readonly recordingQuality = signal('STANDARD');
  readonly codec = signal('H264');
  readonly streamQuality = signal('');
  readonly lang = signal('en');
  readonly supportedLangs = signal<{ code: string; label: string }[]>([]);

  readonly updateInfo = signal<CheckUpdateResponse | null>(null);
  readonly checkingUpdate = signal(false);
  readonly installing = signal(false);

  ngOnInit(): void {
    this.loadSettings();
  }

  private async loadSettings(): Promise<void> {
    try {
      const [quality, locale] = await Promise.all([
        this.clients.settings.getQuality({}),
        this.clients.settings.getLocale({}),
      ]);
      this.recordingQuality.set(quality.recordingQuality ?? 'STANDARD');
      this.codec.set(quality.codec ?? 'H264');
      this.lang.set(locale.lang ?? 'en');
      const supported = locale.supported ?? {};
      this.supportedLangs.set(
        Object.entries(supported).map(([code, label]) => ({ code, label: String(label) }))
      );
    } catch {
      this.statusMsg.set('Failed to load settings');
    } finally {
      this.loading.set(false);
    }
  }

  async saveQuality(): Promise<void> {
    this.saving.set(true);
    this.statusMsg.set('');
    try {
      await this.clients.settings.setQuality({
        recordingQuality: this.recordingQuality() as any,
        codec: this.codec() as any,
      });
      this.statusMsg.set('Quality settings saved');
    } catch {
      this.statusMsg.set('Failed to save quality settings');
    } finally {
      this.saving.set(false);
    }
  }

  async saveLang(): Promise<void> {
    this.saving.set(true);
    try {
      await this.clients.settings.setLocale({ lang: this.lang() });
      this.translate.use(this.lang());
      localStorage.setItem('bw_lang', this.lang());
      this.statusMsg.set('Language updated');
    } catch {
      this.statusMsg.set('Failed to save language');
    } finally {
      this.saving.set(false);
    }
  }

  async checkUpdate(): Promise<void> {
    this.checkingUpdate.set(true);
    this.statusMsg.set('');
    try {
      const resp = await this.clients.updates.checkUpdate({});
      this.updateInfo.set(resp);
      if (!resp.available) this.statusMsg.set('Already up to date');
    } catch {
      this.statusMsg.set('Failed to check for updates');
    } finally {
      this.checkingUpdate.set(false);
    }
  }

  async installUpdate(): Promise<void> {
    if (!confirm('Install update now? The device will reboot.')) return;
    this.installing.set(true);
    try {
      await this.clients.updates.installUpdate({ confirm: true });
      this.statusMsg.set('Update started. Device will reboot shortly.');
    } catch {
      this.statusMsg.set('Failed to start update');
      this.installing.set(false);
    }
  }
}
