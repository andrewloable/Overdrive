# WebView Migration

Findings and reference for the WebView-backed screens in the BladeWatch Android
app: where they were, how the WebView host is implemented, and how each screen
was migrated to native.

> **Status: Migration complete.** All WebView destinations have been migrated to
> native. This document is retained for historical reference and as a guide to
> the `WebViewFragment` architecture, which is still present for the remote
> tunnel / browser UI path.

---

## 1. The big picture

BladeWatch is a hybrid app. The UI shell, navigation rail, and all primary
in-app screens are **native** (Kotlin Fragments). The daemon's HTTP server on
`http://127.0.0.1:8080` serves the embedded web UI — now an **Angular 19 SPA**
(see [Architecture](architecture.md) and the `web/` project) that talks to the
daemon over **ConnectRPC** — for remote browser/tunnel clients. All in-app
screens use native implementations; the SPA replaced the old static HTML pages
that the WebView host once rendered.

Migrated screens (complete list):

| Screen | Native implementation |
|---|---|
| Dashboard | `DashboardFragment` / `DashboardController` |
| Live View | `LiveViewFragment` / `LiveViewController` + `LiveStreamClient` (H.264/WebSocket) |
| Recordings | `RecordingsFragment` |
| Location | `LocationFragment` (OSMDroid) |
| Vehicle | `VehicleFragment` / `VehicleController` |
| Trips | `TripsFragment` / `TripsController` |
| Performance | `PerformanceFragment` / `PerformanceController` |
| Recording Settings | `SettingsRecordingFragment` / `RecordingSettingsController` |
| Surveillance Settings | `SettingsSurveillanceFragment` / `SurveillanceSettingsController` |

---

## 2. Previously: WebView destinations

All were instances of the single `WebViewFragment` class, differing only by the
`page_path` navigation argument. These destinations have all been replaced with
native fragments (see table above).

| Former nav destination ID | `page_path` | Status |
|---|---|---|
| `vehicleControlFragment` | `/vehicle-control` | ✓ Migrated → `VehicleController` |
| `tripsFragment` | `/trips` | ✓ Migrated → `TripsController` |
| `performanceFragment` | `/performance` | ✓ Migrated → `PerformanceController` |
| `recordingSettingsWebFragment` | `/recording` | ✓ Migrated → `RecordingSettingsController` |
| `surveillanceSettingsWebFragment` | `/surveillance` | ✓ Migrated → `SurveillanceSettingsController` |

Remote browser/tunnel access is now the **Angular SPA**, whose router covers the
same surface (dashboard, live, recording, surveillance, events, trips, vehicle,
location, diagnostics, notifications, performance, settings, about, login). The
old static HTML pages (`about.html`, `notifications.html`, `events.html`,
`index.html`, `login.html`, …) are retained only under the daemon's `/legacy/`
prefix for regression testing. In the native shell, `events`/`events.html` links
are still intercepted and rerouted to the native Recordings page (see §4,
`shouldOverrideUrlLoading`).

---

## 3. How the WebView host is implemented

Single class:
[WebViewFragment.kt](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt),
layout
[fragment_webview.xml](../app/src/main/res/layout/fragment_webview.xml).

### Layout
A `FrameLayout` stacking three views (IDs are `findViewById` targets — do not
rename): full-bleed `webView`, a centered `loadingOverlay` (M3 progress +
caption), and an `errorOverlay` (disconnect icon + "Camera daemon not running"
+ tonal `btnRetry`). Overlays use `?attr/colorBackground` so they blend with
the active light/dark theme.

### Construction & arguments
- Reads `page_path` argument; builds
  `http://127.0.0.1:${CameraDaemon.HTTP_PORT}${page_path}` (default
  `/surveillance`).
- `WebSettings`: JS enabled, DOM storage, `mediaPlaybackRequiresUserGesture =
  false`, file/content access **disabled**, `MIXED_CONTENT_ALWAYS_ALLOW`,
  `cacheMode = LOAD_DEFAULT` (respects daemon `Cache-Control`), wide viewport,
  hardware layer for video.

