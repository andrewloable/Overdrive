import { Component, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Global app header — parity with the native top app bar: a connection status
 * pill (dot + the URL you're connected through + copy) and a language / settings
 * quick-access button. Shown on every page except /login (the shell hides it).
 *
 * The tunnel URL isn't exposed by any web RPC, so the pill reflects the actual
 * reachable origin the client is using (the zrok tunnel host or the LAN IP).
 */
@Component({
  selector: 'app-header',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './header.component.html',
  styleUrl: './header.component.scss',
})
export class HeaderComponent {
  readonly host = signal(typeof window !== 'undefined' ? window.location.host : '');
  readonly copied = signal(false);

  async copyUrl(): Promise<void> {
    try {
      await navigator.clipboard.writeText(window.location.origin);
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1500);
    } catch { /* clipboard blocked — no-op */ }
  }
}
