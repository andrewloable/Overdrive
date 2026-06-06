package com.loabletech.bladewatch.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.loabletech.bladewatch.R

/**
 * Integrations roll-up.
 *
 * The Telegram / ABRP / MQTT / BYD Cloud integration cards have been removed.
 * The fragment is retained as a valid navigation destination and simply
 * inflates its layout; there are currently no live integration statuses to
 * bind here.
 */
class IntegrationsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_integrations, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // No integration cards remain — nothing to wire up.
    }
}
