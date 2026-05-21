/**
 * BYD Champ — Web-shell theme bootstrap
 *
 * SOTA pattern:
 *   1. **Instant first paint** — read localStorage synchronously and stamp
 *      <html data-theme="…"> BEFORE any other CSS evaluates. Eliminates the
 *      dark-flash that plagues "load JS, fetch, then apply" approaches.
 *   2. **Server-of-truth sync** — async-fetch /api/settings/appearance to
 *      pick up changes made on another device (or the Android shell). If the
 *      server value differs from localStorage, re-stamp + cache. Idempotent.
 *   3. **System mode** — when the user picks "auto", follow
 *      prefers-color-scheme and listen for changes so the app flips with the
 *      OS in real time. The picker remembers the auto choice; we don't store
 *      a derived dark/light value.
 *   4. **API surface** — BYD.theme.{get(), set(value), apply()} so the
 *      settings UI / dropdown picker works the same on every page.
 *
 * Default: dark. The Android WebViewFragment ALSO stamps data-theme on every
 * page-load — when running in-app, that wins (set later) and overrides this
 * file's stamp. Outside the app (Chrome / tunnel / standalone PWA), this
 * bootstrap is the only theme source.
 */
(function () {
    'use strict';
    var STORAGE_KEY = 'overdrive_theme';
    var DEFAULT_THEME = 'dark';

    // ─── Step 1: instant first paint ────────────────────────────────────────
    function readStoredTheme() {
        try {
            var v = localStorage.getItem(STORAGE_KEY);
            if (v === 'dark' || v === 'light' || v === 'auto') return v;
        } catch (e) {}
        return DEFAULT_THEME;
    }

    function resolveEffective(theme) {
        if (theme === 'auto') {
            var mq = window.matchMedia && window.matchMedia('(prefers-color-scheme: light)');
            return mq && mq.matches ? 'light' : 'dark';
        }
        return theme === 'light' ? 'light' : 'dark';
    }

    function stamp(effective) {
        document.documentElement.setAttribute('data-theme', effective);
    }

    // The bootstrap runs at <script> include time — for the first-paint
    // guarantee, this file MUST be loaded synchronously in <head> BEFORE
    // any stylesheet that depends on the variables. (CSS files cascade
    // from the top down: if data-theme isn't on <html> yet when CSS parses,
    // the page paints dark, then re-paints light a moment later — visible
    // flash.)
    //
    // Inside the Android WebView, the AndroidBridge interface is injected
    // by WebViewFragment.kt; the fragment ALSO stamps `data-theme` from the
    // app's appearance setting on every page-load. The web picker is
    // pointless there (it would be immediately overridden) so we suppress
    // the picker UI and the server sync, and just leave the fragment's
    // stamp as the only theme source. Detection is done via a feature
    // probe (typeof AndroidBridge) — a tunneled / external browser doesn't
    // see this interface.
    var IN_APP_WEBVIEW = (typeof window !== 'undefined' && typeof window.AndroidBridge !== 'undefined');

    if (IN_APP_WEBVIEW) {
        // Inside the Android WebView the app theme wins. Read it
        // synchronously via AndroidBridge.getAppTheme() so the very first
        // paint already uses the right palette (no dark→light flash on
        // light-mode devices). The fragment ALSO re-stamps in
        // onPageFinished as a belt-and-suspenders against any race where
        // CSS evaluates before this script runs.
        try {
            var appTheme = window.AndroidBridge.getAppTheme();
            stamp(appTheme === 'light' ? 'light' : 'dark');
        } catch (e) {
            // Bridge probe failed — fall back to dark default. The
            // fragment's onPageFinished injection will correct it shortly.
            stamp('dark');
        }
    } else {
        var stored = readStoredTheme();
        stamp(resolveEffective(stored));
    }

    // ─── Step 2: server sync ────────────────────────────────────────────────
    // Defer the fetch until DOMContentLoaded so it doesn't block first paint.
    // Skipped inside the Android WebView (the app theme is the source of
    // truth there; syncing the web preference would let it flicker over
    // the app's value on the next data-theme stamp).
    function syncWithServer() {
        if (IN_APP_WEBVIEW) return;
        try {
            fetch('/api/settings/appearance', { credentials: 'same-origin' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (j) {
                    if (!j || !j.theme) return;
                    var serverTheme = j.theme;
                    var local = readStoredTheme();
                    if (serverTheme !== local) {
                        try { localStorage.setItem(STORAGE_KEY, serverTheme); } catch (e) {}
                        stamp(resolveEffective(serverTheme));
                    }
                })
                .catch(function () { /* offline / no server — keep local */ });
        } catch (e) { /* fetch unavailable */ }
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', syncWithServer);
    } else {
        syncWithServer();
    }

    // ─── Step 3: system-mode listener ──────────────────────────────────────
    if (window.matchMedia) {
        var sysMq = window.matchMedia('(prefers-color-scheme: light)');
        var sysListener = function () {
            if (readStoredTheme() === 'auto') {
                stamp(resolveEffective('auto'));
            }
        };
        // Older Safari uses addListener instead of addEventListener.
        if (sysMq.addEventListener) sysMq.addEventListener('change', sysListener);
        else if (sysMq.addListener) sysMq.addListener(sysListener);
    }

    // ─── Step 4: public API ─────────────────────────────────────────────────
    window.BYD = window.BYD || {};
    window.BYD.theme = {
        /** "dark" | "light" | "auto" — the preference the user picked. */
        get: function () { return readStoredTheme(); },

        /**
         * Set a new theme. Persists locally (instant) AND to the server
         * (best-effort). If the server write fails, the local copy still
         * applies — an offline mode toggle survives the next page-load.
         *
         * @param value  "dark" | "light" | "auto"
         */
        set: function (value) {
            if (value !== 'dark' && value !== 'light' && value !== 'auto') return;
            try { localStorage.setItem(STORAGE_KEY, value); } catch (e) {}
            stamp(resolveEffective(value));
            try {
                fetch('/api/settings/appearance', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ theme: value })
                }).catch(function () { /* offline — local copy is still applied */ });
            } catch (e) {}
        },

        /** Re-apply the stored theme (e.g. after dynamic DOM changes). */
        apply: function () { stamp(resolveEffective(readStoredTheme())); }
    };

    // ─── Step 5: floating theme picker ──────────────────────────────────────
    // SOTA mini-picker injected once per page so the user can toggle without
    // navigating to a settings panel. Kept tiny (~36px button → 3-tile popup)
    // and collision-free with the camera top bar / vehicle-control bar by
    // anchoring to bottom-right with backdrop-filter glass.
    function injectPickerStyles() {
        if (document.getElementById('byd-theme-picker-style')) return;
        var s = document.createElement('style');
        s.id = 'byd-theme-picker-style';
        // Position is set inline by `applyPickerAnchor()` so per-page layout
        // hints (vehicle-control's vc-bar, live view's car selector) can
        // shift the picker out of the way without specificity wars. The
        // popup direction (`up` vs `down`) and horizontal alignment also
        // come from data attributes set by applyPickerAnchor().
        s.textContent = [
            '#bydThemePicker { position: fixed; z-index: 9000;',
            '   font-family: Inter, system-ui, sans-serif; }',
            '#bydThemePicker .byd-theme-fab {',
            '   width: 36px; height: 36px; border-radius: 50%;',
            '   display: flex; align-items: center; justify-content: center;',
            '   background: var(--bg-elevated, #1E1E24);',
            '   color: var(--text-primary, #fff);',
            '   border: 1px solid var(--border-default, rgba(255,255,255,0.1));',
            '   box-shadow: 0 4px 16px rgba(0,0,0,0.25);',
            '   cursor: pointer; transition: transform .15s ease, background .15s ease, opacity .15s ease;',
            '   -webkit-backdrop-filter: blur(12px); backdrop-filter: blur(12px); }',
            '#bydThemePicker .byd-theme-fab:hover { transform: scale(1.08); }',
            '#bydThemePicker .byd-theme-fab:active { transform: scale(0.94); }',
            '#bydThemePicker .byd-theme-fab svg { width: 18px; height: 18px; }',
            // Idle dim — fade the picker to 0.45 when the user isn\'t hovering
            // so it never competes with content underneath. Comes back to full
            // opacity on hover/focus AND while the popup is open.
            '#bydThemePicker:not(:hover):not(.open) .byd-theme-fab { opacity: 0.55; }',
            '#bydThemePicker .byd-theme-popup {',
            '   position: absolute; display: none; flex-direction: column; gap: 4px;',
            '   padding: 6px; min-width: 140px;',
            '   background: var(--bg-elevated, #1E1E24);',
            '   border: 1px solid var(--border-default, rgba(255,255,255,0.1));',
            '   border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.3); }',
            // Popup anchored above the FAB (default).
            '#bydThemePicker[data-popup="up"] .byd-theme-popup { right: 0; bottom: 44px; }',
            // Popup anchored below the FAB (used when picker sits high on the page).
            '#bydThemePicker[data-popup="down"] .byd-theme-popup { right: 0; top: 44px; }',
            // Left-aligned variants (when picker is at left edge of screen).
            '#bydThemePicker[data-popup="up-left"] .byd-theme-popup { left: 0; bottom: 44px; }',
            '#bydThemePicker[data-popup="down-left"] .byd-theme-popup { left: 0; top: 44px; }',
            '#bydThemePicker.open .byd-theme-popup { display: flex; }',
            '#bydThemePicker .byd-theme-opt {',
            '   display: flex; align-items: center; gap: 10px;',
            '   padding: 8px 12px; border-radius: 8px;',
            '   color: var(--text-secondary, #A1A1AA); font-size: 13px; font-weight: 500;',
            '   cursor: pointer; transition: background .12s ease, color .12s ease; }',
            '#bydThemePicker .byd-theme-opt:hover {',
            '   background: var(--bg-hover, rgba(255,255,255,0.06));',
            '   color: var(--text-primary, #fff); }',
            '#bydThemePicker .byd-theme-opt.active {',
            '   background: rgba(0, 212, 170, 0.12);',
            '   color: var(--brand-primary, #00D4AA); }',
            '#bydThemePicker .byd-theme-opt svg { width: 16px; height: 16px; flex-shrink: 0; }'
        ].join(' ');
        document.head.appendChild(s);
    }

    /**
     * Smart placement so the FAB doesn\'t collide with per-page bottom bars
     * (vehicle-control\'s vc-bar tabbed control panel, events\' tab-bar,
     * live view\'s car selector). Detection is structural — we look for the
     * element classes the host pages use and shift the FAB accordingly,
     * rather than baking page names into the picker. Re-runs on resize so
     * landscape rotation on the head unit picks the right corner each time.
     */
    function applyPickerAnchor(wrap) {
        if (!wrap) return;
        // Reset all positioning properties; the branch below re-sets only
        // the ones that apply. Transform is reset too so a previous
        // mid-edge placement doesn\'t carry over to a corner placement.
        wrap.style.top = wrap.style.bottom = wrap.style.left = wrap.style.right = '';
        wrap.style.transform = '';

        // Vehicle Control — keep on the RIGHT (per design), but stay clear
        // of the 3D Surround button at top:56-100 and the vc-bar tab panel
        // at bottom (which has an expandable panel that grows up to ~200px
        // tall). Mid-height on the right edge sits inside the empty 3D
        // viewport area between the secondary status row and the control
        // bar. Popup opens upward so it never clips the secondary row.
        if (document.querySelector('.vc-bar')) {
            wrap.style.top = '50%';
            wrap.style.right = '14px';
            wrap.style.transform = 'translateY(-50%)';
            wrap.setAttribute('data-popup', 'up');
            return;
        }

        // Live View (index.html) — `.seamless-camera-view` has the
        // `.camera-top-bar` overlay at top (connection pill + quality
        // selector + fullscreen button), the `.mini-preview` Map↔Cameras
        // toggle pinned bottom-right, the `.car-selector-bottom` panel
        // at the bottom (car silhouette + 5 hotspot labels), and a
        // floating `.menu-toggle` hamburger at top-left on mobile.
        // The only conflict-free spot is mid-height on the right edge,
        // tucked into the empty video letterbox area between the camera
        // top bar and the car selector. Same treatment as Vehicle Control.
        if (document.querySelector('.seamless-camera-view')) {
            wrap.style.top = '50%';
            wrap.style.right = '14px';
            wrap.style.transform = 'translateY(-50%)';
            wrap.setAttribute('data-popup', 'up');
            return;
        }

        // Events / Trips / Notifications — these are scrollable list pages
        // with a top filter bar and (on mobile) a bottom mobile-nav bar.
        // Default bottom-right works on desktop; on mobile we shift above
        // the mobile-nav (which is roughly 60-64px tall).
        if (document.querySelector('.mobile-nav, .footer-bar, .tab-bar')) {
            wrap.style.bottom = 'calc(80px + env(safe-area-inset-bottom, 0px))';
            wrap.style.right = '16px';
            wrap.setAttribute('data-popup', 'up');
            return;
        }

        // Default — bottom-right floating, popup opens upward.
        wrap.style.bottom = 'calc(16px + env(safe-area-inset-bottom, 0px))';
        wrap.style.right = '16px';
        wrap.setAttribute('data-popup', 'up');
    }

    function buildPickerDom() {
        if (document.getElementById('bydThemePicker')) return;
        var lang = (window.BYD && window.BYD.i18n) ? window.BYD.i18n.t : null;
        var t = function (key, fallback) {
            if (!lang) return fallback;
            var v = lang(key);
            return v && v !== key ? v : fallback;
        };
        var wrap = document.createElement('div');
        wrap.id = 'bydThemePicker';
        // Default popup direction; applyPickerAnchor() may override.
        wrap.setAttribute('data-popup', 'up');
        wrap.innerHTML =
            '<button class="byd-theme-fab" type="button" aria-label="Theme">' +
              '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">' +
                '<circle cx="12" cy="12" r="4"></circle>' +
                '<path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41"/>' +
              '</svg>' +
            '</button>' +
            '<div class="byd-theme-popup" role="menu">' +
              '<div class="byd-theme-opt" data-theme="dark" role="menuitem">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>' +
                '<span>' + t('theme.dark', 'Dark') + '</span>' +
              '</div>' +
              '<div class="byd-theme-opt" data-theme="light" role="menuitem">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg>' +
                '<span>' + t('theme.light', 'Light') + '</span>' +
              '</div>' +
              '<div class="byd-theme-opt" data-theme="auto" role="menuitem">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><path d="M12 3v18"/><path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor"/></svg>' +
                '<span>' + t('theme.auto', 'Auto') + '</span>' +
              '</div>' +
            '</div>';
        document.body.appendChild(wrap);

        var fab = wrap.querySelector('.byd-theme-fab');
        var opts = wrap.querySelectorAll('.byd-theme-opt');
        var refresh = function () {
            var cur = window.BYD.theme.get();
            for (var i = 0; i < opts.length; i++) {
                opts[i].classList.toggle('active', opts[i].dataset.theme === cur);
            }
        };
        refresh();
        fab.addEventListener('click', function (e) {
            e.stopPropagation();
            wrap.classList.toggle('open');
            refresh();
        });
        opts.forEach(function (o) {
            o.addEventListener('click', function () {
                window.BYD.theme.set(o.dataset.theme);
                refresh();
                wrap.classList.remove('open');
            });
        });
        document.addEventListener('click', function (e) {
            if (!wrap.contains(e.target)) wrap.classList.remove('open');
        });
        // Initial placement; recompute on resize so landscape ↔ portrait
        // rotation on the head unit picks the right corner each time.
        applyPickerAnchor(wrap);
        var anchorTimer = null;
        window.addEventListener('resize', function () {
            if (anchorTimer) clearTimeout(anchorTimer);
            anchorTimer = setTimeout(function () { applyPickerAnchor(wrap); }, 80);
        });
    }

    function mountPicker() {
        // Skip on login (no body yet during head load) — runs after DOMContentLoaded.
        // Skip if a host page explicitly opted out by setting body[data-no-theme-picker].
        // Skip inside the Android WebView — the app's Settings → Appearance
        // panel is the source of truth there; showing a redundant web picker
        // that gets overridden on next page-load would just confuse users.
        if (IN_APP_WEBVIEW) return;
        if (document.body && document.body.hasAttribute('data-no-theme-picker')) return;
        if (!document.body) return;
        injectPickerStyles();
        buildPickerDom();
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', mountPicker);
    } else {
        mountPicker();
    }

    // ─── Step 6: dynamic favicon / brand-logo selection ────────────────
    // Two icon families:
    //   • Baked tile (app-icon-{light,dark}.webp) — bg + glyph, used for
    //     favicon / apple-touch-icon where the surface has to be self-contained.
    //   • Transparent glyph (app-icon-glyph-{light,dark}.webp) — alpha + glyph
    //     only, used for in-page .brand-logo so the container can paint its
    //     own background from design-system tokens (no luminance mismatch).
    function applyThemeIcons(effective) {
        try {
            var isDark = (effective === 'dark');
            var tile = isDark ? '/shared/app-icon-dark.webp'        : '/shared/app-icon-light.webp';
            var glyph = isDark ? '/shared/app-icon-glyph-dark.webp' : '/shared/app-icon-glyph-light.webp';
            var fav = document.querySelector('link[rel~="icon"]');
            var at = document.querySelector('link[rel~="apple-touch-icon"]');
            var imgs = document.querySelectorAll('.brand-logo img');
            if (fav) fav.href = tile;
            if (at) at.href = tile;
            for (var i = 0; i < imgs.length; i++) imgs[i].src = glyph;
        } catch (e) { console.warn('applyThemeIcons', e); }
    }

    // Expose so late-mounted markup (sidebar injected by app-shell.js after
    // DOMContentLoaded) can re-apply once its <img> elements exist.
    window.BYD.theme.applyIcons = function () {
        var v = document.documentElement.getAttribute('data-theme');
        applyThemeIcons(v === 'light' ? 'light' : 'dark');
    };

    // Initial apply (favicon only — brand-logo imgs may not exist yet) and
    // observe future data-theme changes.
    try {
        applyThemeIcons(resolveEffective(readStoredTheme()));
        // Re-apply on DOMContentLoaded so any markup injected synchronously
        // during parse (or by scripts running before us) picks up the right
        // icon. app-shell.js also calls BYD.theme.applyIcons() after mount.
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', window.BYD.theme.applyIcons);
        } else {
            window.BYD.theme.applyIcons();
        }
        var mo = new MutationObserver(function (mutations) {
            mutations.forEach(function (m) {
                if (m.attributeName === 'data-theme') {
                    window.BYD.theme.applyIcons();
                }
            });
        });
        mo.observe(document.documentElement, { attributes: true });
    } catch (e) { /* noop */ }
})();
