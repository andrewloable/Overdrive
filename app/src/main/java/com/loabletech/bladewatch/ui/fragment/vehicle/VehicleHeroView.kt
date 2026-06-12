package net.bladewatch.app.ui.fragment.vehicle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * 3D vehicle hero — three.js in a small embedded WebView (web/hero/hero.html).
 *
 * History: this was first ported to native Filament, but the head unit's
 * Adreno 610 GL driver (V@415.0, Dec 2021) SIGSEGVs (null memcpy in
 * libGLESv2_adreno) after 1–4 minutes of continuous gltfio rendering.
 * The three.js WebView stack is what the pre-migration vehicle-control page
 * used and is proven stable on this exact device, so the hero strip now
 * embeds it directly. Same vendor bundle (three r147 + Draco), same lighting,
 * same body-paint recolor heuristic.
 *
 * Public surface kept identical to the Filament version so TyreOverlay and
 * VehicleController didn't have to change shape:
 *   view / isAvailable / onModelState / loadBundledModel / applyBodyColor /
 *   onResume / onPause / destroy.
 */
class VehicleHeroView(context: Context) {

    companion object {
        private const val TAG = "BladeWatch"
        // Synthetic https origin served from APK assets via shouldInterceptRequest.
        // A real https origin (not file://) avoids the sandboxed WebView's block on
        // file:// XHR — GLTFLoader/DRACOLoader fetch the GLB + decoder over XHR, and
        // those fail silently from a file:// page, leaving the placeholder showing.
        private const val ASSET_HOST = "bladewatch.assets"
        private const val HERO_URL = "https://$ASSET_HOST/web/hero/hero.html"
    }

    private val appContext = context.applicationContext
    val view: WebView = WebView(context)
    var isAvailable = true
        private set

    /** Invoked on the main thread: true once a model rendered, false on load failure. */
    var onModelState: ((loaded: Boolean) -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pageReady = false
    private var destroyed = false
    private var currentModelFile: String? = null
    private val pendingJs = mutableListOf<String>()

    init {
        try {
            initWebView()
        } catch (t: Throwable) {
            // No WebView provider on the device → keep the 2D placeholder.
            Log.w(TAG, "VehicleHero: WebView init failed, keeping 2D placeholder", t)
            isAvailable = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isHorizontalScrollBarEnabled = false
        view.isVerticalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false   // assets are served over the synthetic https origin instead
        }
        view.webViewClient = AssetClient()
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(TAG, "VehicleHero JS: ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                }
                return true
            }
        }
        view.addJavascriptInterface(Bridge(), "AndroidHero")
        view.loadUrl(HERO_URL)
    }

    /** Serves the synthetic https origin from packaged APK assets. */
    private inner class AssetClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != ASSET_HOST) return null
            // Strip leading '/', map to assets/<path>. Normalize ../ segments
            // (hero.html references ../shared/...) against the asset root.
            val rawPath = url.path?.removePrefix("/") ?: return notFound()
            val assetPath = normalize(rawPath)
            return try {
                val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
                WebResourceResponse(mimeFor(assetPath), null, 200, "OK", corsHeaders(), ByteArrayInputStream(bytes))
            } catch (t: Throwable) {
                Log.w(TAG, "VehicleHero: asset not found: $assetPath", t)
                notFound()
            }
        }

        private fun notFound() =
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, ByteArrayInputStream(ByteArray(0)))

        private fun corsHeaders() = hashMapOf("Access-Control-Allow-Origin" to "*")
    }

    /**
     * Collapse "a/b/../c" → "a/c". The result is relative to the APK assets
     * root: AssetManager.open() is already rooted there, so no "assets/" prefix.
     */
    private fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(seg)
            }
        }
        return out.joinToString("/")
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js")   -> "application/javascript"
        path.endsWith(".css")  -> "text/css"
        path.endsWith(".glb")  -> "model/gltf-binary"
        path.endsWith(".wasm") -> "application/wasm"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".png")  -> "image/png"
        else -> "application/octet-stream"
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() {
            mainHandler.post {
                if (destroyed) return@post
                pageReady = true
                pendingJs.forEach { view.evaluateJavascript(it, null) }
                pendingJs.clear()
            }
        }

        @JavascriptInterface
        fun onModelState(loaded: Boolean) {
            mainHandler.post {
                if (!destroyed) onModelState?.invoke(loaded)
            }
        }
    }

    private fun js(script: String) {
        mainHandler.post {
            if (destroyed) return@post
            if (pageReady) view.evaluateJavascript(script, null)
            else pendingJs.add(script)
        }
    }

    /** True if loadBundledModel(fileName) would actually trigger a new load. */
    fun willChangeModel(fileName: String): Boolean =
        isAvailable && !destroyed && fileName != currentModelFile

    /** Load a bundled GLB by file name (resolved by hero.html against web/shared/models/). */
    fun loadBundledModel(fileName: String) {
        if (!isAvailable || destroyed) return
        if (fileName == currentModelFile) return   // already showing this car
        currentModelFile = fileName
        js("Hero.loadModel('${fileName.replace("'", "")}')")
    }

    /** Apply a sRGB hex body color (e.g. "#1B4D3E") to the detected body-paint meshes. */
    fun applyBodyColor(colorHex: String) {
        if (!isAvailable || destroyed) return
        js("Hero.setColor('${colorHex.replace("'", "")}')")
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun onResume() {
        if (!isAvailable || destroyed) return
        view.onResume()
        js("Hero.setRunning(true)")
    }

    fun onPause() {
        if (!isAvailable || destroyed) return
        js("Hero.setRunning(false)")
        view.onPause()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        try {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "VehicleHero: WebView destroy failed", t)
        }
        isAvailable = false
    }
}
