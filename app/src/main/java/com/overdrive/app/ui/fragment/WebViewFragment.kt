package com.overdrive.app.ui.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.overdrive.app.R
import com.overdrive.app.daemon.CameraDaemon

/**
 * WebView fragment that loads pages from the daemon's HTTP server.
 *
 * Problem: sing-box sets a system HTTP proxy (127.0.0.1:8119) which WebView
 * honors. Requests to 127.0.0.1:8080 get routed through the proxy and fail.
 *
 * Solution: Temporarily clear the system proxy before loading, restore after.
 * Also inject auth JWT cookie so API calls pass AuthMiddleware.
 */
class WebViewFragment : Fragment() {

    companion object {
        const val ARG_PAGE_PATH = "page_path"
        private const val KEY_SAVED_URL = "saved_url"
        
        /**
         * JavaScript injected after each page load.
         *
         *   1. Tags <html data-app-shell="1"> so the page CSS can opt into
         *      app-shell-only tweaks via that attribute selector — cleaner
         *      than scattering Android-specific overrides across pages.
         *   2. Hides the in-page sidebar / mobile header / fullscreen
         *      button — the Android shell already provides a navigation rail
         *      and top app bar, so the page-internal navigation is redundant
         *      and creates the overlap the user reports on Live View.
         *   3. Repositions the Map ↔ Cameras mini-preview toggle to the
         *      bottom-right (default top-left collides with the camera-top-bar
         *      pill in landscape windowed mode).
         *   4. Patches window.fetch() to route POST/PUT/DELETE through
         *      AndroidBridge.httpRequest() so writes bypass sing-box proxy.
         *      GET requests go through the normal WebView path so polling
         *      doesn't block the JS thread.
         */
        private const val INJECT_JS = """
(function() {
    document.documentElement.setAttribute('data-app-shell', '1');

    var css = [
        // === Global: hide page-internal navigation. The Android shell already
        //     provides the nav rail + top app bar, so the in-page sidebar,
        //     mobile header, and floating mini-preview tab switcher are all
        //     redundant and visually noisy. ===
        '.sidebar, .sidebar-overlay, .mobile-header { display: none !important; }',
        '.main-content { margin-left: 0 !important; padding-top: 0 !important; }',
        '.pip-container, .pip-toggle-btn, #pipToggleBtn, #pipContainer { display: none !important; }',
        '.toast-container { z-index: 20000 !important; bottom: 70px !important; }',
        '.page-header { padding-top: 12px !important; }',
        '.page-body { padding-bottom: 80px !important; }',
        '.footer-bar { bottom: 0 !important; left: 0 !important; right: 0 !important;',
        '              padding: 12px 16px !important; padding-bottom: 12px !important;',
        '              z-index: 10000 !important; }',

        // === Live View (index.html) tweaks ===
        // The mini-preview is the only Map ↔ Cameras toggle on this page,
        // so we MUST keep it visible. Match the web-tunnel placement
        // (top-left) — earlier injection moved it bottom-right because the
        // page-internal mobile-header pushed content down, but we hide that
        // header above so the original top: 80px / left: 24px works fine
        // and the in-app WebView matches what users see on the tunnel.
        '[data-app-shell="1"] .mini-preview { top: 80px !important; left: 24px !important;',
        '   right: auto !important; bottom: auto !important;',
        '   width: 64px !important; height: 64px !important; z-index: 60 !important; }',
        '[data-app-shell="1"] .mini-preview-content svg { width: 22px !important; height: 22px !important; }',
        '[data-app-shell="1"] .mini-preview-label { font-size: 9px !important; padding: 3px 0 !important; }',
        // Hide the in-page fullscreen button — the WebView already fills
        // the destination and the button's request would be denied here.
        '[data-app-shell="1"] .top-bar-btn { display: none !important; }',
        // Pull the absolute-positioned camera top bar in by a hair so the
        // connection-status pill and quality dropdown breathe at narrow
        // landscape widths (head-unit windowed mode, ~600-900px wide).
        '[data-app-shell="1"] .camera-top-bar { padding: 12px 14px !important; gap: 8px; }',
        '[data-app-shell="1"] .camera-top-bar .top-bar-left,',
        '[data-app-shell="1"] .camera-top-bar .top-bar-right { min-width: 0; flex-wrap: nowrap; }',
        '[data-app-shell="1"] .quality-select-sota { min-width: 96px; max-width: 140px; }',
        // Map overlay buttons (My Location / Directions) — keep them clear
        // of the top-bar pill. The default top: 16px lands underneath the
        // pill on narrow viewports.
        '[data-app-shell="1"] #panelMap .map-overlay-actions { top: 14px !important; right: 14px !important; gap: 10px !important; }',
        '[data-app-shell="1"] .btn-map-float { width: 44px !important; height: 44px !important; }',
        '[data-app-shell="1"] .btn-map-float svg { width: 20px !important; height: 20px !important; }',
        // Camera hotspot labels — clamp width and slightly shrink the
        // negative offsets so labels don't clip the .seamless-camera-view
        // when the WebView is in landscape windowed mode.
        '[data-app-shell="1"] .cam-hotspot .hotspot-label {',
        '   max-width: 64px; white-space: nowrap; overflow: hidden;',
        '   text-overflow: ellipsis; padding: 3px 7px; font-size: 9px; }',
        '[data-app-shell="1"] .cam-hotspot[data-cam="4"] .hotspot-label { left: -34px !important; }',
        '[data-app-shell="1"] .cam-hotspot[data-cam="2"] .hotspot-label { right: -34px !important; }',

        // === Page-specific carry-overs (kept from previous behaviour) ===
        '#safeLocMap { z-index: 1 !important; position: relative !important; overflow: hidden !important; }',
        '#safeLocMap .leaflet-pane { z-index: 1 !important; }',
        '#safeLocMap .leaflet-control-container { z-index: 10 !important; }',
        '#roiCanvasContainer { position: relative !important; width: 100% !important;',
        '                      height: 200px !important; padding-bottom: 0 !important;',
        '                      overflow: hidden !important; z-index: 0 !important; }',
        '#roiCanvas { position: absolute !important; top: 0 !important; left: 0 !important;',
        '             width: 100% !important; height: 100% !important;',
        '             max-width: 100% !important; max-height: 200px !important; }'
    ].join(' ');

    var s = document.createElement('style');
    s.textContent = css;
    document.head.appendChild(s);

    // Replace fetch() to bypass sing-box proxy for localhost
    if (window.AndroidBridge && !window._fetchPatched) {
        window._fetchPatched = true;
        var _orig = window.fetch;
        window.fetch = function(input, init) {
            init = init || {};
            var url = (typeof input === 'string') ? input : (input.url || '');
            var isLocal = url.startsWith('/') || url.indexOf('127.0.0.1') !== -1 || url.indexOf('localhost') !== -1;
            
            // Only intercept API calls — let media (video/thumb/snapshot/h264) go through normally
            // Also let /status go through normal async path — it polls every 3s and would block the JS thread
            var isApi = url.indexOf('/api/') !== -1 || url.indexOf('/auth/') !== -1;
            if (!isLocal || !isApi) return _orig.call(window, input, init);
            
            var method = (init.method || 'GET').toUpperCase();
            
            // Only use synchronous AndroidBridge for POST/PUT/DELETE (writes).
            // GET requests go through normal async WebView path (shouldInterceptRequest handles proxy bypass).
            // This prevents the synchronous bridge from blocking the JS thread during polling/config loads.
            if (method === 'GET') return _orig.call(window, input, init);
            var body = init.body || '';
            var headers = {};
            if (init.headers) {
                if (init.headers instanceof Headers) {
                    init.headers.forEach(function(v, k) { headers[k] = v; });
                } else if (typeof init.headers === 'object') {
                    headers = init.headers;
                }
            }
            if (!headers['Content-Type'] && !headers['content-type'] && body) {
                headers['Content-Type'] = 'application/json';
            }
            
            var fullUrl = url.startsWith('/') ? 'http://127.0.0.1:8080' + url : url;
            
            return new Promise(function(resolve) {
                try {
                    var raw = AndroidBridge.httpRequest(fullUrl, method, body, JSON.stringify(headers));
                    var status = 200;
                    try {
                        var parsed = JSON.parse(raw);
                        if (parsed._status) { status = parsed._status; delete parsed._status; raw = JSON.stringify(parsed); }
                    } catch(e) {}
                    resolve(new Response(raw, { status: status, headers: { 'Content-Type': 'application/json' } }));
                } catch(e) {
                    console.error('AndroidBridge error:', e);
                    resolve(new Response('{"error":"bridge_error"}', { status: 500 }));
                }
            });
        };
        console.log('[OverDrive] fetch() patched to bypass proxy');
    }
})();
"""
    }

