import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { BehaviorSubject, from, map, tap, type Observable } from 'rxjs';
import { ConnectError, Code } from '@connectrpc/connect';
import { ConnectClients } from '../connect/connect-clients';
import type { GetAuthStatusResponse } from '../../../../gen/bladewatch/v1/auth_pb';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly clients = inject(ConnectClients);
  private readonly router = inject(Router);

  readonly authState$ = new BehaviorSubject<boolean>(this.isAuthenticated());

  login(token: string): Observable<void> {
    return from(this.clients.auth.login({ token })).pipe(
      tap(resp => {
        if (!resp.success) {
          throw new Error(resp.error || 'Invalid token');
        }
        this.authState$.next(true);
      }),
      map(() => void 0 as void),
    );
  }

  logout(): void {
    from(this.clients.auth.logout({})).subscribe({
      complete: () => this.clearAndRedirect(),
      error: () => this.clearAndRedirect(),
    });
  }

  getStatus(): Observable<GetAuthStatusResponse> {
    return from(this.clients.auth.getAuthStatus({}));
  }

  isAuthenticated(): boolean {
    // The JWT lives in the `byd_session` cookie, which the server sets HttpOnly
    // — JavaScript cannot read it, so checking it here always fails and traps
    // the user in a /login redirect loop. The server also sets a non-HttpOnly
    // `byd_auth=1` hint cookie (same lifetime, set by both the REST /auth/token
    // and Connect AuthService.Login paths) precisely so the SPA can detect an
    // active session. Test that instead.
    return document.cookie.split(';').some(c => {
      const t = c.trim();
      return t.startsWith('byd_auth=') && t.slice('byd_auth='.length) !== '';
    });
  }

  static connectErrorMessage(err: unknown): string {
    if (err instanceof ConnectError) {
      if (err.code === Code.InvalidArgument || err.code === Code.Unauthenticated) {
        return 'Invalid token';
      }
      return err.message || 'Connection error';
    }
    if (err instanceof Error) return err.message;
    return 'Unknown error';
  }

  private clearAndRedirect(): void {
    // byd_session is HttpOnly (the logout RPC expires it server-side); clear the
    // JS-readable byd_auth hint cookie locally so isAuthenticated() flips to
    // false immediately, before the guard re-evaluates.
    document.cookie = 'byd_session=; path=/; max-age=0; samesite=lax';
    document.cookie = 'byd_auth=; path=/; max-age=0; samesite=lax';
    this.authState$.next(false);
    this.router.navigate(['/login']);
  }
}
