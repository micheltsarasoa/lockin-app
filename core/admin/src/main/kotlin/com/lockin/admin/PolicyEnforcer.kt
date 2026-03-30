package com.lockin.admin

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates all Device Owner policies.
 *
 * Called:
 *   1. During initial setup (after Device Owner is confirmed and PIN is set)
 *   2. On every boot via [BootReceiver]
 *   3. Periodically by [com.lockin.sync.BlocklistSyncWorker] as a health check
 */
@Singleton
class PolicyEnforcer @Inject constructor(
    private val deviceOwnerManager: DeviceOwnerManager,
) {
    companion object {
        private const val TAG = "PolicyEnforcer"
        private const val ACCESSIBILITY_SERVICE_COMPONENT =
            "com.lockin.app/com.lockin.accessibility.LockInAccessibilityService"
    }

    /**
     * Applies all Device Owner policies.
     * Safe to call multiple times — each policy is idempotent.
     */
    fun applyAllPolicies() {
        if (!deviceOwnerManager.isDeviceOwner()) {
            Log.w(TAG, "Not device owner — skipping policy enforcement")
            return
        }

        Log.i(TAG, "Applying all lockdown policies")

        // Layer 1: Network lockdown — most critical
        deviceOwnerManager.setAlwaysOnVpn()

        // Layer 2: Prevent ADB bypass
        deviceOwnerManager.disableUsbDebugging()

        // Layer 3: Prevent safe mode bypass
        deviceOwnerManager.blockSafeBoot()

        // Layer 4: Factory reset protection
        deviceOwnerManager.enableFactoryResetProtection()

        // Layer 5: Suspend bypass apps
        deviceOwnerManager.suspendBypassApps()

        // Layer 6: Additional user restrictions
        deviceOwnerManager.disableAddUser()

        // Layer 7: Ensure accessibility service watchdog is running
        deviceOwnerManager.enableAccessibilityService(ACCESSIBILITY_SERVICE_COMPONENT)

        Log.i(TAG, "All policies applied")
    }
}
