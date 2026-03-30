package com.lockin.accessibility

import android.view.accessibility.AccessibilityEvent

/**
 * Detects when the child navigates to sensitive Settings sections.
 *
 * Monitors window state changes. When a blocked component is detected
 * (and the parent PIN has not been recently verified), the caller should:
 *   1. Navigate back immediately
 *   2. Show the PIN prompt overlay
 *   3. Log the bypass attempt
 */
object SettingsWatchdog {

    /**
     * Package/activity combinations that should be blocked without parent PIN.
     * Checked against [AccessibilityEvent.getPackageName] and [AccessibilityEvent.getClassName].
     */
    private val BLOCKED_PACKAGES = setOf(
        "com.android.settings",
        "com.samsung.android.settings",  // Samsung
        "com.miui.securitycenter",       // MIUI
        "com.huawei.systemmanager",      // HUAWEI
        "com.oneplus.settings",          // OnePlus
    )

    /**
     * Activity class name fragments that indicate a sensitive settings page.
     * Matched as substrings (case-insensitive) of the full class name.
     */
    private val BLOCKED_ACTIVITY_FRAGMENTS = listOf(
        "AppDetailSettings",    // App info page (Force Stop, Uninstall)
        "ApplicationSettings",  // App management
        "InstalledAppDetails",  // Alternative app info
        "SecuritySettings",     // Security settings
        "NetworkSettings",      // Network/VPN settings
        "WifiSettings",         // Wi-Fi (can toggle Wi-Fi)
        "VpnSettings",          // VPN configuration
        "AccessibilitySettings", // Accessibility (can disable watchdog)
        "DevelopmentSettings",  // Developer options
        "ResetDashboardFragment", // Factory reset
        "FactoryReset",
        "ManageApplications",   // App list
        "DeviceAdminSettings",  // Device admin (can remove admin)
        "TrustedCredentials",   // Cert management
        "PrivateDnsSettings",   // Private DNS (DoT bypass)
    )

    /** VPN-related activity fragments — most critical to block */
    private val VPN_ACTIVITY_FRAGMENTS = listOf(
        "VpnDialog",
        "ConfirmDialog",        // com.android.vpndialogs
        "VpnSettings",
        "AlwaysOnVpnFragment",
    )

    /** VPN-related packages */
    private val VPN_PACKAGES = setOf(
        "com.android.vpndialogs",
    )

    /**
     * Returns true if this accessibility event represents navigation to a blocked screen.
     *
     * @param event The accessibility event to evaluate
     * @return true if the event indicates a restricted screen was opened
     */
    fun isSensitiveScreen(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false

        val pkg = event.packageName?.toString() ?: return false
        val cls = event.className?.toString() ?: return false

        // Block VPN consent dialogs — these appear when a VPN app starts
        if (pkg in VPN_PACKAGES) return true
        if (VPN_ACTIVITY_FRAGMENTS.any { cls.contains(it, ignoreCase = true) }) return true

        // Block sensitive Settings pages
        if (pkg in BLOCKED_PACKAGES) {
            if (BLOCKED_ACTIVITY_FRAGMENTS.any { cls.contains(it, ignoreCase = true) }) return true
        }

        return false
    }

    /**
     * Returns true if this event is specifically the app detail page for LockIn itself.
     * This is the most common bypass attempt — navigating to App Info to Force Stop.
     *
     * @param event The accessibility event
     * @param lockInPackage The package name of the LockIn app (e.g. "com.lockin.app")
     */
    fun isLockInAppDetailPage(event: AccessibilityEvent, lockInPackage: String): Boolean {
        val pkg = event.packageName?.toString() ?: return false
        val cls = event.className?.toString() ?: return false
        return pkg in BLOCKED_PACKAGES &&
                cls.contains("AppDetail", ignoreCase = true) &&
                event.text.any { it.contains(lockInPackage, ignoreCase = true) }
    }
}
