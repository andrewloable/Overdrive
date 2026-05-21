/**
 * BYD Champ - Core Module
 * Shared utilities, status polling, and toast notifications
 */

window.BYD = window.BYD || {};

/**
 * i18n runtime — ES5-compatible (Chrome 58 / Android 7.1 head-unit).
 *
 * Usage:
 *   BYD.i18n.t('nav.live_view')                 // → "Live View"
 *   BYD.i18n.t('trip.tier_score', {score: 85})  // → "Score: 85"
 *   BYD.i18n.plural('trip.stored', count)       // pluralized
 *   <span data-i18n="nav.events">Events</span>  // hydrated by BYD.i18n.hydrate(root)
 *   <input data-i18n-attr="placeholder:auth.code_placeholder">
 *
 * Why a custom runtime instead of i18next:
 *   - APK perf budget; i18next + Intl.PluralRules polyfill ~80KB minified.
 *   - Chrome 58 lacks Intl.PluralRules and modern template strings; we hand-roll
 *     plural rules from CLDR for our 16 supported langs (~3KB total).
 *   - One synchronous load before first paint avoids the flash-of-English.
 */
BYD.i18n = (function () {
    var SUPPORTED = [
        'en', 'zh-CN', 'zh-TW', 'pt-BR', 'es', 'de', 'fr', 'it',
        'nb', 'nl', 'ja', 'ko', 'th', 'vi', 'hi', 'tr', 'ru'
    ];
    var DEFAULT_LANG = 'en';
    var STORAGE_KEY = 'overdrive_locale';

    // Native-script display labels (sidebar picker shows these — no flags by design).
    var DISPLAY_NAMES = {
        'en':    'English',
        'zh-CN': '简体中文',
        'zh-TW': '繁體中文',
        'pt-BR': 'Português (Brasil)',
        'es':    'Español',
        'de':    'Deutsch',
        'fr':    'Français',
        'it':    'Italiano',
        'nb':    'Norsk',
        'nl':    'Nederlands',
        'ja':    '日本語',
        'ko':    '한국어',
        'th':    'ไทย',
        'vi':    'Tiếng Việt',
        'hi':    'हिन्दी',
        'tr':    'Türkçe',
        'ru':    'Русский'
    };

    // CLDR plural rules condensed to two-form (one/other) and language-specific quirks.
    // Returns 'one', 'few', 'many', or 'other' so translation files can carry a
    // matching nested object for any plural-aware key.
    function pluralRule(lang, n) {
        n = Math.abs(n);
        var i = Math.floor(n);
        switch (lang) {
            case 'zh-CN': case 'zh-TW': case 'ja': case 'ko': case 'th': case 'vi':
                return 'other';                            // no plural distinction
            case 'fr': case 'pt-BR':
                return n < 2 ? 'one' : 'other';            // 0 and 1 are singular
            case 'tr':
                return 'other';                            // optional plural marker, treat all as other
            case 'hi':
                return n === 0 || n === 1 ? 'one' : 'other';
            case 'ru':
                // Russian / Slavic three-form plural per CLDR:
                //   one  → ends in 1 but not 11           (1, 21, 31, ...; not 11)
                //   few  → ends in 2-4 but not 12-14      (2-4, 22-24, ...; not 12-14)
                //   many → ends in 0, 5-9, or 11-14       (0, 5-20, 25-30, ...)
                var mod10 = i % 10;
                var mod100 = i % 100;
                if (mod10 === 1 && mod100 !== 11) return 'one';
                if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'few';
                return 'many';
            default:
                // en, es, de, it, nb, nl
                return n === 1 ? 'one' : 'other';
        }
    }

    var state = {
        lang: DEFAULT_LANG,
        catalog: {},          // flat key → string OR { one, other, ... }
        loaded: false,
        loadingPromise: null,
        listeners: []
    };

    /** Normalise an arbitrary BCP-47 tag to our supported set, with sensible fallbacks. */
    function resolveLang(raw) {
        if (!raw) return DEFAULT_LANG;
        // Exact match first
        for (var i = 0; i < SUPPORTED.length; i++) {
            if (SUPPORTED[i].toLowerCase() === raw.toLowerCase()) return SUPPORTED[i];
        }
        // Region fallback: zh-Hans → zh-CN, zh-Hant → zh-TW, pt → pt-BR, etc.
        var lower = raw.toLowerCase();
        if (lower.indexOf('zh-hans') === 0 || lower === 'zh-cn' || lower === 'zh') return 'zh-CN';
        if (lower.indexOf('zh-hant') === 0 || lower === 'zh-tw' || lower === 'zh-hk') return 'zh-TW';
        if (lower.indexOf('pt') === 0) return 'pt-BR';
        if (lower.indexOf('no') === 0 || lower.indexOf('nn') === 0) return 'nb';
        // Bare-language fallback
        var bare = lower.split('-')[0];
        for (var j = 0; j < SUPPORTED.length; j++) {
            if (SUPPORTED[j].toLowerCase().split('-')[0] === bare) return SUPPORTED[j];
        }
        return DEFAULT_LANG;
    }

    function detectFromBrowser() {
        if (navigator.languages && navigator.languages.length) {
            for (var i = 0; i < navigator.languages.length; i++) {
                var resolved = resolveLang(navigator.languages[i]);
                if (resolved !== DEFAULT_LANG || navigator.languages[i].indexOf('en') === 0) {
                    return resolved;
                }
            }
        }
        return resolveLang(navigator.language);
    }

    function getStored() {
        try { return localStorage.getItem(STORAGE_KEY); } catch (e) { return null; }
    }
    function setStored(lang) {
        try { localStorage.setItem(STORAGE_KEY, lang); } catch (e) { /* private mode */ }
    }

    /** Fetch the catalog JSON for `lang`. Falls back to en on failure. */
    function fetchCatalog(lang) {
        return fetch('/i18n/' + lang + '.json', { cache: 'no-cache' })
            .then(function (r) {
                if (!r.ok) throw new Error('catalog ' + lang + ' http ' + r.status);
                return r.json();
            })
            .catch(function () {
                if (lang === DEFAULT_LANG) return {};
                return fetch('/i18n/' + DEFAULT_LANG + '.json').then(function (r) { return r.json(); });
            });
    }

    /** Look up a dotted key inside a nested catalog. */
    function lookup(catalog, key) {
        if (catalog[key] != null) return catalog[key];   // flat hit
        var parts = key.split('.');
        var cur = catalog;
        for (var i = 0; i < parts.length; i++) {
            if (cur == null) return null;
            cur = cur[parts[i]];
        }
        return cur == null ? null : cur;
    }

    /** {var} interpolation. Missing vars are left as-is so missing data is visible. */
    function interpolate(str, vars) {
        if (!vars || typeof str !== 'string') return str;
        return str.replace(/\{(\w+)\}/g, function (match, name) {
            return vars[name] != null ? vars[name] : match;
        });
    }

    function t(key, vars) {
        var val = lookup(state.catalog, key);
        if (val == null) {
            // No translation available. Two cases:
            //   1. Catalog hasn't loaded yet — return null so hydrate keeps
            //      the existing default text rather than writing the raw key.
            //   2. Catalog IS loaded but the key is missing — return the key
            //      as a dev-visible miss indicator.
            return state.loaded ? key : null;
        }
        if (typeof val === 'object' && val.other) val = val.other;
        return interpolate(val, vars);
    }

    function plural(key, count, vars) {
        var val = lookup(state.catalog, key);
        if (val == null) return key;
        if (typeof val === 'string') return interpolate(val, vars);
        var rule = pluralRule(state.lang, count);
        var pick = val[rule] != null ? val[rule] : (val.other != null ? val.other : val.one);
        if (pick == null) return key;
        var merged = { count: count };
        if (vars) for (var k in vars) if (vars.hasOwnProperty(k)) merged[k] = vars[k];
        return interpolate(pick, merged);
    }

    /**
     * Walk `root` and rewrite element text per [data-i18n] / attribute per
     * [data-i18n-attr="attr1:key1;attr2:key2"]. Idempotent — safe to call
     * many times. Stores the original key in dataset so subsequent language
     * switches re-translate from the catalog rather than the previous render.
     */
    function hydrate(root) {
        root = root || document;
        var nodes = root.querySelectorAll('[data-i18n]');
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            var key = n.getAttribute('data-i18n');
            var translated = t(key);
            // null = catalog not loaded yet → leave existing text alone, the
            // listener fired on catalog-ready will re-hydrate. Don't write
            // raw keys to DOM.
            if (translated == null) continue;
            // If the node has children other than the original text, only replace its
            // first text node so we don't blow away nested icons/SVGs (e.g. nav links).
            if (n.children.length > 0) {
                var replaced = false;
                for (var c = 0; c < n.childNodes.length; c++) {
                    var child = n.childNodes[c];
                    if (child.nodeType === 3 && child.nodeValue.replace(/\s/g, '').length > 0) {
                        child.nodeValue = translated;
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) n.appendChild(document.createTextNode(translated));
            } else {
                n.textContent = translated;
            }
        }
        var attrNodes = root.querySelectorAll('[data-i18n-attr]');
        for (var j = 0; j < attrNodes.length; j++) {
            var an = attrNodes[j];
            var spec = an.getAttribute('data-i18n-attr').split(';');
            for (var s = 0; s < spec.length; s++) {
                var pair = spec[s].split(':');
                if (pair.length === 2) {
                    var translatedAttr = t(pair[1].trim());
                    if (translatedAttr != null) {
                        an.setAttribute(pair[0].trim(), translatedAttr);
                    }
                }
            }
        }
        // Update <html lang="..."> so screen readers and CSS :lang() work.
        if (document.documentElement) document.documentElement.setAttribute('lang', state.lang);
    }

    function onChange(fn) { state.listeners.push(fn); }
    function notify() {
        for (var i = 0; i < state.listeners.length; i++) {
            try { state.listeners[i](state.lang); } catch (e) { console.error('[i18n]', e); }
        }
    }

    // True when the page is loaded inside the Android WebView (the app
     // shell injects a JavascriptInterface called AndroidBridge). The locale
     // policy differs between the two contexts:
     //
     //   In-app WebView   — the APP's locale is the source of truth. Any
     //                      web-side picker change is pushed to the server
     //                      (which writes the app-side LocaleManager); the
     //                      server's value is also pulled in via /status so
     //                      flipping language in the Android Settings panel
     //                      live-syncs the WebView.
     //   External tunnel  — the BROWSER's localStorage is the source of
     //                      truth. The picker writes only locally; we do
     //                      NOT post to the server (would cross-pollute
     //                      the app's locale) and we do NOT honour
     //                      `status.locale` overrides (that's the app's
     //                      preference, not ours).
     //
     // This keeps the two locales fully separated, matching the design
     // already in place for the theme picker.
    function inAppWebView() {
        return typeof window !== 'undefined' && typeof window.AndroidBridge !== 'undefined';
    }

    /**
     * Switch active language. Always persists locally. Server persistence
     * splits two ways:
     *   In-app WebView  → POST /api/i18n/lang (writes the app's
     *                     LocaleManager so server-emitted strings match).
     *   External tunnel → POST /api/settings/appearance with {locale}
     *                     (writes a SEPARATE web-only locale into the
     *                     unified config). Survives tunnel-URL rotation:
     *                     each new zrok session is a fresh origin so
     *                     localStorage alone is not enough.
     * Either way, server writes are fire-and-forget; the catalog refetch
     * is the only thing the UI waits on.
     */
    function setLang(lang) {
        var resolved = resolveLang(lang);
        if (resolved === state.lang && state.loaded) return Promise.resolve();
        state.lang = resolved;
        setStored(resolved);
        if (inAppWebView()) {
            try { fetch('/api/i18n/lang', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ lang: resolved })
            }); } catch (e) {}
        } else {
            try { fetch('/api/settings/appearance', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ locale: resolved })
            }); } catch (e) {}
        }
        return fetchCatalog(resolved).then(function (cat) {
            state.catalog = cat || {};
            state.loaded = true;
            hydrate(document);
            notify();
        });
    }

    /**
     * Returns true if the live `status.locale` from the server should
     * override the locally-chosen language. In-app WebView: yes (app is
     * the source of truth). External tunnel/browser: no (web picker is
     * the source of truth). Exposed via the public API so core.js's
     * /status handler can decide whether to call setLang().
     */
    function shouldFollowServerLocale() {
        return inAppWebView();
    }

    /**
     * Bootstrap. Pick order depends on context:
     *   In-app WebView  — AndroidBridge.getAppLocale() (sync, always fresh)
     *                     → localStorage → navigator.language → 'en'
     *   External        — localStorage → navigator.language → 'en'
     *                     and asynchronously sync from /api/settings/appearance
     *                     so a freshly-rotated tunnel URL still serves the
     *                     user's last-picked language (localStorage is per
     *                     origin, the server-stored value survives URL flips).
     */
    function init() {
        if (state.loadingPromise) return state.loadingPromise;
        var picked = null;
        if (inAppWebView()) {
            try {
                if (typeof window.AndroidBridge.getAppLocale === 'function') {
                    var fromApp = window.AndroidBridge.getAppLocale();
                    if (fromApp) {
                        picked = fromApp;
                        // The app's locale is the source of truth in-app.
                        // Mirror it into localStorage so a stale value left
                        // there from before this code shipped (or from a
                        // prior tunnel session on the same WebView profile)
                        // can never resurface in a future load. Without
                        // this, a user who picked French on the tunnel,
                        // then English in the app, would still see French
                        // until they cleared cache.
                        try { setStored(resolveLang(fromApp)); } catch (e) {}
                    }
                }
            } catch (e) { /* fall through to localStorage */ }
        }
        if (!picked) picked = getStored() || detectFromBrowser();
        state.lang = resolveLang(picked);
        state.loadingPromise = fetchCatalog(state.lang).then(function (cat) {
            state.catalog = cat || {};
            state.loaded = true;
            hydrate(document);
            notify();
            // External mode: pull the server-stored web locale to handle
            // the tunnel-URL-rotation case (localStorage on the new
            // origin is empty, but the server remembers the last pick).
            // Skipped in-app — the AndroidBridge sync read above is
            // already authoritative.
            if (!inAppWebView()) {
                fetchServerWebLocale().then(function (serverLang) {
                    if (!serverLang) return;
                    var resolved = resolveLang(serverLang);
                    if (resolved && resolved !== state.lang) {
                        // Mirror the server pick into localStorage so a
                        // subsequent reload short-circuits without a fetch.
                        setStored(resolved);
                        // setLang() refetches + rehydrates. Skip the
                        // server POST inside it (we just READ the value).
                        state.lang = resolved;
                        fetchCatalog(resolved).then(function (cat2) {
                            state.catalog = cat2 || {};
                            hydrate(document);
                            notify();
                        });
                    }
                });
            }
        });
        return state.loadingPromise;
    }

    /** Read the web-only locale from /api/settings/appearance. Returns null
     *  on any failure or if the server has the "auto" sentinel (which means
     *  "no explicit pick — use the local detection"). */
    function fetchServerWebLocale() {
        try {
            return fetch('/api/settings/appearance', { credentials: 'same-origin' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (j) {
                    if (!j || !j.locale || j.locale === 'auto') return null;
                    return j.locale;
                })
                .catch(function () { return null; });
        } catch (e) {
            return Promise.resolve(null);
        }
    }

    return {
        init: init,
        t: t,
        plural: plural,
        hydrate: hydrate,
        setLang: setLang,
        onChange: onChange,
        getLang: function () { return state.lang; },
        getDisplayName: function (lang) { return DISPLAY_NAMES[lang] || lang; },
        supported: function () { return SUPPORTED.slice(); },
        // True when the app's server-side locale should override the local
        // pick — i.e. inside the Android WebView. The /status poll uses
        // this to avoid clobbering a tunnel user's web-only choice.
        shouldFollowServerLocale: shouldFollowServerLocale,
        // For tests / picker UI
        _resolve: resolveLang
    };
})();

