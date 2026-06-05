package com.overdrive.app.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Custom view that renders event detection markers on a video timeline.
 * Each event span is drawn as a colored rectangle proportional to the video duration.
 * A playhead indicator shows current position.
 *
 * Colors: motion=gray, person=red, car=blue, bike=green
 */
class EventTimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TimelineEvent(
        val startMs: Long,
        val endMs: Long,
        val type: String,
        val confidence: Float = 0f
    )

    private var events: List<TimelineEvent> = emptyList()
    private var durationMs: Long = 0
    private var playheadMs: Long = 0

    private val paintMotion = Paint().apply { color = 0x99888888.toInt(); isAntiAlias = true }
    private val paintPerson = Paint().apply { color = 0xCCFF4444.toInt(); isAntiAlias = true }
    private val paintCar = Paint().apply { color = 0xCC4488FF.toInt(); isAntiAlias = true }
    private val paintBike = Paint().apply { color = 0xCC44CC44.toInt(); isAntiAlias = true }
    private val paintPlayhead = Paint().apply { color = 0xFFFFFFFF.toInt(); isAntiAlias = true }

    fun setEvents(events: List<TimelineEvent>, durationMs: Long) {
        this.events = events
        this.durationMs = durationMs
        invalidate()
    }

    fun setPlayhead(positionMs: Long) {
        this.playheadMs = positionMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationMs <= 0 || width <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        val barTop = h * 0.2f
        val barBottom = h * 0.8f

        // Draw event spans
        for (ev in events) {
            val left = (ev.startMs.toFloat() / durationMs) * w
            val right = ((ev.endMs.toFloat() / durationMs) * w).coerceAtLeast(left + 2f)
            val paint = when (ev.type) {
                "person" -> paintPerson
                "car" -> paintCar
                "bike" -> paintBike
                else -> paintMotion
            }
            canvas.drawRect(left, barTop, right, barBottom, paint)
        }

        // Draw playhead
        if (playheadMs in 0..durationMs) {
            val x = (playheadMs.toFloat() / durationMs) * w
            canvas.drawRect(x - 1.5f, 0f, x + 1.5f, h, paintPlayhead)
        }
    }
}
