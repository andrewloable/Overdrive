import { Component, inject, signal, OnInit } from '@angular/core';
import { TranslateModule } from '@ngx-translate/core';
import { ConnectClients } from '../../core/connect/connect-clients';

/**
 * About page — parity with native Settings > About (SettingsAboutFragment.kt).
 * Identity card (name + version + build/package), a License card that opens the
 * full MIT text, and a Getting Started card that opens the setup-guide steps.
 */
@Component({
  selector: 'app-about',
  standalone: true,
  imports: [TranslateModule],
  templateUrl: './about.component.html',
  styleUrl: './about.component.scss',
})
export default class AboutComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  readonly appVersion = signal('');
  readonly deviceId = signal('');
  readonly loading = signal(true);

  /** Which modal is open: 'license' | 'setup' | null. */
  readonly openDialog = signal<'license' | 'setup' | null>(null);

  /** Android application id, shown as the build identifier (matches native). */
  readonly packageId = 'net.bladewatch.app';

  /** Full MIT license text (kept in sync with the repo LICENSE file). */
  readonly licenseText =
    `MIT License

Copyright (c) 2026 Loable Technologies
Copyright (c) 2026 Yash Srivastava (original Overdrive project, https://github.com/yash-srivastava/Overdrive-release)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;

  /** Getting-started steps (mirrors the native SetupGuideDialog). */
  readonly setupSteps = [
    {
      title: 'Choose your language',
      detail: 'Pick a language in Settings → Appearance, or leave it on Auto to follow the head unit.',
    },
    {
      title: 'Allow auto-start',
      detail: 'On the head unit, enable BladeWatch in the system auto-start / background-restriction list so the daemons launch on boot.',
    },
    {
      title: 'Grant overlay permission',
      detail: 'Allow "Display over other apps" on the head unit so the REC / TRIP status overlay can show while driving.',
    },
  ];

  ngOnInit(): void {
    this.clients.system.getStatus({}).then(resp => {
      this.appVersion.set(resp.appVersion ?? '');
      this.deviceId.set(resp.deviceId ?? '');
    }).catch(() => { /* offline — leave blanks */ })
      .finally(() => this.loading.set(false));
  }

  open(dialog: 'license' | 'setup'): void {
    this.openDialog.set(dialog);
  }

  close(): void {
    this.openDialog.set(null);
  }
}
