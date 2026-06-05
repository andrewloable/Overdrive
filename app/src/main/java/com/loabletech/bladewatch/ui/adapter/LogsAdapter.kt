package com.overdrive.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.overdrive.app.ui.model.LogEntry
import com.overdrive.app.ui.model.LogLevel
import com.overdrive.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for displaying log entries in a RecyclerView.
 */
class LogsAdapter : ListAdapter<LogEntry, LogsAdapter.LogViewHolder>(LogDiffCallback()) {
    
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_entry, parent, false)
        return LogViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val levelIndicator: View = itemView.findViewById(R.id.levelIndicator)
        private val tvTag: TextView = itemView.findViewById(R.id.tvTag)
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        
        fun bind(entry: LogEntry) {
            tvTimestamp.text = timeFormat.format(Date(entry.timestamp))
            tvTag.text = "[${entry.tag}]"
            tvMessage.text = entry.message

            // Level stripe — resolve M3 theme attrs at bind time so the tint
            // flips with the active light/dark theme. Falls back to the
            // legacy palette when an attr can't be resolved. (colorPrimary /
            // colorError live in appcompat's R.attr; colorTertiary /
            // colorOutline live in material's R.attr — non-transitive R is
            // enabled, so we have to qualify each one.)
            val ctx = itemView.context
            val attr = when (entry.level) {
                LogLevel.DEBUG -> com.google.android.material.R.attr.colorOutline
                LogLevel.INFO  -> androidx.appcompat.R.attr.colorPrimary
                LogLevel.WARN  -> com.google.android.material.R.attr.colorTertiary
                LogLevel.ERROR -> androidx.appcompat.R.attr.colorError
            }
            val fallback = when (entry.level) {
                LogLevel.DEBUG -> ctx.getColor(R.color.text_secondary)
                LogLevel.INFO  -> ctx.getColor(R.color.status_running)
                LogLevel.WARN  -> ctx.getColor(R.color.accent_orange)
                LogLevel.ERROR -> ctx.getColor(R.color.status_error)
            }
            levelIndicator.setBackgroundColor(
                MaterialColors.getColor(itemView, attr, fallback)
            )
        }
    }
    
    private class LogDiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.tag == newItem.tag
        }
        
        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem == newItem
        }
    }
}