    private var webView: WebView? = null
    private var loadingOverlay: View? = null
    private var errorOverlay: View? = null
    private var btnRetry: MaterialButton? = null
    private var currentUrl: String? = null
    private var pageLoadFailed = false

    // Pending callback from onShowFileChooser — must be invoked exactly once,
    // with either the selected URIs or null on cancel.
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    // Picker launcher — registered in onCreate so it survives config changes.
    // We use OpenMultipleDocuments so the same handler works whether the
    // <input> allows a single file or has the `multiple` attribute.
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris: List<Uri> ->
            val result = if (uris.isNullOrEmpty()) null else uris.toTypedArray()
            pendingFileCallback?.onReceiveValue(result)
            pendingFileCallback = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_webview, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = view.findViewById(R.id.webView)
        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        errorOverlay = view.findViewById(R.id.errorOverlay)
        btnRetry = view.findViewById(R.id.btnRetry)
        btnRetry?.setOnClickListener { retryLoad() }

        setupWebView()
        injectAuthCookie()

        currentUrl = savedInstanceState?.getString(KEY_SAVED_URL)
            ?: ("http://127.0.0.1:${CameraDaemon.HTTP_PORT}" +
                (arguments?.getString(ARG_PAGE_PATH)
                    ?: arguments?.getString("page_path")
                    ?: "/surveillance"))