/**
 * Unit formatting utility. All backend values are stored in km/km·h.
 * When the user's vehicle is set to miles, this module converts for display.
 * The mode is updated from the /status response on every poll cycle.
 */
BYD.units = {
    mode: 'km',  // 'km' or 'mi' — updated from /status.distanceUnit
    KM_TO_MI: 0.621371,

    /** Format a distance value (stored in km) for display. */
    dist(km, decimals) {
        if (km == null || isNaN(km)) return '--';
        if (this.mode === 'mi') return Math.round(km * this.KM_TO_MI) + ' mi';
        return (decimals != null ? km.toFixed(decimals) : Math.round(km)) + ' km';
    },

    /** Format a speed value (stored in km/h) for display. */
    speed(kmh, decimals) {
        if (kmh == null || isNaN(kmh)) return '--';
        var d = decimals != null ? decimals : 1;
        if (this.mode === 'mi') return (kmh * this.KM_TO_MI).toFixed(d) + ' mph';
        return kmh.toFixed(d) + ' km/h';
    },

    /** Return just the distance unit label. */
    distLabel() { return this.mode === 'mi' ? 'mi' : 'km'; },

    /** Return just the speed unit label. */
    speedLabel() { return this.mode === 'mi' ? 'mph' : 'km/h'; },

    /** Convert km value to display value (number only, no label). */
    distVal(km) {
        if (km == null || isNaN(km)) return 0;
        return this.mode === 'mi' ? Math.round(km * this.KM_TO_MI) : Math.round(km);
    },

    /** Convert km/h value to display value (number only, no label). */
    speedVal(kmh) {
        if (kmh == null || isNaN(kmh)) return 0;
        return this.mode === 'mi' ? kmh * this.KM_TO_MI : kmh;
    },

    /**
     * Convert a "per-100km" rate (kWh/100km, %/100km, anything-per-100km)
     * to "per-100mi". Same rate over a longer distance unit, so the
     * numerator scales by 1/KM_TO_MI ≈ 1.609.
     */
    per100Val(perKm) {
        if (perKm == null || isNaN(perKm)) return 0;
        return this.mode === 'mi' ? perKm / this.KM_TO_MI : perKm;
    },

    /** Per-100 consumption label: "kWh/100km" or "kWh/100mi". */
    consumptionLabel() { return this.mode === 'mi' ? 'kWh/100mi' : 'kWh/100km'; },

    /** "per km" or "per mi" for cost display. */
    perDistLabel() { return this.mode === 'mi' ? '/mi' : '/km'; },

    /** "%/km" or "%/mi" for SoC-based efficiency. */
    socPerDistLabel() { return this.mode === 'mi' ? ' %/mi' : ' %/km'; },

    /** Round a km/h threshold (40, 80) to the user's unit for legend labels. */
    speedThreshold(kmh) {
        return this.mode === 'mi' ? Math.round(kmh * this.KM_TO_MI) : kmh;
    }
};

