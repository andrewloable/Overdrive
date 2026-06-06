package net.bladewatch.app.ui.fragment

import android.Manifest
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import net.bladewatch.app.ui.fragment.location.LocationGpsController
import net.bladewatch.app.ui.fragment.location.LocationLoading
import net.bladewatch.app.ui.fragment.location.LocationMapController
import net.bladewatch.app.ui.fragment.location.LocationPermissionMissing

class LocationFragment : Fragment() {

    private var mapController: LocationMapController? = null
    private var gpsController: LocationGpsController? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        gpsController?.onPermissionResult(granted)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val mc = LocationMapController(requireContext())
        mapController = mc
        mc.setBannerActionHandler { state ->
            when (state) {
                is LocationPermissionMissing -> requestLocationPermission()
                else -> {
                    gpsController?.stop()
                    gpsController?.start()
                }
            }
        }
        return mc.view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mc = mapController ?: return
        val gc = LocationGpsController(
            context = requireContext(),
            onStateChanged = { state ->
                if (!isAdded) return@LocationGpsController
                requireActivity().runOnUiThread {
                    mc.render(state, isNetworkAvailable())
                }
            },
        )
        gpsController = gc
        gc.start()
    }

    override fun onResume() {
        super.onResume()
        mapController?.onResume()
        gpsController?.refresh()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mapController?.onConfigurationChanged()
    }

    override fun onPause() {
        super.onPause()
        mapController?.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        gpsController?.stop()
        gpsController = null
        mapController?.onDestroy()
        mapController = null
    }

    private fun requestLocationPermission() {
        gpsController?.onPermissionPrompted()
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }
}
