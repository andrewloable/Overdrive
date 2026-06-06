package net.bladewatch.app.ui.fragment.liveview

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal class LiveViewController(private val context: Context) {

    private val root = FrameLayout(context)
    private val textureView = TextureView(context)
    private val banner = LinearLayout(context)
    private val bannerMessage = TextView(context)
    private val retryButton = Button(context)
    private val directionBar = LinearLayout(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val streamClient = LiveStreamClient()
    private var currentSurface: Surface? = null
    private var state = LiveViewState()

    val view: View get() = root

    init {
        buildView()
    }

    fun onResume() {
        // Surface may already be available if coming back from another fragment.
        val surface = currentSurface
        if (surface != null) startStream(surface)
        // Otherwise, onSurfaceTextureAvailable fires and calls startStream.
    }

    fun onPause() {
        streamClient.stop()
        renderState(state.copy(status = LiveStreamStatus.Idle))
    }

    fun onDestroy() {
        streamClient.stop()
        currentSurface?.release()
        currentSurface = null
    }

    private fun startStream(surface: Surface) {
        streamClient.stop()
        streamClient.setPreviewSurface(surface)
        streamClient.connect(state.direction) { status ->
            mainHandler.post { renderState(state.copy(status = status)) }
        }
    }

    private fun retry() {
        val surface = currentSurface ?: return
        startStream(surface)
    }

    private fun selectDirection(direction: LiveViewDirection) {
        state = state.copy(direction = direction)
        renderDirectionBar()
        streamClient.selectDirection(direction)
    }

    private fun buildView() {
        root.setBackgroundColor(Color.BLACK)

        textureView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                val surface = Surface(st)
                currentSurface = surface
                startStream(surface)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                streamClient.stop()
                streamClient.setPreviewSurface(null)
                currentSurface?.release()
                currentSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        banner.orientation = LinearLayout.VERTICAL
        banner.gravity = Gravity.CENTER
        banner.setBackgroundColor(Color.parseColor("#CC000000"))
        banner.setPadding(dp(32), dp(24), dp(32), dp(24))
        banner.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER }

        bannerMessage.setTextColor(Color.WHITE)
        bannerMessage.textSize = 16f
        bannerMessage.gravity = Gravity.CENTER

        retryButton.text = "Retry"
        retryButton.visibility = View.GONE
        retryButton.setOnClickListener { retry() }

        banner.addView(bannerMessage)
        banner.addView(
            retryButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(16) },
        )

        directionBar.orientation = LinearLayout.HORIZONTAL
        directionBar.gravity = Gravity.CENTER_VERTICAL
        directionBar.setBackgroundColor(Color.parseColor("#CC101010"))
        directionBar.setPadding(dp(8), dp(4), dp(8), dp(4))
        LiveViewDirection.entries.forEach { dir ->
            val btn = TextView(context)
            btn.tag = dir
            btn.text = dir.label
            btn.gravity = Gravity.CENTER
            btn.textSize = 13f
            btn.minWidth = dp(60)
            btn.minHeight = dp(40)
            btn.setPadding(dp(12), 0, dp(12), 0)
            btn.setOnClickListener { selectDirection(dir) }
            directionBar.addView(btn)
        }
        directionBar.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(24)
        }

        root.addView(textureView)
        root.addView(banner)
        root.addView(directionBar)

        renderState(state)
    }

    private fun renderState(newState: LiveViewState) {
        state = newState
        renderBanner(newState.status)
        renderDirectionBar()
    }

    private fun renderBanner(status: LiveStreamStatus) {
        when (status) {
            is LiveStreamStatus.Idle -> banner.visibility = View.GONE
            is LiveStreamStatus.Connecting -> {
                bannerMessage.text = "Connecting to camera…"
                retryButton.visibility = View.GONE
                banner.visibility = View.VISIBLE
            }
            is LiveStreamStatus.Live -> banner.visibility = View.GONE
            is LiveStreamStatus.Error -> {
                bannerMessage.text = "Error: ${status.reason}"
                retryButton.visibility = View.VISIBLE
                banner.visibility = View.VISIBLE
            }
            is LiveStreamStatus.Unavailable -> {
                bannerMessage.text = "Camera unavailable\n${status.reason}"
                retryButton.visibility = View.VISIBLE
                banner.visibility = View.VISIBLE
            }
        }
    }

    private fun renderDirectionBar() {
        for (i in 0 until directionBar.childCount) {
            val btn = directionBar.getChildAt(i) as? TextView ?: continue
            val selected = btn.tag == state.direction
            if (selected) {
                btn.setTextColor(Color.parseColor("#151515"))
                btn.setBackgroundColor(Color.parseColor("#EDEFEFEF"))
            } else {
                btn.setTextColor(Color.WHITE)
                btn.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