### Request interception (`shouldInterceptRequest`)
The crux of the host. **Every** `127.0.0.1:8080` and whitelisted CDN/map-tile
request is fetched manually over `HttpURLConnection` with **`Proxy.NO_PROXY`**,
because the head unit may have a system HTTP proxy configured that would
otherwise break localhost calls. Key behaviors:
- Injects the auth cookie (`byd_session=<jwt>; byd_auth=1`) on localhost fetches.
- `instanceFollowRedirects = false` so `AuthMiddleware`'s 302 → `/login.html`
  is visible to WebView (preserves back-nav).
- Forwards `Range` headers (vital for `.mp4` video seeking); forces
  `video/mp4` MIME for `.mp4`.
- Strips conditional-GET headers (`If-None-Match`/`If-Modified-Since`) and
  hop-by-hop / `Content-Encoding` / `Content-Length` headers so WebView doesn't
  double-decode or 304-with-empty-body.
- **Never returns `null` for a localhost URL** — synthesizes a `503` instead, so
  `onReceivedError` fires and the user sees the retry overlay rather than a
  forever-spinner (a Chrome 58 WebView hazard).
- External map tiles / CDN (`tile.openstreetmap.org`, `basemaps.cartocdn.com`,
  `unpkg.com`, `cdn.jsdelivr.net`, `fonts.googleapis.com`, `fonts.gstatic.com`)
  are fetched direct; on failure they fall back to WebView's own path.

### JS injection (`INJECT_JS`, run on every `onPageFinished`)
- Tags `<html data-app-shell="1">` so page CSS can opt into app-shell-only
  tweaks.
- Hides page-internal nav (`.sidebar`, `.mobile-header`, `.page-header`,
  `.pip-container`) since the native shell already provides rail + title bar.
- Many narrow-landscape layout fixes and a Chrome-58 focus-ring killer
  (`.bottom-tab` blur on tap).
- **Patches `window.fetch`**: routes **POST/PUT/DELETE** (writes) through the
  synchronous `AndroidBridge.httpRequest()` so they bypass the proxy; **GET**
  requests stay on the normal async path (so 3 s polling doesn't block the JS
  thread).

### Native JS bridge (`ProxyBypassBridge`, `@JavascriptInterface "AndroidBridge"`)
- `httpRequest(url, method, body, headers)` — synchronous direct
  (`NO_PROXY`) HTTP, always injects the auth cookie, returns the body with an
  injected `_status` field.
- `getAppTheme()` → `"light"`/`"dark"` — lets the page paint the correct theme
  on first paint (PreferencesManager → AppCompatDelegate → `uiMode`, in order).
- `getAppLocale()` → BCP-47 from `LocaleManager`.

### Auth (JWT)
- `getAuthJwt()` mints a JWT via `AuthManager.generateJwt()`, cached 5 min and
  pinned to `AuthManager.getStateVersion()`.
- `injectAuthCookie()` sets `byd_session` / `byd_auth` cookies via
  `CookieManager`, retrying up to ~10 s while the daemon writes the unified
  config on cold boot. Fires **at most one** reload once auth comes online
  (`authReloadFired`) to avoid the overlapping `loadUrl`+`reload` hang.

