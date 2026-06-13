import {
  EnvironmentProviders,
  InjectionToken,
  makeEnvironmentProviders,
} from '@angular/core';
import { createConnectTransport } from '@connectrpc/connect-web';
import { type Transport } from '@connectrpc/connect';
import { authInterceptor } from './auth.interceptor';

/** Injection token for the ConnectRPC transport. */
export const CONNECT_TRANSPORT = new InjectionToken<Transport>('ConnectTransport');

/**
 * Registers the ConnectRPC transport in the Angular DI tree.
 *
 * baseUrl is intentionally empty (relative URLs) so the transport routes
 * through the Vite dev proxy in development and through the same loopback
 * origin in the production WebView.
 *
 * Add provideConnect() to the providers array in app.config.ts.
 */
export function provideConnect(): EnvironmentProviders {
  return makeEnvironmentProviders([
    {
      provide: CONNECT_TRANSPORT,
      useFactory: () =>
        createConnectTransport({
          baseUrl: '',
          interceptors: [authInterceptor],
          // Tolerate response fields not modeled in the proto. The daemon's
          // GetStatus re-emits the full REST /status JSON verbatim (the cosmetic
          // `status` string, nested `gps`, `network.signal`,
          // `battery.voltage/soc/lastUpdate`); without this, connect-web's strict
          // JSON parse rejects the whole response and the About page (and any
          // other GetStatus consumer) silently gets nothing.
          jsonOptions: { ignoreUnknownFields: true },
        }),
    },
  ]);
}
