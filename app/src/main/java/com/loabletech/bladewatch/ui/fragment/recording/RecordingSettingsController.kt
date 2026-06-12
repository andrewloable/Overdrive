package net.bladewatch.app.ui.fragment.recording

import android.content.Context
import android.graphics.Color
import android.widget.Toast
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.bladewatch.app.ui.common.BladeTheme

class RecordingSettingsController(private val context: Context) {

    private val root = FrameLayout(context)
    private val client = RecordingSettingsClient()
    private val theme = BladeTheme(context)

    private var activeTab = RecordingSettingsTab.STATUS
    private lateinit var statusTabBtn: TextView
    private lateinit var captureTabBtn: TextView
    private lateinit var qualityTabBtn: TextView
    private lateinit var storageTabBtn: TextView
    private lateinit var contentArea: LinearLayout

    // Editable state
    private var selectedMode = RecordingMode.NONE
    private var selectedQuality = RecordingQuality.STANDARD
    private var selectedCodec = "H264"
    private var selectedLimit = RecordingLimit.FIVE
    private var selectedStorageType = "INTERNAL"
    private var selectedLimitMb = 500L
    private var storageMinLimitMb = 100L
    private var storageMaxLimitMb = 100000L
    private var storageMaxLimitMbSdCard = 100000L
    private var storageInternalTotalMb = 0L
    private var storageSdCardTotalMb = 0L
    private var loadedState: RecordingSettingsLoadState? = null
    private var dirty = false
    private var loadedOnce = false

    // Format drive state
    private var formatConfirmPending = false
    private var formatRunning = false
    private var formatResultMessage: String? = null

    // Sync catalog state
    private var syncRunning = false
    private var syncResultMessage: String? = null

    init { buildView(); loadData() }

    val view: View get() = root
    fun onResume() { if (loadedOnce) loadData() }
    fun onPause() {}
    fun onDestroy() {}
    fun onConfigurationChanged() { applyTheme(); renderCurrentTab() }

    // ─────────────────────────── BUILD ───────────────────────────────────

