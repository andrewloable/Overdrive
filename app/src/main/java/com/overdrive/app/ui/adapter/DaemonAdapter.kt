package com.overdrive.app.ui.adapter

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.overdrive.app.ui.model.DaemonState
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.google.android.material.switchmaterial.SwitchMaterial
import com.overdrive.app.R

/**
 * Adapter for displaying daemon cards in a RecyclerView.
 */
class DaemonAdapter(
    private val onToggle: (DaemonType, Boolean) -> Unit,
    private val onConfigureClick: ((DaemonType) -> Unit)? = null,
    private val onDownloadLog: ((DaemonType) -> Unit)? = null
) : ListAdapter<DaemonState, DaemonAdapter.DaemonViewHolder>(DaemonDiffCallback()) {
    
    // Track expanded states
    private val expandedStates = mutableMapOf<DaemonType, Boolean>()
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DaemonViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_daemon_card, parent, false)
        return DaemonViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: DaemonViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class DaemonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
        private val ivDaemonIcon: ImageView = itemView.findViewById(R.id.ivDaemonIcon)
        private val tvDaemonName: TextView = itemView.findViewById(R.id.tvDaemonName)
        private val tvDaemonStatus: TextView = itemView.findViewById(R.id.tvDaemonStatus)
        private val switchDaemon: SwitchMaterial = itemView.findViewById(R.id.switchDaemon)
        private val ivExpand: ImageView = itemView.findViewById(R.id.ivExpand)
        private val ivConfigure: ImageView = itemView.findViewById(R.id.ivConfigure)
        private val ivDownloadLog: ImageView = itemView.findViewById(R.id.ivDownloadLog)
        private val mainRow: View = itemView.findViewById(R.id.mainRow)
        private val subprocessContainer: View = itemView.findViewById(R.id.subprocessContainer)
        private val subprocessList: LinearLayout = itemView.findViewById(R.id.subprocessList)

        fun bind(state: DaemonState) {
            tvDaemonName.text = getDaemonDisplayName(state.type)
            ivDaemonIcon.setImageResource(getDaemonIcon(state.type))
            
            // Build status text with uptime
            val statusText = when {
                state.needsConfiguration -> state.configurationMessage ?: "Configuration required"
                state.status == DaemonStatus.RUNNING -> {
                    val uptimeStr = state.uptime
                    if (!uptimeStr.isNullOrEmpty()) {
                        "Running • Uptime: $uptimeStr"
                    } else {
                        "Running"
                    }
                }
                state.status == DaemonStatus.STOPPED -> "Stopped"
                state.status == DaemonStatus.STARTING -> state.statusText.ifEmpty { "Starting..." }
                state.status == DaemonStatus.STOPPING -> state.statusText.ifEmpty { "Stopping..." }
                state.status == DaemonStatus.ERROR -> state.statusText.ifEmpty { "Error" }
                else -> state.statusText
            }
            tvDaemonStatus.text = statusText
            
            // Set status indicator color
            val colorRes = when {
                state.needsConfiguration -> R.color.status_starting // Yellow/amber for needs config
                state.status == DaemonStatus.RUNNING -> R.color.status_running
                state.status == DaemonStatus.STOPPED -> R.color.status_stopped
                state.status == DaemonStatus.ERROR -> R.color.status_error
                else -> R.color.status_starting
            }
            val color = ContextCompat.getColor(itemView.context, colorRes)
            (statusIndicator.background as? GradientDrawable)?.setColor(color)

            // Status-text color: live status colors when meaningful, otherwise
            // fall back to the M3 onSurfaceVariant baseline used by the layout.
            val textColorRes = when {
                state.needsConfiguration -> R.color.status_starting
                state.status == DaemonStatus.RUNNING -> R.color.status_running
                state.status == DaemonStatus.ERROR -> R.color.status_error
                state.status == DaemonStatus.STARTING || state.status == DaemonStatus.STOPPING -> R.color.status_starting
                else -> null
            }
            if (textColorRes != null) {
                tvDaemonStatus.setTextColor(ContextCompat.getColor(itemView.context, textColorRes))
            } else {
                // Resolve ?attr/colorOnSurfaceVariant from theme so we never hard-code hex.
                val ta = itemView.context.obtainStyledAttributes(
                    intArrayOf(com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
                val themed = ta.getColor(0, 0)
                ta.recycle()
                tvDaemonStatus.setTextColor(themed)
            }
            
            // Set switch state without triggering listener
            switchDaemon.setOnCheckedChangeListener(null)
            switchDaemon.isChecked = state.status == DaemonStatus.RUNNING
            switchDaemon.isEnabled = !state.needsConfiguration && 
                                     state.status != DaemonStatus.STARTING && 
                                     state.status != DaemonStatus.STOPPING
            
            // Set listener
            switchDaemon.setOnCheckedChangeListener { _, isChecked ->
                onToggle(state.type, isChecked)
            }
            
            // Handle subprocess expansion
            val hasSubprocesses = state.subprocesses.isNotEmpty()
            ivExpand.visibility = if (hasSubprocesses) View.VISIBLE else View.GONE
            
            val isExpanded = expandedStates[state.type] == true
            subprocessContainer.visibility = if (isExpanded && hasSubprocesses) View.VISIBLE else View.GONE
            ivExpand.rotation = if (isExpanded) 180f else 0f
            
            // Show configure icon for configurable daemons (always visible for Zrok and Tailscale)
            val isConfigurable = (state.type == DaemonType.ZROK_TUNNEL || state.type == DaemonType.TAILSCALE_TUNNEL) && onConfigureClick != null
            ivConfigure.visibility = if (isConfigurable) View.VISIBLE else View.GONE
            if (isConfigurable) {
                ivConfigure.setOnClickListener {
                    onConfigureClick?.invoke(state.type)
                }
            }
            
            // Show download log button (only if callback provided — debug builds only)
            val hasLog = onDownloadLog != null && hasLogFile(state.type)
            ivDownloadLog.visibility = if (hasLog) View.VISIBLE else View.GONE
            if (hasLog) {
                ivDownloadLog.setOnClickListener {
                    onDownloadLog?.invoke(state.type)
                }
            }
            
            // Handle configuration click for daemons that need setup
            if (state.needsConfiguration && onConfigureClick != null) {
                itemView.setOnClickListener {
                    onConfigureClick.invoke(state.type)
                }
                mainRow.setOnClickListener {
                    onConfigureClick.invoke(state.type)
                }
                // Make status text look clickable
                tvDaemonStatus.text = itemView.context.getString(
                    R.string.daemon_configuration_message,
                    state.configurationMessage ?: itemView.context.getString(R.string.daemon_configuration_required)
                )
            } else if (hasSubprocesses) {
                // Click to expand/collapse subprocesses
                mainRow.setOnClickListener {
                    expandedStates[state.type] = !(expandedStates[state.type] ?: false)
                    notifyItemChanged(bindingAdapterPosition)
                }
                itemView.setOnClickListener(null)
            } else {
                mainRow.setOnClickListener(null)
                itemView.setOnClickListener(null)
            }
            
            // Populate subprocess list
            if (hasSubprocesses) {
                subprocessList.removeAllViews()
                // Resolve M3 themed colors once so we don't hard-code hex.
                val themeAttrs = itemView.context.obtainStyledAttributes(
                    intArrayOf(
                        com.google.android.material.R.attr.colorOnSurface,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                val onSurface = themeAttrs.getColor(0, 0)
                val onSurfaceVariant = themeAttrs.getColor(1, 0)
                themeAttrs.recycle()

                state.subprocesses.forEach { subprocess ->
                    val subView = LayoutInflater.from(itemView.context)
                        .inflate(android.R.layout.simple_list_item_2, subprocessList, false)

                    subView.findViewById<TextView>(android.R.id.text1).apply {
                        text = "${subprocess.name} (PID: ${subprocess.pid})"
                        setTextColor(onSurface)
                        textSize = 13f
                    }

                    subView.findViewById<TextView>(android.R.id.text2).apply {
                        text = "Uptime: ${subprocess.uptime}"
                        setTextColor(onSurfaceVariant)
                        textSize = 11f
                    }
                    subprocessList.addView(subView)
                }
            }
        }
        
        private fun getDaemonDisplayName(type: DaemonType): String {
            return when (type) {
                DaemonType.CAMERA_DAEMON -> "Camera Daemon"
                DaemonType.SENTRY_DAEMON -> "Surveillance Daemon"
                DaemonType.ACC_SENTRY_DAEMON -> "ACC Surveillance"
                DaemonType.SINGBOX_PROXY -> "Sing-box Proxy"
                DaemonType.CLOUDFLARED_TUNNEL -> "Cloudflared Tunnel"
                DaemonType.ZROK_TUNNEL -> "Zrok Tunnel"
                DaemonType.TAILSCALE_TUNNEL -> "Tailscale Tunnel"
                DaemonType.TELEGRAM_DAEMON -> "Telegram Bot"
            }
        }

        @androidx.annotation.DrawableRes
        private fun getDaemonIcon(type: DaemonType): Int = when (type) {
            DaemonType.CAMERA_DAEMON -> R.drawable.ic_camera_select
            DaemonType.SENTRY_DAEMON -> R.drawable.ic_sentry
            DaemonType.ACC_SENTRY_DAEMON -> R.drawable.ic_directions_car
            DaemonType.SINGBOX_PROXY -> R.drawable.ic_vpn_lock
            DaemonType.CLOUDFLARED_TUNNEL -> R.drawable.ic_cloud
            DaemonType.ZROK_TUNNEL -> R.drawable.ic_link
            DaemonType.TAILSCALE_TUNNEL -> R.drawable.ic_mqtt
            DaemonType.TELEGRAM_DAEMON -> R.drawable.ic_telegram
        }
        
        private fun hasLogFile(type: DaemonType): Boolean {
            return getLogFilePath(type) != null
        }
    }
    
    companion object {
        /**
         * Map daemon types to their log file paths.
         * Returns null for daemons without a known log file.
         */
        fun getLogFilePath(type: DaemonType): String? {
            return when (type) {
                DaemonType.CAMERA_DAEMON -> "/data/local/tmp/cam_daemon.log"
                DaemonType.SENTRY_DAEMON -> "/data/local/tmp/sentry_daemon.log"
                DaemonType.ACC_SENTRY_DAEMON -> "/data/local/tmp/acc_sentry_daemon.log"
                DaemonType.CLOUDFLARED_TUNNEL -> "/data/local/tmp/cloudflared.log"
                DaemonType.ZROK_TUNNEL -> "/data/local/tmp/zrok.log"
                DaemonType.TAILSCALE_TUNNEL -> "/data/local/tmp/.tailscale/tailscale.log"
                DaemonType.SINGBOX_PROXY -> "/data/local/tmp/singbox.log"
                DaemonType.TELEGRAM_DAEMON -> "/data/local/tmp/telegrambotdaemon.log"
            }
        }
    }
    
    private class DaemonDiffCallback : DiffUtil.ItemCallback<DaemonState>() {
        override fun areItemsTheSame(oldItem: DaemonState, newItem: DaemonState): Boolean {
            return oldItem.type == newItem.type
        }
        
        override fun areContentsTheSame(oldItem: DaemonState, newItem: DaemonState): Boolean {
            return oldItem == newItem
        }
    }
}
