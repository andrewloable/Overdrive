import { expect, type Page } from '@playwright/test';

/** Path where the authenticated browser state is persisted by auth.setup.ts. */
export const STORAGE_STATE = 'e2e/.auth/state.json';

/** Access code from the gitignored env (loaded by playwright.config.ts). */
export const ACCESS_CODE = process.env.BLADEWATCH_ACCESS_CODE ?? '';

/**
 * Protected routes behind the auth guard. Each routed page renders a host
 * element matching its component selector, inside the persistent <app-nav>
 * shell. `heading` is a stable, user-visible string on that page (used as a
 * secondary content assertion); the dashboard has no heading so it relies on
 * its component + nav only.
 */
export const ROUTES: ReadonlyArray<{
  path: string;
  name: string;
  component: string;
  heading?: string;
  /** true when reachable directly from the bottom tab bar; false = under "More". */
  primaryTab: boolean;
}> = [
  { path: '/', name: 'dashboard', component: 'app-dashboard', primaryTab: true },
  { path: '/recording', name: 'recording', component: 'app-recording', heading: 'Recordings', primaryTab: true },
  { path: '/surveillance', name: 'surveillance', component: 'app-surveillance', heading: 'Surveillance', primaryTab: true },
  { path: '/trips', name: 'trips', component: 'app-trips', heading: 'Trips', primaryTab: true },
  { path: '/vehicle', name: 'vehicle', component: 'app-vehicle', heading: 'Vehicle Control', primaryTab: true },
  { path: '/live', name: 'live', component: 'app-live', primaryTab: false },
  { path: '/location', name: 'location', component: 'app-location', primaryTab: false },
  { path: '/diagnostics', name: 'diagnostics', component: 'app-diagnostics', primaryTab: false },
  { path: '/events', name: 'events', component: 'app-events', heading: 'Events', primaryTab: false },
  { path: '/notifications', name: 'notifications', component: 'app-notifications', heading: 'Notifications', primaryTab: false },
  { path: '/performance', name: 'performance', component: 'app-performance', heading: 'Performance', primaryTab: false },
  { path: '/settings', name: 'settings', component: 'app-settings', heading: 'Settings', primaryTab: false },
  { path: '/about', name: 'about', component: 'app-about', heading: 'About', primaryTab: false },
];

/**
 * Fail fast with a clear message if the live-target env is not configured,
 * instead of producing a confusing "navigated to about:blank" error.
 */
export function requireEnv(): void {
  if (!process.env.BLADEWATCH_E2E_URL || !ACCESS_CODE) {
    throw new Error(
      'BladeWatch e2e is not configured. Copy web/e2e/.env.example to web/e2e/.env ' +
        'and set BLADEWATCH_E2E_URL and BLADEWATCH_ACCESS_CODE.',
    );
  }
}

/**
 * Drive the real (static) login page: wait for the device id to load, enter the
 * access code, submit, and wait until we land inside the authenticated SPA.
 *
 * The server redirects unauthenticated page requests to /login.html (a static
 * page whose login() builds `<deviceId>-<accessCode>` and POSTs /auth/token).
 * After success it navigates to '/', the Angular app boots, and — because the
 * non-HttpOnly `byd_auth=1` hint cookie is now set — the auth guard lets us
 * through to the dashboard.
 */
/**
 * Wait for the static login page to finish loading the device id from
 * /auth/status. Over a cold tunnel this fetch can be slow or transiently fail
 * ("Connection error"); reload once before giving up so a single hiccup does
 * not flake the suite.
 */
export async function waitForDeviceId(page: Page): Promise<void> {
  const deviceId = page.locator('#deviceIdDisplay');
  try {
    await expect(deviceId).toContainText('byd-', { timeout: 20_000 });
  } catch {
    await page.reload();
    await expect(deviceId).toContainText('byd-', { timeout: 25_000 });
  }
}

export async function loginViaUi(page: Page, accessCode = ACCESS_CODE): Promise<void> {
  await page.goto('/');
  await page.waitForURL(/\/login\.html/, { timeout: 30_000 });
  await waitForDeviceId(page);

  await page.locator('#tokenInput').fill(accessCode);
  await page.locator('#loginBtn').click();

  // Land inside the SPA shell — URL no longer on any /login page and nav visible.
  await page.waitForURL((url) => !/login/.test(url.pathname), { timeout: 30_000 });
  await expect(page.locator('app-nav')).toBeVisible({ timeout: 30_000 });
}

/**
 * Collect console + page errors during a callback. Returns the captured
 * messages so a test can assert specific regressions are absent (e.g. NG0908)
 * without being brittle about known, tolerated errors.
 */
export async function captureErrors(
  page: Page,
  run: () => Promise<void>,
): Promise<string[]> {
  const errors: string[] = [];
  const onConsole = (m: import('@playwright/test').ConsoleMessage) => {
    if (m.type() === 'error') errors.push(m.text());
  };
  const onPageError = (e: Error) => errors.push(String(e.stack || e.message || e));
  page.on('console', onConsole);
  page.on('pageerror', onPageError);
  try {
    await run();
  } finally {
    page.off('console', onConsole);
    page.off('pageerror', onPageError);
  }
  return errors;
}
