package com.lockin.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.lockin.security.PinManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Secondary defense layer — intercepts navigation to sensitive system settings.
 *
 * When a child attempts to navigate to Settings > Apps, Settings > Security,
 * Settings > VPN, or similar restricted pages, this service:
 *   1. Immediately navigates back (global BACK action)
 *   2. Displays a PIN prompt overlay
 *   3. Only allows access if the parent enters the correct PIN
 *
 * The service monitors TYPE_WINDOW_STATE_CHANGED events across ALL packages.
 *
 * Reliability:
 *   - Uses Device Owner to re-enable itself if system auto-disables it
 *   - Has high event priority (notificationTimeout = 50ms)
 *   - Does not stop with task (stopWithTask=false in manifest)
 */
@AndroidEntryPoint
class LockInAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LockInA11y"

        // Grace window after PIN verification — parent can navigate freely for 60 seconds
        private const val PIN_VERIFIED_GRACE_MS = 60_000L
    }

    @Inject lateinit var pinManager: PinManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pinVerifiedAt: Long = 0L
    private var overlayManager: BlockedActivityOverlay? = null

    override fun onServiceConnected() {
        Log.i(TAG, "Accessibility service connected")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50L
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        overlayManager = BlockedActivityOverlay(this, pinManager) { pinVerified ->
            if (pinVerified) {
                pinVerifiedAt = System.currentTimeMillis()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Check if within grace window (parent recently verified PIN)
        if (isPinGraceActive()) return

        if (SettingsWatchdog.isSensitiveScreen(event)) {
            Log.i(TAG, "Sensitive screen detected: ${event.packageName} / ${event.className}")
            handleRestrictedNavigation()
        }
    }

    private fun handleRestrictedNavigation() {
        // Step 1: Immediately navigate back
        performGlobalAction(GLOBAL_ACTION_BACK)

        // Step 2: Show PIN overlay
        serviceScope.launch {
            overlayManager?.show()
        }
    }

    private fun isPinGraceActive(): Boolean {
        return pinVerifiedAt > 0 &&
                System.currentTimeMillis() - pinVerifiedAt < PIN_VERIFIED_GRACE_MS
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        overlayManager?.dismiss()
        serviceScope.cancel()
        super.onDestroy()
    }
}
