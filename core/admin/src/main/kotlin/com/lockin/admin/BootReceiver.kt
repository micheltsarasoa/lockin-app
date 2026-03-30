package com.lockin.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-enforces all Device Owner policies on every boot.
 *
 * Listens for:
 *   - BOOT_COMPLETED: Normal boot
 *   - LOCKED_BOOT_COMPLETED: Direct Boot (before credential unlock) — for faster protection
 *
 * This is a defensive measure: Device Owner policies should persist across reboots,
 * but this ensures they are re-applied in case of any system anomaly.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    @Inject lateinit var policyEnforcer: PolicyEnforcer

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                Log.i(TAG, "Boot completed — re-enforcing policies")
                policyEnforcer.applyAllPolicies()
            }
        }
    }
}
