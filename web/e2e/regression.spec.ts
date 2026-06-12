import { test, expect } from '@playwright/test';
import { captureErrors } from './helpers';

/**
 * Regression tests for the two P0 bugs fixed in the webapp:
 *   - BladeWatch-0u0t: blank page — zone.js was never loaded, so the NgZone
 *     constructor threw NG0908 during bootstrap and <app-root> stayed empty.
 *   - BladeWatch-75gq: login loop — isAuthenticated() read the HttpOnly
 *     byd_session cookie (invisible to JS) and bounced every route to /login.
 *
 * Uses the authenticated storage state from auth.setup.ts.
 */
test.describe('regression', () => {
  test('app bootstraps and renders content (not blank)', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('app-root')).not.toBeEmpty({ timeout: 20_000 });
    await expect(page.locator('app-nav')).toBeVisible();

    const html = await page.locator('app-root').innerHTML();
    expect(html.length, 'app-root should contain a rendered component tree').toBeGreaterThan(200);
  });

  test('zone.js polyfill is loaded (NG0908 cause)', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('app-nav')).toBeVisible();
    const hasZone = await page.evaluate(() => typeof (globalThis as { Zone?: unknown }).Zone !== 'undefined');
    expect(hasZone, 'window.Zone must be defined or Angular throws NG0908').toBe(true);
  });

  test('no NG0908 bootstrap error on load', async ({ page }) => {
    const errors = await captureErrors(page, async () => {
      await page.goto('/');
      await expect(page.locator('app-nav')).toBeVisible();
      await page.waitForTimeout(1500);
    });
    // NOTE: a separate, tolerated "JIT compiler unavailable" error may appear
    // (tracked in BladeWatch-bhj6). We assert specifically on NG0908 so this
    // regression test is not coupled to that known issue.
    const ng0908 = errors.filter((e) => /NG0908/.test(e));
    expect(ng0908, `unexpected NG0908 errors: ${ng0908.join(' | ')}`).toHaveLength(0);
  });

  test('authenticated session persists across a full reload (no login loop)', async ({ page }) => {
    await page.goto('/surveillance');
    await expect(page.locator('app-surveillance')).toBeVisible({ timeout: 20_000 });

    await page.reload();

    expect(new URL(page.url()).pathname).toBe('/surveillance');
    await expect(page.locator('app-surveillance')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('app-nav')).toBeVisible();
  });

  test('clearing the session sends the guard back to login', async ({ page, context }) => {
    await page.goto('/');
    await expect(page.locator('app-dashboard')).toBeVisible();

    // Drop the JS-visible auth hint cookie the guard relies on, then navigate
    // to a protected route: the guard must redirect to login.
    await context.clearCookies();
    await page.goto('/settings');
    await expect(page.locator('app-settings')).toHaveCount(0);
    await expect(page).toHaveURL(/login/, { timeout: 20_000 });
  });
});
