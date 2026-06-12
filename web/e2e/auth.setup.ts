import { test as setup } from '@playwright/test';
import { loginViaUi, requireEnv, STORAGE_STATE } from './helpers';

/**
 * Authenticate once via the real login UI and persist the browser storage state
 * (including the HttpOnly byd_session cookie and the byd_auth hint cookie) so the
 * authenticated specs start already logged in. Runs as a dependency of the
 * `chromium` project — see playwright.config.ts.
 */
setup('authenticate', async ({ page }) => {
  requireEnv();
  await loginViaUi(page);
  await page.context().storageState({ path: STORAGE_STATE });
});
