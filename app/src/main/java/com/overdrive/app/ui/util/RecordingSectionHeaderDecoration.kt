package com.overdrive.app.ui.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.R
import com.overdrive.app.ui.model.RecordingFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Sticky time-of-day section headers for the recording grid.
 *
 * The single-day case (most common — user picked a date in MaterialDatePicker)
 * groups by hour bucket: MORNING / AFTERNOON / EVENING / NIGHT.
 * Multi-day lists fall back to TODAY / YESTERDAY / "Mon, May 12" headers.
 *
 * Implementation: pure ItemDecoration so the adapter stays single-type and
 * we don't pay the cost of a header view-type. The first item in each
 * section gets extra top inset; the canvas pass paints the section label
 * (and a sticky version pinned to the top of the list while that section is
 * onscreen).
 */
class RecordingSectionHeaderDecoration(
    context: Context,
    private val items: () -> List<RecordingFile>
) : RecyclerView.ItemDecoration() {

    /** When true, label per item is the time-of-day bucket (single-day list). */
    var singleDayMode: Boolean = true

    private val res = context.resources
    private val density = res.displayMetrics.density

    private val headerHeightPx: Int = (40f * density).toInt()
    private val headerInsetTopPx: Int = (12f * density).toInt()
    private val headerPaddingHorizPx: Float = 20f * density
    private val labelOffsetYPx: Float = 18f * density

    private val labelStrings = LabelStrings(
        morning = context.getString(R.string.recording_lib_section_morning),
        afternoon = context.getString(R.string.recording_lib_section_afternoon),
        evening = context.getString(R.string.recording_lib_section_evening),
        night = context.getString(R.string.recording_lib_section_night),
        today = context.getString(R.string.recording_lib_date_today),
        yesterday = context.getString(R.string.recording_lib_date_yesterday)
    )
    private val multiDayDateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, res.displayMetrics
        )
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
        color = ResourcesCompat.getColor(res, R.color.text_secondary, null)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ResourcesCompat.getColor(res, R.color.bg_surface, null)
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: android.view.View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        val data = items()
        if (pos >= data.size) return

        // Only the first item in each section reserves space for the label —
        // and in a 2-column grid, only the leftmost column of that row.
        val isFirstInSection = pos == 0 || labelFor(data[pos]) != labelFor(data[pos - 1])
        if (!isFirstInSection) return

        // For grids: only inset rows that actually start a section. For all
        // columns in that row we still want the row to align, so we apply
        // the inset on every column of the row's first item — but since
        // section transitions are by item (not by row), we additionally
        // inset every item up to the next column == 0.
        val lm = parent.layoutManager
        val spanCount = if (lm is GridLayoutManager) lm.spanCount else 1
        val columnInRow = pos % spanCount
        // Inset only the first column so the row's top aligns; the other
        // cells in the row are taller via wrap_content matching, but since
        // we draw per-item and our items are the same size, this is enough
        // for the header-area. To keep all cells in the row aligned we'd
        // ideally inset every cell of the row that contains pos; for our
        // simple case (section-start lands at column 0 in single-day mode
        // since adjacent items share buckets) this is sufficient.
        if (columnInRow == 0) {
            outRect.top = headerHeightPx + headerInsetTopPx
        } else {
            // If section start lands mid-row (rare across-day boundaries),
            // still inset to keep alignment.
            outRect.top = headerHeightPx + headerInsetTopPx
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val data = items()
        if (data.isEmpty()) return

        val lm = parent.layoutManager as? GridLayoutManager ?: return
        val firstVisible = lm.findFirstVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION) return

        // The section label that the sticky pass will paint at the top — skip
        // the per-row paint for the same label to avoid drawing it twice.
        val stickyLabel = if (firstVisible < data.size) labelFor(data[firstVisible]) else null

        // Paint section labels above each section-start row.
        val seenInDraw = HashSet<Int>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos >= data.size) continue
            val isFirstInSection = pos == 0 || labelFor(data[pos]) != labelFor(data[pos - 1])
            if (!isFirstInSection) continue
            // Only paint once per section per row (grid: avoid double-paint
            // for the same row's other column).
            val rowKey = pos / lm.spanCount
            if (!seenInDraw.add(rowKey)) continue

            val label = labelFor(data[pos]) ?: continue
            if (label == stickyLabel) continue
            val top = (child.top - headerHeightPx).toFloat()
            drawLabel(c, parent, label, top)
        }

        // Sticky top: the label of the section that owns the first visible row.
        if (stickyLabel != null) {
            // If the next section's first item is ABOVE the sticky region,
            // push the sticky upward so the new label slides in.
            var stickyTop = 0f
            // Find the next section start within the visible window.
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION || pos >= data.size) continue
                val isFirstInSection = pos > firstVisible &&
                    labelFor(data[pos]) != labelFor(data[pos - 1])
                if (!isFirstInSection) continue
                val nextSectionTop = (child.top - headerHeightPx).toFloat()
                if (nextSectionTop in 0f..headerHeightPx.toFloat()) {
                    stickyTop = nextSectionTop - headerHeightPx
                    break
                }
            }
            drawLabel(c, parent, stickyLabel, stickyTop, sticky = true)
        }
    }

    private fun drawLabel(
        c: Canvas,
        parent: RecyclerView,
        label: String,
        top: Float,
        sticky: Boolean = false
    ) {
        val left = parent.paddingLeft.toFloat()
        val right = (parent.width - parent.paddingRight).toFloat()
        val bottom = top + headerHeightPx
        // Solid background only for the sticky row so non-sticky labels float
        // over the list cleanly.
        if (sticky) {
            c.drawRect(left, top, right, bottom, backgroundPaint)
        }
        c.drawText(
            label.uppercase(Locale.getDefault()),
            left + headerPaddingHorizPx,
            top + labelOffsetYPx + (headerHeightPx / 2f) - (labelOffsetYPx / 2f),
            textPaint
        )
    }

    /** Section label for an item. Null = skip (shouldn't happen here). */
    private fun labelFor(rec: RecordingFile): String? {
        return if (singleDayMode) timeOfDayLabel(rec.timestamp)
        else dateLabel(rec.timestamp)
    }

    private fun timeOfDayLabel(ts: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = ts }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> labelStrings.morning
            in 12..16 -> labelStrings.afternoon
            in 17..20 -> labelStrings.evening
            else -> labelStrings.night
        }
    }

    private fun dateLabel(ts: Long): String {
        val itemCal = Calendar.getInstance().apply {
            timeInMillis = ts
            zeroTime()
        }
        val today = Calendar.getInstance().apply { zeroTime() }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when (itemCal.timeInMillis) {
            today.timeInMillis -> labelStrings.today
            yesterday.timeInMillis -> labelStrings.yesterday
            else -> multiDayDateFmt.format(Date(ts))
        }
    }

    private fun Calendar.zeroTime() {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private data class LabelStrings(
        val morning: String,
        val afternoon: String,
        val evening: String,
        val night: String,
        val today: String,
        val yesterday: String
    )
}