    private fun buildView() {
        root.setBackgroundColor(bgColor())
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        contentArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        scroll.addView(contentArea)
        outer.addView(scroll)
        outer.addView(buildTabBar())
        root.addView(outer)
    }

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(surfaceColor())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER
        }
        statusTabBtn = makeTabBtn("Status", RecordingSettingsTab.STATUS)
        captureTabBtn = makeTabBtn("Capture", RecordingSettingsTab.CAPTURE)
        qualityTabBtn = makeTabBtn("Quality", RecordingSettingsTab.QUALITY)
        storageTabBtn = makeTabBtn("Storage", RecordingSettingsTab.STORAGE)
        listOf(statusTabBtn, captureTabBtn, qualityTabBtn, storageTabBtn).forEach { btn ->
            bar.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        updateTabs()
        return bar
    }

    private fun makeTabBtn(label: String, tab: RecordingSettingsTab): TextView =
        TextView(context).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { activeTab = tab; updateTabs(); renderCurrentTab() }
        }

    private fun updateTabs() {
        listOf(
            statusTabBtn to RecordingSettingsTab.STATUS,
            captureTabBtn to RecordingSettingsTab.CAPTURE,
            qualityTabBtn to RecordingSettingsTab.QUALITY,
            storageTabBtn to RecordingSettingsTab.STORAGE,
        ).forEach { (btn, tab) ->
            if (tab == activeTab) {
                btn.background = pill(accentColor()); btn.setTextColor(theme.onAccentColor())
            } else {
                btn.background = pill(inactivePillColor())
                btn.setTextColor(mutedTextColor())
            }
        }
    }

    // ─────────────────────────── DATA ────────────────────────────────────

    private fun loadData() {
        root.post { contentArea.removeAllViews(); contentArea.addView(centeredText("Loading…", 15f)) }
        Thread({
            try {
                val status = client.fetchStatus()
                val quality = client.fetchQuality()
                val storage = client.fetchStorage()
                val mode = status?.currentMode ?: "NONE"
                selectedMode = RecordingMode.values().find { it.value == mode } ?: RecordingMode.NONE
                if (quality != null) {
                    selectedQuality = quality.quality; selectedCodec = quality.codec
                    selectedLimit = RecordingLimit.fromMinutes(quality.segmentMinutes)
                }
                if (storage != null) {
                    selectedStorageType = storage.storageType; selectedLimitMb = storage.limitMb
                    storageMinLimitMb = storage.minLimitMb
                    storageMaxLimitMb = storage.maxLimitMb
                    storageMaxLimitMbSdCard = storage.maxLimitMbSdCard
                    storageInternalTotalMb = storage.internalTotalMb
                    storageSdCardTotalMb = storage.sdCardTotalMb
                }
                loadedState = RecordingSettingsLoadState.Loaded(
                    RecordingAllSettings(status, quality, storage, mode))
                dirty = false
                root.post { renderCurrentTab(); loadedOnce = true }
            } catch (e: Exception) {
                // Without this the spinner posted above stays forever: the
                // Error render branch in renderCurrentTab() is otherwise
                // unreachable. Surface the failure so the user isn't stuck.
                loadedState = RecordingSettingsLoadState.Error(
                    e.message ?: e.javaClass.simpleName)
                root.post { renderCurrentTab(); loadedOnce = true }
            }
        }, "RecSettingsLoad").apply { isDaemon = true; start() }
    }

    // ─────────────────────────── RENDER ──────────────────────────────────

    private fun renderCurrentTab() {
        if (activeTab != RecordingSettingsTab.STORAGE) {
            formatConfirmPending = false; formatRunning = false; formatResultMessage = null
            syncRunning = false; syncResultMessage = null
        }
        applyTheme(); updateTabs()
        contentArea.removeAllViews()
        when (val state = loadedState) {
            null, RecordingSettingsLoadState.Loading -> contentArea.addView(centeredText("Loading…", 15f))
            is RecordingSettingsLoadState.Error -> contentArea.addView(centeredText("Error: ${state.message}", 13f))
            is RecordingSettingsLoadState.Loaded -> when (activeTab) {
                RecordingSettingsTab.STATUS -> renderStatus(state.settings)
                RecordingSettingsTab.CAPTURE -> renderCapture()
                RecordingSettingsTab.QUALITY -> renderQuality()
                RecordingSettingsTab.STORAGE -> renderStorage(state.settings.storage)
            }
        }
    }

    private fun applyTheme() { root.setBackgroundColor(bgColor()) }

    // ─────────────────────────── STATUS TAB ─────────────────────────────

    private fun renderStatus(s: RecordingAllSettings) {
        val status = s.status
        val card = makeCard()
        card.addView(sectionLabel("Recording Status"))
        card.addView(spacer(dp(10)))

        val modeFriendly = RecordingMode.values().find { it.value == s.currentMode }?.label ?: s.currentMode
        card.addView(infoRow("Current State", modeFriendly))
        card.addView(spacer(dp(6)))

        val todayCount = (status?.normalTodayCount ?: 0) + (status?.proximityTodayCount ?: 0)
        card.addView(infoRow("Recordings Today", "$todayCount"))
        contentArea.addView(card)
    }

    // ─────────────────────────── CAPTURE TAB ────────────────────────────

    private fun renderCapture() {
        val card = makeCard()
        card.addView(sectionLabel("Recording Mode (ACC ON)"))
        card.addView(spacer(dp(4)))
        card.addView(fieldDesc("Choose when dashcam recording should occur while driving."))
        card.addView(spacer(dp(12)))

        RecordingMode.values().forEach { mode ->
            card.addView(buildModeOption(mode))
            card.addView(spacer(dp(8)))
        }
        contentArea.addView(card)

        // Recording limit — max length per file before the recording rotates
        // into a new file. Keeps individual clips manageable and makes storage
        // cleanup more granular.
        contentArea.addView(spacer(dp(12)))
        val limitCard = makeCard()
        limitCard.addView(sectionLabel("Recording Limit"))
        limitCard.addView(spacer(dp(4)))
        limitCard.addView(fieldDesc("Maximum length per file. Recordings split into new files at this interval."))
        limitCard.addView(spacer(dp(12)))
        val limitRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        RecordingLimit.values().forEach { limit ->
            val btn = makeSegmentBtn(limit.label, selectedLimit == limit) {
                selectedLimit = limit; dirty = true; renderCurrentTab()
            }
            limitRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(4), 0)
            })
        }
        limitCard.addView(limitRow)
        contentArea.addView(limitCard)

        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildApplyButton())
    }

    private fun buildModeOption(mode: RecordingMode): LinearLayout {
        val selected = selectedMode == mode
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(if (selected) theme.accentColor() else inactivePillColor())
                cornerRadius = dp(8).toFloat()
                if (selected) setStroke(dp(2), accentColor())
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { selectedMode = mode; dirty = true; renderCurrentTab() }
        }
        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textBlock.addView(TextView(context).apply {
            text = mode.label; textSize = 14f
            setTextColor(theme.textColor())
        })
        textBlock.addView(TextView(context).apply {
            text = mode.description; textSize = 12f; setTextColor(mutedTextColor())
        })
        val radio = TextView(context).apply {
            text = if (selected) "◉" else "○"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(if (selected) accentColor() else mutedTextColor())
            layoutParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(textBlock); row.addView(radio)
        return row
    }

    // ─────────────────────────── QUALITY TAB ────────────────────────────

    private fun renderQuality() {
        val card = makeCard()
        card.addView(sectionLabel("Recording Quality"))
        card.addView(spacer(dp(12)))

        // Quality tier row
        val tierRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        RecordingQuality.values().forEach { q ->
            val btn = makeSegmentBtn(q.value, selectedQuality == q) {
                selectedQuality = q; dirty = true; renderCurrentTab()
            }
            tierRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(4), 0)
            })
        }
        card.addView(tierRow)
        card.addView(spacer(dp(16)))

        // Codec
        card.addView(sectionLabel("Codec"))
        card.addView(spacer(dp(8)))
        val codecRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("H264", "H265").forEach { codec ->
            val btn = makeSegmentBtn(codec, selectedCodec == codec) {
                selectedCodec = codec; dirty = true; renderCurrentTab()
            }
            codecRow.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, dp(8), 0)
            })
        }
        card.addView(codecRow)

        contentArea.addView(card)
        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildApplyButton())
    }

    // ─────────────────────────── STORAGE TAB ────────────────────────────

    private fun renderStorage(storage: RecordingStorageSettings?) {
        val card = makeCard()
        card.addView(sectionLabel("Recording Storage"))
        card.addView(spacer(dp(12)))

        // Storage type
        card.addView(fieldLabel("Storage Location"))
        card.addView(spacer(dp(4)))
        val locRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val sdAvail = storage?.sdCardAvailable ?: false
        val intBtn = makeSegmentBtn("Internal", selectedStorageType == "INTERNAL") {
            selectedStorageType = "INTERNAL"; dirty = true; renderCurrentTab()
        }
        locRow.addView(intBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(8), 0) })
        val sdLabel = "SD Card${if (!sdAvail) " (N/A)" else ""}"
        val sdBtn = makeSegmentBtn(sdLabel, selectedStorageType == "SD_CARD") {
            if (sdAvail) { selectedStorageType = "SD_CARD"; dirty = true; renderCurrentTab() }
        }
        sdBtn.isEnabled = sdAvail
        if (!sdAvail) sdBtn.alpha = 0.5f
        locRow.addView(sdBtn)
        card.addView(locRow)
        card.addView(spacer(dp(12)))

        // Storage limit slider (auto-delete oldest when reached) — restored from
        // the pre-native-migration web UI, which had this MB-cap control.
        // Cap the slider at the total physical capacity of the selected volume;
        // fall back to the daemon's configured max if the size is unknown (0).
        val totalMb = if (selectedStorageType == "SD_CARD") storageSdCardTotalMb else storageInternalTotalMb
        val daemonMax = if (selectedStorageType == "SD_CARD") storageMaxLimitMbSdCard else storageMaxLimitMb
        val maxMb = (if (totalMb > 0L) totalMb else daemonMax).coerceAtLeast(storageMinLimitMb + 100L)
        val minMb = storageMinLimitMb.coerceAtLeast(100L)
        selectedLimitMb = selectedLimitMb.coerceIn(minMb, maxMb)
        card.addView(fieldLabel("Storage Limit — auto-deletes oldest when reached"))
        card.addView(spacer(dp(4)))
        val limitValue = TextView(context).apply {
            text = formatMb(selectedLimitMb); textSize = 14f
            setTextColor(theme.textColor())
        }
        card.addView(limitValue)
        val seek = android.widget.SeekBar(context).apply {
            max = (((maxMb - minMb) / 100L).toInt()).coerceAtLeast(1)
            progress = (((selectedLimitMb - minMb) / 100L).toInt()).coerceIn(0, max)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    selectedLimitMb = (minMb + p.toLong() * 100L).coerceIn(minMb, maxMb)
                    limitValue.text = formatMb(selectedLimitMb)
                    if (fromUser) dirty = true
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        card.addView(seek)
        card.addView(spacer(dp(12)))

        // Storage info
        if (storage != null) {
            val sizeMb = storage.recordingsSize / (1024.0 * 1024.0)
            card.addView(infoRow("Storage Usage", "%.1f MB used / ${storage.limitMb} MB limit".format(sizeMb)))
            card.addView(spacer(dp(4)))
            card.addView(infoRow("Files", "${storage.recordingsCount} recordings"))
            if (storage.recordingsPath.isNotBlank()) {
                card.addView(spacer(dp(4)))
                card.addView(infoRow("Path", storage.recordingsPath))
            }
            if (sdAvail && storage.sdCardFreeFormatted.isNotBlank()) {
                card.addView(spacer(dp(4)))
                card.addView(infoRow("SD Card Free", storage.sdCardFreeFormatted))
            }
            if (storage.internalFreeFormatted.isNotBlank()) {
                card.addView(spacer(dp(4)))
                card.addView(infoRow("Internal Free", storage.internalFreeFormatted))
            }
        }

        contentArea.addView(card)
        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildApplyButton())

        if (sdAvail) {
            contentArea.addView(spacer(dp(12)))
            contentArea.addView(buildFormatCard())
        }

        contentArea.addView(spacer(dp(12)))
        contentArea.addView(buildSyncCard())
    }

    // ─────────────────────────── FORMAT ──────────────────────────────────

    private fun buildFormatCard(): android.view.View {
        val card = makeCard()
        card.background = GradientDrawable().apply {
            setColor(theme.surfaceColor())
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), theme.warningColor())
        }
        card.addView(sectionLabel("Format External Drive"))
        card.addView(spacer(dp(6)))
        card.addView(fieldLabel("Permanently erases ALL data on the SD card or USB drive."))
        card.addView(spacer(dp(12)))

        when {
            formatResultMessage != null -> {
                val isError = formatResultMessage!!.startsWith("Error") || formatResultMessage!!.contains("failed")
                card.addView(TextView(context).apply {
                    text = formatResultMessage
                    textSize = 13f
                    setTextColor(if (isError) theme.errorColor() else theme.accentColor())
                })
                card.addView(spacer(dp(8)))
                card.addView(TextView(context).apply {
                    text = "Dismiss"
                    textSize = 13f; gravity = android.view.Gravity.CENTER
                    setTextColor(mutedTextColor())
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { formatResultMessage = null; renderCurrentTab() }
                })
            }
            formatRunning -> {
                card.addView(centeredText("Formatting… please wait", 13f))
            }
            formatConfirmPending -> {
                card.addView(TextView(context).apply {
                    text = "Tap again — ALL data will be ERASED"
                    textSize = 13f; gravity = android.view.Gravity.CENTER
                    setTextColor(theme.onAccentColor())
                    background = pill(theme.errorColor())
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener { executeFormat() }
                })
                card.addView(spacer(dp(8)))
                card.addView(TextView(context).apply {
                    text = "Cancel"
                    textSize = 13f; gravity = android.view.Gravity.CENTER
                    setTextColor(mutedTextColor())
                    setPadding(0, dp(6), 0, dp(2))
                    setOnClickListener { formatConfirmPending = false; renderCurrentTab() }
                })
            }
            else -> {
                card.addView(TextView(context).apply {
                    text = "Format SD Card / USB"
                    textSize = 13f; gravity = android.view.Gravity.CENTER
                    setTextColor(theme.onAccentColor())
                    background = pill(theme.warningColor())
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setOnClickListener { formatConfirmPending = true; renderCurrentTab() }
                })
            }
        }
        return card
    }

    private fun executeFormat() {
        formatConfirmPending = false
        formatRunning = true
        renderCurrentTab()
        Thread({
            val volumes = client.listFormattableVolumes()
            val first = volumes.firstOrNull()
            if (first == null) {
                root.post {
                    formatRunning = false
                    formatResultMessage = "Error: No removable drive found"
                    renderCurrentTab()
                }
                return@Thread
            }
            val result = client.formatVolume(first.volumeId)
            root.post {
                formatRunning = false
                formatResultMessage = if (result.success) {
                    "Formatted successfully. New path: ${result.mountPath ?: "unknown"}"
                } else {
                    "Error: ${result.message}"
                }
                loadData()
            }
        }, "FormatDrive").apply { isDaemon = true; start() }
    }

    // ─────────────────────────── SYNC ────────────────────────────────────

    private fun buildSyncCard(): android.view.View {
        val card = makeCard()
        card.addView(sectionLabel("Database Catalog"))
        card.addView(spacer(dp(6)))
        card.addView(fieldLabel("Reconcile the recordings index with files on disk."))
        card.addView(spacer(dp(12)))
        when {
            syncResultMessage != null -> {
                val isError = !syncResultMessage!!.startsWith("Synced")
                card.addView(TextView(context).apply {
                    text = syncResultMessage
                    textSize = 13f
                    setTextColor(if (isError) theme.errorColor() else theme.accentColor())
                })
                card.addView(spacer(dp(8)))
                card.addView(TextView(context).apply {
                    text = "Dismiss"
                    textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(mutedTextColor())
                    setPadding(0, dp(4), 0, dp(4))
                    setOnClickListener { syncResultMessage = null; renderCurrentTab() }
                })
            }
            syncRunning -> card.addView(centeredText("Syncing…", 13f))
            else -> card.addView(TextView(context).apply {
                text = "Sync Database"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(theme.onAccentColor())
                background = pill(accentColor())
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { executeSyncDatabase() }
            })
        }
        return card
    }

    private fun executeSyncDatabase() {
        syncRunning = true
        renderCurrentTab()
        Thread({
            val result = client.syncCatalog()
            root.post {
                syncRunning = false
                syncResultMessage = result.message
                renderCurrentTab()
            }
        }, "RecordingDbSync").apply { isDaemon = true; start() }
    }

    // ─────────────────────────── APPLY ───────────────────────────────────

    private fun buildApplyButton(): TextView = TextView(context).apply {
        text = "Apply Changes"
        textSize = 14f; gravity = Gravity.CENTER
        setTextColor(theme.onAccentColor())
        background = pill(accentColor())
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { applyChanges() }
    }

    private fun applyChanges() {
        Thread({
            val result = when (activeTab) {
                RecordingSettingsTab.CAPTURE -> {
                    val r1 = client.saveMode(selectedMode.value)
                    val r2 = client.saveRecordingLimit(selectedLimit.minutes)
                    if (!r1.ok) r1 else r2
                }
                RecordingSettingsTab.QUALITY -> client.saveQuality(selectedQuality.value, selectedCodec)
                RecordingSettingsTab.STORAGE -> client.saveStorage(selectedStorageType, selectedLimitMb)
                RecordingSettingsTab.STATUS -> null
            }
            dirty = false
            root.post {
                if (result != null && !result.ok) {
                    val msg = result.error?.takeIf { it.isNotEmpty() } ?: "Save failed"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                loadData()
            }
        }, "RecSettingsSave").apply { isDaemon = true; start() }
    }

    // ─────────────────────────── HELPERS ────────────────────────────────

    private fun isDark()           = theme.isDark()
    private fun bgColor()          = theme.bgColor()
    private fun surfaceColor()     = theme.surfaceColor()
    private fun accentColor()      = theme.accentColor()
    private fun inactivePillColor() = theme.pillBgColor()
    private fun mutedTextColor()   = theme.mutedColor()

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }

    private fun makeCard(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(surfaceColor()); cornerRadius = dp(8).toFloat() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun makeSegmentBtn(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = pill(if (selected) accentColor() else inactivePillColor())
            setTextColor(if (selected) Color.WHITE else mutedTextColor())
            setOnClickListener { onClick() }
        }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text; textSize = 14f
        setTextColor(theme.textColor())
    }

    private fun fieldLabel(text: String) = TextView(context).apply {
        this.text = text; textSize = 12f; setTextColor(mutedTextColor())
    }

    private fun fieldDesc(text: String) = TextView(context).apply {
        this.text = text; textSize = 12f; setTextColor(mutedTextColor())
    }

    private fun infoRow(label: String, value: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(context).apply {
            text = label; textSize = 13f; setTextColor(mutedTextColor())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(context).apply {
            text = value; textSize = 13f
            setTextColor(theme.textColor())
        })
    }

    private fun centeredText(text: String, size: Float) = TextView(context).apply {
        this.text = text; textSize = size; gravity = Gravity.CENTER; setTextColor(mutedTextColor())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(32) }
    }

    private fun spacer(h: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun formatMb(mb: Long): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB"
}
