package net.bladewatch.app.ui.fragment.vehicle

import android.content.Context
import android.view.Choreographer
import android.view.MotionEvent
import android.view.TextureView
import android.util.Log
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.View as FilamentView
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native 3D vehicle hero — Filament port of the WebView three.js car renderer
 * (web/shared/vehicle-control.js + ev-card-3d.js).
 *
 * - GLB loading: gltfio (Draco-compressed models decode natively).
 * - TextureView, translucent: composites between the themed GridView behind it
 *   and the tyre corner cards above it, exactly like the old WebView canvas.
 * - Render loop: Choreographer, capped to ~30fps (the head-unit GPU budget the
 *   WebView _detectPerformanceProfile() enforced via pixel-ratio/FPS caps).
 * - Load-generation counter + hard load timeout, mirroring loadModel() in
 *   vehicle-control.js so a stale async load can never clobber a newer one.
 */
class VehicleHeroView(context: Context) {

    companion object {
        private const val TAG = "BladeWatch"
        private const val FRAME_INTERVAL_NANOS = 33_000_000L   // ~30fps cap
        private const val LOAD_TIMEOUT_MS = 60_000L            // parity: 60s in loadModel()
        private const val AUTO_ROTATE_RAD_PER_SEC = 0.25       // slow idle spin
        private const val ASSET_MODEL_DIR = "web/shared/models/"

        // One-time native lib init (filament + gltfio JNI). If the device can't
        // load them this throws; callers treat construction failure as "no 3D".
        private val utilsReady: Boolean by lazy {
            try { Utils.init(); true } catch (t: Throwable) {
                Log.w(TAG, "VehicleHero: Filament native init failed", t); false
            }
        }
    }

    val view: TextureView = TextureView(context)
    var isAvailable = false
        private set

    /** Invoked on the main thread: true once a model rendered, false on load failure. */
    var onModelState: ((loaded: Boolean) -> Unit)? = null

