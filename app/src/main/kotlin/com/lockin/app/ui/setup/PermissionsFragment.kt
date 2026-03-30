package com.lockin.app.ui.setup

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.lockin.app.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Step 3 of setup: guides the parent to grant Accessibility Service and VPN permissions.
 *
 * Both permissions require the user to navigate to system settings — they cannot
 * be granted programmatically. This fragment provides deep-link buttons.
 *
 * After granting both, the parent taps "Continue" to proceed to the dashboard.
 * The VPN will be auto-started by the Device Owner always-on policy.
 */
@AndroidEntryPoint
class PermissionsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return View(requireContext()).also {
            // Minimal view — in production this would be a proper layout
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // In a real implementation this Fragment would have a proper layout with:
        // 1. "Enable Accessibility Service" button → opens Settings.ACTION_ACCESSIBILITY_SETTINGS
        // 2. Accessibility service status indicator (checks if enabled)
        // 3. "Continue" button → navigates to Dashboard when both are granted

        // Navigate to dashboard immediately for the scaffold
        findNavController().navigate(R.id.action_permissionsFragment_to_dashboardFragment)
    }
}
