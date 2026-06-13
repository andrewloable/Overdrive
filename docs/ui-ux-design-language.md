# UI/UX Design Language

BladeWatch's interface follows **Material 3** (Material You), as defined at
<https://m3.material.io/>. The **native Android shell** is the canonical M3
surface — color roles, type scale, shape scale, elevation model, and motion
curves — and additionally adopts **Material 3 Expressive** refinements (tighter
type tracking, tonal active indicators) tuned for a large in-car display.

The **embedded web UI** has two generations. The legacy static pages (now served
only under the daemon's `/legacy/` prefix) mirror the native M3 system through
the generated [design-tokens.css](../app/src/main/assets/web/shared/design-tokens.css).
The current web UI is an **Angular 19 SPA** (`web/`) with its own component-scoped
SCSS; it shares the same visual language and design vocabulary but is **not** wired
to `design-tokens.css` — its SCSS uses CSS custom properties with literal
fallbacks rather than the canonical M3 role variables, so when changing a color
role it is the native layer and the legacy token pipeline that stay in lockstep,
not the SPA. Treat the design tokens below as the **Android source of truth**; the
SPA tracks the same look by convention.

> Material 3 version: **1.13.0** (Android Material Components), per
> [libs.versions.toml:9](../gradle/libs.versions.toml#L9).

## Two layers, one language

| Layer | Renders | M3 source of truth |
|-------|---------|--------------------|
| **Android** (native shell) | `MainActivity`, fragments, dialogs, navigation rail | [themes_bladewatch.xml](../app/src/main/res/values/themes_bladewatch.xml) (roles + widgets), [colors_m3.xml](../app/src/main/res/values/colors_m3.xml) (+ `values-night`), [dimens_bladewatch.xml](../app/src/main/res/values/dimens_bladewatch.xml) (shape/spacing) |
| **Legacy web** (static pages, `/legacy/`) | the old static HTML pages served by `CameraDaemon` | [design-tokens.css](../app/src/main/assets/web/shared/design-tokens.css) (CSS custom properties mirroring the same M3 roles) |
| **Web SPA** (Angular 19, `web/`) | the embedded web UI / browser / tunnel client | component-scoped SCSS under `web/src` (own values; same visual language, not wired to `design-tokens.css`) |

The Android shell and the **legacy** `design-tokens.css` are kept in **parity**:
the web `design-tokens.css` is generated to mirror `colors_m3.xml` (light) and its
`values-night` counterpart (dark). Changing a color role means changing it in both
places — see [Theming & token pipeline](#theming--token-pipeline). The Angular SPA
is **not** part of that generated pipeline; it carries its own SCSS values and
should be updated by hand to keep visual parity when roles change.

**Target device.** BYD Seal 15.6″ rotatable infotainment — landscape
`1920×1080` (`960×540dp`), portrait `1080×1920` (`540×960dp`). The design
language is tuned for this large, bright, glanceable surface, not a phone.

## UI Refactor Ground Rules

> Read this before starting any task under the **BladeWatch UI → Material 3**
> initiative. These rules are non-negotiable.

**Scope: visual / UI refactor ONLY.** The app is in production and runs
perfectly today. This initiative changes only the *appearance* of the UI to the
Material 3 language. It must **not change, add, or remove any functionality,
feature, or behavior.**

**Preserve all behavior:**

- Do not add new features/screens, and do not remove or hide existing ones.
- Do not change navigation structure, routes, or the set of actions/controls on
  any screen.
- Do not change the data shown, units, number/time formatting semantics,
  thresholds, or any business logic.
- Do not touch daemon / IPC / HTTP / WebSocket / BYD comms, config keys, storage
  paths, auth, or any non-UI code.
- Keep every user-visible string and its meaning — you may restyle text, not
  reword it.

**Do not break the code ↔ view wiring:**

- Do **not** rename or delete view IDs (`@+id/...`), click handlers,
  `ViewBinding` / `findViewById` references, adapter view types, tags, or
  `contentDescription`s that code relies on. If a view ID genuinely must change,
  update **every** reference in Kotlin/Java/tests in the same change.
- Keep view types compatible with the fragment's code (a `RecyclerView` stays a
  `RecyclerView`, a `ViewPager2` stays a `ViewPager2`, etc.).
- Icon refactors keep the **same drawable resource names/IDs** (`@drawable/ic_*`)
  so every layout/menu/code reference still resolves — only the vector art
  changes to the Material Symbols style.

**Allowed changes (visual only):** colors → M3 roles; corners → M3 shape scale;
text styles → M3 type scale; spacing/padding → M3 spacing tokens; swapping a raw
widget for the `Widget.BladeWatch.M3.*` equivalent **when behavior is
identical**; icons → M3 Material Symbols; ripple/press feedback; motion and
transitions.

**Verify before closing:** build, deploy to the head unit
(`192.168.0.251:5555`) following the clean-reinstall steps in `CLAUDE.md`, and
confirm the screen looks M3-correct in **light and dark** *and* behaves exactly
as before — every control, list, dialog, and data field works identically. A
behavior difference is a regression; fix it before closing.

**If unsure, stop and ask.** Never guess at functionality.

## Color

BladeWatch uses the full M3 tonal color-role system
(<https://m3.material.io/styles/color/system/overview>). Every UI color is a
**role**, never a raw hex value. Both **light and dark** variants ship and are
complete; dark is the default on the head unit.

**Accent roles** — each has a `*-container` and an `on-*` text pair:

- `primary` — teal-green brand accent (dark `#5DDBB6`, light `#00876C`); CTAs,
  active states, sliders.
- `secondary` — muted green; secondary affordances, nav active-indicator container.
- `tertiary` — sky-blue (dark `#85CFFF`); links, info accents, and the second
  stop of the brand accent stripe.
- `error` — destructive actions and validation.

**Surfaces & neutrals:**

- `background` / `surface` — the base canvas.
- Five **surface-container tiers** — `surface-container-lowest` → `-low` →
  `surface-container` → `-high` → `-highest` — used for tonal elevation (cards,
  sheets, and dialogs sit on progressively higher tiers).
- `surface-variant`, `surface-dim`, `surface-bright`.
- `outline` / `outline-variant` — borders, dividers, hints.
- `inverse-surface` / `inverse-on-surface` / `inverse-primary` — snackbars and
  inverted chips.
- `scrim` — modal scrims.

**Status colors** (BladeWatch domain, layered on top of M3):
`status-success`, `status-warning`, `status-danger`, `status-info` — SOC/battery
state, sentry state, alerts.

- Android: [colors_m3.xml](../app/src/main/res/values/colors_m3.xml) (light) and
  [values-night/colors_m3.xml](../app/src/main/res/values-night/colors_m3.xml)
  (dark), bound to theme attributes in
  [themes_bladewatch.xml](../app/src/main/res/values/themes_bladewatch.xml).
- Web: [design-tokens.css](../app/src/main/assets/web/shared/design-tokens.css) —
  dark in `:root`, light in `:root[data-theme="light"]`.

> **Rule:** reference color **roles**, never raw hex.

## Typography

Type follows the M3 type scale
(<https://m3.material.io/styles/typography/type-scale-tokens>) with **M3
Expressive** tightening so text reads crisp on a 1920×1080 head unit — stock M3
tracking looks soft at that size and viewing distance.

- **Android** overrides the display / headline / title / label roles to
  `sans-serif-medium` with negative tracking on the large roles and small
  positive tracking on labels. Body roles inherit M3 defaults (long-form text
  doesn't benefit from tighter tracking). See
  `TextAppearance.BladeWatch.*` in
  [themes_bladewatch.xml](../app/src/main/res/values/themes_bladewatch.xml).
- **Web** families: `Inter` (sans, `--family-sans`) and `JetBrains Mono`
  (mono, `--family-mono`).

| Role | Letter spacing (Android / web var) |
|------|-----------------------------------|
| Display Small | `-0.02` |
| Headline Large | `-0.02` |
| Headline Medium | `-0.015` |
| Headline Small | `-0.01` |
| Title Large | `-0.005` |
| Title Medium | `0` |
| Label Large | `+0.01` |
| Label Medium | `+0.04` |

Web tracking vars: `--tracking-display` `-0.02em`, `--tracking-headline`
`-0.015em`, `--tracking-title` `-0.005em`, `--tracking-label` `0.01em`.

## Shape & Elevation

### Shape

M3 rounded shape scale (<https://m3.material.io/styles/shape/overview>), mapped
to components:

| Token (Android / web) | Radius | Used by |
|-----------------------|--------|---------|
| `card_radius_xs` / `--radius-xs` | `4dp` | M3 extra-small: very small chips, badge corners |
| `card_radius_sm` / `--radius-sm` | `8dp` | M3 small: nav-rail active indicator, small badges |
| `card_radius_accent` / `--radius-md` | `14dp` | pills inside cards, segmented buttons, buttons |
| `card_radius_standard` / `--radius-lg` | `20dp` | dashboard metrics, diagnostics tiles, integration cards, settings sections |
| `card_radius_hero` / `--radius-xl` | `24dp` | dashboard hero, recordings preview pane |
| `card_radius_dialog` / `--radius-2xl` | `28dp` | dialogs and bottom sheets (M3 spec) |
| — / `--radius-full` | `full` | fully-rounded pills |

- Android: `card_radius_*` in
  [dimens_bladewatch.xml](../app/src/main/res/values/dimens_bladewatch.xml) and
  `ShapeAppearance.BladeWatch.Small`/`LargeComponent` in
  [themes.xml](../app/src/main/res/values/themes.xml).
- Web: `--radius-sm/md/lg/xl/2xl/full` in
  [design-tokens.css](../app/src/main/assets/web/shared/design-tokens.css).

### Elevation

M3 **tonal elevation** (<https://m3.material.io/styles/elevation/overview>):
components elevate by sitting on a higher **surface-container** tier, not by
casting heavy shadows.

- Android cards are flat — `cardElevation=0dp` on `colorSurfaceContainer`
  (Filled card style).
- Web exposes optional drop shadows `--shadow-sm/md/lg` (softer values under
  light theme) for floating elements, but surface tiers carry most of the
  hierarchy.

## Motion & Layout

### Motion

M3 motion (<https://m3.material.io/styles/motion/overview>) — durations and
easings in
[design-tokens.css](../app/src/main/assets/web/shared/design-tokens.css):

- Durations: `--duration-short` `180ms`, `--duration-med` `240ms`,
  `--duration-long` `320ms`.
- Easings: `--easing-standard` `cubic-bezier(0.2, 0, 0, 1)`,
  `--easing-emphasized` `cubic-bezier(0.05, 0.7, 0.1, 1)` (M3 emphasized),
  `--easing-decel` `cubic-bezier(0, 0, 0.2, 1)`.

Interactions share a consistent press/hover language — hover lifts onto a
tonal-primary surface, press scales down — between the native nav links and the
web brand cluster.

### Layout rhythm

Canonical spacing tokens. **Layouts must reference tokens, never hard-coded dp.**

| Token | Value |
|-------|-------|
| page padding (h / top / bottom) | `24` / `20` / `24` dp |
| inter-card gap | `12dp` (split `6`/`6` in horizontal grids) |
| card padding (standard / hero) | `20` / `24` dp |
| grid tile min-height | `128dp` |
| card icon (standard / service / hero) | `24` / `32` / `56` dp |

- Android:
  [dimens_bladewatch.xml](../app/src/main/res/values/dimens_bladewatch.xml).
- Web: `--page-pad-x`/`-y`, `--card-gap`, `--card-pad`, `--card-pad-hero`,
  `--tile-min-h` in
  [design-tokens.css](../app/src/main/assets/web/shared/design-tokens.css).

## Components

All native components derive from `Widget.Material3.*` via
`Widget.BladeWatch.M3.*` in
[themes_bladewatch.xml](../app/src/main/res/values/themes_bladewatch.xml); the
legacy web pages mirror them with the shared tokens, and the Angular SPA renders
the same component vocabulary in its own SCSS
(<https://m3.material.io/components>).

- **Cards** — Filled (`colorSurfaceContainer`, `0dp` elevation, `20dp` corners)
  is the default; the Outlined variant uses a `1dp` `colorOutlineVariant` stroke.
- **Buttons** — Filled, Tonal, Outlined, Text. `textAllCaps=false`,
  `sans-serif-medium`, `14dp` pill corners (Text = `10dp`). Outlined uses a
  `colorOutline` stroke.
- **Navigation rail** (primary navigation, M3 Expressive) — `colorSurface`
  background, `colorOnSurfaceVariant` items, a `colorSecondaryContainer`
  `56×32dp` pill **active indicator**, labels always visible.
- **Segmented buttons** — single-selection `MaterialButtonGroup`
  (e.g. the Dashcam / Surveillance mode toggle).
- **Slider** — `colorPrimary` track / thumb / halo; inactive track
  `colorSurfaceContainerHighest`.
- **Bottom sheet** — `colorSurfaceContainerLow`, large-component (`16dp`) shape.
- **Dialogs** — `28dp` corners on `colorSurfaceContainerHigh`; a **filled-tonal
  primary CTA** plus a flat-text neutral action; a tinted `32dp` `colorPrimary`
  title icon. Used by `MaterialAlertDialogBuilder` and the custom-view dialogs
  (setup guide, battery health, reset data, camera selection, ROI drawing, …).
- **Top app bar & accent stripe** — an optional glass top app bar; a `3px`
  `primary → tertiary` gradient **accent stripe** anchors the brand at the top
  of every page (the native toolbar and the web sidebar share the same
  gradient). See
  [app-shell.css](../app/src/main/assets/web/shared/app-shell.css).

## Icons

BladeWatch uses **Material Symbols Rounded** exclusively for all UI icons
(<https://m3.material.io/styles/icons/overview>). Every icon is drawn on a
`24dp` grid at **weight 400**, **grade 0**, **optical size 24**. Most icons use
**fill 0** (outlined); a small set use **fill 1** (filled) for active or
positive states.

### Implementation pattern

Android vector drawables use one of two approaches:

1. **Scaled group (most icons)** — viewport stays `24×24`; a `<group>` applies
   `scaleX/Y=0.025` + `translateY=24` so the Material Symbols 960-unit path
   data renders on the correct pixel grid without coordinate transforms in the
   path itself.
2. **Direct 960 viewport** (e.g. `ic_notifications`) — `viewportWidth/Height`
   is `960`; the `<group>` applies only `translateY=960`. This avoids a
   Chrome-58 / Android 7.1 clip bug that occasionally crops the trailing edge
   of paths rendered through an inner-group scale transform.

All M3 icons carry `android:tint="?attr/colorOnSurfaceVariant"` so they
auto-flip between light and dark themes. The navigation rail additionally
applies a `colorSecondaryContainer` active-indicator tint via the
`Widget.BladeWatch.M3.NavigationRailView` style — the icon drawable itself does
not encode that active color.

> **Rule:** when replacing icon art, keep the exact resource file name
> (`@drawable/ic_*`) so every layout, menu, and code reference continues to
> resolve — only the `<path>` data changes.

### Icon → Material Symbol mapping

| Resource ID | Material Symbol | Fill | Role |
|-------------|----------------|------|------|
| `@drawable/ic_back` | `arrow_back` | 0 | Navigation back button |
| `@drawable/ic_battery_health` | `battery_charging_full` | 0 | Battery health status |
| `@drawable/ic_camera_probe` | `photo_camera` | 0 | Camera setup / probe |
| `@drawable/ic_camera_select` | `switch_camera` | 0 | Camera source selection |
| `@drawable/ic_check` | `check` | 1 | Confirmation / done (filled) |
| `@drawable/ic_check_circle` | `check_circle` | 1 | Success state (filled circle) |
| `@drawable/ic_chevron_left` | `chevron_left` | 0 | Navigate left / collapse left |
| `@drawable/ic_chevron_right` | `chevron_right` | 0 | Navigate right / expand right |
| `@drawable/ic_clear` | `close` | 0 | Clear input / dismiss |
| `@drawable/ic_cloud` | `cloud` | 0 | Cloud connectivity |
| `@drawable/ic_collapse` | `expand_less` | 0 | Collapse panel upward |
| `@drawable/ic_console` | `terminal` | 0 | ADB debug console |
| `@drawable/ic_copy` | `content_copy` | 0 | Copy to clipboard |
| `@drawable/ic_daemons` | `hub` | 0 | Background daemon processes |
| `@drawable/ic_dashboard` | `dashboard` | 0 | Dashboard screen |
| `@drawable/ic_delete` | `delete` | 0 | Delete / remove |
| `@drawable/ic_diagnostics` | `monitor_heart` | 0 | System health diagnostics |
| `@drawable/ic_directions_car` | `directions_car` | 0 | Vehicle / car |
| `@drawable/ic_download_log` | `download` | 0 | Download log file |
| `@drawable/ic_error` | `error` | 1 | Error / failure (filled) |
| `@drawable/ic_events` | `event` | 0 | Surveillance events list |
| `@drawable/ic_expand` | `expand_more` | 0 | Expand panel downward |
| `@drawable/ic_favorite` | `favorite` | 1 | Favourite / saved trip (filled) |
| `@drawable/ic_filter_list` | `tune` | 0 | Filter / sort controls |
| `@drawable/ic_fullscreen` | `fullscreen` | 0 | Enter full-screen |
| `@drawable/ic_fullscreen_exit` | `fullscreen_exit` | 0 | Exit full-screen |
| `@drawable/ic_kofi` | `local_cafe` | 0 | Support link (Ko-fi) |
| `@drawable/ic_language` | `language` | 0 | Language selection |
| `@drawable/ic_link` | `link` | 0 | External link / URL |
| `@drawable/ic_live` | `live_tv` | 0 | Live camera stream |
| `@drawable/ic_location` | `location_on` | 1 | GPS location pin (filled) |
| `@drawable/ic_mqtt` | `router` | 0 | MQTT / network routing |
| `@drawable/ic_notifications` | `notifications` | 0 | Push notifications |
| `@drawable/ic_play_circle` | `play_circle` | 1 | Play video (filled circle) |
| `@drawable/ic_recording` | `videocam` | 0 | Dashcam recording |
| `@drawable/ic_route` | `route` | 0 | Navigation route |
| `@drawable/ic_sentry` | `shield` | 0 | Sentry / surveillance mode |
| `@drawable/ic_services` | `memory` | 0 | System services / CPU |
| `@drawable/ic_settings` | `settings` | 0 | Settings screen |
| `@drawable/ic_share` | `share` | 0 | Share action |
| `@drawable/ic_signal_disconnected` | `cloud_off` | 0 | Server disconnected |
| `@drawable/ic_smart_toy` | `smart_toy` | 0 | AI / ML object detection |
| `@drawable/ic_star` | `star` | 1 | Star / rating (filled) |
| `@drawable/ic_traffic_monitor` | `traffic` | 0 | Traffic monitoring |
| `@drawable/ic_trips` | `timeline` | 0 | Trips and analytics |
| `@drawable/ic_update` | `system_update` | 0 | App update available |
| `@drawable/ic_vehicle_control` | `directions_car` | 0 | Vehicle control tab |
| `@drawable/ic_videocam_off` | `videocam_off` | 0 | Recording off / camera muted |
| `@drawable/ic_vpn_lock` | `vpn_lock` | 0 | Secure tunnel / VPN |
| `@drawable/ic_warning` | `warning` | 0 | Warning / caution |

### Custom / non-Material-Symbols drawables

These files live in `drawable/` but are **not** Material Symbols icons and must
**not** be restyled to the outlined symbol set:

| Resource ID | Purpose | Notes |
|-------------|---------|-------|
| `@drawable/ic_sidebar_logo` | BladeWatch brand logo (72dp) | Custom artwork — cyan camera + glow ring |
| `@drawable/ic_status_dot` | 10dp solid circle | Tinted at call-site to `status_success`, `status_warning`, or `status_danger` |
| `@drawable/ic_overlay_rec_active` | Recording-active overlay indicator | Hardcoded green `#22C55E` (≈ M3 status-success) |
| `@drawable/ic_overlay_rec_inactive` | Recording-inactive overlay indicator | Grey videocam body + red slash |
| `@drawable/ic_overlay_trip_active` | Trip-active overlay indicator | Hardcoded green `#22C55E` navigation arrow |
| `@drawable/ic_overlay_trip_inactive` | Trip-inactive overlay indicator | Grey arrow + red slash |
| `@drawable/ic_launcher_background` | Adaptive launcher icon background | Not a UI icon |
| `@drawable/ic_launcher_foreground` | Adaptive launcher icon foreground | Not a UI icon |

## Theming & token pipeline

### Light / dark

- **Android** — `Theme.BladeWatch.M3` extends
  `Theme.Material3.Light.NoActionBar`; the dark variant lives in `values-night/`.
  Mode is driven by `AppCompatDelegate.setDefaultNightMode`. Component widget
  styles reference `?attr/color*`, so they auto-flip; system bars, window
  background, and text colors are all wired to M3 roles.
- **Legacy web** (`design-tokens.css`) — dark is the `:root` default;
  `:root[data-theme="light"]` overrides the roles. Components reference only CSS
  vars, so they are theme-agnostic.
- **Web SPA** (Angular) — theme is selected in the SPA's own SCSS / component
  logic; it does **not** load `design-tokens.css`, so the M3 role vars above are
  not in scope there.

### Token pipeline

- [colors_m3.xml](../app/src/main/res/values/colors_m3.xml) (+ `values-night`)
  is the **Android source of truth** for color roles.
- [design-tokens.css](../app/src/main/assets/web/shared/design-tokens.css) is
  **generated** — per its header, by `dev/build_design_tokens.py` from
  `dev/design-tokens.json`, which mirrors `colors_m3.xml` plus its night variant.
  **Do not hand-edit `design-tokens.css`**; edit the source and regenerate, then
  keep Android ↔ legacy-web **parity**. This pipeline feeds only the legacy
  static pages — the Angular SPA is **not** generated from it.
- Legacy aliases (`--bg-base`, `--bg-surface`, `--brand-primary`, …) resolve to
  the canonical M3 vars so older pages keep working, but **do not use them in
  new code** — use the M3 role names.

### Authoring rules

- Use **roles / tokens**, never raw hex or hard-coded `dp`.
- Native: inherit `Widget.BladeWatch.M3.*` / `TextAppearance.BladeWatch.*`;
  reference `?attr/color*` and `@dimen/*`.
- Legacy web pages: reference `var(--role)` / `var(--radius-*)` /
  `var(--duration-*)` from `design-tokens.css`.
- Angular SPA: keep its component SCSS visually aligned with the same M3 roles
  by hand (it does not inherit the token pipeline).
- Keep both light and dark complete for any new role.

## Source References

- M3 theme parent, color roles, and component widgets:
  [themes_bladewatch.xml:15](../app/src/main/res/values/themes_bladewatch.xml#L15)
  (theme parent),
  [themes_bladewatch.xml:95](../app/src/main/res/values/themes_bladewatch.xml#L95)
  (type-scale wiring),
  [themes_bladewatch.xml:177](../app/src/main/res/values/themes_bladewatch.xml#L177)
  (navigation rail),
  [themes_bladewatch.xml:194](../app/src/main/res/values/themes_bladewatch.xml#L194)
  (segmented buttons),
  [themes_bladewatch.xml:205](../app/src/main/res/values/themes_bladewatch.xml#L205)
  (dialog overlay),
  [themes_bladewatch.xml:315](../app/src/main/res/values/themes_bladewatch.xml#L315)
  (M3 Expressive type styles).
- Shape appearances:
  [themes.xml:10](../app/src/main/res/values/themes.xml#L10).
- Color roles (light / dark):
  [colors_m3.xml](../app/src/main/res/values/colors_m3.xml),
  [values-night/colors_m3.xml](../app/src/main/res/values-night/colors_m3.xml).
- Shape and spacing dimens:
  [dimens_bladewatch.xml:19](../app/src/main/res/values/dimens_bladewatch.xml#L19).
- Legacy web tokens (color / shape / type / motion / elevation):
  [design-tokens.css:12](../app/src/main/assets/web/shared/design-tokens.css#L12)
  (dark `:root`),
  [design-tokens.css:107](../app/src/main/assets/web/shared/design-tokens.css#L107)
  (light `:root[data-theme="light"]`).
- App-shell identity (accent stripe, app bar, nav affordance):
  [app-shell.css](../app/src/main/assets/web/shared/app-shell.css).
- Angular SPA styling (own SCSS, not the token pipeline):
  [web/src/styles.scss](../web/src/styles.scss),
  [web/src/app/shared/page-shared.scss](../web/src/app/shared/page-shared.scss).
- Material Components version: [libs.versions.toml:9](../gradle/libs.versions.toml#L9).
