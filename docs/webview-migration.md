# WebView Migration

Findings and reference for the WebView-backed screens in the BladeWatch Android
app: where they are, how the WebView host is implemented, how they talk to the
daemon, and what it takes to migrate each one to native.

> Status as of this document: **Live View was just migrated to native**
> (`LiveViewFragment` + `LiveViewController` + `LiveStreamClient`, H.264 over
> WebSocket). The screens below are the **remaining WebView destinations**.

---

## 1. The big picture

BladeWatch is a hybrid app. The UI shell, navigation rail, and an increasing
number of screens are **native** (Kotlin Fragments). The remaining screens are
rendered by an embedded **WebView** that loads static pages served by the
daemon's HTTP server on `http://127.0.0.1:8080`.

The whole app is on a slow migration from WebView → native. Screens that have
already been migrated: Dashboard, Recordings, Location (OSMDroid), Live View
(native camera). Settings → Appearance is native; Settings → Recording and
Settings → Surveillance are **not** native — their `Settings*Fragment`s are thin
wrappers that embed a `WebViewFragment` as a child (see §2).

The WebView pages are the same static assets that also serve remote
tunnel/browser clients, so they carry a lot of "works in a desktop browser AND
in a Chrome 58 head-unit WebView" baggage.

---

## 2. Remaining WebView destinations

All are instances of the single `WebViewFragment` class, differing only by the
`page_path` navigation argument. Defined in
[nav_graph.xml](../app/src/main/res/navigation/nav_graph.xml).

| Nav destination ID | `page_path` | Daemon route → file | How it's reached | Screenshot |
|---|---|---|---|---|
| `vehicleControlFragment` | `/vehicle-control` | `local/vehicle-control.html` | **Rail item "Vehicle"** (`MainActivity` rail) | `screenshots/04_vehicle.png` |
| `tripsFragment` | `/trips` | `local/trips.html` | **Rail item "Trips"** + Dashboard shortcut | `screenshots/05_trips_list.png`, `05a_trips_stats.png`, `05b_trips_storage.png` |
| `performanceFragment` | `/performance` | `local/performance.html` | **Diagnostics → Performance** (drill-down) | (sub-page of `07_diagnostics.png`) |
| `recordingSettingsWebFragment` | `/recording` | `local/recording.html` | **Portrait Settings hub card** (`cardSectionRecording`) + **Recordings → Settings ↗** (Dashcam) | `screenshots/11*_settings_recording_*.png` |
| `surveillanceSettingsWebFragment` | `/surveillance` | `local/surveillance.html` | **Portrait Settings hub card** (`cardSectionSurveillance`) + **Recordings → Settings ↗** (Surveillance) | `screenshots/12*_settings_surveillance_*.png` |

Notes on reachability:

