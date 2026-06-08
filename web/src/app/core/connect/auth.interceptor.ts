import { type Interceptor } from '@connectrpc/connect';

function getCookieValue(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|;)\\s*' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * Connect interceptor that reads the byd_session cookie and injects it as a
 * Bearer Authorization header on every outgoing RPC request.
 *
 * On a permission_denied response from the server the caller must handle the
 * ConnectError and redirect to /login — that logic lives in each page component.
 */
export const authInterceptor: Interceptor = (next) => async (req) => {
  const jwt = getCookieValue('byd_session');
  if (jwt) {
    req.header.set('Authorization', `Bearer ${jwt}`);
  }
  return next(req);
};
