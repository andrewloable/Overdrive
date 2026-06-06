package net.bladewatch.app.ui.fragment

import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.bladewatch.app.ui.adapter.DaemonAdapter
import net.bladewatch.app.ui.viewmodel.DaemonsViewModel
import net.bladewatch.app.ui.model.DaemonType
import net.bladewatch.app.ui.model.DaemonStatus
import net.bladewatch.app.R

/**
 * Fragment for managing background daemons.
 */
class DaemonsFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())

    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private lateinit var recyclerDaemons: RecyclerView
    private lateinit var tvDaemonsCount: TextView
    private lateinit var daemonAdapter: DaemonAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_daemons, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initViews(view)
        setupRecyclerView()
        observeViewModel()
        
        // Check Zrok token status on view creation
        checkZrokTokenStatus()
    }
    
    private fun initViews(view: View) {
        recyclerDaemons = view.findViewById(R.id.recyclerDaemons)
        tvDaemonsCount = view.findViewById(R.id.tvDaemonsCount)
    }
    
    private fun setupRecyclerView() {
        daemonAdapter = DaemonAdapter(
            onToggle = { type, enabled -> onDaemonToggled(type, enabled) },
            onConfigureClick = { type -> onDaemonConfigureClicked(type) },
            onDownloadLog = if (net.bladewatch.app.BuildConfig.DEBUG) {
                { type -> onDownloadLogClicked(type) }
            } else null
        )
        
        recyclerDaemons.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = daemonAdapter
        }
    }
    
    private fun observeViewModel() {
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            // Convert map to list sorted by daemon type ordinal
            val sortedList = states.values.sortedBy { it.type.ordinal }
            daemonAdapter.submitList(sortedList)

            // Update hero header live count: "X of Y running"
            val total = sortedList.size
            val running = sortedList.count { it.status == DaemonStatus.RUNNING }
            tvDaemonsCount.text = getString(R.string.daemons_count_fmt, running, total)
        }
    }
    
    /**
     * Check if Zrok token is configured and update state accordingly.
     */
    private fun checkZrokTokenStatus() {
        daemonsViewModel.zrokController.hasEnableToken { hasToken ->
            activity?.runOnUiThread {
                if (!hasToken) {
                    // Update Zrok state to show configuration needed
                    daemonsViewModel.updateZrokNeedsConfig("No token configured. Tap to set up.")
                }
            }
        }
    }
    
    private fun onDaemonToggled(type: DaemonType, enabled: Boolean) {
        // Save preference for optional daemons (so they auto-start on next app launch if enabled)
        daemonsViewModel.daemonStartupManager?.onDaemonToggled(type, enabled)
        
        if (enabled) {
            daemonsViewModel.startDaemon(type)
        } else {
            daemonsViewModel.stopDaemon(type)
        }
    }
    
    private fun onDaemonConfigureClicked(type: DaemonType) {
        when (type) {
            DaemonType.ZROK_TUNNEL -> showZrokTokenDialog()
            else -> {
                // Other daemons don't need configuration yet
                Toast.makeText(context, getString(R.string.toast_no_config_needed, type.displayName), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Show dialog to configure Zrok enable token.
     */
    private fun showZrokTokenDialog() {
        val context = context ?: return
        
        // First get current token to show in dialog
        daemonsViewModel.zrokController.getEnableToken { currentToken ->
            activity?.runOnUiThread {
                val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_zrok_token, null)
                val editToken = dialogView.findViewById<EditText>(R.id.editZrokToken)
                
                // Pre-fill with current token if exists
                currentToken?.let { editToken.setText(it) }
                
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_BladeWatch_M3_Dialog)
                    .setIcon(R.drawable.ic_link)
                    .setTitle(getString(R.string.dialog_zrok_token_title))
                    .setMessage(getString(R.string.dialog_zrok_token_message))
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.dialog_save)) { _, _ ->
                        val token = editToken.text.toString().trim()
                        if (token.isNotEmpty()) {
                            saveZrokToken(token)
                        } else {
                            Toast.makeText(context, getString(R.string.toast_token_cannot_be_empty), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .setNeutralButton(getString(R.string.dialog_delete)) { _, _ ->
                        deleteZrokToken()
                    }
                    .create()
                
                // Wire up the Reset Environment button
                dialogView.findViewById<View>(R.id.btnResetZrokEnvironment)?.setOnClickListener {
                    dialog.dismiss()
                    confirmResetZrokEnvironment()
                }
                
                dialog.show()
            }
        }
    }

    /**
     * Show confirmation dialog before resetting zrok environment.
     */
    private fun confirmResetZrokEnvironment() {
        val context = context ?: return
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context, R.style.Theme_BladeWatch_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_zrok_reset_title))
            .setMessage(getString(R.string.dialog_zrok_reset_message))
            .setPositiveButton(getString(R.string.dialog_reset)) { _, _ ->
                resetZrokEnvironment()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }
    
    /**
     * Reset zrok environment: stop tunnel, disable environment, delete token.
     */
    private fun resetZrokEnvironment() {
        val context = context ?: return
        Toast.makeText(context, getString(R.string.toast_resetting_zrok), Toast.LENGTH_SHORT).show()

        // First stop the tunnel if running
        daemonsViewModel.stopDaemon(DaemonType.ZROK_TUNNEL)

        // Then disable the environment (removes environment.json and reserved tokens)
        daemonsViewModel.zrokController.disableEnvironment(object : net.bladewatch.app.ui.daemon.DaemonCallback {
            override fun onStatusChanged(status: net.bladewatch.app.ui.model.DaemonStatus, message: String) {
                // Environment disabled, now delete the enable token
                daemonsViewModel.zrokController.deleteEnableToken { success ->
                    activity?.runOnUiThread {
                        if (success) {
                            Toast.makeText(context, getString(R.string.toast_zrok_reset_success), Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, getString(R.string.toast_zrok_reset_partial), Toast.LENGTH_LONG).show()
                        }
                        daemonsViewModel.updateZrokNeedsConfig(getString(R.string.zrok_no_token_configured))
                    }
                }
            }

            override fun onError(error: String) {
                // Even if disable fails, still try to delete the token
                daemonsViewModel.zrokController.deleteEnableToken { _ ->
                    activity?.runOnUiThread {
                        Toast.makeText(context, getString(R.string.toast_zrok_reset_warnings, error), Toast.LENGTH_LONG).show()
                        daemonsViewModel.updateZrokNeedsConfig(getString(R.string.zrok_no_token_configured))
                    }
                }
            }
        })
    }

    private fun saveZrokToken(token: String) {
        daemonsViewModel.zrokController.saveEnableToken(token) { success ->
            activity?.runOnUiThread {
                if (success) {
                    Toast.makeText(context, getString(R.string.toast_zrok_token_saved), Toast.LENGTH_SHORT).show()
                    // Refresh Zrok status
                    daemonsViewModel.refreshDaemonStatus(DaemonType.ZROK_TUNNEL)
                } else {
                    Toast.makeText(context, getString(R.string.toast_zrok_token_save_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun deleteZrokToken() {
        daemonsViewModel.zrokController.deleteEnableToken { success ->
            activity?.runOnUiThread {
                if (success) {
                    Toast.makeText(context, getString(R.string.toast_zrok_token_deleted), Toast.LENGTH_SHORT).show()
                    // Update state to show configuration needed
                    daemonsViewModel.updateZrokNeedsConfig(getString(R.string.zrok_no_token_configured))
                } else {
                    Toast.makeText(context, getString(R.string.toast_zrok_token_delete_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // ==================== Log Download (Debug Only) ====================
    
    /**
     * Download a daemon's log file from /data/local/tmp/ and share it.
     * Uses tail to limit output size and avoid OOM on large log files.
     */
    private fun onDownloadLogClicked(type: DaemonType) {
        val logPath = DaemonAdapter.getLogFilePath(type) ?: return
        val ctx = context ?: return
        val daemonName = type.displayName.replace(" ", "_").lowercase()
        
        Toast.makeText(ctx, getString(R.string.toast_fetching_log, type.displayName), Toast.LENGTH_SHORT).show()
        
        // Use tail to limit output — 10000 lines is ~1-2MB which is safe for ADB + String
        val adb = net.bladewatch.app.launcher.AdbDaemonLauncher(ctx)
        adb.executeShellCommand(
            "wc -l < $logPath 2>/dev/null; echo '---SEPARATOR---'; tail -10000 $logPath 2>/dev/null",
            object : net.bladewatch.app.launcher.AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {
                    activity?.runOnUiThread {
                        if (message.isBlank()) {
                            Toast.makeText(ctx, getString(R.string.toast_log_empty_or_missing), Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }

                        try {
                            // Parse: first part is line count, after separator is the log content
                            val parts = message.split("---SEPARATOR---", limit = 2)
                            val totalLines = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                            val logContent = parts.getOrNull(1)?.trimStart('\n') ?: message

                            if (logContent.isBlank()) {
                                Toast.makeText(ctx, getString(R.string.toast_log_empty), Toast.LENGTH_SHORT).show()
                                return@runOnUiThread
                            }

                            // Write to a shareable file in cache dir
                            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                            val fileName = "${daemonName}_${timestamp}.log"
                            val cacheDir = java.io.File(ctx.cacheDir, "logs")
                            cacheDir.mkdirs()
                            val logFile = java.io.File(cacheDir, fileName)

                            // Add header with metadata
                            val header = buildString {
                                appendLine(getString(R.string.log_header_title, type.displayName))
                                appendLine(getString(R.string.log_header_source, logPath))
                                appendLine(getString(R.string.log_header_exported, java.util.Date().toString()))
                                if (totalLines > 10000) {
                                    appendLine(getString(R.string.log_header_truncated, totalLines))
                                }
                                appendLine("===")
                                appendLine()
                            }
                            logFile.writeText(header + logContent)

                            // Share via intent
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                ctx,
                                "${ctx.packageName}.fileprovider",
                                logFile
                            )

                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                this.type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.log_share_title, type.displayName, timestamp))
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(android.content.Intent.createChooser(shareIntent, getString(R.string.log_share_chooser, type.displayName)))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, getString(R.string.toast_log_save_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                }

                override fun onLaunched() {}

                override fun onError(error: String) {
                    activity?.runOnUiThread {
                        Toast.makeText(ctx, getString(R.string.toast_log_not_found), Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}
