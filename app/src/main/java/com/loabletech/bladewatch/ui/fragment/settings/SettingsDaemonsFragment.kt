package com.overdrive.app.ui.fragment.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import com.overdrive.app.ui.fragment.DaemonsFragment

/**
 * Settings → Daemons pane.
 *
 * Embeds the existing [DaemonsFragment] inside the sub-rail's detail
 * pane. We don't reimplement the daemons UI; we just host the canonical
 * fragment via the child fragment manager so all its existing wiring
 * (start/stop, logs, dialogs) keeps working unchanged.
 */
class SettingsDaemonsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
        childFragmentManager.commitNow {
            replace(HOST_ID, DaemonsFragment(), TAG_DAEMONS)
        }
    }

    companion object {
        private const val HOST_ID = 0x0F100003
        private const val TAG_DAEMONS = "settings_daemons_inner"
    }
}