    private val appContext = context.applicationContext
    private var modelViewer: ModelViewer? = null
    private var uiHelper: UiHelper? = null
    private val choreographer = Choreographer.getInstance()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val loadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "VehicleHeroLoad").apply { isDaemon = true }
    }

    // Stale-load protection (parity with vehicle-control.js _loadGen).
    private val loadGen = AtomicInteger(0)
    private var timeoutRunnable: Runnable? = null

    private var baseTransform: FloatArray? = null
    private var rotationRad = 0.0
    private var lastFrameNanos = 0L
    private var rendering = false
    private var destroyed = false
    private var userInteracting = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!rendering || destroyed) return
            choreographer.postFrameCallback(this)
            if (frameTimeNanos - lastFrameNanos < FRAME_INTERVAL_NANOS) return
            val mv = modelViewer ?: return
            if (!userInteracting && lastFrameNanos != 0L) {
                rotationRad += AUTO_ROTATE_RAD_PER_SEC * (frameTimeNanos - lastFrameNanos) / 1e9
                applyAutoRotation()
            }
            lastFrameNanos = frameTimeNanos
            mv.render(frameTimeNanos)
        }
    }

    init {
        if (utilsReady) {
            try {
                initFilament()
                isAvailable = true
            } catch (t: Throwable) {
                Log.w(TAG, "VehicleHero: Filament init failed, keeping 2D placeholder", t)
                safeDestroyEngine()
            }
        }
    }

    private fun initFilament() {
        view.isOpaque = false
        val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
        uiHelper = helper
        val mv = ModelViewer(textureView = view, uiHelper = helper)
        modelViewer = mv
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> userInteracting = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> userInteracting = false
            }
            mv.onTouch(v, event)
            true
        }

        // Transparent canvas over the native grid background (parity with the
        // WebView's alpha:true WebGL context over the page grid).
        mv.view.blendMode = FilamentView.BlendMode.TRANSLUCENT
        mv.scene.skybox = null
        val clear = Renderer.ClearOptions().apply {
            clear = true
            clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
        mv.renderer.setClearOptions(clear)

        // Lighting parity with vehicle-control.js addLighting(): ModelViewer
        // already adds a key directional light; add a uniform ambient (no IBL
        // asset is bundled — without indirect light PBR paint renders black)
        // and the cool purple back light (0x6644aa @ 0.3).
        val engine = mv.engine
        mv.scene.indirectLight = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.9f, 0.9f, 1.0f))
            .intensity(25_000f)
            .build(engine)
        val backLight = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.4f, 0.27f, 0.67f)
            .intensity(20_000f)
            .direction(0f, -0.45f, 0.9f)
            .castShadows(false)
            .build(engine, backLight)
        mv.scene.addEntity(backLight)
    }

    // ─── Model loading ───────────────────────────────────────────────────────

    /**
     * Load a bundled GLB from APK assets (e.g. "destroyer.glb"). Bundled models
     * ship inside the APK under assets/web/shared/models/ — the copies in
     * /data/local/tmp/web are shell-owned mode 600 and unreadable from app UID.
     */
    fun loadBundledModel(fileName: String) {
        if (!isAvailable || destroyed) return
        val gen = loadGen.incrementAndGet()
        armLoadTimeout(gen)
        loadExecutor.execute {
            val bytes = try {
                appContext.assets.open(ASSET_MODEL_DIR + fileName).use { it.readBytes() }
            } catch (t: Throwable) {
                Log.w(TAG, "VehicleHero: failed reading asset $fileName", t)
                null
            }
            mainHandler.post {
                if (gen != loadGen.get() || destroyed) return@post
                if (bytes == null) { finishLoad(gen, false); return@post }
                try {
                    val mv = modelViewer ?: return@post
                    mv.destroyModel()
                    mv.loadModelGlb(ByteBuffer.wrap(bytes))
                    mv.transformToUnitCube()
                    captureBaseTransform()
                    rotationRad = 0.0
                    finishLoad(gen, true)
                } catch (t: Throwable) {
                    Log.w(TAG, "VehicleHero: GLB parse/render failed for $fileName", t)
                    finishLoad(gen, false)
                }
            }
        }
    }

    private fun armLoadTimeout(gen: Int) {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            if (gen == loadGen.get() && !destroyed) {
                Log.w(TAG, "VehicleHero: model load timed out")
                onModelState?.invoke(false)
            }
        }
        timeoutRunnable = r
        mainHandler.postDelayed(r, LOAD_TIMEOUT_MS)
    }

    private fun finishLoad(gen: Int, ok: Boolean) {
        if (gen != loadGen.get()) return
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        onModelState?.invoke(ok)
    }

    // ─── Auto-rotation ───────────────────────────────────────────────────────

    private fun captureBaseTransform() {
        val mv = modelViewer ?: return
        val asset = mv.asset ?: return
        val tm = mv.engine.transformManager
        val inst = tm.getInstance(asset.root)
        if (inst == 0) return
        baseTransform = FloatArray(16).also { tm.getTransform(inst, it) }
    }

    private fun applyAutoRotation() {
        val mv = modelViewer ?: return
        val asset = mv.asset ?: return
        val base = baseTransform ?: return
        val tm = mv.engine.transformManager
        val inst = tm.getInstance(asset.root)
        if (inst == 0) return
        val c = Math.cos(rotationRad).toFloat()
        val s = Math.sin(rotationRad).toFloat()
        // Column-major rotation about Y, applied in model space: M = base × rotY
        val rot = floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f, 0f, 0f,
            s, 0f, c, 0f,
            0f, 0f, 0f, 1f,
        )
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var v = 0f
                for (k in 0 until 4) v += base[k * 4 + row] * rot[col * 4 + k]
                out[col * 4 + row] = v
            }
        }
        tm.setTransform(inst, out)
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    fun onResume() {
        if (!isAvailable || destroyed || rendering) return
        rendering = true
        lastFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    fun onPause() {
        rendering = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        onPause()
        loadGen.incrementAndGet()
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        loadExecutor.shutdownNow()
        safeDestroyEngine()
    }

    private fun safeDestroyEngine() {
        try {
            modelViewer?.destroyModel()
        } catch (t: Throwable) { Log.w(TAG, "VehicleHero: destroyModel failed", t) }
        try {
            uiHelper?.detach()
        } catch (t: Throwable) { Log.w(TAG, "VehicleHero: uiHelper detach failed", t) }
        try {
            modelViewer?.engine?.destroy()
        } catch (t: Throwable) { Log.w(TAG, "VehicleHero: engine destroy failed", t) }
        modelViewer = null
        uiHelper = null
        isAvailable = false
    }
}