        loadPage()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Respect server Cache-Control headers. The daemon serves HTML with no-store
            // and shared static assets (CSS/JS/fonts/icons, ~360KB total) with a 24h
            // max-age, so switching from LOAD_NO_CACHE keeps HTML always fresh while
            // avoiding a full re-download of every asset on each page load.
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // Resolve background color from the active theme so the WebView's
            // outer chrome flips with light/dark mode.
            setBackgroundColor(resolveThemeBackground())
            // Enable hardware acceleration for video playback
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            webViewClient = object : WebViewClient() {
                
                /**
                 * SOTA FIX: Intercept VIDEO requests with aggressive header handling.
                 * Bypasses sing-box proxy for all localhost requests.
                 */
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null

                    // FILTER: Only intercept our local server and external map/CDN resources
                    val isLocalServer = url.contains("127.0.0.1:${CameraDaemon.HTTP_PORT}") ||
                        url.contains("localhost:${CameraDaemon.HTTP_PORT}")
                    
                    // Bypass proxy for map tiles and CDN resources (sing-box proxy blocks these)
                    val isMapTile = url.contains("tile.openstreetmap.org") ||
                        url.contains("basemaps.cartocdn.com") ||
                        url.contains("unpkg.com") ||
                        url.contains("cdn.jsdelivr.net") ||
                        url.contains("fonts.googleapis.com") ||
                        url.contains("fonts.gstatic.com")
                    
                    if (!isLocalServer && !isMapTile) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    
                    // For map tiles/CDN: route through sing-box proxy (HTTP or SOCKS).
                    // The BYD head unit has no direct internet — all external requests
                    // must go through the sing-box mixed proxy on port 8119.
                    // Strategy: try HTTP proxy first, then SOCKS proxy, then direct, then
                    // fall back to letting WebView handle it (which uses system proxy).
                    if (isMapTile) {
                        val proxyAvailable = com.overdrive.app.mqtt.ProxyHelper.isProxyAvailable()
                        
                        // Build list of proxy strategies to try
                        val strategies = mutableListOf<java.net.Proxy>()
                        if (proxyAvailable) {
                            // sing-box "mixed" inbound supports both HTTP and SOCKS5
                            strategies.add(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", 8119)))
                            strategies.add(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 8119)))
                        }
                        strategies.add(java.net.Proxy.NO_PROXY) // direct as last resort
                        
                        for (proxy in strategies) {
                            try {
                                val connection = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
                                connection.connectTimeout = if (proxy == java.net.Proxy.NO_PROXY) 3000 else 8000
                                connection.readTimeout = 15000
                                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0 Safari/537.36")
                                request?.requestHeaders?.forEach { (key, value) ->
                                    if (key != "User-Agent") connection.setRequestProperty(key, value)
                                }
                                connection.connect()
                                
                                if (connection.responseCode !in 200..399) {
                                    connection.disconnect()
                                    continue
                                }
                                
                                val stream = connection.inputStream
                                val rawContentType = connection.contentType ?: "application/octet-stream"
                                val mime = rawContentType.split(";").first().trim()
                                val encoding = if (rawContentType.contains("charset=")) {
                                    rawContentType.substringAfter("charset=").trim()
                                } else null
                                
                                val response = WebResourceResponse(mime, encoding, stream)
                                response.setStatusCodeAndReasonPhrase(connection.responseCode, connection.responseMessage ?: "OK")
                                
                                val headers = mutableMapOf<String, String>()
                                connection.headerFields?.forEach { (k, v) ->
                                    if (k != null && v.isNotEmpty()) headers[k] = v.last()
                                }
                                headers["Access-Control-Allow-Origin"] = "*"
                                response.responseHeaders = headers
                                
                                android.util.Log.d("WebViewProxy", "CDN OK via ${proxy.type()}: $url")
                                return response
                            } catch (e: Exception) {
                                android.util.Log.w("WebViewProxy", "CDN ${proxy.type()} failed for $url: ${e.message}")
                                // Try next strategy
                            }
                        }
                        
                        // All strategies failed — let WebView try its own way
                        android.util.Log.e("WebViewProxy", "All CDN strategies failed for: $url")
                        return super.shouldInterceptRequest(view, request)
                    }

