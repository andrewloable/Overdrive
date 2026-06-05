package com.overdrive.app.ui.fragment

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.overdrive.app.R
import com.overdrive.app.ui.model.RecordingFile
import com.overdrive.app.ui.util.RecordingScanner
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Recordings page — single native two-pane surface.
 *
 * The top header bundles every filter the user can reach (events.html parity):
 *   • Title + Settings.
 *   • Counts subtitle ("X today · Y total · Z GB").
 *   • Dashcam | Surveillance segmented control with running per-segment counts.
 *   • Date jump card (◀ Today / clip count ▶) opening MaterialDatePicker on tap.
 *   • What chip row (Any / Person / Vehicle / Bike / Animal) — additive.
 *   • Severity chip row (Any / Alert / Critical) — additive.
 *   • Reset chip-button (visible only while filters narrow the list).
 *
 * The embedded [RecordingLibraryFragment] hosts the recyclerview only — its
 * own date row + filter bar are hidden via [RecordingLibraryFragment.newInstanceEmbedded].
 *
 * In landscape, taps swap the right preview pane for an inline
 * [VideoPlayerFragment]; the visible recording list (post-filter) is passed
 * through as a playlist so the player can offer prev/next buttons.
 */
class RecordingsFragment : Fragment() {

    private var libraryFragment: RecordingLibraryFragment? = null
    private var currentSource: Source = Source.DASHCAM

    // -------- Filter state owned at this level --------
    private val actorClassFilter = mutableSetOf<String>()  // lowercase
    private val severityFilter = mutableSetOf<String>()    // upper
    /**
     * Dashcam-only: narrow visible list to a specific type. "NORMAL" =
     * continuous-record cam_* clips, "PROXIMITY" = radar-triggered clips.
     * Empty set => show both (the default). Both selected => same as empty.
     */
    private val dashcamTypes = mutableSetOf<String>()

    // -------- Date state --------
    private val calendar = Calendar.getInstance()
    private var selectedDay = calendar.get(Calendar.DAY_OF_MONTH)
    /**
     * Whether the visible list is narrowed to a single day. False until the
     * user explicitly picks a date or taps prev/next-day. The default
     * "today" date stored in [calendar] / [selectedDay] is just the picker
     * pre-selection; without this flag the page would silently filter every
     * recording that wasn't captured today.
     */
    private var dateNarrowed: Boolean = false
    private val dayHeaderFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    // -------- Playlist for inline player prev/next --------
    private var currentPlaylist: List<RecordingFile> = emptyList()
    private var currentInlineIndex: Int = -1

    /**
     * Whether the inline player is currently maximized (chrome hidden +
     * preview pane occupying the whole screen). Only ever true on landscape
     * with an active inline player; portrait has no inline pane.
     */
    private var playerFullscreen: Boolean = false

