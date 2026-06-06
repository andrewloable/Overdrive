package net.bladewatch.app.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import net.bladewatch.app.ui.fragment.liveview.LiveViewController

class LiveViewFragment : Fragment() {

    private var viewController: LiveViewController? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val vc = LiveViewController(requireContext())
        viewController = vc
        return vc.view
    }

    override fun onResume() {
        super.onResume()
        viewController?.onResume()
    }

    override fun onPause() {
        super.onPause()
        viewController?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewController?.onDestroy()
        viewController = null
    }
}
