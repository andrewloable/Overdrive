package net.bladewatch.app.ui.fragment

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.bladewatch.app.ui.fragment.performance.PerformanceController

class PerformanceFragment : Fragment() {

    private var controller: PerformanceController? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val c = PerformanceController(requireContext())
        controller = c
        return c.view
    }

    override fun onResume() { super.onResume(); controller?.onResume() }
    override fun onPause() { super.onPause(); controller?.onPause() }

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
