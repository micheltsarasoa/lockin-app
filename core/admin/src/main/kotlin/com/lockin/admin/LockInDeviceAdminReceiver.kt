package com.lockin.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin Receiver — the anchor of the entire device lockdown layer.
 *
 * Registration: `adb shell dpm set-device-owner com.lockin.app/.admin.LockInDeviceAdminReceiver`
 *
 * As a Device Owner, this app:
 *   - Cannot be uninstalled by the user
 *   - Can set system-level restrictions (USB debugging, Safe Boot, etc.)
 *   - Can force always-on VPN with network lockdown
 *   - Can suspend/hide other apps
 *
 * Security: [onDisableRequested] denies all deactivation attempts without parent PIN.
 * [onDisabled] should never fire in production — it means device owner was removed,
 * which requires a factory reset (mitigated by Factory Reset Protection policy).
 */
class LockInDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "LockInAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled — applying lockdown policies")
        // PolicyEnforcer will be triggered by the setup flow after PIN creation
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Disable requested — this should be blocked by the UI")
        // The Accessibility Service watchdog intercepts Settings > Security > Device Admin
        // before the user can reach the deactivation dialog. This is a last-resort message.
        return "LockIn cannot be disabled without the parent PIN. Please contact your parent."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // This should never happen in normal operation.
        // If it does, it means the device was factory reset and is being re-setup.
        Log.e(TAG, "CRITICAL: Device Admin was disabled. Lockdown lost.")
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.d(TAG, "Device password attempt failed")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.d(TAG, "Device password attempt succeeded")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.i(TAG, "Lock task mode entering for package: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.i(TAG, "Lock task mode exiting")
    }
}
