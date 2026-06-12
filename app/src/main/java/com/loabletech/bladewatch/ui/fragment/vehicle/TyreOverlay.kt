package net.bladewatch.app.ui.fragment.vehicle

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import net.bladewatch.app.R

// ─── Tyre card ────────────────────────────────────────────────────────────────

private data class TyreCard(
    val root: LinearLayout,
    val psiTv: TextView,
    val kpaTv: TextView,
    val tempTv: TextView,
    val stateTv: TextView,
    val dot: View,
    var pulseAnimator: ValueAnimator? = null,
)

// ─── Hero region ─────────────────────────────────────────────────────────────

class TyreOverlay(private val context: Context, private val factory: VehicleViewFactory) {

    companion object {
        const val DEFAULT_FALLBACK_GLB = "destroyer.glb"
    }

    // Full-bleed: the 3D car is the page background; everything else floats on
    // glass over it. Transparent so the controller's light studio backdrop shows.
    private val frame: FrameLayout = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val view: View get() = frame

    private lateinit var hero: VehicleHeroView
    private lateinit var placeholderView: ImageView
    private lateinit var loadingSpinner: ProgressBar

    private val flCard: TyreCard  // front-left of car = screen RIGHT, BOTTOM
    private val frCard: TyreCard  // front-right of car = screen LEFT,  BOTTOM
    private val rlCard: TyreCard  // rear-left of car  = screen RIGHT,  TOP
    private val rrCard: TyreCard  // rear-right of car = screen LEFT,   TOP

