package net.bladewatch.app.ui.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.connectrpc.ResponseMessage
import kotlinx.coroutines.runBlocking
import net.bladewatch.app.R
import net.bladewatch.app.auth.AuthManager
import net.bladewatch.app.client.ConnectClientProvider
import net.bladewatch.app.grpc.v1.GetModelsManifestRequest
import net.bladewatch.app.grpc.v1.GetSelectedModelRequest
import net.bladewatch.app.grpc.v1.GetSohNominalRequest
import net.bladewatch.app.grpc.v1.GetSohStatusRequest
import net.bladewatch.app.grpc.v1.InvalidateAuthCacheRequest
import net.bladewatch.app.grpc.v1.ListTripsRequest
import net.bladewatch.app.grpc.v1.SetSelectedModelRequest
import net.bladewatch.app.grpc.v1.SetSohNominalRequest
import net.bladewatch.app.ui.model.DaemonStatus
import net.bladewatch.app.ui.model.DaemonType
import net.bladewatch.app.ui.util.QrCodeGenerator
import net.bladewatch.app.ui.util.RecordingScanner
import net.bladewatch.app.ui.viewmodel.DaemonsViewModel
import net.bladewatch.app.ui.viewmodel.MainViewModel
import net.bladewatch.app.ui.viewmodel.RecordingViewModel
import net.bladewatch.app.util.DeviceIdGenerator
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * SOTA Dashboard.
 *
 * Layout (top to bottom):
 *   - Hero card: time-of-day greeting + system summary subtitle.
 *   - Metric grid: Recordings · Storage · Remote · Services.
 *   - Connect card: QR + tunnel chips + access code.
 *   - Quick actions row: Live · Recordings · Settings.
 */
class DashboardFragment : Fragment() {

    private val mainViewModel: MainViewModel by activityViewModels()
    private val daemonsViewModel: DaemonsViewModel by activityViewModels()
    private val recordingViewModel: RecordingViewModel by activityViewModels()

    // Trip stats hero — top of dashboard
    private lateinit var tripStatsCard: MaterialCardView
    private lateinit var tvTripHeadline: TextView
    private lateinit var tvStatTrips: TextView
    private lateinit var tvStatDistance: TextView
    private lateinit var tvStatDriveTime: TextView
    private var btnViewAllTrips: com.google.android.material.button.MaterialButton? = null

    // Status chips (compact row below trip stats)
    private var heroChipTunnel: com.google.android.material.chip.Chip? = null
    private var heroChipServices: com.google.android.material.chip.Chip? = null
    private var heroChipRecording: com.google.android.material.chip.Chip? = null

    // Metric tiles
    private lateinit var metricRecordings: MaterialCardView
    private lateinit var metricRecordingsValue: TextView
    // Storage line lives inside the combined Recordings tile now; no
    // separate clickable card. The text view is still bound so the
    // background metrics walker can populate "8.4 GB used · 12 GB free".
    private lateinit var metricStorageValue: TextView
    private lateinit var metricTunnel: MaterialCardView
    private lateinit var metricTunnelValue: TextView
    private lateinit var tunnelStateDot: View
    private lateinit var cardDaemons: MaterialCardView
    private lateinit var tvDaemonsStatus: TextView

    // Connect card
    private lateinit var ivQrCode: ImageView
    private lateinit var qrCodeContainer: View
    private lateinit var tvQrPlaceholder: TextView
    private lateinit var tvUrl: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var chipGroupTunnels: ChipGroup
    private var selectedTunnel: DaemonType? = null

    // Auth
    private lateinit var tvDeviceToken: TextView
    private lateinit var btnToggleToken: ImageView
    private lateinit var btnCopyToken: ImageView
    private lateinit var btnRegenerateToken: MaterialButton
    private lateinit var btnSetPassword: MaterialButton
    private var isTokenVisible = false

    // Quick-action tiles (cards now, not buttons — they share dimensions
    // with the metric tiles so the dashboard reads as one rhythm). The
    // Settings tile was removed in the wide-Vehicle layout — Settings is
    // still reachable from the navigation rail.
    private lateinit var quickLive: MaterialCardView

    // Vehicle tile — battery capacity + model summary. Tap opens a dialog.
    private var metricVehicle: MaterialCardView? = null
    private var metricVehicleValue: TextView? = null

    // Background work for storage / recording-count tiles. Single thread is enough
    // — both probes are just a directory walk, and serializing them keeps disk I/O
    // out of the UI thread without contending with itself.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var metricsExecutor: ExecutorService? = null

    // Latest values cached so the recording-state observer (which fires on its
    // own cadence) can re-render without re-walking the disk.
    @Volatile private var todayClipCount: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        wireClicks()
        observeViewModels()

        tvDeviceId.text = DeviceIdGenerator.generateDeviceId(requireContext())
        loadAuthState()

