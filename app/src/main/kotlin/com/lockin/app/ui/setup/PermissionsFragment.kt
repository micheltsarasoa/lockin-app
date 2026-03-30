package com.lockin.app.ui.setup

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.lockin.app.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * Step 3 of setup: guides the parent to grant Accessibility Service and VPN permissions.
 *
 * Both permissions cannot be granted programmatically — the user must navigate to system
 * settings. This fragment provides deep-link buttons and polls status on resume.
 *
 * Continue button is only enabled when both permissions are confirmed granted.
 */
@AndroidEntryPoint
class PermissionsFragment : Fragment() {

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvVpnStatus: TextView
    private lateinit var btnOpenAccessibility: Button
    private lateinit var btnRequestVpn: Button
    private lateinit var btnContinue: Button
    private lateinit var tvHint: TextView

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                updateStatuses()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_permissions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvAccessibilityStatus = view.findViewById(R.id.tvAccessibilityStatus)
        tvVpnStatus            = view.findViewById(R.id.tvVpnStatus)
        btnOpenAccessibility   = view.findViewById(R.id.btnOpenAccessibility)
        btnRequestVpn          = view.findViewById(R.id.btnRequestVpn)
        btnContinue            = view.findViewById(R.id.btnContinue)
        tvHint                 = view.findViewById(R.id.tvPermissionsHint)

        btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnRequestVpn.setOnClickListener {
            val intent = VpnService.prepare(requireContext())
            if (intent != null) {
                vpnPermissionLauncher.launch(intent)
            } else {
                // Already granted
                updateStatuses()
            }
        }

        btnContinue.setOnClickListener {
            findNavController().navigate(R.id.action_permissionsFragment_to_dashboardFragment)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatuses()
    }

    private fun updateStatuses() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val vpnGranted = VpnService.prepare(requireContext()) == null

        // Accessibility status
        if (accessibilityEnabled) {
            tvAccessibilityStatus.text = getString(R.string.accessibility_enabled)
            tvAccessibilityStatus.setTextColor(0xFF006400.toInt()) // dark green
        } else {
            tvAccessibilityStatus.text = getString(R.string.accessibility_not_enabled)
            tvAccessibilityStatus.setTextColor(0xFFCC0000.toInt())
        }

        // VPN status
        if (vpnGranted) {
            tvVpnStatus.text = getString(R.string.vpn_granted)
            tvVpnStatus.setTextColor(0xFF006400.toInt())
        } else {
            tvVpnStatus.text = getString(R.string.vpn_not_granted)
            tvVpnStatus.setTextColor(0xFFCC0000.toInt())
        }

        val allGranted = accessibilityEnabled && vpnGranted
        btnContinue.isEnabled = allGranted
        tvHint.text = if (allGranted) {
            getString(R.string.permissions_all_granted)
        } else {
            getString(R.string.permissions_incomplete)
        }
    }

    /**
     * Checks whether LockInAccessibilityService is listed in the enabled accessibility services
     * setting. This is the reliable way to verify service status without needing a service
     * connection.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${requireContext().packageName}/.accessibility.LockInAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabledServices) }
            .asSequence()
            .any { it.equals(serviceName, ignoreCase = true) }
    }
}
