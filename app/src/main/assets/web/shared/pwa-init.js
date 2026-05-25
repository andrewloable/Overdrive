/**
 * OverDrive PWA bootstrap.
 *
 * Loaded after auth.js on every dashboard page. Skips entirely on the
 * in-app WebView (loopback host) so notification permission prompts never
 * appear inside the car. On the user's external phone install, registers
 * the service worker, requests notification permission, subscribes to push,
 * and posts the resulting subscription to the head unit.
 *
 * No-op on browsers that don't support PWAs (e.g. older Android browsers).
 */
(function () {
    'use strict';

    if (typeof navigator === 'undefined') return;
    if (!('serviceWorker' in navigator)) return;

    // Dev escape hatch: ?devPwa=1 in the URL forces SW + subscribe to run on
    // localhost. Used by dev/preview-server.py — Chrome treats localhost as
    // a secure context, so the whole flow can be exercised without a real
    // tunnel, real cert, or a deployed APK.
    var devPwa = /[?&]devPwa=1\b/.test(window.location.search);

    var host = window.location.hostname;
    var isLoopback = host === '127.0.0.1' || host === 'localhost' || host === '0.0.0.0';
    if (isLoopback && !devPwa) {
        // WebView or LAN — never install a PWA against an unstable origin.
        return;
    }

    if (window.location.protocol !== 'https:' && !isLoopback) {
        // Service workers require a secure context. https:// is the normal
        // one; localhost is also accepted by Chrome/Firefox/Safari.
        return;
    }

    function log() {
        if (window.console && console.log) {
            console.log.apply(console, ['[pwa]'].concat([].slice.call(arguments)));
        }
    }

    function urlBase64ToUint8Array(b64) {
        // Web Push expects applicationServerKey as Uint8Array of the raw
        // 65-byte uncompressed P-256 point, decoded from base64url.
        var padding = '='.repeat((4 - b64.length % 4) % 4);
        var base64 = (b64 + padding).replace(/-/g, '+').replace(/_/g, '/');
        var raw = atob(base64);
        var arr = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; ++i) arr[i] = raw.charCodeAt(i);
        return arr;
    }

    function authedFetch(url, opts) {
        opts = opts || {};
        opts.credentials = opts.credentials || 'same-origin';
        return fetch(url, opts);
    }

    async function getCategoriesAndKey() {
        var r = await authedFetch('/api/notifications/categories');
        if (!r.ok) throw new Error('categories fetch ' + r.status);
        return r.json();
    }

    async function ensureSubscription(reg, vapidPublicKey) {
        var existing = await reg.pushManager.getSubscription();
        if (existing) {
            try {
                var keys = existing.options && existing.options.applicationServerKey;
                // No clean way to compare a Uint8Array vs a base64url; accept the
                // existing subscription as-is, the server will re-key it via subscribe.
                return existing;
            } catch (e) { /* fall through to resubscribe */ }
        }
        return reg.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: urlBase64ToUint8Array(vapidPublicKey)
        });
    }

    async function postSubscription(sub) {
        var json = sub.toJSON();
        var label = inferLabel();
        var body = {
            endpoint: json.endpoint,
            keys: json.keys,
            label: label
        };
        var r = await authedFetch('/api/push/subscribe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (!r.ok) throw new Error('subscribe POST ' + r.status);
        return r.json();
    }

    function inferLabel() {
        // Best-effort device label from User-Agent; user can rename later.
        var ua = navigator.userAgent || '';
        if (/iPhone/i.test(ua)) return 'iPhone';
        if (/iPad/i.test(ua)) return 'iPad';
        if (/Android/i.test(ua)) {
            var m = ua.match(/Android[^;]*;\s*([^)]+)\)/);
            if (m) return m[1].trim();
            return 'Android';
        }
        return 'Browser';
    }

    async function init() {
        try {
            var reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
            log('SW registered, scope:', reg.scope);

            // Wait for SW to be active before subscribing.
            await navigator.serviceWorker.ready;

            // Permission flow: only auto-prompt if not blocked. If denied, we
            // stop silently; if default, we wait for an explicit user action
            // (e.g. test button on /notifications.html).
            if (Notification.permission === 'denied') {
                log('notifications denied — skipping subscribe');
                return;
            }
            if (Notification.permission === 'default') {
                // Don't prompt on every page load — only the settings page
                // should trigger this. Here we just register the SW.
                log('notifications permission not yet granted');
                return;
            }

            if (!await reg.pushManager.getSubscription()) {
                // Don't recreate the subscription on init as the user could have disabled after previous enabling
                log('push subscription was previously registered');
            }
        } catch (e) {
            log('init failed:', e && e.message ? e.message : e);
        }
    }

    // Expose minimal API for the settings page to drive permission prompts
    // and explicit (re)subscribe flow.
    window.OverdrivePush = {
        async requestAndSubscribe() {
            var perm = await Notification.requestPermission();
            if (perm !== 'granted') return { ok: false, reason: 'permission-' + perm };
            var reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
            await navigator.serviceWorker.ready;
            var meta = await getCategoriesAndKey();
            if (!meta || !meta.vapidPublicKey) return { ok: false, reason: 'no-vapid-key' };
            var sub = await ensureSubscription(reg, meta.vapidPublicKey);
            var resp = await postSubscription(sub);
            return { ok: true, id: resp.id };
        },
        async unsubscribe() {
            var reg = await navigator.serviceWorker.getRegistration();
            if (!reg) return { ok: true };
            var sub = await reg.pushManager.getSubscription();
            if (!sub) return { ok: true };
            try {
                await authedFetch('/api/push/unsubscribe', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ endpoint: sub.endpoint })
                });
            } catch (e) { /* ignore — still try to local-unsubscribe */ }
            try { await sub.unsubscribe(); } catch (e) {}
            return { ok: true };
        },
        async sendTest(severity) {
            return authedFetch('/api/push/test', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ severity: severity || 'info' })
            }).then(function (r) { return r.json(); });
        }
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