    init {
        // The 2D silhouette is kept only as a last-resort fallback for when the
        // 3D hero fails to load (no WebView provider, or a GLB load error).
        // During a normal load the circular spinner below is shown instead.
        placeholderView = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.vehicle_hero_placeholder))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                factory.dp(260), factory.dp(110), Gravity.CENTER)
            visibility = View.GONE
        }

        // Circular loading indicator shown while the GLB streams in / decodes.
        loadingSpinner = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(factory.accentColor())
            layoutParams = FrameLayout.LayoutParams(
                factory.dp(40), factory.dp(40), Gravity.CENTER)
        }

        hero = VehicleHeroView(context)
        if (hero.isAvailable) {
            hero.view.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Hero first (bottom), then spinner/placeholder above it.
            frame.addView(hero.view)
            frame.addView(placeholderView)
            frame.addView(loadingSpinner)
            hero.onModelState = { loaded ->
                loadingSpinner.visibility = View.GONE
                placeholderView.visibility = if (loaded) View.GONE else View.VISIBLE
                hero.view.visibility = if (loaded) View.VISIBLE else View.INVISIBLE
            }
            hero.view.visibility = View.INVISIBLE
            // Show the default car (Seal 5 DM-i / destroyer.glb) right away;
            // startAppearanceLoad() swaps in the persisted selection once the
            // daemon responds. The WebView queues this until hero.html is ready.
            hero.loadBundledModel(DEFAULT_FALLBACK_GLB)
        } else {
            // No 3D engine available → spinner is pointless; show the silhouette.
            frame.addView(placeholderView)
            frame.addView(loadingSpinner)
            loadingSpinner.visibility = View.GONE
            placeholderView.visibility = View.VISIBLE
        }

        // Tyre cards float as glass over the car, stacked in two columns in the
        // upper area so they stay clear of the bottom controls panel. Mirror is
        // preserved: car-left tyres (RR/FR) on screen-left, car-right (RL/FL) right.
        rrCard = buildCard("RR")
        frCard = buildCard("FR")
        rlCard = buildCard("RL")
        flCard = buildCard("FL")

        frame.addView(buildTyreColumn(rrCard, frCard), columnLp(Gravity.TOP or Gravity.START))
        frame.addView(buildTyreColumn(rlCard, flCard), columnLp(Gravity.TOP or Gravity.END))
    }

    private fun buildTyreColumn(top: TyreCard, bottom: TyreCard): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(top.root)
            addView(bottom.root, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = factory.dp(8) })
        }
    }

    private fun columnLp(gravity: Int): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(
            factory.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT, gravity
        ).apply {
            val m = factory.dp(14)
            // Top margin clears the status pills overlaid at the very top.
            setMargins(m, factory.dp(60), m, m)
        }
    }

    private fun buildCard(cornerLabel: String): TyreCard {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(factory.dp(10), factory.dp(8), factory.dp(10), factory.dp(8))
            background = factory.glassPanel(14)
            elevation = factory.dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(factory.dp(8), factory.dp(8)).apply { marginEnd = factory.dp(4) }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.GRAY)
            }
        }
        val labelTv = TextView(context).apply {
            text = cornerLabel; textSize = 10f; setTextColor(factory.glassMuted())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(dot); headerRow.addView(labelTv)
        root.addView(headerRow)

        val psiTv = TextView(context).apply {
            text = "—"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(factory.glassText())
        }
        root.addView(psiTv)

        val kpaTv = TextView(context).apply {
            text = "— kPa"; textSize = 10f; setTextColor(factory.glassMuted())
        }
        root.addView(kpaTv)

        val tempTv = TextView(context).apply {
            text = ""; textSize = 10f; setTextColor(factory.glassMuted())
        }
        root.addView(tempTv)

        val stateTv = TextView(context).apply {
            text = ""; textSize = 10f; setTextColor(factory.glassMuted())
        }
        root.addView(stateTv)

        return TyreCard(root, psiTv, kpaTv, tempTv, stateTv, dot)
    }

    fun update(tyres: TyreSetInfo) {
        updateCard(flCard, tyres.fl, "FL")
        updateCard(frCard, tyres.fr, "FR")
        updateCard(rlCard, tyres.rl, "RL")
        updateCard(rrCard, tyres.rr, "RR")
    }

    private fun updateCard(card: TyreCard, t: TyreInfo, cornerLabel: String) {
        val tier = VehicleFormatters.tyreTier(t)
        val dotColor = when (tier) {
            TyreTier.MUTED   -> Color.GRAY
            TyreTier.ALERT   -> Color.parseColor("#F44336")
            TyreTier.WARN    -> Color.parseColor("#FF9800")
            TyreTier.CAUTION -> Color.parseColor("#FFC107")
            TyreTier.NORMAL  -> Color.parseColor("#4CAF50")
        }
        (card.dot.background as? android.graphics.drawable.GradientDrawable)?.setColor(dotColor)

        card.psiTv.text = if (t.psi != null) "${"%.1f".format(t.psi)} PSI" else "—"
        card.kpaTv.text = if (t.kPa != null) "${t.kPa} kPa" else "— kPa"
        card.tempTv.text = if (t.temperatureC != null) "${t.temperatureC}°C" else ""
        card.stateTv.text = when (tier) {
            TyreTier.MUTED   -> context.getString(R.string.vehicle_tyre_no_signal)
            TyreTier.ALERT   -> when {
                t.airLeakState >= 2 -> context.getString(R.string.vehicle_tyre_fast_leak)
                t.airLeakState == 1 -> context.getString(R.string.vehicle_tyre_slow_leak)
                else                -> context.getString(R.string.vehicle_tyre_low)
            }
            TyreTier.WARN    -> context.getString(R.string.vehicle_tyre_check_pressure)
            TyreTier.CAUTION -> if (t.psi != null && t.psi > 45) context.getString(R.string.vehicle_tyre_high) else context.getString(R.string.vehicle_tyre_low)
            TyreTier.NORMAL  -> context.getString(R.string.vehicle_tyre_ok)
        }

        // Pulse animation for ALERT tier
        if (tier == TyreTier.ALERT) {
            if (card.pulseAnimator == null || card.pulseAnimator?.isRunning != true) {
                card.pulseAnimator = ValueAnimator.ofFloat(1f, 1.35f, 1f).apply {
                    duration = 1400
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { anim ->
                        val s = anim.animatedValue as Float
                        card.dot.scaleX = s; card.dot.scaleY = s
                    }
                    start()
                }
            }
        } else {
            card.pulseAnimator?.cancel()
            card.pulseAnimator = null
            card.dot.scaleX = 1f; card.dot.scaleY = 1f
        }
    }

    fun cancelAnimations() {
        listOf(flCard, frCard, rlCard, rrCard).forEach {
            it.pulseAnimator?.cancel()
            it.pulseAnimator = null
        }
    }

    // ─── 3D hero lifecycle (driven by VehicleController) ─────────────────────

    fun heroResume() { if (::hero.isInitialized) hero.onResume() }
    fun heroPause() { if (::hero.isInitialized) hero.onPause() }
    fun heroDestroy() { if (::hero.isInitialized) hero.destroy() }

    fun heroLoadModel(glbFile: String) {
        if (::hero.isInitialized && hero.isAvailable) {
            // Show the spinner over the current car only if this is a real swap;
            // loadBundledModel no-ops when the file is already showing.
            if (hero.willChangeModel(glbFile)) {
                loadingSpinner.visibility = View.VISIBLE
                hero.view.visibility = View.INVISIBLE
                placeholderView.visibility = View.GONE
            }
            hero.loadBundledModel(glbFile)
        }
    }

    fun heroApplyColor(colorHex: String) {
        if (::hero.isInitialized && hero.isAvailable) hero.applyBodyColor(colorHex)
    }
}
