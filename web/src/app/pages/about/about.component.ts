import { Component, inject, signal, OnInit } from '@angular/core';
import { ConnectClients } from '../../core/connect/connect-clients';

@Component({
  selector: 'app-about',
  standalone: true,
  templateUrl: './about.component.html',
  styleUrl: './about.component.scss',
})
export default class AboutComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  readonly appVersion = signal('');
  readonly deviceId = signal('');
  readonly loading = signal(true);

  ngOnInit(): void {
    this.clients.system.getStatus({}).then(resp => {
      this.appVersion.set(resp.appVersion ?? '');
      this.deviceId.set(resp.deviceId ?? '');
    }).finally(() => this.loading.set(false));
  }
}
