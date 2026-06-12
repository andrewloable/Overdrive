import { test, expect } from '@playwright/test';
import { ROUTES } from './helpers';

/**
 * Authenticated route coverage. Uses the shared storage state from auth.setup.ts
 * (configured on the `chromium` project), so every test starts logged in.
 *
 * For each protected route we assert:
 *   - we are NOT redirected to /login (auth guard lets us through),
 *   - the route's own component host element renders,
 *   - the persistent <app-nav> shell is present,
 *   - a stable heading is visible (where the page has one).
 */
test.describe('authenticated navigation', () => {
  for (const route of ROUTES) {
    test(`route ${route.path} renders ${route.component}`, async ({ page }) => {
      await page.goto(route.path);

      expect(new URL(page.url()).pathname, 'should not be bounced to login').not.toMatch(/login/);
      await expect(page.locator('app-nav')).toBeVisible();
      await expect(page.locator(route.component)).toBeVisible({ timeout: 20_000 });
      if (route.heading) {
        await expect(page.getByText(route.heading, { exact: false }).first()).toBeVisible();
      }
    });
  }

  test('bottom tab bar navigates between primary pages', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('app-dashboard')).toBeVisible();

    // Click through the primary tabs by their accessible labels.
    for (const route of ROUTES.filter((r) => r.primaryTab && r.path !== '/')) {
      await page.locator('app-nav').getByRole('link', { name: route.name, exact: false }).first().click();
      await expect(page).toHaveURL(new RegExp(`${route.path}$`));
      await expect(page.locator(route.component)).toBeVisible({ timeout: 20_000 });
    }
  });

  test('the "More" overflow menu reveals and opens secondary pages', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('app-dashboard')).toBeVisible();

    const secondary = ROUTES.filter((r) => !r.primaryTab);
    for (const route of secondary) {
      await page.getByRole('button', { name: 'More' }).click();
      await page.locator('.overflow-menu').getByRole('link', { name: route.name, exact: false }).first().click();
      await expect(page).toHaveURL(new RegExp(`${route.path}$`));
      await expect(page.locator(route.component)).toBeVisible({ timeout: 20_000 });
    }
  });
});