        refreshMetricsTiles()
        refreshVehicleTile()
        refreshTripStats()
    }

    override fun onResume() {
        super.onResume()
        // Cheap-but-not-free disk walk: refresh on every resume so the storage
        // and today's-clip-count numbers update after the user records, deletes,
        // or sentry events fire while the dashboard wasn't on screen.
        //
        // Drop the scanner cache first — deletions in other fragments don't
        // notify the dashboard, so without this the tile and the carousel
        // could read up to 5s of stale data after the user navigates back
        // from the recordings page.
        RecordingScanner.invalidateCache()
        refreshMetricsTiles()
        refreshVehicleTile()
        refreshTripStats()
        // Re-read the access code on every resume. On a fresh install the
        // app process initializes AuthManager with an in-memory secret
        // before the daemon writes the canonical one to /data/local/tmp/;
        // a resume-time refresh means navigating away and back is enough
        // to pick up the daemon's secret if the bootstrap reconcile
        // (1s cadence in AuthManager.getState()) hasn't fired yet.
        loadAuthState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        metricsExecutor?.shutdownNow()
        metricsExecutor = null
    }

    private fun bindViews(view: View) {
        tripStatsCard = view.findViewById(R.id.tripStatsCard)
        tvTripHeadline = view.findViewById(R.id.tvTripHeadline)
        tvStatTrips = view.findViewById(R.id.tvStatTrips)
        tvStatDistance = view.findViewById(R.id.tvStatDistance)
        tvStatDriveTime = view.findViewById(R.id.tvStatDriveTime)
        btnViewAllTrips = view.findViewById(R.id.btnViewAllTrips)

        heroChipTunnel = view.findViewById(R.id.heroChipTunnel)
        heroChipServices = view.findViewById(R.id.heroChipServices)
        heroChipRecording = view.findViewById(R.id.heroChipRecording)

        metricRecordings = view.findViewById(R.id.metricRecordings)
        metricRecordingsValue = view.findViewById(R.id.metricRecordingsValue)
        metricStorageValue = view.findViewById(R.id.metricStorageValue)
        metricTunnel = view.findViewById(R.id.metricTunnel)
        metricTunnelValue = view.findViewById(R.id.metricTunnelValue)
        tunnelStateDot = view.findViewById(R.id.tunnelStateDot)
        cardDaemons = view.findViewById(R.id.cardDaemons)
        tvDaemonsStatus = view.findViewById(R.id.tvDaemonsStatus)

        ivQrCode = view.findViewById(R.id.ivQrCode)
        qrCodeContainer = view.findViewById(R.id.qrCodeContainer)
        tvQrPlaceholder = view.findViewById(R.id.tvQrPlaceholder)
        tvUrl = view.findViewById(R.id.tvUrl)
        tvDeviceId = view.findViewById(R.id.tvDeviceId)
        chipGroupTunnels = view.findViewById(R.id.chipGroupTunnels)

        tvDeviceToken = view.findViewById(R.id.tvDeviceToken)
        btnToggleToken = view.findViewById(R.id.btnToggleToken)
        btnCopyToken = view.findViewById(R.id.btnCopyToken)
        btnRegenerateToken = view.findViewById(R.id.btnRegenerateToken)
        btnSetPassword = view.findViewById(R.id.btnSetPassword)

        quickLive = view.findViewById(R.id.quickLive)

        // Vehicle tile present in both portrait and landscape layouts.
        metricVehicle = view.findViewById(R.id.metricVehicle)
        metricVehicleValue = view.findViewById(R.id.metricVehicleValue)
    }

    private fun wireClicks() {
        val fadeThrough = net.bladewatch.app.ui.util.NavOptionsExt.m3FadeThrough()
        btnViewAllTrips?.setOnClickListener {
            findNavController().navigate(R.id.tripsFragment, null, fadeThrough)
        }
        metricRecordings.setOnClickListener {
            findNavController().navigate(R.id.recordingsFragment, null, fadeThrough)
        }
        metricTunnel.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, fadeThrough)
        }
        cardDaemons.setOnClickListener {
            findNavController().navigate(R.id.daemonsFragment, null, fadeThrough)
        }
        quickLive.setOnClickListener {
            findNavController().navigate(R.id.liveViewFragment, null, fadeThrough)
        }
        metricVehicle?.setOnClickListener { showVehicleCapacityDialog() }

        btnToggleToken.setOnClickListener { toggleTokenVisibility() }
        btnCopyToken.setOnClickListener { copyTokenToClipboard() }
        btnRegenerateToken.setOnClickListener { showRegenerateConfirmation() }
        btnSetPassword.setOnClickListener { showSetPasswordDialog() }
    }

    private fun observeViewModels() {
        // Daemon health drives the hero subtitle, the services tile, and chip rebuild.
        daemonsViewModel.daemonStates.observe(viewLifecycleOwner) { states ->
            val running = states.values.count { it.status == DaemonStatus.RUNNING }
            val total = states.size
            tvDaemonsStatus.text = getString(R.string.dashboard_daemons_running, running, total)
            rebuildTunnelChips()
            refreshHeroChips()
        }

        // Tunnel URL → tile state + chip refresh.
        val rebuild = Observer<String?> { _ ->
            rebuildTunnelChips()
            updateTunnelTile()
            refreshHeroChips()
        }
        daemonsViewModel.zrokController.tunnelUrl.observe(viewLifecycleOwner, rebuild)

        // Recording state → live "● <count>" prefix on the recordings tile.
        // The numeric count itself comes from refreshMetricsTiles() below; this
        // observer just toggles the red-dot prefix without re-walking the disk.
        recordingViewModel.isRecording.observe(viewLifecycleOwner) { _ ->
            renderRecordingsValue()
            refreshHeroChips()
        }
    }

    /**
     * Push the latest tunnel / services / recording status into the three
     * hero chips. Each chip just mirrors the corresponding metric tile
     * value, but at the top of the hero they're discoverable at-a-glance
     * without needing to scan the 5-tile grid.
     *
     * Lazy-tolerates absent chips (landscape variant has no hero chips).
     */
    private fun refreshHeroChips() {
        // Only show the tunnel chip when the tunnel is actually Online.
        // Showing "Connecting..." or "Offline" adds noise without actionable info.
        val tunnelOnline = !daemonsViewModel.zrokController.tunnelUrl.value.isNullOrEmpty()
        heroChipTunnel?.let { chip ->
            if (tunnelOnline) {
                chip.text = getString(R.string.dashboard_tunnel_online)
                chip.visibility = View.VISIBLE
            } else {
                chip.visibility = View.GONE
            }
        }
        heroChipServices?.text = tvDaemonsStatus.text
        val recording = recordingViewModel.isRecording.value == true
        heroChipRecording?.text = if (recording) {
            getString(R.string.dashboard_chip_recording_active)
        } else {
            getString(R.string.dashboard_chip_recording_idle)
        }
    }

    // ============== Metric tiles (storage + today's recordings) ==============

    /**
     * Walks the recordings directories on a background thread via the
     * shared [RecordingScanner], then posts today's clip count back.
     *
     * Uses the same source-of-truth as [RecordingsFragment] so the dashboard
     * tile and the recordings page can never disagree. The scanner already
     * walks active + alternate + legacy paths for cam_* / surveillance /
     * proximity, dedupes by filename, and exposes parsed-from-filename
     * timestamps — counting via mtime here would have missed every clip
     * whose file was copied after capture (different mtime than filename).
     */
    private fun refreshMetricsTiles() {
        val ctx = context?.applicationContext ?: return
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }

        executor.execute {
            var clipCountToday = 0
            try {
                val startOfDayMs = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                clipCountToday = RecordingScanner.scanRecordings(ctx)
                    .count { it.timestamp >= startOfDayMs }
            } catch (_: Throwable) {
                // Leave defaults — still post so the tile updates off "—".
            }

            mainHandler.post {
                if (!isAdded || view == null) return@post
                // Tile is a single-line label so it never ellipsizes on
                // longer locales (fr/de). Storage figure used to be appended
                // here ("Today's recordings · 8.4 GB"); we dropped it because
                // localized strings made the line overflow on the BYD tile
                // width. Storage lives in its own surfaces.
                metricStorageValue.text = getString(R.string.dashboard_metric_recordings)

                todayClipCount = clipCountToday
                renderRecordingsValue()
            }
        }
    }

    /**
     * Renders the recordings tile value from the cached clip count + the
     * current recording state. Pulled into a helper so both the metric
     * refresh and the isRecording observer can call it without duplicating
     * the format logic.
     */
    private fun renderRecordingsValue() {
        if (!::metricRecordingsValue.isInitialized) return
        val isRec = recordingViewModel.isRecording.value == true
        metricRecordingsValue.text = if (isRec) {
            getString(R.string.dashboard_recordings_value_live, todayClipCount)
        } else {
            todayClipCount.toString()
        }
    }

    private fun updateTunnelTile() {
        val anyUrl = !daemonsViewModel.zrokController.tunnelUrl.value.isNullOrEmpty()

        val states = daemonsViewModel.daemonStates.value
        val anyStarting = states?.values?.any {
            it.type == DaemonType.ZROK_TUNNEL &&
                it.status == DaemonStatus.STARTING
        } == true

        when {
            anyUrl -> {
                metricTunnelValue.text = getString(R.string.dashboard_tunnel_online)
                tunnelStateDot.setBackgroundResource(R.drawable.status_dot_online)
            }
            anyStarting -> {
                metricTunnelValue.text = getString(R.string.dashboard_tunnel_connecting)
                tunnelStateDot.setBackgroundResource(R.drawable.status_dot_offline)
            }
            else -> {
                metricTunnelValue.text = getString(R.string.dashboard_tunnel_offline)
                tunnelStateDot.setBackgroundResource(R.drawable.status_dot_offline)
            }
        }
    }

    // ============== Tunnel chips + QR ==============

    private fun rebuildTunnelChips() {
        val available = collectAvailableTunnels()

        if (available.isEmpty()) {
            chipGroupTunnels.removeAllViews()
            chipGroupTunnels.visibility = View.GONE
            selectedTunnel = null
            renderQr(null)
            return
        }

        val newSelection = selectedTunnel?.takeIf { prev -> available.any { it.first == prev } }
            ?: available.first().first
        selectedTunnel = newSelection

        val currentTags = (0 until chipGroupTunnels.childCount)
            .map { (chipGroupTunnels.getChildAt(it) as Chip).tag as DaemonType }
        val newTags = available.map { it.first }
        if (currentTags != newTags) {
            chipGroupTunnels.setOnCheckedStateChangeListener(null)
            chipGroupTunnels.removeAllViews()
            available.forEach { (type, _) ->
                val chip = Chip(requireContext()).apply {
                    id = View.generateViewId()
                    tag = type
                    text = labelFor(type)
                    isCheckable = true
                    isCheckedIconVisible = false
                }
                chipGroupTunnels.addView(chip)
            }
            chipGroupTunnels.setOnCheckedStateChangeListener { group, ids ->
                val checkedId = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
                val chip = group.findViewById<Chip>(checkedId) ?: return@setOnCheckedStateChangeListener
                val type = chip.tag as? DaemonType ?: return@setOnCheckedStateChangeListener
                if (type != selectedTunnel) {
                    selectedTunnel = type
                    renderQr(urlFor(type))
                }
            }
        }

        for (i in 0 until chipGroupTunnels.childCount) {
            val chip = chipGroupTunnels.getChildAt(i) as Chip
            chip.isChecked = (chip.tag as DaemonType) == newSelection
        }

        chipGroupTunnels.visibility = if (available.size > 1) View.VISIBLE else View.GONE
        renderQr(urlFor(newSelection))
    }

    private fun collectAvailableTunnels(): List<Pair<DaemonType, String>> {
        val list = mutableListOf<Pair<DaemonType, String>>()
        daemonsViewModel.zrokController.tunnelUrl.value
            ?.takeIf { it.isNotEmpty() }
            ?.let { list.add(DaemonType.ZROK_TUNNEL to it) }
        return list
    }

    private fun urlFor(type: DaemonType): String? = when (type) {
        DaemonType.ZROK_TUNNEL -> daemonsViewModel.zrokController.tunnelUrl.value
        else -> null
    }

    private fun labelFor(type: DaemonType): String = when (type) {
        DaemonType.ZROK_TUNNEL -> getString(R.string.tunnel_label_zrok)
        else -> type.displayName
    }

    private fun renderQr(url: String?) {
        if (url.isNullOrEmpty()) {
            showPlaceholder()
            return
        }
        try {
            val qrBitmap = QrCodeGenerator.generate(url, 400)
            if (qrBitmap != null) {
                qrCodeContainer.visibility = View.VISIBLE
                ivQrCode.setImageBitmap(qrBitmap)
                ivQrCode.visibility = View.VISIBLE
                tvQrPlaceholder.visibility = View.GONE
                tvUrl.text = url
                tvUrl.visibility = View.VISIBLE
            } else {
                showPlaceholder()
            }
        } catch (e: Exception) {
            showPlaceholder()
        }
    }

    private fun showPlaceholder() {
        val states = daemonsViewModel.daemonStates.value
        val zrokState = states?.get(DaemonType.ZROK_TUNNEL)
        val tunnelActive = zrokState?.status == DaemonStatus.STARTING ||
                zrokState?.status == DaemonStatus.RUNNING
        if (tunnelActive) {
            // Tunnel is starting/waiting — show the QR frame with placeholder text.
            qrCodeContainer.visibility = View.VISIBLE
            ivQrCode.setImageDrawable(null)
            ivQrCode.visibility = View.VISIBLE
            tvQrPlaceholder.visibility = View.VISIBLE
            tvQrPlaceholder.text = if (zrokState?.status == DaemonStatus.STARTING)
                getString(R.string.dashboard_starting_zrok) else getString(R.string.dashboard_waiting_url)
        } else {
            // No tunnel running — hide the QR box entirely and show status text.
            qrCodeContainer.visibility = View.GONE
            ivQrCode.setImageDrawable(null)
            ivQrCode.visibility = View.GONE
            tvQrPlaceholder.visibility = View.VISIBLE
            tvQrPlaceholder.text = getString(R.string.dashboard_no_tunnel)
        }
        tvUrl.visibility = View.GONE
    }

    private fun getTunnelPlaceholderText(): String {
        val states = daemonsViewModel.daemonStates.value ?: return getString(R.string.dashboard_no_tunnel)
        val zrokState = states[DaemonType.ZROK_TUNNEL]
        return when {
            zrokState?.status == DaemonStatus.STARTING -> getString(R.string.dashboard_starting_zrok)
            zrokState?.status == DaemonStatus.RUNNING -> getString(R.string.dashboard_waiting_url)
            else -> getString(R.string.dashboard_no_tunnel)
        }
    }

    // ============== Auth (access code) ==============

    private fun loadAuthState() {
        // getState()/initialize() can return null on a fresh install before the
        // daemon has populated the unified config — in that window the access
        // code genuinely doesn't exist yet. Worse, on a fresh install
        // initialize() persists the freshly-minted secret via a BLOCKING IPC to
        // the daemon (SecretConfigBridge.putString → runIpcBlocking), which can
        // park for seconds while the daemon is still booting. Doing that on the
        // main thread freezes the UI and ANRs (input-dispatch timeout at 5s), so
        // resolve auth off-thread and post the result back. Show the masked
        // placeholder immediately; the daemon writes the canonical secret within
        // ~1-2s of boot and the poll below fills the tile in.
        tvDeviceToken.text = getString(R.string.dashboard_token_masked)
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            val state = try {
                AuthManager.getState() ?: AuthManager.initialize()
            } catch (e: Exception) {
                null
            }
            mainHandler.post {
                if (!isAdded) return@post
                if (state != null) {
                    updateTokenDisplay(state.secret)
                } else {
                    scheduleAuthRetry(attempt = 1)
                }
            }
        }
    }

    private fun scheduleAuthRetry(attempt: Int) {
        // Cap the retry storm at ~10 seconds total (10 attempts × 1s).
        // Anything beyond that is a real config problem, not a daemon
        // boot race; falling back to user-driven onResume()/regenerate
        // is fine.
        if (attempt > 10) return
        mainHandler.postDelayed({
            if (!isAdded) return@postDelayed
            // getState() can still trigger a blocking IPC (see loadAuthState),
            // so keep it off the main thread here too.
            val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
                .also { metricsExecutor = it }
            executor.execute {
                val state = try { AuthManager.getState() } catch (e: Exception) { null }
                mainHandler.post {
                    if (!isAdded) return@post
                    if (state != null) {
                        updateTokenDisplay(state.secret)
                    } else {
                        scheduleAuthRetry(attempt + 1)
                    }
                }
            }
        }, 1000)
    }

    private fun updateTokenDisplay(secret: String) {
        tvDeviceToken.text = if (isTokenVisible) secret else getString(R.string.dashboard_token_masked)
    }

    private fun toggleTokenVisibility() {
        isTokenVisible = !isTokenVisible
        AuthManager.getState()?.let { updateTokenDisplay(it.secret) }
        btnToggleToken.setImageResource(
            if (isTokenVisible) android.R.drawable.ic_menu_close_clear_cancel
            else android.R.drawable.ic_menu_view
        )
    }

    private fun copyTokenToClipboard() {
        val state = AuthManager.getState() ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.clip_label_access_code), state.secret)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), getString(R.string.toast_access_code_copied), Toast.LENGTH_SHORT).show()
    }

    private fun showRegenerateConfirmation() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.Theme_BladeWatch_M3_Dialog)
            .setIcon(R.drawable.ic_warning)
            .setTitle(getString(R.string.dialog_regenerate_token_title))
            .setMessage(getString(R.string.dialog_regenerate_token_message))
            .setPositiveButton(getString(R.string.dialog_regenerate)) { _, _ -> regenerateToken() }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun showSetPasswordDialog() {
        val ctx = context ?: return
        val input = com.google.android.material.textfield.TextInputEditText(ctx).apply {
            hint = getString(R.string.dialog_set_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = com.google.android.material.textfield.TextInputLayout(ctx).apply {
            addView(input)
            setPadding(padding, 8, padding, 8)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx, R.style.Theme_BladeWatch_M3_Dialog)
            .setTitle(getString(R.string.dialog_set_password_title))
            .setMessage(getString(R.string.dialog_set_password_message))
            .setView(container)
            .setPositiveButton(getString(R.string.action_done)) { _, _ ->
                val pw = input.text?.toString()?.trim().orEmpty()
                if (pw.length < 4) {
                    Toast.makeText(ctx, getString(R.string.toast_password_too_short), Toast.LENGTH_SHORT).show()
                } else {
                    setCustomPassword(pw)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun setCustomPassword(password: String) {
        val ctx = context?.applicationContext ?: return
        val newToken = AuthManager.setCustomSecret(password)
        if (newToken == null) {
            Toast.makeText(ctx, ctx.getString(R.string.toast_password_save_failed), Toast.LENGTH_LONG).show()
            return
        }
        loadAuthState()
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            try {
                val req = InvalidateAuthCacheRequest.newBuilder().build()
                runBlocking { ConnectClientProvider.authService().invalidateAuthCache(req, emptyMap()) }
            } catch (_: Exception) { }
            mainHandler.post {
                if (isAdded) {
                    Toast.makeText(ctx, ctx.getString(R.string.toast_password_set), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun regenerateToken() {
        val newToken = AuthManager.regenerateToken()
        // Use the lifecycle-managed metricsExecutor (shut down in onDestroyView)
        // instead of a bare Thread that would outlive the fragment and leak
        // its Activity reference. The applicationContext for the Toast also
        // bypasses requireContext()'s detach-aware throw.
        val ctx = context?.applicationContext ?: return
        if (newToken == null) {
            // Persistence failed — usually means the daemon hasn't booted
            // yet so the unified config file isn't writable from app UID.
            // Better to surface this than to claim success and leave the
            // user wondering why login still rejects the new code.
            Toast.makeText(ctx, ctx.getString(R.string.toast_token_regenerated_restart), Toast.LENGTH_LONG).show()
            loadAuthState()
            return
        }
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            val msgRes = try {
                val req = InvalidateAuthCacheRequest.newBuilder().build()
                val resp = runBlocking { ConnectClientProvider.authService().invalidateAuthCache(req, emptyMap()) }
                if (resp is ResponseMessage.Success && resp.message.success) R.string.toast_token_regenerated_logged_out
                else R.string.toast_token_regenerated_restart
            } catch (_: Exception) {
                R.string.toast_token_regenerated
            }
            // Use the application context for Toast — survives fragment detach
            // and is the recommended pattern for "fire-and-forget" notifications
            // from a background thread.
            mainHandler.post {
                if (isAdded) {
                    Toast.makeText(ctx, ctx.getString(msgRes), Toast.LENGTH_SHORT).show()
                }
            }
        }
        loadAuthState()
    }

    // ============== Vehicle tile ==============

    /**
     * Read /api/performance/soh/nominal + /api/models/selected and render
     * "82.5 kWh · BYD Seal" or "Tap to set" if no nominal yet.
     *
     * Both calls run on a worker thread (HTTP). Defaults survive a daemon
     * boot race — the tile flashes "Tap to set" until the first successful
     * round-trip lands.
     */
    private fun refreshVehicleTile() {
        val tile = metricVehicleValue ?: return
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            var nominalKwh = 0.0
            var modelId: String? = null
            try {
                val req = GetSohNominalRequest.newBuilder().build()
                val resp = runBlocking { ConnectClientProvider.systemService().getSohNominal(req, emptyMap()) }
                if (resp is ResponseMessage.Success && resp.message.hasNominalKwh()) {
                    nominalKwh = resp.message.nominalKwh
                }
            } catch (_: Throwable) { /* keep defaults */ }
            try {
                val req = GetSelectedModelRequest.newBuilder().build()
                val resp = runBlocking { ConnectClientProvider.systemService().getSelectedModel(req, emptyMap()) }
                if (resp is ResponseMessage.Success && resp.message.modelId.isNotEmpty()) {
                    modelId = resp.message.modelId
                }
            } catch (_: Throwable) {}

            mainHandler.post {
                if (!isAdded || view == null) return@post
                if (nominalKwh > 0) {
                    tile.text = if (modelId != null) {
                        getString(R.string.dashboard_vehicle_summary, nominalKwh, modelDisplayName(modelId))
                    } else {
                        String.format("%.1f kWh", nominalKwh)
                    }
                } else {
                    tile.text = getString(R.string.dashboard_vehicle_tap_to_set)
                }
            }
        }
    }

    // ============== Trip stats hero ==============

    private fun refreshTripStats() {
        if (!::tvTripHeadline.isInitialized) return
        tvTripHeadline.text = getString(R.string.dashboard_trips_loading)
        tvStatTrips.text = getString(R.string.dashboard_metric_value_pending)
        tvStatDistance.text = getString(R.string.dashboard_metric_value_pending)
        tvStatDriveTime.text = getString(R.string.dashboard_metric_value_pending)

        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            var tripCount = 0
            var totalDistanceKm = 0.0
            var totalDurationSeconds = 0
            var dataAvailable = false

            try {
                // Use TripsService.ListTrips Connect RPC — same source as the Trips screen,
                // so the dashboard count and the Trips list can never disagree.
                val req = ListTripsRequest.newBuilder().setDays(7).setLimit(100).build()
                val resp = runBlocking { ConnectClientProvider.tripsService().listTrips(req, emptyMap()) }
                if (resp is ResponseMessage.Success) {
                    val trips = resp.message.tripsList
                    tripCount = trips.size
                    for (trip in trips) {
                        totalDistanceKm += trip.distanceKm
                        totalDurationSeconds += trip.durationSeconds
                    }
                    dataAvailable = true
                }
            } catch (_: Throwable) { }

            mainHandler.post {
                if (!isAdded || view == null) return@post
                if (!dataAvailable) {
                    tvTripHeadline.text = getString(R.string.dashboard_trips_unavailable)
                    tvStatTrips.text = getString(R.string.dashboard_metric_value_pending)
                    tvStatDistance.text = getString(R.string.dashboard_metric_value_pending)
                    tvStatDriveTime.text = getString(R.string.dashboard_metric_value_pending)
                    return@post
                }
                if (tripCount == 0) {
                    tvTripHeadline.text = getString(R.string.dashboard_trips_no_data)
                    tvStatTrips.text = "0"
                    tvStatDistance.text = "0 km"
                    tvStatDriveTime.text = "0m"
                    return@post
                }
                val distanceStr = if (totalDistanceKm >= 1000)
                    String.format("%.0f km", totalDistanceKm)
                else
                    String.format("%.1f km", totalDistanceKm)
                val tripWord = if (tripCount == 1) "trip" else "trips"
                tvTripHeadline.text = "$tripCount $tripWord · $distanceStr"
                tvStatTrips.text = tripCount.toString()
                tvStatDistance.text = distanceStr
                tvStatDriveTime.text = formatDuration(totalDurationSeconds)
            }
        }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    /**
     * Dialog with capacity input + model dropdown. POSTs to
     * /api/performance/soh/nominal and /api/models/selected.
     */
    private fun showVehicleCapacityDialog() {
        val ctx = context ?: return

        // Inflate the M3 layout (outlined inputs + ExposedDropdownMenu).
        val dialogView = layoutInflater.inflate(
            R.layout.dialog_vehicle_capacity, null, false)

        val summaryCapacity = dialogView.findViewById<TextView>(R.id.vehicleSummaryCapacity)
        val summarySoh = dialogView.findViewById<TextView>(R.id.vehicleSummarySoh)
        val summaryEffective = dialogView.findViewById<TextView>(R.id.vehicleSummaryEffective)
        val summaryModel = dialogView.findViewById<TextView>(R.id.vehicleSummaryModel)
        val summaryCalibration = dialogView.findViewById<TextView>(R.id.vehicleSummaryCalibration)
        val summaryDivider = dialogView.findViewById<View>(R.id.vehicleSummaryDivider)
        val capInput = dialogView.findViewById<
            com.google.android.material.textfield.TextInputEditText>(R.id.vehicleCapacityInput)
        val modelDropdown = dialogView.findViewById<
            com.google.android.material.textfield.MaterialAutoCompleteTextView>(
            R.id.vehicleModelDropdown)

        // Track the selected model's id locally (the dropdown's text holds
        // the user-facing title; the id is what we POST). Each entry also
        // carries the manifest's canonical nominalKwh so picking a model
        // can auto-fill the capacity input. The list is refreshed from
        // the manifest below.
        data class ModelEntry(val id: String, val title: String, val nominalKwh: Double)
        val modelEntries = mutableListOf<ModelEntry>()
        var selectedModelId: String? = null
        modelDropdown.setOnItemClickListener { _, _, position, _ ->
            if (position in modelEntries.indices) {
                val entry = modelEntries[position]
                selectedModelId = entry.id
                // Auto-fill the capacity field with the manifest's
                // canonical nominalKwh for this model. The user can still
                // edit it before saving — this is just a sensible starting
                // value rather than leaving the field showing the previous
                // model's number.
                if (entry.nominalKwh > 0) {
                    capInput.setText(String.format("%.1f", entry.nominalKwh))
                }
            }
        }

        // Pre-populate from the current state via background fetch.
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            var initialKwh = 0.0
            val modelIds = mutableListOf<ModelEntry>()
            var initialModelId: String? = null

            // Full status fields for the summary section.
            var nominalKwh = 0.0
            var nominalSource = "unset"
            var displaySoh = -1.0
            var displaySource = "unavailable"
            var estimatedKwh = 0.0
            var statusModelId: String? = null
            var calSoh = 0.0
            var calTs = 0L

            try {
                val req = GetSohNominalRequest.newBuilder().build()
                val resp = runBlocking { ConnectClientProvider.systemService().getSohNominal(req, emptyMap()) }
                if (resp is ResponseMessage.Success && resp.message.hasNominalKwh()) initialKwh = resp.message.nominalKwh
            } catch (_: Throwable) {}
            try {
                val req = GetSohStatusRequest.newBuilder().build()
                val resp = runBlocking { ConnectClientProvider.systemService().getSohStatus(req, emptyMap()) }
                if (resp is ResponseMessage.Success) {
                    nominalKwh = resp.message.nominalCapacityKwh
                    if (resp.message.nominalSource.isNotEmpty()) nominalSource = resp.message.nominalSource
                    displaySoh = resp.message.displaySoh
                    if (resp.message.displaySource.isNotEmpty()) displaySource = resp.message.displaySource
                    // estimatedKwh, statusModelId, calSoh, calTs not in this RPC — remain default
                }
            } catch (_: Throwable) {}
            try {
                val req = GetModelsManifestRequest.newBuilder().build()
                val resp = runBlocking { ConnectClientProvider.systemService().getModelsManifest(req, emptyMap()) }
                if (resp is ResponseMessage.Success && resp.message.manifestJson.isNotEmpty()) {
                    val json = org.json.JSONObject(resp.message.manifestJson)
                    val arr = json.optJSONArray("models")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val m = arr.getJSONObject(i)
                            val id = m.optString("id", "")
                            val title = when {
                                m.optString("name", "").isNotEmpty() -> m.optString("name")
                                m.optString("title", "").isNotEmpty() -> m.optString("title")
                                else -> id
                            }
                            val kwh = m.optDouble("nominalKwh", 0.0)
                            if (id.isNotEmpty()) modelIds.add(ModelEntry(id, title, kwh))
                        }
                    }
                }
            } catch (_: Throwable) {}
            try {
                val req = GetSelectedModelRequest.newBuilder().build()
                val resp = runBlocking { ConnectClientProvider.systemService().getSelectedModel(req, emptyMap()) }
                if (resp is ResponseMessage.Success && resp.message.modelId.isNotEmpty()) {
                    initialModelId = resp.message.modelId
                }
            } catch (_: Throwable) {}

            val finalNominalKwh = nominalKwh
            val finalNominalSource = nominalSource
            val finalDisplaySoh = displaySoh
            val finalDisplaySource = displaySource
            val finalEstimatedKwh = estimatedKwh
            val finalStatusModelId = statusModelId ?: initialModelId
            val finalCalSoh = calSoh
            val finalCalTs = calTs

            mainHandler.post {
                if (!isAdded || view == null) return@post

                // Capacity input — current user value if any.
                if (initialKwh > 0) capInput.setText(String.format("%.1f", initialKwh))

                // Model dropdown — populate using a Material adapter so the
                // popup uses M3 list-item styling. setText(filter=false) sets
                // the displayed value without filtering the list.
                modelEntries.clear()
                modelEntries.addAll(modelIds)
                val titles = modelIds.map { it.title }
                val adapter = android.widget.ArrayAdapter(
                    ctx,
                    com.google.android.material.R.layout.m3_auto_complete_simple_item,
                    titles)
                modelDropdown.setAdapter(adapter)
                if (initialModelId != null) {
                    val idx = modelIds.indexOfFirst { it.id == initialModelId }
                    if (idx >= 0) {
                        modelDropdown.setText(titles[idx], false)
                        selectedModelId = initialModelId
                    }
                }

                // Populate summary section. Each line shows only when its data
                // is meaningful — keeps the dialog tight when the daemon is
                // still seeding.
                val capacityText = if (finalNominalKwh > 0) {
                    val suffix = when (finalNominalSource) {
                        "user" -> " (" + getString(R.string.soh_dialog_source_user) + ")"
                        "auto" -> " (" + getString(R.string.soh_dialog_source_auto) + ")"
                        else -> ""
                    }
                    String.format("%.1f kWh", finalNominalKwh) + suffix
                } else {
                    getString(R.string.soh_dialog_capacity_not_detected)
                }
                summaryCapacity.text = getString(R.string.vehicle_dialog_summary_capacity, capacityText)
                summaryCapacity.visibility = View.VISIBLE

                val sohText = when {
                    finalDisplaySoh > 0 && finalDisplaySource == "live" ->
                        String.format("%.1f%% (live)", finalDisplaySoh)
                    finalDisplaySoh > 0 && finalDisplaySource == "calibration" ->
                        String.format("%.1f%% (from last charge)", finalDisplaySoh)
                    // OEM is the BYD StatisticBatteryHealthyIndex recovered
                    // from the legacy app. Surface it in the vehicle summary,
                    // but label it so it is not mistaken for calculated SOH.
                    finalDisplaySoh > 0 && finalDisplaySource == "oem" ->
                        String.format("%.1f%% (vehicle)", finalDisplaySoh)
                    finalDisplaySoh > 0 && finalDisplaySource == "nominal" ->
                        String.format("%.1f%% (nominal)", finalDisplaySoh)
                    else -> getString(R.string.vehicle_dialog_soh_unavailable)
                }
                summarySoh.text = getString(R.string.vehicle_dialog_summary_soh, sohText)
                summarySoh.visibility = View.VISIBLE

                if (finalEstimatedKwh > 0) {
                    summaryEffective.text = getString(
                        R.string.vehicle_dialog_summary_effective, finalEstimatedKwh)
                    summaryEffective.visibility = View.VISIBLE
                }

                val modelText = if (finalStatusModelId != null) modelDisplayName(finalStatusModelId)
                else getString(R.string.soh_dialog_model_not_selected)
                summaryModel.text = getString(R.string.vehicle_dialog_summary_model, modelText)
                summaryModel.visibility = View.VISIBLE

                if (finalCalSoh > 0 && finalCalTs > 0) {
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(finalCalTs))
                    summaryCalibration.text = getString(
                        R.string.vehicle_dialog_summary_calibration, finalCalSoh, date)
                    summaryCalibration.visibility = View.VISIBLE
                }

                summaryDivider.visibility = View.VISIBLE
            }
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx, R.style.Theme_BladeWatch_M3_Dialog)
            .setTitle(getString(R.string.vehicle_dialog_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.vehicle_dialog_save)) { _, _ ->
                val raw = capInput.text?.toString()?.trim().orEmpty()
                val kwh = raw.toDoubleOrNull()
                if (kwh == null || kwh < 8.0 || kwh > 120.0) {
                    Toast.makeText(ctx, getString(R.string.vehicle_dialog_invalid_capacity), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                postNominalAndModel(kwh, selectedModelId)
            }
            .setNeutralButton(getString(R.string.vehicle_dialog_reset)) { _, _ ->
                postNominal(null)
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun postNominal(kwh: Double?) {
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            try {
                val builder = SetSohNominalRequest.newBuilder()
                if (kwh != null) builder.setNominalKwh(kwh)
                runBlocking { ConnectClientProvider.systemService().setSohNominal(builder.build(), emptyMap()) }
            } catch (_: Throwable) {}
            mainHandler.post { refreshVehicleTile() }
        }
    }

    private fun postNominalAndModel(kwh: Double, modelId: String?) {
        val executor = metricsExecutor ?: Executors.newSingleThreadExecutor()
            .also { metricsExecutor = it }
        executor.execute {
            try {
                val req = SetSohNominalRequest.newBuilder().setNominalKwh(kwh).build()
                runBlocking { ConnectClientProvider.systemService().setSohNominal(req, emptyMap()) }
            } catch (_: Throwable) {}

            if (!modelId.isNullOrEmpty()) {
                try {
                    val req = SetSelectedModelRequest.newBuilder().setModelId(modelId).build()
                    runBlocking { ConnectClientProvider.systemService().setSelectedModel(req, emptyMap()) }
                } catch (_: Throwable) {}
            }

            mainHandler.post { refreshVehicleTile() }
        }
    }

    private fun modelDisplayName(modelId: String?): String {
        return when (modelId?.lowercase()) {
            null -> "—"
            "seal" -> "BYD Seal"
            "atto3", "atto-3" -> "BYD Atto 3"
            "atto2", "atto-2" -> "BYD Atto 2"
            "atto1", "atto-1" -> "BYD Atto 1"
            "han" -> "BYD Han"
            "tang" -> "BYD Tang"
            "song" -> "BYD Song"
            "qin" -> "BYD Qin"
            "dolphin" -> "BYD Dolphin"
            "seagull" -> "BYD Seagull"
            "sealion6" -> "BYD Sealion 6"
            "sealion7" -> "BYD Sealion 7"
            "sealu", "seal-u" -> "BYD Seal U"
            "seal5-dmi-dynamic" -> "BYD Seal 5 DM-i Dynamic"
            "seal5-dmi-premium" -> "BYD Seal 5 DM-i Premium"
            else -> modelId.replaceFirstChar { it.uppercase() }
        }
    }

}
