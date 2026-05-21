/**
 * Overdrive — EV-card sprite cache.
 *
 * IndexedDB-backed cache of pre-rendered EV-card images keyed by
 * (modelId, paint colour, view, dpr-bucket). The sidebar EV card is
 * structurally identical on every PWA page, so the first page to paint
 * the 3D scene snaps the canvas into a webp Blob and every subsequent
 * page renders that blob into the canvas immediately — bypassing
 * three.js, GLTFLoader, DRACOLoader, and the GLB decode entirely.
 *
 * Cache invalidates whenever the user changes vehicle/colour on
 * vehicle-control.html. The sidebar refresh path (app-shell.js
 * applyVehicleSelection → setEvCardAppearance) is the single funnel
 * for selection changes and also the single funnel that writes/reads
 * sprites — so cache and selection stay in lockstep.
 *
 * Schema:
 *   db = 'overdrive-ev-sprites'
 *   store = 'sprites'
 *   key = `${modelId}|${color}|${view}|${dprBucket}`
 *   value = { blob: Blob, w: number, h: number, ts: number }
 *
 * Why dpr-bucket: the 3D renderer scales pixel-ratio up to 2 for
 * crisp lines. A DPR-1 device repainting a DPR-2 sprite blurs; DPR-2
 * repainting a DPR-1 sprite waste. Bucket the value (1 vs 2) so the
 * key collides cleanly across same-density devices.
 *
 * ES5-friendly. Falls through to a no-op cache on browsers without
 * IndexedDB, Blob, or HTMLImageElement (the BYD WebView floor at
 * Chrome 58 has all three — checked). Lazy-loaded by app-shell.js
 * only on PWA pages, so vehicle-control.html's stricter ES5/Chrome 58
 * scope (see WebView constraint feedback) doesn't bind this file.
 */