BYD.core = {
    deviceId: null,
    pollInterval: null,
    lastStatus: null,
    // Counts /status fetch failures (network error, non-2xx, JSON parse).
    // Drives the UI "stale" / "disconnected" indicators and a sooner retry,
    // so a brief tunnel/Wi-Fi blip doesn't blank the dashboard for 5 s.
    pollFailureCount: 0,
    // Whether we've ever received a populated vehicle-data status. Used to
    // decide between "Waiting for vehicle…" (first load, binders not bound
    // yet) and last-known-good (we had data, transient error since).
    hasEverHadVehicleData: false,
    POLL_INTERVAL_OK_MS: 5000,
    POLL_INTERVAL_RETRY_MS: 1500,
    POLL_STALE_AFTER_FAILURES: 2,

    /**
     * Initialize core module
     */
    init() {
        this.startStatusPolling();
        this.startClock();
        console.log('[Core] Initialized');
    },

    /**
     * Start clock update (if element exists)
     */
    startClock() {
        const update = () => {
            const el = document.getElementById('currentTime');
            if (el) {
                el.textContent = new Date().toLocaleTimeString(BYD.i18n.getLang(), {
                    hour: '2-digit',
                    minute: '2-digit',
                    hour12: false
                });
            }
        };
        update();
        setInterval(update, 1000);
    },

    /**
     * Start status polling.
     *
     * Adaptive cadence: 5 s on success, 1.5 s while failing. Self-rescheduling
     * (no fixed setInterval) so the next tick always reflects the current
     * health — without this, a single long-fail period would still hold the
     * UI in "stale" for the full 5 s after recovery.
     */
    startStatusPolling() {
        var self = this;
        function tick() {
            self.refreshStatus().then(function (ok) {
                var delay = ok ? self.POLL_INTERVAL_OK_MS : self.POLL_INTERVAL_RETRY_MS;
                self.pollInterval = setTimeout(tick, delay);
            });
        }
        tick();
    },

    /**
     * Refresh status from server (consolidated — includes GPS, vehicle data, etc).
     *
     * Resilience contract:
     *   - On HTTP 401: redirect to /login.html (JWT expired or never set).
     *   - On network/parse error: keep previously-rendered values, increment
     *     failure counter, and let startStatusPolling() retry sooner. We do
     *     NOT reset cards to "--" on a single bad poll — that's what made
     *     the dashboard look broken on tunnel hiccups.
     *   - On success but vehicleDataReady=false: show "Waiting for vehicle…"
     *     in the EV card on first load; keep last-known after that.
     *
     * @returns {Promise<Object|null>} the parsed status object on success
     *          (truthy → OK, used by tick() and update-flow's drift watch),
     *          or null on failure.
     */
    async refreshStatus() {
        try {
            const res = await fetch('/status');
            // 401 means JWT is missing/expired/invalid — bounce to login so
            // the user lands on a screen that actually does something. The
            // global fetch wrapper in auth.js attaches Authorization but does
            // NOT redirect on 401; we handle it here for /status specifically.
            if (res.status === 401) {
                this._showStaleBanner('disconnected');
                const path = window.location.pathname + window.location.search;
                window.location.href = '/login.html?redirect=' + encodeURIComponent(path);
                return null;
            }
            if (!res.ok) {
                throw new Error('HTTP ' + res.status);
            }
            const status = await res.json();
            this.pollFailureCount = 0;
            this._clearStaleBanner();
            this.lastStatus = status;
            // Track whether the server has ever delivered real vehicle data.
            // Drives the "Waiting for vehicle…" placeholder vs. last-known
            // behaviour on cards downstream.
            const hadData = !!(status.soc || status.range || status.charging);
            if (hadData) this.hasEverHadVehicleData = true;

            // Distance unit preference (from user setting / auto-detect)
            if (status.distanceUnit) {
                BYD.units.mode = status.distanceUnit;
            }

            // Locale sync — ONLY in the Android WebView, where the app's
            // language picker is the source of truth. External tunnel /
            // browser users keep their own web-only locale; we must not
            // clobber their pick with the app's server-side LocaleManager
            // value (which is what status.locale carries).
            if (status.locale && BYD.i18n
                    && BYD.i18n.shouldFollowServerLocale
                    && BYD.i18n.shouldFollowServerLocale()
                    && status.locale !== BYD.i18n.getLang()) {
                BYD.i18n.setLang(status.locale);
            }

            // Device ID
            if (status.deviceId) {
                this.deviceId = status.deviceId;
                const el = document.getElementById('deviceId');
                if (el) el.textContent = status.deviceId;
            }

            // App version
            if (status.appVersion) {
                const el = document.getElementById('appVersion');
                if (el) el.textContent = 'v' + status.appVersion;
            }

            // 12V Battery
            if (status.battery) {
                const el = document.getElementById('batteryValue');
                if (el) el.textContent = (status.battery.voltage || 0).toFixed(1) + 'V';
            }

            // ACC status
            const accEl = document.getElementById('accValue');
            if (accEl) {
                accEl.textContent = status.acc ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
                accEl.className = 'status-value ' + (status.acc ? 'on' : 'off');
            }

            // Surveillance status
            const survEl = document.getElementById('survStatus');
            if (survEl) {
                if (status.safeZoneSuppressed || status.inSafeZone) {
                    survEl.textContent = '🏠 ' + BYD.i18n.t('status.safe');
                    survEl.className = 'status-value safe';
                } else {
                    const active = status.gpuSurveillance || false;
                    survEl.textContent = active ? BYD.i18n.t('status.on') : BYD.i18n.t('status.off');
                    survEl.className = 'status-value ' + (active ? 'on' : 'off');
                }
            }

            // Network status (WiFi SSID + IP or Mobile Data)
            this.updateNetworkStatus(status);

            // Connection dot
            const connDot = document.getElementById('connDot');
            if (connDot) {
                connDot.classList.add('connected');
            }

            // EV Battery SOC
            this.updateEvStatus(status);

            // GPS data is now in status.gps - notify map module if exists
            if (status.gps && BYD.map && BYD.map.updateFromStatus) {
                BYD.map.updateFromStatus(status.gps);
            }

            // Notify surveillance module if exists
            if (BYD.surveillance && BYD.surveillance.updateFromStatus) {
                BYD.surveillance.updateFromStatus(status);
            }

            return status;
        } catch (e) {
            this.pollFailureCount++;
            console.warn('[Core] Status refresh failed (' + this.pollFailureCount + '): ' + e);
            // Don't blank the dashboard on a single hiccup — keep the last
            // good values rendered. After a couple of consecutive failures,
            // surface a clear "Disconnected" indicator so the user knows the
            // numbers on screen are no longer fresh.
            if (this.pollFailureCount >= this.POLL_STALE_AFTER_FAILURES) {
                const connDot = document.getElementById('connDot');
                if (connDot) connDot.classList.remove('connected');
                this._showStaleBanner(this.pollFailureCount > 4 ? 'disconnected' : 'stale');
            }
            return null;
        }
    },

    /**
     * Show a small connection-state pill near the sidebar status card.
     * Created lazily so pages without the sidebar (e.g. login) cost nothing.
     * The pill replaces the deviceId text on the device row when stale —
     * keeps the existing two-column status-row layout intact and stays
     * clear of the data-i18n hydration path on the label.
     */
    _showStaleBanner(state) {
        var deviceEl = document.getElementById('deviceId');
        if (!deviceEl) return;
        // Stash the real device id so we can restore it on recovery.
        if (deviceEl.dataset.realText === undefined) {
            deviceEl.dataset.realText = deviceEl.textContent;
        }
        var pill = document.getElementById('connStatePill');
        if (!pill) {
            pill = document.createElement('span');
            pill.id = 'connStatePill';
            // Tight pill sized to fit the .status-value column even on
            // narrow sidebars. Long translations (e.g. Norwegian
            // "Frakoblet") clip with ellipsis rather than push the row out
            // of the card.
            pill.style.cssText = 'padding:2px 6px;border-radius:10px;' +
                'font-size:10px;font-weight:600;letter-spacing:.3px;' +
                'text-transform:uppercase;max-width:100%;display:inline-block;' +
                'overflow:hidden;text-overflow:ellipsis;white-space:nowrap;' +
                'vertical-align:middle;';
            deviceEl.textContent = '';
            deviceEl.appendChild(pill);
        }
        // i18n.t() returns null while the catalog is still loading and the
        // raw key (e.g. "status.disconnected") when loaded but the key is
        // missing in the active locale. Treat both as "fall back to the
        // English label" so the user never sees a dotted-namespace string.
        var i18nLookup = function (key, fallback) {
            if (!window.BYD || !BYD.i18n) return fallback;
            var v = BYD.i18n.t(key);
            return (v && v !== key) ? v : fallback;
        };
        if (state === 'disconnected') {
            pill.textContent = i18nLookup('status.disconnected', 'Disconnected');
            pill.style.background = 'rgba(239,68,68,0.18)';
            pill.style.color = '#ef4444';
        } else {
            pill.textContent = i18nLookup('status.stale', 'Stale');
            pill.style.background = 'rgba(251,191,36,0.18)';
            pill.style.color = '#f59e0b';
        }
        pill.style.display = '';
    },

    _clearStaleBanner() {
        var pill = document.getElementById('connStatePill');
        if (!pill) return;
        // Restore the real device id text so the row reads normally again.
        var deviceEl = document.getElementById('deviceId');
        if (deviceEl) {
            var real = deviceEl.dataset.realText;
            deviceEl.removeAttribute('data-real-text');
            // The next /status tick will overwrite this with the live id;
            // we only need to make the pill go away cleanly.
            deviceEl.textContent = real != null ? real : (this.deviceId || '');
        } else {
            pill.parentNode && pill.parentNode.removeChild(pill);
        }
    },

    /**
     * Update EV battery and charging status - White rims with flow animation
     */
    updateEvStatus(status) {
        const evCard = document.getElementById('evCard');
        if (!evCard) return;

        // Get SOC percentage from status.soc.percent
        let soc = null;
        if (status.soc && status.soc.percent !== undefined) {
            soc = status.soc.percent;
        }

        // Update elements
        const evPercentValue = document.getElementById('evPercentValue');
        const evRange = document.getElementById('evRange');

        // First-load placeholder: server says vehicle data isn't ready yet
        // (BYD binders still binding, ACC just came on, etc.). Show an
        // explicit "Waiting for vehicle…" instead of the silent "--%" that
        // looked like the app was simply broken.
        if (soc === null && status.vehicleDataReady === false && !this.hasEverHadVehicleData) {
            if (evPercentValue) {
                evPercentValue.textContent = (BYD.i18n && BYD.i18n.t('status.waiting_vehicle')) || 'Waiting…';
                evPercentValue.style.fontSize = '11px';
                evPercentValue.style.fontWeight = '600';
                evPercentValue.style.letterSpacing = '.3px';
            }
            if (evRange) evRange.textContent = '—';
            return;
        }
        // We've had real data at some point, or are getting it now — restore
        // the percentage formatting to its default look.
        if (evPercentValue && evPercentValue.style.fontSize) {
            evPercentValue.style.fontSize = '';
            evPercentValue.style.fontWeight = '';
            evPercentValue.style.letterSpacing = '';
        }

        if (soc !== null) {
            const socRounded = Math.round(soc);

            // Update percentage text
            if (evPercentValue) {
                evPercentValue.textContent = `${socRounded}%`;
            }

            // Body-mesh emissive glow. The teal/amber/red ramp + intensity
            // scaling lives in OverdriveEvCard3D — see ev-card-3d.js
            // socRampColor + _applyEmissive. No-op on pages where the 3D
            // shell isn't loaded yet (login.html, dev-only pages).
            if (window.OverdriveAppShell && window.OverdriveAppShell.setSoc) {
                window.OverdriveAppShell.setSoc(soc);
            }
        }

        // Update range from actual API data (electric range only)
        if (evRange) {
            if (status.range && status.range.elecRangeKm !== undefined) {
                // Use electric range from BYD API — convert to user's display unit
                const rangeKm = status.range.elecRangeKm;
                evRange.textContent = BYD.units.dist(rangeKm);
                
                // Add warning styling if range is low
                if (status.range.isCritical) {
                    evRange.classList.add('critical');
                    evRange.classList.remove('low');
                } else if (status.range.isLow) {
                    evRange.classList.add('low');
                    evRange.classList.remove('critical');
                } else {
                    evRange.classList.remove('low', 'critical');
                }
            } else if (soc !== null) {
                // Fallback: estimate range (~4km per %)
                const estimatedRange = Math.round(soc * 4);
                evRange.textContent = '~' + BYD.units.dist(estimatedRange);
                evRange.classList.remove('low', 'critical');
            }
        }

        // Charging state
        const evPower = document.getElementById('evPower');

        let isCharging = false;
        let powerKW = 0;

        if (status.charging) {
            var stateName = status.charging.stateName || '';
            powerKW = status.charging.chargingPowerKW || 0;
            var isEstimated = status.charging.isEstimated || false;
            
            // Determine if actively charging
            var chargingStates = ['Charging', 'DC Charging', 'AC Charging', 'Fast Charging'];
            isCharging = chargingStates.some(function(s) { return stateName.toLowerCase().indexOf(s.toLowerCase()) >= 0; }) || powerKW > 0;
        }

        // Update power display
        if (evPower) {
            if (isCharging) {
                if (powerKW > 0) {
                    var prefix = isEstimated ? '~' : '';
                    evPower.textContent = prefix + powerKW.toFixed(1) + ' kW';
                } else {
                    evPower.textContent = '0.0 kW';
                }
            } else {
                evPower.textContent = powerKW > 0 ? powerKW.toFixed(1) + ' kW' : '-- kW';
            }
        }

        // Charging state. The body-overlay sweep + bolt-icon scale-in
        // are driven by toggling the .charging class on the card root.
        // We forward the live charging power (kW) so the sweep
        // animation duration scales with charging speed — DC fast
        // visibly snaps; AC trickle crawls.
        if (isCharging) evCard.classList.add('charging');
        else            evCard.classList.remove('charging');
        if (window.OverdriveAppShell && window.OverdriveAppShell.setCharging) {
            window.OverdriveAppShell.setCharging(isCharging, powerKW);
        }

        // SOH display
        const evSohEl = document.getElementById('evSohValue');
        const evSohRow = document.getElementById('evSohRow');
        if (evSohEl && status.soh && status.soh.percent > 0) {
            evSohEl.textContent = status.soh.percent.toFixed(1) + '%';
            evSohEl.style.color = status.soh.percent >= 90 ? '#22c55e' : status.soh.percent >= 80 ? '#00D4AA' : status.soh.percent >= 70 ? '#fbbf24' : '#ef4444';
            if (evSohRow) evSohRow.style.display = '';
        }

        // Personalized range from trip analytics
        this.updatePersonalizedRange();

        // Fuel card (PHEV only) — show only if real fuel data is available
        const fuelCard = document.getElementById('fuelCard');
        if (fuelCard && status.range) {
            var fuelPct = status.range.fuelPercent;
            var fuelKm = status.range.fuelKm;
            if (fuelPct && fuelPct > 0) {
                fuelCard.style.display = '';
                const fuelPercentEl = document.getElementById('fuelPercentValue');
                const fuelBarFill = document.getElementById('fuelBarFill');
                if (fuelPercentEl) fuelPercentEl.textContent = Math.round(fuelPct) + '%';
                if (fuelBarFill) fuelBarFill.style.width = Math.min(100, fuelPct) + '%';
                if (fuelPercentEl) {
                    fuelPercentEl.style.color = fuelPct <= 15 ? '#EF4444' : fuelPct <= 30 ? '#F59E0B' : '#FBBF24';
                }
            } else {
                fuelCard.style.display = 'none';
            }
        }
    },

    /**
     * Update network status indicator in sidebar.
     * Shows WiFi SSID + IP, or "Mobile Data", or "No Network".
     */
    updateNetworkStatus(status) {
        const netEl = document.getElementById('networkValue');
        const netIcon = document.getElementById('networkIcon');
        if (!netEl) return;

        const net = status.network;
        if (!net) {
            netEl.textContent = '--';
            netEl.className = 'status-value';
            if (netIcon) netIcon.innerHTML = this._wifiSvg();
            return;
        }

        if (net.type === 'wifi') {
            const ssid = net.ssid || BYD.i18n.t('status.wifi');
            const ip = net.ip || '';
            // Show SSID on first line, IP smaller below
            netEl.innerHTML = '<span class="net-ssid">' + this._esc(ssid) + '</span>' +
                (ip ? '<span class="net-ip">' + this._esc(ip) + '</span>' : '');
            netEl.className = 'status-value on net-info';
            if (netIcon) netIcon.innerHTML = this._wifiSvg();
        } else if (net.type === 'cellular') {
            const ip = net.ip || '';
            netEl.innerHTML = '<span class="net-ssid">' + this._esc(BYD.i18n.t('status.mobile_data')) + '</span>' +
                (ip ? '<span class="net-ip">' + this._esc(ip) + '</span>' : '');
            netEl.className = 'status-value on net-info';
            if (netIcon) netIcon.innerHTML = this._cellSvg();
        } else {
            netEl.textContent = BYD.i18n.t('status.no_network');
            netEl.className = 'status-value off';
            if (netIcon) netIcon.innerHTML = this._wifiOffSvg();
        }
    },

    /** Escape HTML */
    _esc(s) { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; },

    /** WiFi SVG icon */
    _wifiSvg() {
        return '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/></svg>';
    },

    /** Cellular SVG icon */
    _cellSvg() {
        return '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="17" width="4" height="5"/><rect x="7" y="12" width="4" height="10"/><rect x="12" y="7" width="4" height="15"/><rect x="17" y="2" width="4" height="20"/></svg>';
    },

    /** WiFi-off SVG icon */
    _wifiOffSvg() {
        return '<svg class="status-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="1" y1="1" x2="23" y2="23"/><path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"/><path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"/><path d="M10.71 5.05A16 16 0 0 1 22.56 9"/><path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/></svg>';
    },

    /**
     * Fetch and display personalized range estimate from trip analytics
     */
    async updatePersonalizedRange() {
        const pRow = document.getElementById('evPersonalizedRow');
        const pVal = document.getElementById('evPersonalizedRange');
        if (!pRow || !pVal) return;

        // Only fetch once per session, cache the result
        if (this._personalizedRangeFetched) {
            if (this._personalizedRangeKm > 0) {
                pRow.style.display = 'flex';
                pVal.textContent = BYD.units.dist(this._personalizedRangeKm);
            }
            return;
        }

        try {
            const resp = await fetch('/api/trips/range');
            const data = await resp.json();
            this._personalizedRangeFetched = true;
            if (data.success && data.range) {
                const predicted = Math.round(data.range.predictedRangeKm || data.range.predicted_range_km || 0);
                if (predicted > 0) {
                    this._personalizedRangeKm = predicted;
                    pRow.style.display = 'flex';
                    pVal.textContent = BYD.units.dist(predicted);
                }
            }
        } catch (e) {
            this._personalizedRangeFetched = true;
        }
    },

    /**
     * Show toast notification
     */
    toast(message, type = 'info', duration = 3000) {
        const container = document.getElementById('toastContainer');
        if (!container) return;

        const toast = document.createElement('div');
        toast.className = 'toast ' + type;
        toast.textContent = message;
        container.appendChild(toast);

        setTimeout(() => {
            toast.style.animation = 'slideIn 0.4s ease reverse';
            setTimeout(() => toast.remove(), 400);
        }, duration);
    }
};

// Expose toast globally for convenience
BYD.utils = BYD.utils || {};
BYD.utils.toast = (msg, type) => BYD.core.toast(msg, type);

// Auto-load the language picker on every page that includes core.js so we
// don't have to touch every HTML file. Picker mounts itself once the DOM is
// ready and a sidebar-footer is found (login page has no sidebar — picker
// silently no-ops there).
(function () {
    if (document.querySelector('script[data-byd-lang-picker]')) return;
    var s = document.createElement('script');
    s.src = '/shared/lang-picker.js';
    s.async = true;
    s.setAttribute('data-byd-lang-picker', '1');
    (document.head || document.documentElement).appendChild(s);
})();
