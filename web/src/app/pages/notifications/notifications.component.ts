import { Component, inject, signal, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ConnectClients } from '../../core/connect/connect-clients';
import type { PushSubscriptionRecord } from '../../../../gen/bladewatch/v1/notifications_pb';

@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './notifications.component.html',
  styleUrl: './notifications.component.scss',
})
export default class NotificationsComponent implements OnInit {
  private readonly clients = inject(ConnectClients);

  readonly subscriptions = signal<readonly PushSubscriptionRecord[]>([]);
  readonly vapidKey = signal('');
  readonly loading = signal(true);
  readonly error = signal('');
  readonly subscribing = signal(false);
  readonly testSending = signal(false);
  readonly statusMsg = signal('');

  ngOnInit(): void {
    this.loadData();
  }

  private async loadData(): Promise<void> {
    try {
      const [cats, subs] = await Promise.all([
        this.clients.notifications.getCategories({}),
        this.clients.notifications.listSubscriptions({}),
      ]);
      this.vapidKey.set(cats.vapidPublicKey ?? '');
      this.subscriptions.set(subs.subscriptions ?? []);
    } catch (e) {
      this.error.set('Failed to load notification settings');
    } finally {
      this.loading.set(false);
    }
  }

  async subscribe(): Promise<void> {
    if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
      this.statusMsg.set('Web Push is not supported in this browser');
      return;
    }
    this.subscribing.set(true);
    this.statusMsg.set('');
    try {
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: this.vapidKey(),
      });
      const json = sub.toJSON();
      const keys = json.keys ?? {};
      await this.clients.notifications.subscribe({
        endpoint: sub.endpoint,
        keys: { p256dh: keys['p256dh'] ?? '', auth: keys['auth'] ?? '' },
        label: navigator.userAgent.substring(0, 60),
      });
      this.statusMsg.set('Push notifications enabled');
      await this.loadData();
    } catch (e: unknown) {
      this.statusMsg.set(e instanceof Error ? e.message : 'Subscription failed');
    } finally {
      this.subscribing.set(false);
    }
  }

  async unsubscribe(id: string): Promise<void> {
    try {
      await this.clients.notifications.unsubscribe({ id });
      this.subscriptions.update(subs => subs.filter(s => s.id !== id));
    } catch {
      this.statusMsg.set('Failed to remove subscription');
    }
  }

  async sendTest(): Promise<void> {
    this.testSending.set(true);
    try {
      await this.clients.notifications.sendTest({});
      this.statusMsg.set('Test notification sent');
    } catch {
      this.statusMsg.set('Failed to send test notification');
    } finally {
      this.testSending.set(false);
    }
  }
}
