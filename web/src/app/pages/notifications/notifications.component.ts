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
    if (
      !('serviceWorker' in navigator) ||
      !('PushManager' in window) ||
      !('Notification' in window)
    ) {
      this.statusMsg.set('Web Push is not supported in this browser');
      return;
    }
    const vapid = this.vapidKey();
    if (!vapid) {
      this.statusMsg.set('Server did not provide a VAPID key');
      return;
    }
    this.subscribing.set(true);
    this.statusMsg.set('');
    try {
      // Ask for notification permission first (Subscribe is an explicit user
      // action, so this is the right moment to prompt).
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') {
        this.statusMsg.set(
          permission === 'denied'
            ? 'Notifications are blocked in this browser'
            : 'Notification permission was not granted',
        );
        return;
      }

      // The SPA must register the service worker itself — without this,
      // navigator.serviceWorker.ready never resolves and Subscribe hangs on
      // "Subscribing…". /sw.js is served by the daemon (local/sw.js); its fetch
      // handler only intercepts known 3D assets and passes everything else
      // (HTML, /api/*, /bladewatch.v1.*, chunks, CDN) straight through, so a
      // root-scope registration is safe for the SPA. Mirrors pwa-init.js.
      await navigator.serviceWorker.register('/sw.js', { scope: '/' });
      const reg = await this.serviceWorkerReady(10_000);

      // Reuse an existing subscription if the browser already has one;
      // re-subscribing while one exists can throw.
      const sub =
        (await reg.pushManager.getSubscription()) ??
        (await reg.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: this.urlBase64ToUint8Array(vapid),
        }));

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

  /**
   * Resolves with the active SW registration, but rejects after `timeoutMs` so
   * a registration that never activates can't leave Subscribe stuck forever
   * (navigator.serviceWorker.ready alone never rejects).
   */
  private serviceWorkerReady(timeoutMs: number): Promise<ServiceWorkerRegistration> {
    return Promise.race([
      navigator.serviceWorker.ready,
      new Promise<ServiceWorkerRegistration>((_, reject) =>
        setTimeout(() => reject(new Error('Service worker did not activate')), timeoutMs),
      ),
    ]);
  }

  /**
   * Web Push expects applicationServerKey as a Uint8Array of the raw 65-byte
   * uncompressed P-256 point decoded from base64url (mirrors pwa-init.js).
   * Passing the raw base64url string works only in Chrome; other browsers throw.
   */
  private urlBase64ToUint8Array(b64: string): Uint8Array {
    const padding = '='.repeat((4 - (b64.length % 4)) % 4);
    const base64 = (b64 + padding).replace(/-/g, '+').replace(/_/g, '/');
    const raw = atob(base64);
    const arr = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; ++i) arr[i] = raw.charCodeAt(i);
    return arr;
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
