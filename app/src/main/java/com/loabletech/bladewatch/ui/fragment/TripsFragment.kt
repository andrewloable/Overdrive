package net.bladewatch.app.ui.fragment

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import net.bladewatch.app.ui.fragment.trips.TripsController

class TripsFragment : Fragment() {

    private var controller: TripsController? = null

    /**
     * Back-press hook enabled only while the trip detail overlay is open.
     * Pressing back closes the detail and returns to the list; when the
     * overlay is closed the callback is disabled so normal nav resumes.
     */
    private val detailBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            controller?.onBackPressed()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val c = TripsController(requireContext())
        c.onDetailVisibilityChanged = { visible -> detailBackCallback.isEnabled = visible }
        controller = c
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, detailBackCallback)
        return c.view
    }

    override fun onResume() {
        super.onResume()
        controller?.onResume()
    }

    override fun onPause() {
        super.onPause()
        controller?.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        controller?.onDestroy()
        controller = null
    }
}