                    // LOGGING: Check if we are seeing the video request
                    if (url.endsWith(".mp4")) {
                        android.util.Log.d("WebViewProxy", "Intercepting Video: $url")
                    }

                    try {
                        // 1. Force Direct Connection (Bypass sing-box)
                        val connection = java.net.URL(url).openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                        connection.connectTimeout = 5000
                        connection.readTimeout = 30000

                        // 2. Forward Range Header (VITAL for video)
                        // Chrome sends "Range: bytes=0-" to start playback
                        val range = request.requestHeaders["Range"]
                        if (range != null) {
                            connection.setRequestProperty("Range", range)
                            android.util.Log.d("WebViewProxy", "Request Range: $range")
                        }

                        // Forward remaining request headers
                        request.requestHeaders?.forEach { (key, value) ->
                            if (key != "Range") {
                                connection.setRequestProperty(key, value)
                            }
                        }

                        // Inject Auth
                        val jwt = getAuthJwt()
                        if (jwt != null) {
                            connection.setRequestProperty("Cookie", "byd_session=$jwt")
                        }

                        connection.connect()

                        // 3. Handle Response
                        val stream = if (connection.responseCode in 200..399) {
                            connection.inputStream
                        } else {
                            connection.errorStream ?: return null
                        }
                        val length = connection.contentLength

                        // SOTA FIX: Determine correct MIME type
                        // 1. Force video/mp4 for .mp4 files (Chrome 74 fails on application/octet-stream)
                        // 2. For other files, parse Content-Type header properly (strip charset)
                        // 3. Fallback to application/octet-stream (not video/mp4!) for unknown types
                        val rawContentType = connection.contentType ?: "application/octet-stream"
                        var mime = rawContentType.split(";").first().trim()
                        val encoding = if (rawContentType.contains("charset=")) {
                            rawContentType.substringAfter("charset=").trim()
                        } else "utf-8"
                        if (url.endsWith(".mp4")) mime = "video/mp4"

                        val response = WebResourceResponse(mime, encoding, stream)

                        // 4. Pass Status Code (206 vs 200)
                        response.setStatusCodeAndReasonPhrase(
                            connection.responseCode,
                            connection.responseMessage ?: "OK"
                        )

                        // 5. Force Response Headers
                        val headers = mutableMapOf<String, String>()
                        // Copy relevant headers from server
                        connection.headerFields?.forEach { (k, v) ->
                            if (k != null && v.isNotEmpty()) headers[k] = v.last()
                        }
                        // MANUAL OVERRIDES: Ensure these exist even if server forgot them
                        headers["Access-Control-Allow-Origin"] = "*"
                        headers["Accept-Ranges"] = "bytes"  // Tells player "You can seek"
                        if (!headers.containsKey("Content-Length") && length > 0) {
                            headers["Content-Length"] = length.toString()
                        }
                        response.responseHeaders = headers

                        if (url.endsWith(".mp4")) {
                            android.util.Log.d("WebViewProxy", "Serving Video: ${connection.responseCode} len=$length mime=$mime")
                            // Log all response headers for debugging
                            headers.forEach { (k, v) ->
                                android.util.Log.d("WebViewProxy", "  Header: $k = $v")
                            }
                        }

                        return response
                    } catch (e: Exception) {
                        android.util.Log.e("WebViewProxy", "Failed: $url - ${e.message}")
                        return null
                    }
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!pageLoadFailed) {
                        showContent()
                        // Theme: tag <html data-theme="…"> BEFORE INJECT_JS so any
                        // CSS that depends on the variable values uses the right
                        // values on first paint.
                        view?.evaluateJavascript(buildThemeInjectJs(), null)
                        // Hide sidebar (app drawer handles navigation) and
                        // patch fetch() to bypass proxy for ALL localhost calls
                        view?.evaluateJavascript(INJECT_JS, null)
                    }
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        pageLoadFailed = true
                        showError()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    
                    // Intercept events page links — use native RecordingLibraryFragment
                    // which has reliable video playback via VideoView
                    if (url.contains("/events.html") || url.endsWith("/events")) {
                        try {
                            // Extract filter / file params if present
                            // (e.g., events.html?filter=sentry&file=event_20260512_143022.mp4)
                            val uri = android.net.Uri.parse(url)
                            val filter = uri.getQueryParameter("filter")
                            val file = uri.getQueryParameter("file")
                            val bundle = android.os.Bundle().apply {
                                if (filter != null) putString("filter", filter)
                                if (file != null) putString("file", file)
                            }
                            // Route web-side "events" links to the unified Recordings page.
                            // RecordingsFragment hosts RecordingLibraryFragment and will pick
                            // up the `filter` / `file` arguments through saved-state if needed.
                            androidx.navigation.fragment.NavHostFragment.findNavController(this@WebViewFragment)
                                .navigate(R.id.recordingsFragment, bundle)
                        } catch (e: Exception) {
                            android.util.Log.e("WebView", "Failed to navigate to events: ${e.message}")
                        }
                        return true
                    }
                    
                    if (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")) {
                        return false
                    }
                    try {
                        startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (_: Exception) {}
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(
                    consoleMessage: android.webkit.ConsoleMessage?
                ): Boolean {
                    consoleMessage?.let {
                        val level = when (it.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR -> android.util.Log.ERROR
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> android.util.Log.WARN
                            android.webkit.ConsoleMessage.MessageLevel.DEBUG -> android.util.Log.DEBUG
                            else -> android.util.Log.INFO
                        }
                        android.util.Log.println(
                            level,
                            "WebViewJS",
                            "${it.sourceId()?.substringAfterLast('/')}:${it.lineNumber()} — ${it.message()}"
                        )
                    }
                    return true
                }

                /**
                 * Bridge <input type="file"> clicks into the Android photo picker.
                 * Without this, the default WebChromeClient ignores the click and
                 * nothing happens (e.g. file upload buttons in the web UI).
                 */
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // Release any stale callback from a previous, unfinished request
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback

                    // Honor the <input accept="..."> MIME hints; default to images.
                    val accept = fileChooserParams?.acceptTypes
                        ?.filter { it.isNotBlank() }
                        ?.toTypedArray()
                        ?.takeIf { it.isNotEmpty() }
                        ?: arrayOf("image/*")

                    return try {
                        fileChooserLauncher.launch(accept)
                        true
                    } catch (e: Exception) {
                        android.util.Log.e("WebView", "File chooser launch failed: ${e.message}")
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = null
                        false
                    }
                }
            }

            // Native bridge for direct HTTP calls bypassing proxy
            addJavascriptInterface(ProxyBypassBridge(), "AndroidBridge")
        }
    }

    /**
     * Native bridge that JavaScript calls for ALL HTTP requests.
     * Uses Proxy.NO_PROXY to bypass sing-box.
     * This is synchronous — called from JS via AndroidBridge.httpRequest().
     */
    inner class ProxyBypassBridge {

        /**
         * Expose the active app theme ("dark" / "light") so theme.js can
         * stamp <html data-theme="…"> at script-include time, BEFORE any
         * stylesheet evaluates. Without this the page paints once with the
         * default-dark palette and then re-paints when onPageFinished
         * injects the theme — visible flash on light-mode devices.
         *
         * Reads UI_MODE_NIGHT_MASK so it tracks the same source the rest of
         * the Android shell uses (PreferencesManager.setThemeMode →
         * AppCompatDelegate.setDefaultNightMode → activity Configuration).
         */
        /**
         * Expose the active app locale (e.g. "en", "de", "zh-CN") so theme.js's
         * sibling i18n bootstrap can stamp the right language at script-include
         * time inside the Android WebView. The web-side picker is suppressed
         * in-app — the app's Settings → Language panel is the source of truth.
         * External tunnel/browser users get their own localStorage-backed
         * picker via the lang-picker.js sheet.
         *
         * Reads LocaleManager.get() which is the same source the rest of the
         * server uses (it's what /status reports as `locale`). On any error
         * (e.g. very early boot before the file is written) returns "en" so
         * the page still loads.
         */
        @android.webkit.JavascriptInterface
        fun getAppLocale(): String {
            return try {
                com.overdrive.app.server.LocaleManager.get()
            } catch (e: Exception) {
                "en"
            }
        }

        @android.webkit.JavascriptInterface
        fun getAppTheme(): String {
            // Source-of-truth ordering — try the strongest signal first:
            //   1. PreferencesManager (the user's explicit pick: AUTO / NO / YES)
            //      AUTO falls through to step 2.
            //   2. AppCompatDelegate.getDefaultNightMode() — the active runtime
            //      mode after setDefaultNightMode() is called. This is what
            //      every Material widget actually uses, and stays correct
            //      across activity recreates.
            //   3. Configuration.uiMode — last-resort fallback for very early
            //      paint moments where AppCompatDelegate hasn't propagated yet.
            //
            // The previous implementation only read step 3 which is the
            // SYSTEM uiMode and ignored AppCompat overrides — picking Light
            // in the app left this returning "dark" until the OS itself
            // flipped, which is exactly the bug the user reported.
            try {
                val pref = com.overdrive.app.ui.util.PreferencesManager.getThemeMode()
                when (pref) {
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> return "light"
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> return "dark"
                }
            } catch (ignored: Exception) {
                // PreferencesManager not initialized (very early boot) —
                // fall through to the next signal.
            }
            try {
                val mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
                if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) return "light"
                if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) return "dark"
            } catch (ignored: Exception) { /* fall through */ }

            val ctx = context ?: return "dark"
            val isNight = (ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (isNight) "dark" else "light"
        }

        @android.webkit.JavascriptInterface
        fun httpRequest(urlStr: String, method: String, body: String, headers: String): String {
            var conn: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL(urlStr)
                conn = url.openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                conn.instanceFollowRedirects = false

                // Parse and set headers
                if (headers.isNotEmpty()) {
                    try {
                        val hJson = org.json.JSONObject(headers)
                        val keys = hJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            conn.setRequestProperty(k, hJson.getString(k))
                        }
                    } catch (_: Exception) {}
                }

                // Always inject auth cookie
                val jwt = getAuthJwt()
                if (jwt != null) {
                    val existing = conn.getRequestProperty("Cookie") ?: ""
                    if (!existing.contains("byd_session")) {
                        val cookie = if (existing.isNotEmpty()) "$existing; byd_session=$jwt" else "byd_session=$jwt"
                        conn.setRequestProperty("Cookie", cookie)
                    }
                }

                // Send body for POST/PUT
                if (body.isNotEmpty() && (method == "POST" || method == "PUT")) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }

                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: return "{\"_status\":$code}")
                val responseBody = stream.bufferedReader(Charsets.UTF_8).readText()
                stream.close()

                // Wrap response with status code so JS can check it
                return if (responseBody.startsWith("{") || responseBody.startsWith("[")) {
                    // JSON response — inject status
                    if (responseBody.startsWith("{")) {
                        "{\"_status\":$code," + responseBody.substring(1)
                    } else {
                        "{\"_status\":$code,\"data\":$responseBody}"
                    }
                } else {
                    "{\"_status\":$code,\"body\":${org.json.JSONObject.quote(responseBody)}}"
                }
            } catch (e: Exception) {
                android.util.Log.e("WebViewBridge", "Request failed: $method $urlStr — ${e.message}")
                return "{\"_status\":0,\"error\":\"${e.message?.replace("\"", "'") ?: "unknown"}\"}"
            } finally {
                conn?.disconnect()
            }
        }
    }

    // ==================== AUTH ====================

    private var cachedJwt: String? = null
    private var jwtExpiry: Long = 0

    private fun getAuthJwt(): String? {
        // Cache JWT for 5 minutes to avoid spamming auth state saves
        val now = System.currentTimeMillis()
        if (cachedJwt != null && now < jwtExpiry) return cachedJwt
        
        return try {
            // Try to initialize AuthManager if not already done
            if (com.overdrive.app.auth.AuthManager.getState() == null) {
                com.overdrive.app.auth.AuthManager.initialize()
            }
            
            var jwt = com.overdrive.app.auth.AuthManager.generateJwt()
            
            // If JWT generation failed, try reading auth state directly from daemon's file
            if (jwt == null) {
                android.util.Log.w("WebView", "JWT generation failed, retrying with fresh init...")
                com.overdrive.app.auth.AuthManager.initialize()
                jwt = com.overdrive.app.auth.AuthManager.generateJwt()
            }
            
            if (jwt != null) {
                cachedJwt = jwt
                jwtExpiry = now + 5 * 60 * 1000  // 5 min cache
                android.util.Log.d("WebView", "JWT generated successfully")
            } else {
                android.util.Log.e("WebView", "JWT generation failed after retry")
            }
            jwt
        } catch (e: Exception) {
            android.util.Log.e("WebView", "JWT failed: ${e.message}")
            null
        }
    }

    private fun injectAuthCookie() {
        try {
            val jwt = getAuthJwt()
            if (jwt != null) {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setCookie("http://127.0.0.1:${CameraDaemon.HTTP_PORT}", "byd_session=$jwt; Path=/; Max-Age=31536000")
                cm.flush()
                android.util.Log.d("WebView", "Auth cookie set")
            } else {
                // JWT not available yet — retry after 2 seconds (daemon may still be starting)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val retryJwt = getAuthJwt()
                        if (retryJwt != null) {
                            val cm = CookieManager.getInstance()
                            cm.setCookie("http://127.0.0.1:${CameraDaemon.HTTP_PORT}", "byd_session=$retryJwt; Path=/; Max-Age=31536000")
                            cm.flush()
                            android.util.Log.d("WebView", "Auth cookie set (retry)")
                            // Reload page now that auth is available
                            webView?.reload()
                        } else {
                            android.util.Log.e("WebView", "Auth cookie retry also failed")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WebView", "Auth cookie retry error: ${e.message}")
                    }
                }, 2000)
            }
        } catch (e: Exception) {
            android.util.Log.e("WebView", "Cookie inject failed: ${e.message}")
        }
    }

    private fun loadPage() {
        pageLoadFailed = false
        showLoading()
        currentUrl?.let { webView?.loadUrl(it) }
    }

    private fun retryLoad() {
        injectAuthCookie()
        loadPage()
    }

    private fun showLoading() {
        loadingOverlay?.alpha = 1f
        loadingOverlay?.visibility = View.VISIBLE
        errorOverlay?.visibility = View.GONE
        webView?.visibility = View.VISIBLE
    }

    private fun showContent() {
        loadingOverlay?.animate()?.alpha(0f)?.setDuration(200)
            ?.withEndAction { loadingOverlay?.visibility = View.GONE }?.start()
        errorOverlay?.visibility = View.GONE
        webView?.visibility = View.VISIBLE
    }

    private fun showError() {
        loadingOverlay?.visibility = View.GONE
        errorOverlay?.visibility = View.VISIBLE
        webView?.visibility = View.INVISIBLE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUrl?.let { outState.putString(KEY_SAVED_URL, it) }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        if (pageLoadFailed) retryLoad()
        // Re-apply theme on resume so a user toggle while the page was
        // backgrounded is reflected without requiring a full reload.
        webView?.let { it.evaluateJavascript(buildThemeInjectJs(), null) }
    }

    /**
     * Resolve the active theme's `colorBackground` so the WebView's outer
     * chrome (visible during page loads or where the page itself is
     * transparent) matches whatever light/dark mode the activity is in.
     */
    private fun resolveThemeBackground(): Int {
        val tv = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(
                android.R.attr.colorBackground, tv, true
            )
        ) tv.data else android.graphics.Color.BLACK
    }

    /**
     * Build the JS snippet that tags <html data-theme="dark|light"> based on
     * the current activity night-mode configuration. Called both on every
     * page-finished and on resume so theme switches propagate without a reload.
     *
     * Reads PreferencesManager → AppCompatDelegate → resources.uiMode in that
     * order so an explicit Light/Dark pick wins over the OS-level uiMode
     * (which lags behind setDefaultNightMode for a frame or two and was the
     * source of the "Light app, dark WebView" report).
     */
    private fun buildThemeInjectJs(): String {
        val theme = resolveActiveTheme()
        return "document.documentElement.setAttribute('data-theme','$theme');"
    }

    private fun resolveActiveTheme(): String {
        try {
            val pref = com.overdrive.app.ui.util.PreferencesManager.getThemeMode()
            when (pref) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> return "light"
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> return "dark"
            }
        } catch (ignored: Exception) { /* fall through */ }
        try {
            val mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) return "light"
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) return "dark"
        } catch (ignored: Exception) { /* fall through */ }
        val isNight = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (isNight) "dark" else "light"
    }

    /**
     * Apply a theme to this WebView immediately. The Settings theme picker
     * calls this on every visible WebView fragment so the switch is instant
     * (no activity recreate, no page reload).
     */
    fun applyTheme(theme: String) {
        val safe = if (theme == "light") "light" else "dark"
        webView?.evaluateJavascript(
            "document.documentElement.setAttribute('data-theme','$safe');",
            null
        )
    }

    /**
     * Apply a locale to this WebView immediately. The Android language
     * picker calls this on every visible WebView fragment so the switch is
     * instant (the activity recreate that follows AppCompatDelegate.set
     * ApplicationLocales would normally re-load the page, but on some
     * head-unit configurations the recreate is skipped — e.g. when the
     * fragment is offscreen — and the stale WebView keeps showing the
     * previous language until the user manually navigates away and back).
     *
     * Calls BYD.i18n.setLang() in the loaded page, which refetches the
     * catalog and re-hydrates every [data-i18n] node. Safe to call before
     * the page has finished loading — the call is no-op'd until BYD is
     * present.
     */
    fun applyLocale(lang: String) {
        if (lang.isBlank()) return
        // Sanitise to a JS string literal — only the BCP-47 alphabet is
        // valid here (a-zA-Z0-9 plus "-"). Drop everything else.
        val safe = lang.replace(Regex("[^a-zA-Z0-9-]"), "")
        if (safe.isEmpty()) return
        webView?.evaluateJavascript(
            "if (window.BYD && BYD.i18n && BYD.i18n.setLang) { BYD.i18n.setLang('$safe'); }",
            null
        )
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        // If a file picker is pending, release its callback so the WebView
        // doesn't hang onto a dangling handle.
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null

        webView?.let { wv ->
            // Stop any media playback and clear content before destroying
            wv.loadUrl("about:blank")
            wv.stopLoading()
            // Remove from parent to prevent leaked window
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroyView()
    }
}
