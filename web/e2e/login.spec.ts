import { test, expect } from '@playwright/test';
import { ACCESS_CODE, loginViaUi, waitForDeviceId } from './helpers';

/**
 * Login-flow tests. These must start UNauthenticated, so they drop the shared
 * storage state from the `chromium` project.
 */
test.use({ storageState: { cookies: [], origins: [] } });

test.describe('login', () => {
  test('unauthenticated visitor is redirected to the login page', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/login(\.html)?/, { timeout: 30_000 });
    // The redirect + login form are the contract here; the device id loads
    // asynchronously from /auth/status and is asserted by the tests that
    // actually submit the form, so we don't gate this one on it.
    await expect(page.getByPlaceholder('Access code')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Login' })).toBeVisible();
  });

  test('an invalid access code is rejected and stays on login', async ({ page }) => {
    await page.goto('/');
    await page.waitForURL(/\/login(\b|$|[/?#])/, { timeout: 30_000 });
    await waitForDeviceId(page);

    await page.getByPlaceholder('Access code').fill('wrongco1'); // 8 chars, not the real code
    await page.getByRole('button', { name: 'Login' }).click();

    // Must NOT reach the app: no SPA shell, still on the login page.
    await expect(page.locator('app-nav')).toHaveCount(0);
    await page.waitForTimeout(2000);
    await expect(page).toHaveURL(/\/login/);
  });

  // Regression for BladeWatch-75gq: isAuthenticated() previously read the
  // HttpOnly byd_session cookie (invisible to JS), so the guard bounced every
  // post-login navigation back to /login — an infinite loop. A valid login must
  // now land on the dashboard and stay there.
  test('a valid access code logs in and reaches the dashboard (no /login loop)', async ({ page }) => {
    await loginViaUi(page, ACCESS_CODE);

    // The key guarantee: we are inside the app shell, not bounced back to login.
    expect(new URL(page.url()).pathname, 'must not be on a login route').not.toMatch(/login/);
    await expect(page.locator('app-nav')).toBeVisible();
    await expect(page.locator('app-dashboard')).toBeVisible({ timeout: 20_000 });
  });
});