- **Vehicle** and **Trips** are top-level navigation rail items
  ([MainActivity.kt:719-721](../app/src/main/java/com/loabletech/bladewatch/ui/MainActivity.kt#L719-L721)),
  so they are the most user-visible WebViews.
- **Performance** is a child of Diagnostics
  ([DiagnosticsFragment.kt:107](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/DiagnosticsFragment.kt#L107)) —
  surfaced as a diagnostics child so the rail stays on Diagnostics, not Live.
- **Recording / Surveillance settings are WebView in BOTH orientations.**
  - In **landscape**, the Settings sub-rail detail pane hosts
    `SettingsRecordingFragment` / `SettingsSurveillanceFragment` — but these are
    **thin wrappers** (~50 lines each) that create a `FrameLayout` and
    `commitNow` a child `WebViewFragment` pointed at `/recording` / `/surveillance`.
    They are **not** native reimplementations. See
    [SettingsRecordingFragment.kt](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/settings/SettingsRecordingFragment.kt)
    and
    [SettingsSurveillanceFragment.kt](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/settings/SettingsSurveillanceFragment.kt).
  - In **portrait**, the Settings hub cards
    ([SettingsFragment.kt:281-286](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/SettingsFragment.kt#L281-L286))
    drill down to the full-screen `recordingSettingsWebFragment` /
    `surveillanceSettingsWebFragment` destinations.
  - The **Recordings → Settings ↗** button also drills down to these web
    destinations
    ([RecordingsFragment.kt:542-543](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/RecordingsFragment.kt#L542-L543)).
  - **Implication:** migrating these requires a **full native build** of the
    settings UI plus rewiring three call sites (the two wrapper fragments, the
    portrait hub cards, and the Recordings ↗ button), then deleting the web
    routes. There is no native reference implementation to start from.

Other daemon pages exist but are **not** wired as nav destinations:
`about.html`, `notifications.html`, `events.html`, `index.html`,
`login.html`, `vehicle-control-3d-test.html`. `index.html` is the page the old
Live View used (a map-first dashboard) — now replaced by the native Live View.
`events.html` links are intercepted and rerouted to the native Recordings page
(see §4, `shouldOverrideUrlLoading`).

---

## 3. UI/UX observed from screenshots

The native shell is always present around every WebView:

- **Top app bar**: BYD head-unit status strip (clock, Radio, GPS/BT/Wi-Fi,
  notifications, profile) above an in-app title bar with the page title and a
  green **"Connecting…"** connection pill on the right.
- **Left navigation rail**: Dashboard, Live, Recordings, Vehicle, Trips,
  Integrations, Diagnostics, Settings, About — this is the MainActivity-level
  rail and frames every screen.
- **Bottom**: BYD's own climate/HVAC control strip (not part of our app).

The WebView fills the area between, and the page's own chrome is suppressed by
injected CSS (§4). Per-page content:

- **Vehicle** (`04_vehicle.png`): a 3D car model viewport ("Loading model…"),
  a top row of paint-color swatches + "BYD Seal" selector, and a bottom pill-tab
  bar: **Security · Trunk · Climate · Windows · Lights · Rear · Charging**.
  This page controls the physical car (BYD cloud APIs) — highest risk.
- **Trips** (`05_trips_list.png`): date-range filters (Select Date / 7 / 14 /
  30 Days), an empty-state ("No trips recorded yet"), a "Period Summary" card
  (Trips, km, Hours, Avg Score, kWh, kWh/100km), and a bottom pill-tab bar:
  **Trips · Stats · Storage**.
- **Recording settings** (`11_*`): "Recording Status" card (Current State,
  Recordings Today) with a bottom pill-tab bar: **Status · Capture · Quality ·
  Storage**.
- **Surveillance settings** (`12_*`): "Surveillance Mode" toggle + "Surveillance
  Schedule" cards with a bottom pill-tab bar: **General · Detection · Recording
  · Storage · Advanced**, plus an **"Apply Changes"** action.

The pill-tab bar at the bottom of these pages is the web `.bottom-tabs`
component; the injected CSS in the host specifically restyles and de-focuses it
(Chrome 58 leaves a stuck focus ring).

---

## 4. How the WebView host is implemented

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
  This is the fix for the Live View "map + overlap" the old `index.html` showed.
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
maps each route to a static file under `assets/web/local/` (extracted to
`/data/local/tmp/web` at runtime). Both `/foo` and `/foo.html` resolve to
`local/foo.html`. HTML is served `no-store`; shared static assets get 24 h
`max-age`. API routes (`/api/...`) are handled separately and gated by
`AuthMiddleware`.

---

## 5. The WebView ↔ daemon contract (what a native port must replicate)

Any native replacement must talk to the same daemon API the web page uses:

| Page | Primary API namespace | Notes |
|---|---|---|
| Vehicle | (BYD cloud control via daemon) + `/status` | Physical car control — test conservatively; see `byd-integrations.md` |
| Trips | `/api/trips...` | Date-range queries, stats, storage |
| Performance | `/api/performance...` | Live metrics dashboard, polls `/status` |
| Recording settings | `/api/recording/mode`, recording config | No native impl — `SettingsRecordingFragment` just wraps a WebView |
| Surveillance settings | `/api/surveillance...`, `/api/surveillance/safe-locations` | No native impl — `SettingsSurveillanceFragment` just wraps a WebView |

All mutating calls require the JWT auth cookie/header (see
[AuthMiddleware.java](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java)).
A native screen can mint a JWT directly via `AuthManager.generateJwt()` and call
the daemon over `HttpURLConnection`/OkHttp with `Proxy.NO_PROXY` — exactly what
`LiveStreamClient` does for the migrated Live View.

---

## 6. Migration considerations (per screen)

Ordered roughly by value-to-effort:

1. **Recording / Surveillance settings → native.** *Lowest physical risk* (no
   car control), but a **full native build** — there is no existing native
   implementation; the current `SettingsRecordingFragment` /
   `SettingsSurveillanceFragment` only wrap a WebView. Work = build the native
   settings UI (tabs, toggles, pickers wired to the daemon config API), swap the
   two wrapper fragments to render native content, repoint the portrait hub
   cards and the Recordings ↗ button, then delete the web routes.
2. **Trips → native.** Medium effort. Pure data UI (lists, filters, summary
   cards, 3 tabs) over `/api/trips`. No physical-control risk. Good candidate
   to follow the Recordings native pattern.
3. **Performance → native.** Medium. Live metrics dashboard; mostly polling
   `/api/performance` + `/status` and rendering charts/gauges. Consider whether
   it's worth it given it's a buried diagnostics child.
4. **Vehicle → native.** *Highest effort and risk.* Includes a 3D car model
   (WebGL) and controls that drive the **physical car** via BYD cloud APIs. A
   native port needs a 3D solution (or a simplified 2D control surface) and very
   careful testing of every control. Migrate last, if at all.

General pattern proven by Live View, Recordings, Location:
- New `XxxFragment` (thin) → `XxxController` (programmatic views or layout) →
  data/client class hitting the daemon API with `AuthManager.generateJwt()` +
  `NO_PROXY`.
- Honor the app theme via `PreferencesManager.getThemeMode()` (see
  `LocationAppearanceResolver` for the AUTO-mode pattern) and
  `onConfigurationChanged`.
- Update [nav_graph.xml](../app/src/main/res/navigation/nav_graph.xml) to point
  the destination at the new fragment; remove the `page_path` argument.

---

## 7. Gotchas carried by the WebView host (do not regress)

These are hard-won fixes encoded in `WebViewFragment`. If you keep any WebView
page, preserve them; if you port to native, you simply stop needing them:

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

## 8. File reference

- Host fragment: [WebViewFragment.kt](../app/src/main/java/com/loabletech/bladewatch/ui/fragment/WebViewFragment.kt)
- Host layout: [fragment_webview.xml](../app/src/main/res/layout/fragment_webview.xml)
- Nav graph: [nav_graph.xml](../app/src/main/res/navigation/nav_graph.xml)
- Route handling: [HttpServer.java](../app/src/main/java/com/loabletech/bladewatch/server/HttpServer.java)
- Auth: [AuthManager.java](../app/src/main/java/com/loabletech/bladewatch/auth/AuthManager.java), [AuthMiddleware.java](../app/src/main/java/com/loabletech/bladewatch/server/AuthMiddleware.java)
- Web assets: [app/src/main/assets/web/local/](../app/src/main/assets/web/local/)
- Native migration exemplars: `LiveViewFragment`/`LiveViewController`/`LiveStreamClient` (liveview), `RecordingsFragment`, `LocationFragment`
- Screenshots: [screenshots/](../screenshots/) — `04_vehicle.png`, `05_trips_list.png`, `05a_trips_stats.png`, `05b_trips_storage.png`, `11*_settings_recording_*.png`, `12*_settings_surveillance_*.png`