(function (root) {
    'use strict';

    if (root.OverdriveEvSpriteCache) return;

    var DB_NAME    = 'overdrive-ev-sprites';
    var DB_VERSION = 1;
    var STORE      = 'sprites';
    // Bump SPRITE_VERSION whenever the rendering pipeline changes in a
    // way that makes prior sprites stale (lighting rig, camera framing,
    // body-paint detection, GLB swap). The version is stamped into the
    // cache key so old sprites become unreachable and LRU eviction
    // sweeps them out — no manual purge required.
    var SPRITE_VERSION = 1;

    // Keep the cache from growing unbounded as the user tries paint
    // colours. The realistic upper bound is:
    //   4 models × 6 colours × 2 views (side+top) × 2 dpr buckets = 96.
    // Cap at 128 so a user who experiments past that doesn't immediately
    // start evicting hot keys (the sidebar's side|dpr=N entry must
    // survive even after the user samples a couple of map markers).
    var MAX_ENTRIES = 128;

    var dbPromise = null;

    function supported() {
        return typeof indexedDB !== 'undefined'
            && typeof Blob !== 'undefined'
            && typeof URL !== 'undefined'
            && typeof URL.createObjectURL === 'function'
            && typeof Image !== 'undefined';
    }

    function openDb() {
        if (dbPromise) return dbPromise;
        dbPromise = new Promise(function (resolve, reject) {
            if (!supported()) { reject(new Error('IndexedDB unsupported')); return; }
            var req;
            try { req = indexedDB.open(DB_NAME, DB_VERSION); }
            catch (e) { reject(e); return; }
            req.onupgradeneeded = function () {
                var db = req.result;
                if (!db.objectStoreNames.contains(STORE)) {
                    var store = db.createObjectStore(STORE, { keyPath: 'key' });
                    store.createIndex('ts', 'ts');
                }
            };
            req.onsuccess = function () { resolve(req.result); };
            req.onerror   = function () { dbPromise = null; reject(req.error); };
            req.onblocked = function () { dbPromise = null; reject(new Error('blocked')); };
        });
        return dbPromise;
    }

    function dprBucket() {
        var d = (root.devicePixelRatio || 1);
        // Three.js clamps pixelRatio to min(devicePixelRatio, 2). Match
        // that here so the cached sprite's resolution lines up with the
        // canvas's backing store.
        return d >= 1.5 ? 2 : 1;
    }

    function buildKey(modelId, color, view) {
        // Lower-case the colour so '#1A1A1E' and '#1a1a1e' don't fork.
        var c = (color || '').toLowerCase();
        var v = (view === 'top') ? 'top' : 'side';
        return 'v' + SPRITE_VERSION + '|'
             + (modelId || '') + '|' + c + '|' + v + '|' + dprBucket();
    }

    function get(modelId, color, view) {
        var key = buildKey(modelId, color, view);
        return openDb().then(function (db) {
            return new Promise(function (resolve, reject) {
                var tx = db.transaction(STORE, 'readonly');
                var req = tx.objectStore(STORE).get(key);
                req.onsuccess = function () { resolve(req.result || null); };
                req.onerror   = function () { reject(req.error); };
            });
        }).catch(function () { return null; });
    }

    function put(modelId, color, view, blob, w, h) {
        var key = buildKey(modelId, color, view);
        return openDb().then(function (db) {
            return new Promise(function (resolve) {
                var tx = db.transaction(STORE, 'readwrite');
                var store = tx.objectStore(STORE);
                store.put({ key: key, blob: blob, w: w, h: h, ts: Date.now() });
                tx.oncomplete = function () { resolve(true); evictIfFull(); };
                tx.onerror    = function () { resolve(false); };
                tx.onabort    = function () { resolve(false); };
            });
        }).catch(function () { return false; });
    }

    // Drop every cached sprite whose key starts with `${modelId}|`.
    // Used when the user picks a different model (all colour and view
    // variants of the prior model become useless) or when the model's
    // GLB has been replaced on disk and old sprites would be stale.
    function invalidateModel(modelId) {
        return openDb().then(function (db) {
            return new Promise(function (resolve) {
                var tx = db.transaction(STORE, 'readwrite');
                var store = tx.objectStore(STORE);
                var prefix = (modelId || '') + '|';
                var req = store.openCursor();
                req.onsuccess = function () {
                    var cur = req.result;
                    if (!cur) return;
                    if (typeof cur.key === 'string' && cur.key.indexOf(prefix) === 0) {
                        cur.delete();
                    }
                    cur['continue']();
                };
                tx.oncomplete = function () { resolve(true); };
                tx.onerror    = function () { resolve(false); };
                tx.onabort    = function () { resolve(false); };
            });
        }).catch(function () { return false; });
    }

    // LRU-ish eviction: when the store crosses MAX_ENTRIES, drop the
    // oldest by ts until we're back under the cap. Cheap because the
    // `ts` index gives us oldest-first iteration directly.
    function evictIfFull() {
        openDb().then(function (db) {
            var tx = db.transaction(STORE, 'readwrite');
            var store = tx.objectStore(STORE);
            var countReq = store.count();
            countReq.onsuccess = function () {
                var n = countReq.result;
                if (n <= MAX_ENTRIES) return;
                var toDrop = n - MAX_ENTRIES;
                var idxReq = store.index('ts').openCursor();
                idxReq.onsuccess = function () {
                    var cur = idxReq.result;
                    if (!cur || toDrop <= 0) return;
                    cur.delete();
                    toDrop--;
                    cur['continue']();
                };
            };
        }).catch(function () {});
    }

    // Paint a cached sprite into the canvas. Sets the backing buffer
    // to the SOURCE dimensions when they match the current backing
    // size (drawImage at 1:1 → no resampling), otherwise stretches.
    // Matching at 1:1 is the common case because both the snapshotter
    // and the painter compute dimensions the same way (clientSize *
    // pixelRatio, with the same Math.min(devicePixelRatio, 2) clamp),
    // so cross-page dimensions land identical on the same device.
    function paintInto(canvasEl, entry) {
        if (!canvasEl || !entry || !entry.blob) return Promise.resolve(false);
        var url = URL.createObjectURL(entry.blob);
        return new Promise(function (resolve) {
            var img = new Image();
            img.onload = function () {
                try {
                    var dpr = Math.min(root.devicePixelRatio || 1, 2);
                    var cw = canvasEl.clientWidth  || canvasEl.width  || entry.w;
                    var ch = canvasEl.clientHeight || canvasEl.height || entry.h;
                    var bw = Math.round(cw * dpr);
                    var bh = Math.round(ch * dpr);
                    if (canvasEl.width !== bw)  canvasEl.width  = bw;
                    if (canvasEl.height !== bh) canvasEl.height = bh;
                    var ctx = canvasEl.getContext('2d');
                    if (!ctx) { resolve(false); return; }
                    ctx.clearRect(0, 0, bw, bh);
                    if (entry.w === bw && entry.h === bh) {
                        // 1:1 blit — no resampling, no quality loss.
                        ctx.drawImage(img, 0, 0);
                    } else {
                        // Fallback for layout drift between pages
                        // (e.g. sidebar has a different padding under
                        // a media query). Stretch-to-fit; the cached
                        // sprite's auto-fit framing is preserved.
                        ctx.drawImage(img, 0, 0, bw, bh);
                    }
                    resolve(true);
                } catch (e) {
                    resolve(false);
                } finally {
                    try { URL.revokeObjectURL(url); } catch (e) {}
                }
            };
            img.onerror = function () {
                try { URL.revokeObjectURL(url); } catch (e) {}
                resolve(false);
            };
            img.src = url;
        });
    }

    // Snap the current contents of a three.js-driven canvas to a Blob
    // suitable for storage. Webp at q=0.9 is ~25-50KB for a 280×200
    // EV-card sprite — about 50× smaller than the GLB+JS pipeline.
    // PNG fallback for browsers without webp encode (older Chrome on
    // some older WebViews).
    function snapshot(canvasEl) {
        return new Promise(function (resolve) {
            if (!canvasEl) { resolve(null); return; }
            try {
                if (typeof canvasEl.toBlob === 'function') {
                    canvasEl.toBlob(function (blob) {
                        if (blob) { resolve(blob); return; }
                        // Some implementations reject webp silently.
                        canvasEl.toBlob(function (b2) { resolve(b2 || null); }, 'image/png');
                    }, 'image/webp', 0.9);
                } else {
                    // Synchronous data-URL fallback.
                    var dataUrl = canvasEl.toDataURL('image/webp', 0.9);
                    fetch(dataUrl).then(function (r) { return r.blob(); }).then(resolve, function () { resolve(null); });
                }
            } catch (e) { resolve(null); }
        });
    }

    root.OverdriveEvSpriteCache = {
        supported: supported,
        get: get,
        put: put,
        invalidateModel: invalidateModel,
        paintInto: paintInto,
        snapshot: snapshot,
        buildKey: buildKey
    };
}(window));