### Theme / locale live updates
- `applyTheme(theme)` / `applyLocale(lang)` are called on **every visible
  WebViewFragment** by `SettingsAppearanceFragment`
  ([line 111](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/settings/SettingsAppearanceFragment.kt#L111))
  so a theme/language switch is instant — no activity recreate, no page reload.
- `onPageFinished` and `onResume` re-stamp `<html data-theme>` so a toggle made
  while the page was backgrounded is reflected.

### Misc
- `onShowFileChooser` bridges `<input type=file>` into the Android photo picker
  (`OpenMultipleDocuments`), honoring `accept` MIME hints.
- `shouldOverrideUrlLoading` intercepts `events.html`/`events` links and routes
  them to the **native** `recordingsFragment` (with `filter`/`file` args);
  external `http(s)` links open in the system browser; localhost links load
  in-place.
- `onConsoleMessage` mirrors page console logs to logcat under tag `WebViewJS`.
- `onDestroyView` loads `about:blank`, stops loading, detaches, and destroys the
  WebView to avoid a leaked window.

### Daemon-side routing
[HttpServer.java](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java)
now serves the **Angular SPA** from `assets/web/angular/` (extracted to
`/data/local/tmp/web/angular` at runtime): `/` and any unrecognised path return
`angular/index.html` so the Angular router resolves the route client-side, and
hashed build chunks are served from `/assets/` and `/vendor/`. The legacy static
HTML pages are still reachable under the `/legacy/` prefix (mapping to
`assets/web/local/`) for regression testing. RPC calls hit
`/bladewatch.v1.<Service>/<Method>` and are dispatched by `ConnectDispatcher`;
inline REST routes (`/api/...`) are handled separately. All routes pass through
`AuthMiddleware` first.

---

## 4. Migration pattern (for reference)

The pattern used for all migrations, proven across Live View, Recordings,
Location, Vehicle, Trips, Performance, and Settings:

- New `XxxFragment` (thin) → `XxxController` (programmatic views or layout) →
  data/client class hitting the daemon API with `AuthManager.generateJwt()` +
  `NO_PROXY`.
- Honor the app theme via `PreferencesManager.getThemeMode()` (see
  `LocationAppearanceResolver` for the AUTO-mode pattern) and
  `onConfigurationChanged`.
- Update [nav_graph.xml](../app/src/main/res/navigation/nav_graph.xml) to point
  the destination at the new fragment; remove the `page_path` argument.
- All mutating calls require JWT auth (see
  [AuthMiddleware.java](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java)).
  Native screens mint a JWT directly via `AuthManager.generateJwt()` and call
  the daemon over `HttpURLConnection` with `Proxy.NO_PROXY`.

---

## 5. Gotchas carried by the WebView host (do not regress)

These are hard-won fixes encoded in `WebViewFragment`. If you ever add a new
WebView page, preserve them:

- **Proxy bypass is mandatory** — head units may have a system HTTP proxy that
  breaks localhost. All localhost + CDN traffic uses `Proxy.NO_PROXY`.
- **Never return `null` from `shouldInterceptRequest` for localhost** — it
  causes a forever-spinner. Synthesize a `503`.
- **Single auth-driven reload** (`authReloadFired`) — overlapping `loadUrl` +
  `reload` on Chrome 58 swallows `onPageFinished`.
- **GET stays async, writes go sync via the bridge** — a synchronous bridge GET
  during 3 s polling blocks the JS thread.
- **Strip `Content-Encoding`/conditional-GET headers** in the intercept — else
  WebView double-decodes or gets a 200-with-empty-body from a 304.
- **Theme first-paint** via `getAppTheme()` + `data-theme` stamping — else a
  light-mode device flashes dark on every page load.
- **Chrome 58 focus ring** — `.bottom-tab` keeps a stuck yellow outline; the
  host blurs it on tap.

---

## 6. File reference

- Host fragment: [WebViewFragment.kt](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt)
- Host layout: [fragment_webview.xml](../app/src/main/res/layout/fragment_webview.xml)
- Nav graph: [nav_graph.xml](../app/src/main/res/navigation/nav_graph.xml)
- Route handling: [HttpServer.java](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java)
- Auth: [AuthManager.java](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java), [AuthMiddleware.java](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java)
- Embedded web UI (Angular SPA): [web/](../web/), built/copied by [build.gradle.kts:497](../app/build.gradle.kts#L497), served from [app/src/main/assets/web/angular/](../app/src/main/assets/web/angular/)
- Legacy web assets (under `/legacy/`): [app/src/main/assets/web/local/](../app/src/main/assets/web/local/)
- Native migration exemplars: `LiveViewFragment`/`LiveViewController`/`LiveStreamClient`, `RecordingsFragment`, `LocationFragment`, `VehicleController`, `TripsController`, `PerformanceController`, `RecordingSettingsController`, `SurveillanceSettingsController`
