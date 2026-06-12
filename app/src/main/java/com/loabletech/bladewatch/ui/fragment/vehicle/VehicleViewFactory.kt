package net.bladewatch.app.ui.fragment.vehicle

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import net.bladewatch.app.R
import net.bladewatch.app.ui.common.BladeTheme

class VehicleViewFactory(val context: Context) {

    private val theme = BladeTheme(context)

    // ─── Theme ───────────────────────────────────────────────────────────────

    fun isDark()        = theme.isDark()
    fun bgColor()       = theme.bgColor()
    fun surfaceColor()  = theme.surfaceColor()
    fun textColor()     = theme.textColor()
    fun mutedColor()    = theme.mutedColor()
    fun dividerColor()  = theme.dividerColor()
    fun pillBgColor()   = theme.pillBgColor()
    fun accentColor()   = theme.accentColor()

    fun heatLabel(level: Int) = when (level) {
        1 -> context.getString(R.string.vehicle_heat_low)
        2 -> context.getString(R.string.vehicle_heat_high)
        else -> context.getString(R.string.vehicle_heat_off)
    }

    fun dp(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    // ─── Liquid glass (Apple-style frosted overlay) ──────────────────────────
    // API 29 has no live backdrop blur (RenderEffect is 31+), so the look is
    // approximated with a translucent fill + hairline edge over the full-bleed
    // 3D car. Everything is theme-aware: light frost + dark text in light mode,
    // dark frost + light text in dark mode, so controls stay legible in both.

    fun glassText()   = if (isDark()) Color.parseColor("#F2F2F7") else Color.parseColor("#15151A")
    fun glassMuted()  = if (isDark()) Color.parseColor("#AEB0BA") else Color.parseColor("#5A5A66")
    /** Tint for the studio backdrop behind the 3D car. */
    fun glassBgTop()    = if (isDark()) Color.parseColor("#22252E") else Color.parseColor("#F6F8FC")
    fun glassBgBottom() = if (isDark()) Color.parseColor("#0C0D11") else Color.parseColor("#DDE3EE")

    /** Frosted fill — bright white frost (light) / smoky dark frost (dark). */
    private fun glassFill(alpha: Int) =
        if (isDark()) Color.argb(alpha, 0x2C, 0x2E, 0x38) else Color.argb(alpha, 255, 255, 255)
    /** Hairline edge highlight — always a soft white catch-light. */
    private fun glassStroke() =
        if (isDark()) Color.argb(0x42, 255, 255, 255) else Color.argb(0xC8, 255, 255, 255)

    /** Rounded translucent panel: frost + hairline highlight. */
    fun glassPanel(cornerRadiusDp: Int, fillAlpha: Int = 0x9E): GradientDrawable =
        GradientDrawable().apply {
            setColor(glassFill(fillAlpha))
            cornerRadius = dp(cornerRadiusDp).toFloat()
            setStroke(dp(1), glassStroke())
        }

    /** Same frost with independently-rounded corners (e.g. only the top). */
    fun glassPanelRadii(tl: Int, tr: Int, br: Int, bl: Int, fillAlpha: Int = 0x9E): GradientDrawable =
        GradientDrawable().apply {
            setColor(glassFill(fillAlpha))
            val r = floatArrayOf(
                dp(tl).toFloat(), dp(tl).toFloat(), dp(tr).toFloat(), dp(tr).toFloat(),
                dp(br).toFloat(), dp(br).toFloat(), dp(bl).toFloat(), dp(bl).toFloat(),
            )
            cornerRadii = r
            setStroke(dp(1), glassStroke())
        }

    /** Faint inset cell used to group a control on the glass panel. */
    fun glassCell(cornerRadiusDp: Int = 14): GradientDrawable = glassPanel(cornerRadiusDp, fillAlpha = 0x66)

    /** Translucent chip background for unselected pills (theme-aware). */
    fun glassChip(cornerRadiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(glassFill(0x5A))
            cornerRadius = dp(cornerRadiusDp).toFloat()
            setStroke(dp(1), glassStroke())
        }

    /** Equal-weight horizontal row of cells with gaps between them. */
    fun rowOf(vararg cells: View): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        cells.forEachIndexed { i, cell ->
            if (i > 0) row.addView(spacer2(dp(8)))
            cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(cell)
        }
        return row
    }

    // ─── Accessibility helper ────────────────────────────────────────────────

    /** Makes TalkBack announce a TextView-styled button with the "Button" role. */
    fun TextView.asButton() {
        isFocusable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
    }

    // ─── Ripple helper ───────────────────────────────────────────────────────

