package net.bladewatch.app.ui.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import net.bladewatch.app.R
import net.bladewatch.app.logging.DebugAppLogger
import net.bladewatch.app.ui.model.DaemonStatus
import net.bladewatch.app.ui.model.DaemonType
import net.bladewatch.app.ui.viewmodel.DaemonsViewModel

class StartupFragment : Fragment() {

    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())

    private val daemonStartedAtMs = mutableMapOf<DaemonType, Long>()
    private val daemonCompletedAtMs = mutableMapOf<DaemonType, Long>()
    private var fragmentCreatedAtMs = 0L
    private var navigated = false

    private lateinit var tvStartupHeader: TextView
    private lateinit var tvStartupElapsed: TextView
    private lateinit var progressStartup: LinearProgressIndicator
    private lateinit var btnContinueAnyway: MaterialButton

    private val statusDots = mutableMapOf<DaemonType, View>()
    private val statusLabels = mutableMapOf<DaemonType, TextView>()
    private val elapsedLabels = mutableMapOf<DaemonType, TextView>()

    private val coreDaemons = listOf(
        DaemonType.CAMERA_DAEMON,
        DaemonType.SENTRY_DAEMON,
        DaemonType.ACC_SENTRY_DAEMON
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_startup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fragmentCreatedAtMs = SystemClock.elapsedRealtime()
        DebugAppLogger.log("StartupFragment", "Startup screen created at elapsedRealtime=${fragmentCreatedAtMs}ms")

        tvStartupHeader = view.findViewById(R.id.tvStartupHeader)
        tvStartupElapsed = view.findViewById(R.id.tvStartupElapsed)
        progressStartup = view.findViewById(R.id.progressStartup)
        btnContinueAnyway = view.findViewById(R.id.btnContinueAnyway)

        statusDots[DaemonType.CAMERA_DAEMON] = view.findViewById(R.id.statusDotCamera)
        statusLabels[DaemonType.CAMERA_DAEMON] = view.findViewById(R.id.tvCameraStatus)
        elapsedLabels[DaemonType.CAMERA_DAEMON] = view.findViewById(R.id.tvCameraElapsed)

        statusDots[DaemonType.SENTRY_DAEMON] = view.findViewById(R.id.statusDotSentry)
        statusLabels[DaemonType.SENTRY_DAEMON] = view.findViewById(R.id.tvSentryStatus)
        elapsedLabels[DaemonType.SENTRY_DAEMON] = view.findViewById(R.id.tvSentryElapsed)

        statusDots[DaemonType.ACC_SENTRY_DAEMON] = view.findViewById(R.id.statusDotAccSentry)
        statusLabels[DaemonType.ACC_SENTRY_DAEMON] = view.findViewById(R.id.tvAccSentryStatus)
        elapsedLabels[DaemonType.ACC_SENTRY_DAEMON] = view.findViewById(R.id.tvAccSentryElapsed)

        btnContinueAnyway.setOnClickListener { navigateToDashboard() }

        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            for (type in coreDaemons) {
                val state = states[type] ?: continue
                updateDaemonRow(type, state.status)
            }
            val allRunning = coreDaemons.all { states[it]?.status == DaemonStatus.RUNNING }
            if (allRunning) onAllDaemonsReady()
        }

        handler.post(tickerRunnable)

        handler.postDelayed({
            if (!navigated) btnContinueAnyway.visibility = View.VISIBLE
        }, 120_000)
    }

    private fun updateDaemonRow(type: DaemonType, status: DaemonStatus) {
        val now = SystemClock.elapsedRealtime()

        if (status == DaemonStatus.STARTING && !daemonStartedAtMs.containsKey(type)) {
            daemonStartedAtMs[type] = now
            DebugAppLogger.log("StartupFragment", "${type.name} STARTING at elapsedRealtime=${now}ms")
        }
        if (status == DaemonStatus.RUNNING && !daemonCompletedAtMs.containsKey(type)) {
            daemonCompletedAtMs[type] = now
            val startedAt = daemonStartedAtMs[type]
            val elapsed = if (startedAt != null) now - startedAt else -1L
            DebugAppLogger.log("StartupFragment", "${type.name} RUNNING after ${elapsed}ms")
        }

        val dot = statusDots[type] ?: return
        val statusLabel = statusLabels[type] ?: return
        val elapsedLabel = elapsedLabels[type] ?: return

        val dotRes = when (status) {
            DaemonStatus.RUNNING -> R.drawable.status_dot_online
            DaemonStatus.STARTING -> R.drawable.status_dot_starting
            else -> R.drawable.status_dot_offline
        }
        dot.setBackgroundResource(dotRes)

        val statusText: String
        val statusColor: Int
        when (status) {
            DaemonStatus.RUNNING -> {
                statusText = getString(R.string.startup_status_ready)
                statusColor = requireContext().getColor(R.color.status_running)
            }
            DaemonStatus.STARTING -> {
                statusText = getString(R.string.startup_status_starting)
                statusColor = requireContext().getColor(R.color.status_starting)
            }
            DaemonStatus.ERROR -> {
                statusText = getString(R.string.startup_status_failed)
                statusColor = requireContext().getColor(R.color.status_error)
            }
            else -> {
                statusText = getString(R.string.startup_status_waiting)
                statusColor = requireContext().getColor(R.color.text_muted)
            }
        }
        statusLabel.text = statusText
        statusLabel.setTextColor(statusColor)

        when (status) {
            DaemonStatus.RUNNING -> {
                val startedAt = daemonStartedAtMs[type]
                val completedAt = daemonCompletedAtMs[type]
                if (startedAt != null && completedAt != null) {
                    val elapsedSec = (completedAt - startedAt) / 1000
                    elapsedLabel.text = "${elapsedSec}s"
                } else {
                    elapsedLabel.text = "—"
                }
            }
            DaemonStatus.STARTING -> { /* ticker updates this live */ }
            else -> elapsedLabel.text = "—"
        }
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (navigated || view == null) return

            val overallElapsed = (SystemClock.elapsedRealtime() - fragmentCreatedAtMs) / 1000
            tvStartupElapsed.text = "${overallElapsed}s"

            val states = daemonsViewModel.daemonStates.value ?: emptyMap()
            tvStartupHeader.text = when {
                coreDaemons.all { states[it]?.status == DaemonStatus.RUNNING } -> getString(R.string.startup_header_ready)
                coreDaemons.any { states[it]?.status == DaemonStatus.STARTING } -> getString(R.string.startup_header_starting)
                else -> getString(R.string.startup_header_preparing)
            }

            for (type in coreDaemons) {
                val startedAt = daemonStartedAtMs[type] ?: continue
                if (states[type]?.status == DaemonStatus.STARTING) {
                    val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
                    elapsedLabels[type]?.text = "${elapsed}s"
                }
            }

            handler.postDelayed(this, 1000)
        }
    }

    private fun onAllDaemonsReady() {
        if (navigated) return
        val elapsed = (SystemClock.elapsedRealtime() - fragmentCreatedAtMs)
        DebugAppLogger.log("StartupFragment", "All daemons ready at +${elapsed}ms — verifying HTTP health")
        tvStartupHeader.text = getString(R.string.startup_header_verifying)
        verifyDaemonHealth()
    }

    private fun verifyDaemonHealth() {
        if (navigated || !isAdded) return
        Thread {
            var serverReady = false
            try {
                val url = java.net.URL("http://127.0.0.1:8080/")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                conn.responseCode  // any response means server is up
                conn.disconnect()
                serverReady = true
            } catch (_: Exception) {
                serverReady = false
            }

            if (serverReady) {
                DebugAppLogger.log("StartupFragment", "HTTP health check passed — navigating to dashboard")
                handler.post {
                    if (!navigated && isAdded && view != null) {
                        tvStartupHeader.text = getString(R.string.startup_header_ready)
                        progressStartup.visibility = View.GONE
                        btnContinueAnyway.visibility = View.VISIBLE
                        btnContinueAnyway.text = getString(R.string.startup_continue)
                        handler.postDelayed({ navigateToDashboard() }, 1500)
                    }
                }
            } else {
                DebugAppLogger.log("StartupFragment", "HTTP health check failed — retrying in 3s", "WARN")
                handler.postDelayed({ verifyDaemonHealth() }, 3000)
            }
        }.start()
    }

    private fun navigateToDashboard() {
        if (navigated) return
        navigated = true
        try {
            findNavController().navigate(R.id.action_startupFragment_to_dashboardFragment)
        } catch (_: Exception) {
            // Fragment detached before navigation completed
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}
