import { defineConfig, devices } from '@playwright/test';
import { config as loadEnv } from 'dotenv';
import * as path from 'node:path';

// Load live-target config (base URL + access code) from a gitignored env file
// so no credentials or tunnel URLs are committed — BladeWatch is open source.
// See e2e/.env.example. Real values live in e2e/.env (gitignored).
loadEnv({ path: path.resolve(__dirname, 'e2e/.env') });

const BASE_URL = process.env.BLADEWATCH_E2E_URL?.replace(/\/$/, '') ?? '';

// Optional: point Playwright at an already-installed Chromium/Chrome binary
// instead of the one managed by `npx playwright install` (useful in CI images
// or when reusing a system browser). Leave unset for the default managed build.
const EXECUTABLE_PATH = process.env.PW_CHROMIUM_PATH;

export default defineConfig({
  testDir: './e2e',
  // The suite targets a single, live, shared device over a tunnel. Run serially
  // (one worker) so concurrent logins/streams/cold-starts don't contend, and
  // allow a couple of retries to absorb first-hit tunnel cold-start latency.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 2,
  timeout: 45_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: BASE_URL,
    ignoreHTTPSErrors: true,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    navigationTimeout: 30_000,
    ...(EXECUTABLE_PATH ? { launchOptions: { executablePath: EXECUTABLE_PATH } } : {}),
  },
  projects: [
    // 1) Log in once via the real UI and persist the session.
    { name: 'setup', testMatch: /auth\.setup\.ts/, use: { ...devices['Desktop Chrome'] } },

    // 2) Authenticated suites reuse the saved storage state.
    {
      name: 'chromium',
      testIgnore: /auth\.setup\.ts/,
      use: {
        ...devices['Desktop Chrome'],
        storageState: 'e2e/.auth/state.json',
      },
      dependencies: ['setup'],
    },
  ],
});
