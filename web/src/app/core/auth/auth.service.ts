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
    return document.cookie.split(';').some(c => c.trim().startsWith('byd_session='));
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
    document.cookie = 'byd_session=; path=/; max-age=0; samesite=lax';
    this.authState$.next(false);
    this.router.navigate(['/login']);
  }
}
