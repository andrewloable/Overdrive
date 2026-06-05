package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import com.overdrive.app.ui.fragment.WebViewFragment

/**
 * Settings → Recording pane.
 *
 * Hosts a [WebViewFragment] pointed at the daemon's "/recording" page so
 * the existing web-based recording settings UI shows up inline in the
 * sub-rail's detail pane (no nav-graph navigation, no full-screen swap).
 *
 * The web page is the canonical source of truth for these settings —
 * we wrap it instead of reimplementing it natively.
 */
class SettingsRecordingFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Bare FrameLayout host — no XML needed.
        return FrameLayout(requireContext()).apply {
            id = HOST_ID
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(HOST_ID) != null) return
        val webFragment = WebViewFragment().apply {
            arguments = Bundle().apply {
                putString(WebViewFragment.ARG_PAGE_PATH, "/recording")
            }
        }
        childFragmentManager.commitNow {
            replace(HOST_ID, webFragment, TAG_WEB)
        }
    }

    companion object {
        // Stable host-id for the inner WebViewFragment.
        private const val HOST_ID = 0x0F100001
        private const val TAG_WEB = "settings_recording_web"
    }
}