    /**
     * Back-press hook installed only while the player is maximized. Pressing
     * back collapses fullscreen first; subsequent back presses fall through
     * to normal nav. Disabled (and removed from the dispatcher's relevant
     * set) when not in fullscreen so it never gets in the way.
     */
    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setPlayerFullscreen(false)
        }
    }

    /** Single-thread executor for filesystem scans. Recreated per view. */
    private var metricsExecutor: ExecutorService? = null

    /** Main-thread handler for posting scan results back to the UI. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Pending UI posts so onDestroyView can cancel any in-flight scan
     * results before the view is gone. Touched from both the metrics
     * executor (add) and the main thread (remove + iteration), so all
     * mutations are guarded by `synchronized(pendingPosts)`.
     */
    private val pendingPosts = mutableListOf<Runnable>()

    enum class Source { DASHCAM, SURVEILLANCE }

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_recordings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore segment + filter state across rotation.
        savedInstanceState?.let { state ->
            currentSource = state.getString(KEY_SOURCE)
                ?.let { runCatching { Source.valueOf(it) }.getOrNull() }
                ?: Source.DASHCAM
            state.getStringArray(KEY_ACTORS)?.let { actorClassFilter.addAll(it) }
            state.getStringArray(KEY_SEVERITY)?.let { severityFilter.addAll(it) }
            state.getStringArray(KEY_DASHCAM_TYPES)?.let { dashcamTypes.addAll(it) }
            val y = state.getInt(KEY_DATE_Y, -1)
            val m = state.getInt(KEY_DATE_M, -1)
            val d = state.getInt(KEY_DATE_D, -1)
            if (y > 0 && m >= 0 && d > 0) {
                calendar.set(y, m, 1)
                selectedDay = d
            }
            dateNarrowed = state.getBoolean(KEY_DATE_NARROWED, false)
            playerFullscreen = state.getBoolean(KEY_PLAYER_FULLSCREEN, false)
        }

        metricsExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "RecordingsMetrics").apply { isDaemon = true }
        }

        attachLibraryFragment()
        setupSegmentedControl(view)
        setupSettingsAction(view)
        setupDateRow(view)
        setupChipFilters(view)
        setupResetButton(view)

        updateDateHeader(view)
        renderActiveFilterAffordances(view)
        refreshCounts()

        // Back-press: collapse fullscreen first when active. The callback is
        // disabled until the user maximizes, so it adds no overhead in the
        // common case.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            fullscreenBackCallback
        )

        // If we're returning to a recreated view while a player still exists
        // in the child manager (e.g. rotation), re-bind the host callback so
        // its maximize button keeps working. The fragment's own state for
        // isFullscreen survives because we mirror it on the fragment itself.
        rebindActiveInlinePlayer()
    }

    override fun onResume() {
        super.onResume()
        // Drop any stale scan-cache the dashboard or another surface left
        // behind. Without this, refreshCounts() and the library reload could
        // both serve the same cached snapshot up to 5s after returning to
        // this page — including any cache populated before a parser bug fix.
        com.overdrive.app.ui.util.RecordingScanner.invalidateCache()
        // Refresh counts every time the user returns to this page so the
        // header reflects any new captures since the last visit.
        refreshCounts()
        // Bounce the library so it re-scans (handles new clips captured while
        // the page was hidden).
        libraryFragment?.let { applyAllFiltersTo(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SOURCE, currentSource.name)
        outState.putStringArray(KEY_ACTORS, actorClassFilter.toTypedArray())
        outState.putStringArray(KEY_SEVERITY, severityFilter.toTypedArray())
        outState.putStringArray(KEY_DASHCAM_TYPES, dashcamTypes.toTypedArray())
        outState.putInt(KEY_DATE_Y, calendar.get(Calendar.YEAR))
        outState.putInt(KEY_DATE_M, calendar.get(Calendar.MONTH))
        outState.putInt(KEY_DATE_D, selectedDay)
        outState.putBoolean(KEY_DATE_NARROWED, dateNarrowed)
        outState.putBoolean(KEY_PLAYER_FULLSCREEN, playerFullscreen)
    }

    override fun onDestroyView() {
        synchronized(pendingPosts) {
            pendingPosts.forEach { mainHandler.removeCallbacks(it) }
            pendingPosts.clear()
        }
        metricsExecutor?.shutdownNow()
        metricsExecutor = null
        super.onDestroyView()
    }

    // -----------------------------------------------------------------
    // Library wiring
    // -----------------------------------------------------------------

    /**
     * Mount the embedded library fragment once. Same instance survives
     * segment switches; we drive it via [applyAllFiltersTo] which atomically
     * pushes every filter dimension and triggers exactly one reload.
     */
    private fun attachLibraryFragment() {
        val existing = childFragmentManager
            .findFragmentById(R.id.libraryContainer) as? RecordingLibraryFragment
        val lib = existing ?: RecordingLibraryFragment.newInstanceEmbedded().also {
            childFragmentManager.commit {
                replace(R.id.libraryContainer, it)
            }
        }
        libraryFragment = lib

        lib.onPlayRecording = if (isLandscape) {
            { recording -> showInlinePreview(recording) }
        } else {
            null
        }
        lib.onListChanged = { list ->
            currentPlaylist = list
            val currentPath = currentInlineIndex
                .takeIf { it in list.indices }
                ?.let { list[it].path }
            if (currentPath == null && currentInlineIndex >= 0) {
                currentInlineIndex = -1
            }
        }
        applyAllFiltersTo(lib)
    }

    /**
     * Push every filter dimension into the library in one atomic call.
     *
     * Dashcam shows BOTH normal continuous-record clips and proximity-radar
     * clips — both come from the dashcam encoder, just triggered differently.
     * Surveillance is the standalone surveillance pipeline.
     *
     * Proximity clips carry actor/severity sidecars (radar detected something
     * + classified it), so the chip filters are kept active even in Dashcam
     * mode — they only narrow the proximity subset and pass cam_* clips
     * through untouched (they have no sidecar fields, so the filter logic
     * naturally excludes them when chips are non-empty — that's intentional).
     */
    private fun applyAllFiltersTo(library: RecordingLibraryFragment) {
        val source: RecordingLibraryFragment.RecordingFilter
        val extra: RecordingLibraryFragment.RecordingFilter?
        val actors: Set<String>
        val severities: Set<String>
        when (currentSource) {
            Source.DASHCAM -> {
                // Derive type narrowing from the user's chip toggles. Empty
                // OR both = show NORMAL + PROXIMITY (the default Dashcam
                // experience). Single chip = narrow to that type only.
                val wantsNormal = dashcamTypes.contains("NORMAL")
                val wantsProximity = dashcamTypes.contains("PROXIMITY")
                when {
                    !wantsNormal && !wantsProximity -> {
                        source = RecordingLibraryFragment.RecordingFilter.NORMAL
                        extra = RecordingLibraryFragment.RecordingFilter.PROXIMITY
                    }
                    wantsNormal && wantsProximity -> {
                        source = RecordingLibraryFragment.RecordingFilter.NORMAL
                        extra = RecordingLibraryFragment.RecordingFilter.PROXIMITY
                    }
                    wantsNormal -> {
                        source = RecordingLibraryFragment.RecordingFilter.NORMAL
                        extra = null
                    }
                    else -> {
                        source = RecordingLibraryFragment.RecordingFilter.PROXIMITY
                        extra = null
                    }
                }
                // Dashcam segment hides the actor/severity rows, so push
                // empty sets — otherwise stale state from a previous
                // Surveillance session would silently filter Dashcam clips.
                actors = emptySet()
                severities = emptySet()
            }
            Source.SURVEILLANCE -> {
                source = RecordingLibraryFragment.RecordingFilter.SENTRY
                extra = null
                actors = actorClassFilter.toSet()
                severities = severityFilter.toSet()
            }
        }
        library.applyAll(
            source = source,
            actorClasses = actors,
            severity = severities,
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH),
            day = selectedDay,
            extraSource = extra,
            narrowToDate = dateNarrowed
        )
    }

    // -----------------------------------------------------------------
    // Inline player
    // -----------------------------------------------------------------

    /**
     * Replace the right-pane preview placeholder with an inline
     * VideoPlayerFragment for the given recording. Uses a soft fade
     * (300ms in / 200ms out) for a non-jarring swap between recordings.
     *
     * Only called on landscape — guarded by [isLandscape] in
     * [attachLibraryFragment]. The target container only exists in
     * `layout-land/fragment_recordings.xml`, so this is also a structural
     * safeguard against accidental portrait invocation.
     */
    private fun showInlinePreview(recording: RecordingFile) {
        val view = view ?: return
        if (view.findViewById<View>(R.id.previewContainer) == null) return

        currentInlineIndex = currentPlaylist.indexOfFirst { it.path == recording.path }
        // Build a parallel string playlist for the player (paths only).
        val paths = currentPlaylist.map { it.path }.toTypedArray()
        val titles = currentPlaylist.map { it.name }.toTypedArray()

        val player = VideoPlayerFragment().apply {
            arguments = Bundle().apply {
                putString(VideoPlayerFragment.ARG_VIDEO_PATH, recording.path)
                putString(VideoPlayerFragment.ARG_VIDEO_TITLE, recording.name)
                putBoolean(VideoPlayerFragment.ARG_INLINE, true)
                putStringArray(VideoPlayerFragment.ARG_PLAYLIST_PATHS, paths)
                putStringArray(VideoPlayerFragment.ARG_PLAYLIST_TITLES, titles)
                putInt(
                    VideoPlayerFragment.ARG_PLAYLIST_INDEX,
                    currentInlineIndex.coerceAtLeast(0)
                )
            }
            isFullscreen = playerFullscreen
            onFullscreenToggle = { wantFullscreen ->
                setPlayerFullscreen(wantFullscreen)
            }
        }
        childFragmentManager.commit {
            setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            replace(R.id.previewContainer, player, TAG_INLINE_PLAYER)
        }
    }

    /**
     * Re-attach host callbacks to a player that's already mounted (typical
     * after a configuration change). Without this, the maximize button on
     * the restored fragment would be hidden because the host's callback
     * reference was lost when the previous host view was destroyed.
     */
    private fun rebindActiveInlinePlayer() {
        val player = childFragmentManager
            .findFragmentByTag(TAG_INLINE_PLAYER) as? VideoPlayerFragment
            ?: return
        player.isFullscreen = playerFullscreen
        player.onFullscreenToggle = { wantFullscreen ->
            setPlayerFullscreen(wantFullscreen)
        }
        // Re-apply the host-side layout to whatever state we restored into.
        view?.let { applyFullscreenLayout(it, playerFullscreen, animate = false) }
    }

    /**
     * Authoritative entry point for swapping fullscreen state. Animates the
     * surrounding chrome away (or back), pushes the new state into the
     * player so its icon flips, and arms/disarms the back-press intercept.
     *
     * No-ops when not landscape, or when there's no active inline player —
     * fullscreen is meaningless without something to fill the screen with.
     */
    private fun setPlayerFullscreen(fullscreen: Boolean) {
        val rootView = view ?: return
        if (!isLandscape) return
        val player = childFragmentManager
            .findFragmentByTag(TAG_INLINE_PLAYER) as? VideoPlayerFragment
        if (fullscreen && player == null) return
        if (playerFullscreen == fullscreen) return

        playerFullscreen = fullscreen
        applyFullscreenLayout(rootView, fullscreen, animate = true)
        player?.isFullscreen = fullscreen
        fullscreenBackCallback.isEnabled = fullscreen
    }

    /**
     * Hide/show the hero header, filter strip, and library pane to give the
     * preview surface the full screen — or restore the two-pane layout.
     *
     * Implementation: we GONE the chrome views (header + filter strip + the
     * library column + its spacer) so the remaining preview card stretches
     * naturally via the parent's match_parent constraints. A short alpha
     * crossfade smooths the swap; instant when [animate] is false (used
     * during configuration restore).
     *
     * The library column is collapsed by zeroing its weight rather than
     * GONE-ing the FragmentContainerView so its hosted fragment isn't
     * re-attached on collapse — list scroll position survives the toggle.
     */
    private fun applyFullscreenLayout(rootView: View, fullscreen: Boolean, animate: Boolean) {
        val header = rootView.findViewById<View>(R.id.heroHeader)
        val filterStrip = rootView.findViewById<View>(R.id.filterStrip)
        val library = rootView.findViewById<View>(R.id.libraryContainer)
        val spacer = rootView.findViewById<View>(R.id.paneSpacer)
        val twoPane = rootView.findViewById<View>(R.id.twoPaneBody)
        val previewCard = rootView.findViewById<com.google.android.material.card.MaterialCardView>(
            R.id.previewCard
        )
        // The chrome views only exist on the landscape layout; portrait
        // would have already short-circuited in [setPlayerFullscreen].
        if (header == null || filterStrip == null || library == null) return

        val animDuration = if (animate) 220L else 0L

        fun fadeAndToggle(v: View, show: Boolean) {
            v.animate().cancel()
            if (show) {
                v.visibility = View.VISIBLE
                if (animate) {
                    v.alpha = 0f
                    v.animate().alpha(1f).setDuration(animDuration).start()
                } else {
                    v.alpha = 1f
                }
            } else {
                if (animate) {
                    v.animate().alpha(0f).setDuration(animDuration)
                        .withEndAction { v.visibility = View.GONE }
                        .start()
                } else {
                    v.alpha = 0f
                    v.visibility = View.GONE
                }
            }
        }

        fadeAndToggle(header, !fullscreen)
        fadeAndToggle(filterStrip, !fullscreen)

        // Collapse the library column without detaching its fragment.
        val params = library.layoutParams as? LinearLayout.LayoutParams
        if (params != null) {
            params.weight = if (fullscreen) 0f else 11f
            params.width = 0
            library.layoutParams = params
        }
        spacer?.visibility = if (fullscreen) View.GONE else View.VISIBLE
        if (animate) {
            library.animate().alpha(if (fullscreen) 0f else 1f)
                .setDuration(animDuration).start()
        } else {
            library.alpha = if (fullscreen) 0f else 1f
        }

        // Push the preview to true edge-to-edge: drop the page padding +
        // card corner radius while fullscreen, restore both on collapse.
        if (twoPane != null) {
            val pad = if (fullscreen) 0 else
                resources.getDimensionPixelSize(R.dimen.page_padding_horizontal)
            val padBottom = if (fullscreen) 0 else
                resources.getDimensionPixelSize(R.dimen.page_padding_bottom)
            twoPane.setPadding(pad, twoPane.paddingTop, pad, padBottom)
        }
        previewCard?.radius = if (fullscreen) 0f else
            resources.getDimension(R.dimen.card_radius_hero)
    }

    // -----------------------------------------------------------------
    // Top-bar controls
    // -----------------------------------------------------------------

    private fun setupSegmentedControl(view: View) {
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.segmentedSource)
        when (currentSource) {
            Source.DASHCAM -> group.check(R.id.segmentDashcam)
            Source.SURVEILLANCE -> group.check(R.id.segmentSurveillance)
        }
        applyChipRowVisibility(view)
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentSource = when (checkedId) {
                R.id.segmentSurveillance -> Source.SURVEILLANCE
                else -> Source.DASHCAM
            }
            applyChipRowVisibility(view)
            // Reset button visibility depends on which segment we're on
            // (Dashcam = dashcamTypes, Surveillance = actor/severity).
            renderActiveFilterAffordances(view)
            libraryFragment?.let { applyAllFiltersTo(it) }
        }
    }

    /**
     * Per-segment chip row visibility:
     *  - Dashcam: only the Type row (Normal / Proximity) is visible. The
     *    What + Severity rows make no sense for continuous-record cam_*
     *    clips (no actor classification), so they're hidden.
     *  - Surveillance: What + Severity rows are visible, Type row is hidden.
     *
     * On the landscape layout there is no separate `rowWhatFilter` — the
     * What + Severity chips share a single combined row whose id is
     * `rowSeverityFilter`. The null-safe `findViewById` handles both cases.
     */
    private fun applyChipRowVisibility(view: View) {
        val isDashcam = currentSource == Source.DASHCAM
        view.findViewById<View>(R.id.rowTypeFilter)?.visibility =
            if (isDashcam) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.rowWhatFilter)?.visibility =
            if (isDashcam) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.rowSeverityFilter)?.visibility =
            if (isDashcam) View.GONE else View.VISIBLE
    }

    private fun setupSettingsAction(view: View) {
        view.findViewById<MaterialButton>(R.id.btnRecordingsSettings)?.setOnClickListener {
            val target = when (currentSource) {
                Source.DASHCAM -> R.id.recordingSettingsWebFragment
                Source.SURVEILLANCE -> R.id.surveillanceSettingsWebFragment
            }
            findNavController().navigate(target)
        }
    }

    private fun setupDateRow(view: View) {
        view.findViewById<MaterialCardView>(R.id.cardDateJump)?.setOnClickListener {
            showDatePicker()
        }
        view.findViewById<MaterialButton>(R.id.btnPrevDay)?.setOnClickListener {
            shiftSelectedDay(-1)
        }
        view.findViewById<MaterialButton>(R.id.btnNextDay)?.setOnClickListener {
            shiftSelectedDay(+1)
        }
        view.findViewById<ImageButton>(R.id.btnClearDate)?.setOnClickListener {
            // Drop the date narrowing without touching chip filters. Reset
            // is a "wipe everything" hammer; this is the surgical version
            // for the date dimension only.
            dateNarrowed = false
            updateDateHeader(view)
            renderActiveFilterAffordances(view)
            libraryFragment?.let { applyAllFiltersTo(it) }
        }
    }

    private fun setupChipFilters(view: View) {
        // WHAT (actor class) — multi-select. "Any" clears the row.
        val chipActorAny = view.findViewById<Chip>(R.id.chipActorAny)
        val chipPerson = view.findViewById<Chip>(R.id.chipActorPerson)
        val chipVehicle = view.findViewById<Chip>(R.id.chipActorVehicle)
        val chipBike = view.findViewById<Chip>(R.id.chipActorBike)
        val chipAnimal = view.findViewById<Chip>(R.id.chipActorAnimal)

        // SEVERITY — multi-select. "Any" clears the row.
        val chipSevAny = view.findViewById<Chip>(R.id.chipSevAny)
        val chipSevAlert = view.findViewById<Chip>(R.id.chipSevAlert)
        val chipSevCritical = view.findViewById<Chip>(R.id.chipSevCritical)

        // TYPE (Dashcam-only) — multi-select toggle. Empty selection = both.
        val chipTypeNormal = view.findViewById<Chip>(R.id.chipTypeNormal)
        val chipTypeProximity = view.findViewById<Chip>(R.id.chipTypeProximity)
        val typeMap = mapOf(
            chipTypeNormal to "NORMAL",
            chipTypeProximity to "PROXIMITY"
        )
        for ((chip, name) in typeMap) {
            chip?.setOnClickListener {
                if (dashcamTypes.contains(name)) dashcamTypes.remove(name)
                else dashcamTypes.add(name)
                syncChipChecks(view)
                onFiltersChanged(view)
            }
        }

        chipActorAny?.setOnClickListener {
            actorClassFilter.clear()
            syncChipChecks(view)
            onFiltersChanged(view)
        }
        val mapActor = mapOf(
            chipPerson to "person",
            chipVehicle to "vehicle",
            chipBike to "bike",
            chipAnimal to "animal"
        )
        for ((chip, name) in mapActor) {
            chip?.setOnClickListener {
                if (actorClassFilter.contains(name)) actorClassFilter.remove(name)
                else actorClassFilter.add(name)
                syncChipChecks(view)
                onFiltersChanged(view)
            }
        }

        chipSevAny?.setOnClickListener {
            severityFilter.clear()
            syncChipChecks(view)
            onFiltersChanged(view)
        }
        val mapSev = mapOf(
            chipSevAlert to "ALERT",
            chipSevCritical to "CRITICAL"
        )
        for ((chip, name) in mapSev) {
            chip?.setOnClickListener {
                if (severityFilter.contains(name)) severityFilter.remove(name)
                else severityFilter.add(name)
                syncChipChecks(view)
                onFiltersChanged(view)
            }
        }

        syncChipChecks(view)
    }

    private fun syncChipChecks(view: View) {
        view.findViewById<Chip>(R.id.chipActorAny)?.isChecked = actorClassFilter.isEmpty()
        view.findViewById<Chip>(R.id.chipActorPerson)?.isChecked = actorClassFilter.contains("person")
        view.findViewById<Chip>(R.id.chipActorVehicle)?.isChecked = actorClassFilter.contains("vehicle")
        view.findViewById<Chip>(R.id.chipActorBike)?.isChecked = actorClassFilter.contains("bike")
        view.findViewById<Chip>(R.id.chipActorAnimal)?.isChecked = actorClassFilter.contains("animal")
        view.findViewById<Chip>(R.id.chipSevAny)?.isChecked = severityFilter.isEmpty()
        view.findViewById<Chip>(R.id.chipSevAlert)?.isChecked = severityFilter.contains("ALERT")
        view.findViewById<Chip>(R.id.chipSevCritical)?.isChecked = severityFilter.contains("CRITICAL")
        view.findViewById<Chip>(R.id.chipTypeNormal)?.isChecked = dashcamTypes.contains("NORMAL")
        view.findViewById<Chip>(R.id.chipTypeProximity)?.isChecked = dashcamTypes.contains("PROXIMITY")
    }

    private fun setupResetButton(view: View) {
        view.findViewById<MaterialButton>(R.id.btnFilterReset)?.setOnClickListener {
            // Reset only clears CHIP filters. The date has its own clear-X
            // affordance on the date card — bundling them confused users
            // because the card kept reading "Today" while the list silently
            // showed every day after Reset.
            actorClassFilter.clear()
            severityFilter.clear()
            dashcamTypes.clear()
            syncChipChecks(view)
            onFiltersChanged(view)
        }
    }

    private fun renderActiveFilterAffordances(view: View) {
        val chipsActive = when (currentSource) {
            Source.DASHCAM -> dashcamTypes.isNotEmpty()
            Source.SURVEILLANCE -> actorClassFilter.isNotEmpty() || severityFilter.isNotEmpty()
        }
        // Reset only governs chip filters now — the date has its own clear-X
        // on the date card.
        view.findViewById<MaterialButton>(R.id.btnFilterReset)?.visibility =
            if (chipsActive) View.VISIBLE else View.GONE
    }

    private fun onFiltersChanged(view: View) {
        renderActiveFilterAffordances(view)
        libraryFragment?.let { applyAllFiltersTo(it) }
    }

    // -----------------------------------------------------------------
    // Date handling
    // -----------------------------------------------------------------

    private fun showDatePicker() {
        val constraints = CalendarConstraints.Builder()
            .setEnd(MaterialDatePicker.todayInUtcMilliseconds())
            .setValidator(DateValidatorPointBackward.now())
            .build()

        val selectedUtcMs = utcMidnightMillis(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            selectedDay
        )

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.recording_lib_pick_date)
            .setCalendarConstraints(constraints)
            .setSelection(selectedUtcMs)
            .build()

        picker.addOnPositiveButtonClickListener { utcMs ->
            val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = utcMs
            }
            calendar.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), 1)
            selectedDay = cal.get(Calendar.DAY_OF_MONTH)
            dateNarrowed = true
            view?.let { updateDateHeader(it) }
            view?.let { renderActiveFilterAffordances(it) }
            libraryFragment?.let { applyAllFiltersTo(it) }
        }

        picker.show(parentFragmentManager, "recording_lib_date_picker")
    }

    private fun utcMidnightMillis(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun shiftSelectedDay(delta: Int) {
        val cal = Calendar.getInstance().apply {
            set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), selectedDay)
            add(Calendar.DAY_OF_MONTH, delta)
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val candidate = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (candidate.after(today)) return

        calendar.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), 1)
        selectedDay = cal.get(Calendar.DAY_OF_MONTH)
        dateNarrowed = true
        view?.let { updateDateHeader(it) }
        view?.let { renderActiveFilterAffordances(it) }
        libraryFragment?.let { applyAllFiltersTo(it) }
    }

    private fun updateDateHeader(view: View) {
        val selectedCal = Calendar.getInstance().apply {
            set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), selectedDay, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val yesterday = (today.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }

        view.findViewById<TextView>(R.id.tvSelectedDate)?.text = when {
            !dateNarrowed -> getString(R.string.recording_lib_date_all_days)
            selectedCal.timeInMillis == today.timeInMillis ->
                getString(R.string.recording_lib_date_today)
            selectedCal.timeInMillis == yesterday.timeInMillis ->
                getString(R.string.recording_lib_date_yesterday)
            else -> dayHeaderFormat.format(Date(selectedCal.timeInMillis))
        }

        // Prev/next day chevrons only mean something while a single day is
        // selected. When the user is showing all days, hide them so they
        // can't accidentally jump into single-day mode by tapping ▶.
        view.findViewById<MaterialButton>(R.id.btnPrevDay)?.visibility =
            if (dateNarrowed) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.btnNextDay)?.let {
            it.visibility = if (dateNarrowed) View.VISIBLE else View.GONE
            it.isEnabled = selectedCal.timeInMillis < today.timeInMillis
            it.alpha = if (it.isEnabled) 1f else 0.4f
        }

        // Clear-X is only relevant while narrowed; same goes for the count
        // pill, which is meaningless when the list is all days.
        view.findViewById<ImageButton>(R.id.btnClearDate)?.visibility =
            if (dateNarrowed) View.VISIBLE else View.GONE
    }

    // -----------------------------------------------------------------
    // Counts (executor + post)
    // -----------------------------------------------------------------

    /**
     * Walk both recording directories on the metrics executor, then post
     * the totals back to the UI. Lifecycle-safe: the executor is shut
     * down in onDestroyView, and the UI post bails if the view is gone.
     */
    private fun refreshCounts() {
        val ctx = context ?: return
        val executor = metricsExecutor ?: return
        val viewRef = WeakReference(view)

        executor.execute {
            val today0 = startOfTodayMillis()
            // Use the unified scanner so the segment counts and the library
            // list always agree on what counts as a "Dashcam" vs "Surveillance"
            // recording (segmented continuations, legacy paths, etc).
            val all = RecordingScanner.scanRecordings(ctx)
            // Dashcam segment = continuous cam_* + proximity_* clips
            // (both come off the dashcam encoder).
            val dashcam = all.filter {
                it.type == RecordingFile.RecordingType.NORMAL ||
                    it.type == RecordingFile.RecordingType.PROXIMITY
            }
            val surveillance = all.filter { it.type == RecordingFile.RecordingType.SENTRY }
            val dashcamStats = aggregate(dashcam, today0)
            val surveillanceStats = aggregate(surveillance, today0)

            val totalCount = dashcamStats.total + surveillanceStats.total
            val totalToday = dashcamStats.today + surveillanceStats.today
            val totalBytes = dashcamStats.bytes + surveillanceStats.bytes

            val post = object : Runnable {
                override fun run() {
                    synchronized(pendingPosts) { pendingPosts.remove(this) }
                    val v = viewRef.get() ?: return
                    val activeCtx = v.context ?: return
                    val sizeText = Formatter.formatShortFileSize(activeCtx, totalBytes)

                    v.findViewById<TextView>(R.id.tvRecordingsSummary)?.text =
                        activeCtx.getString(
                            R.string.recordings_summary_format,
                            totalToday,
                            totalCount,
                            sizeText
                        )
                    v.findViewById<MaterialButton>(R.id.segmentDashcam)?.text =
                        activeCtx.getString(
                            R.string.recordings_segment_dashcam_count,
                            dashcamStats.total
                        )
                    v.findViewById<MaterialButton>(R.id.segmentSurveillance)?.text =
                        activeCtx.getString(
                            R.string.recordings_segment_surveillance_count,
                            surveillanceStats.total
                        )
                }
            }
            synchronized(pendingPosts) { pendingPosts.add(post) }
            mainHandler.post(post)
        }
    }

    /**
     * Aggregate stats across an already-scanned list of [RecordingFile]s:
     * total clip count, count of clips modified today, and summed byte size.
     */
    private fun aggregate(list: List<RecordingFile>, startOfTodayMillis: Long): DirStats {
        var total = 0
        var today = 0
        var bytes = 0L
        for (rec in list) {
            total++
            bytes += rec.sizeBytes
            if (rec.timestamp >= startOfTodayMillis) today++
        }
        return DirStats(total, today, bytes)
    }

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private data class DirStats(val total: Int, val today: Int, val bytes: Long)

    companion object {
        private const val KEY_SOURCE = "recordings_source"
        private const val KEY_ACTORS = "recordings_actor_classes"
        private const val KEY_SEVERITY = "recordings_severity"
        private const val KEY_DASHCAM_TYPES = "recordings_dashcam_types"
        private const val KEY_DATE_Y = "recordings_date_year"
        private const val KEY_DATE_M = "recordings_date_month"
        private const val KEY_DATE_D = "recordings_date_day"
        private const val KEY_DATE_NARROWED = "recordings_date_narrowed"
        private const val KEY_PLAYER_FULLSCREEN = "recordings_player_fullscreen"
        private const val TAG_INLINE_PLAYER = "inline_player"
    }
}
