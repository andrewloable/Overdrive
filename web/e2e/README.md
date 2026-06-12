# BladeWatch web — Playwright e2e

End-to-end tests for the hosted Angular SPA, run against a **live** BladeWatch
instance (zrok tunnel, device LAN IP, or `adb forward`). They exercise the real
login flow, every protected route, and regression cases for two P0 bugs.

## Setup

```bash
cd web
npm install                      # installs @playwright/test
npx playwright install chromium  # one-time browser download
cp e2e/.env.example e2e/.env     # then edit e2e/.env (gitignored)
```

`e2e/.env` (never committed):

```
BLADEWATCH_E2E_URL=https://<your-tunnel>.share.zrok.io
BLADEWATCH_ACCESS_CODE=xxxxxxxx
```

The access code is the 8-character value in the app under **Dashboard → Scan to
Connect**. The login page combines it with the device id (`<deviceId>-<code>`).

## Run

```bash
npm run test:e2e            # headless
npm run test:e2e:headed    # watch it drive a browser
npm run test:e2e:ui        # Playwright UI mode
npm run test:e2e:report    # open the last HTML report
```

The target must be reachable and the BladeWatch daemon running (for a zrok
target, the device must be on and the tunnel up).

## What's covered

| Spec | Covers |
| --- | --- |
| `auth.setup.ts` | Logs in via the static login page once; saves the session (storage state) for the authenticated specs. |
| `login.spec.ts` | Unauthenticated redirect to login; invalid code rejected; **valid code reaches the dashboard** (regression: login loop, `BladeWatch-75gq`). |
| `navigation.spec.ts` | All 10 protected routes mount their `app-*` component behind `<app-nav>`; bottom-tab and "More" overflow navigation. |
| `regression.spec.ts` | `<app-root>` not blank; `window.Zone` defined and **no NG0908** (regression: zone.js, `BladeWatch-0u0t`); session persists across reload; cleared session redirects to login. |

## Notes

- A tolerated `JIT compiler unavailable` console error may appear
  (`BladeWatch-bhj6`); the NG0908 assertion is scoped so it does not fail on it.
- Tests hit a shared device, so the config runs with low concurrency
  (`workers: 2`, `fullyParallel: false`).
- No URL or access code is committed — everything sensitive lives in the
  gitignored `e2e/.env`.