    private fun ripple(base: GradientDrawable): RippleDrawable {
        val rippleColor = ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
        return RippleDrawable(rippleColor, base, null)
    }

    // ─── UI component builders ───────────────────────────────────────────────

    fun makePanel() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun makeTwoColGrid(vararg views: View): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var i = 0
        while (i < views.size) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            views[i].layoutParams = lp
            row.addView(views[i])
            if (i + 1 < views.size) {
                row.addView(spacer2(dp(8)))
                views[i + 1].layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(views[i + 1])
            }
            grid.addView(row)
            if (i + 2 < views.size) grid.addView(spacer(dp(8)))
            i += 2
        }
        return grid
    }

    fun buildActionButton(label: String, color: Int, pending: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        minimumHeight = dp(44); minimumWidth = dp(44)
        val base = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
        background = ripple(base)
        alpha = if (pending) 0.5f else 1.0f
        isClickable = !pending
        setOnClickListener { onClick() }
        asButton()
    }

    fun buildWideButton(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        minimumHeight = dp(44)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val base = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat() }
        background = ripple(base)
        setOnClickListener { onClick() }
        asButton()
    }

    fun buildSmallPresetButton(label: String, active: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 11f; gravity = Gravity.CENTER
        setTextColor(if (active) Color.WHITE else textColor())
        setPadding(dp(8), dp(6), dp(8), dp(6))
        minimumHeight = dp(44); minimumWidth = dp(44)
        val base = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (active) accentColor() else pillBgColor())
        }
        background = ripple(base)
        setOnClickListener { onClick() }
        asButton()
    }

    fun buildCycleButton(label: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        minimumHeight = dp(44); minimumWidth = dp(44)
        val base = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        background = ripple(base)
        setOnClickListener { onClick() }
        asButton()
    }

    /**
     * Returns the stepper row and a reference to the value TextView for in-place updates.
     */
    fun buildStepperResult(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit): Pair<LinearLayout, TextView> {
        val valueTv = TextView(context).apply {
            text = value; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(textColor())
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(TextView(context).apply {
            text = label; textSize = 13f; setTextColor(textColor())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(buildRoundButton("−", context.getString(R.string.vehicle_a11y_decrease_fmt, label), onMinus))
        row.addView(valueTv)
        row.addView(buildRoundButton("+", context.getString(R.string.vehicle_a11y_increase_fmt, label), onPlus))
        return Pair(row, valueTv)
    }

    fun buildStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout =
        buildStepperResult(label, value, onMinus, onPlus).first

    fun buildRoundButton(label: String, contentDesc: String? = null, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        val base = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentColor()) }
        background = ripple(base)
        if (contentDesc != null) contentDescription = contentDesc
        setOnClickListener { onClick() }
        asButton()
    }

    fun buildToggleRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit): Pair<LinearLayout, TextView> {
        val pill = buildTogglePill(enabled) { onToggle(!enabled) }
        val stateStr = if (enabled) context.getString(R.string.vehicle_toggle_on) else context.getString(R.string.vehicle_toggle_off)
        pill.tag = label
        pill.contentDescription = "$label: $stateStr"
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(surfaceColor()); cornerRadius = dp(8).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumHeight = dp(44)
        }
        row.addView(TextView(context).apply {
            text = label; textSize = 14f; setTextColor(textColor())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        row.addView(pill)
        return Pair(row, pill)
    }

    fun buildTogglePill(enabled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        val stateStr = if (enabled) context.getString(R.string.vehicle_toggle_on) else context.getString(R.string.vehicle_toggle_off)
        text = stateStr
        contentDescription = stateStr
        textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minimumHeight = dp(44); minimumWidth = dp(44)
        val base = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (enabled) accentColor() else Color.parseColor("#607D8B"))
        }
        background = ripple(base)
        setOnClickListener { onClick() }
        asButton()
    }

    fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(textColor())
    }

    fun labelText(text: String) = TextView(context).apply {
        this.text = text; textSize = 13f; setTextColor(mutedColor())
    }

    fun centeredLabel(text: String) = TextView(context).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER; setTextColor(mutedColor())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
    }

    fun infoLabel(text: String) = TextView(context).apply {
        this.text = text; textSize = 12f; setTextColor(mutedColor())
        background = GradientDrawable().apply {
            setColor(if (isDark()) Color.parseColor("#1A2A1A") else Color.parseColor("#E8F5E9"))
            cornerRadius = dp(6).toFloat()
        }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun spacer(h: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    fun spacer2(w: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

}
