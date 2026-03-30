package com.lockin.admin

import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper around [DevicePolicyManager] that exposes all Device Owner policies.
 *
 * Each method is a no-op if the app is not the device owner (safe for debug builds
 * and non-provisioned devices).
 *
 * All methods log their outcome for audit purposes.
 */
@Singleton
class DeviceOwnerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dpm: DevicePolicyManager,
    private val adminComponent: ComponentName,
) {
    companion object {
        private const val TAG = "DeviceOwner"

        // Packages that are explicitly allowed to remain active
        // (everything else is evaluated for suspension)
        private val ALWAYS_ALLOWED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",  // Partially — watchdog monitors it
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.phone",
            "com.android.dialer",
            "com.android.contacts",
            "com.android.mms",
        )

        // Packages to suspend — VPN bypass tools, alternative browsers, anonymizers
        private val PACKAGES_TO_SUSPEND = setOf(
            "org.torproject.android",      // Tor / Orbot
            "org.torproject.torbrowser",   // Tor Browser
            "com.cloudflare.onedotonedotonedot", // 1.1.1.1 (can configure custom DNS)
            "com.opera.browser",
            "com.opera.mini.native",
            "com.brave.browser",           // Has built-in VPN
            "com.protonvpn.android",
            "com.nordvpn.android",
            "com.expressvpn.vpn",
            "ch.protonvpn.android",
        )
    }

    /** Returns true if this app is currently the Device Owner. */
    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * Sets this app's VPN service as the always-on VPN with network lockdown.
     *
     * lockdown=true means NO network traffic flows if the VPN is not active.
     * This is the most important policy — it makes the VPN mandatory at the OS level.
     */
    fun setAlwaysOnVpn() {
        if (!isDeviceOwner()) return
        try {
            val vpnPackage = context.packageName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setAlwaysOnVpnPackage(adminComponent, vpnPackage, true)
                Log.i(TAG, "Always-on VPN (lockdown) set for $vpnPackage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set always-on VPN", e)
        }
    }

    /**
     * Suspends known VPN bypass and anonymizer packages.
     * Suspended apps cannot be launched and are hidden from recent apps.
     */
    fun suspendBypassApps() {
        if (!isDeviceOwner()) return
        try {
            val toSuspend = PACKAGES_TO_SUSPEND
                .filter { isPackageInstalled(it) }
                .toTypedArray()

            if (toSuspend.isNotEmpty()) {
                dpm.setPackagesSuspended(adminComponent, toSuspend, true)
                Log.i(TAG, "Suspended ${toSuspend.size} bypass apps: ${toSuspend.toList()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to suspend bypass apps", e)
        }
    }

    /**
     * Disables USB debugging (ADB).
     * Prevents `adb uninstall` and `adb shell` bypass attempts.
     */
    fun disableUsbDebugging() {
        if (!isDeviceOwner()) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
            Log.i(TAG, "USB debugging disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable USB debugging", e)
        }
    }

    /**
     * Blocks booting into Safe Mode.
     * Requires Android 9+ (API 28) for this restriction to take effect.
     * On Android 11+ (API 30), it is enforced at boot level.
     */
    fun blockSafeBoot() {
        if (!isDeviceOwner()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                Log.i(TAG, "Safe boot blocked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to block safe boot", e)
        }
    }

    /**
     * Enables Factory Reset Protection requiring a Google account to re-setup.
     * Requires Android 12+ (API 31).
     */
    fun enableFactoryResetProtection() {
        if (!isDeviceOwner()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val policy = FactoryResetProtectionPolicy.Builder()
                    .setFactoryResetProtectionEnabled(true)
                    .build()
                dpm.setFactoryResetProtectionPolicy(adminComponent, policy)
                Log.i(TAG, "Factory Reset Protection enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable FRP", e)
        }
    }

    /**
     * Blocks user from changing the system wallpaper (cosmetic hardening).
     */
    fun disableWallpaperChange() {
        if (!isDeviceOwner()) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SET_WALLPAPER)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable wallpaper change", e)
        }
    }

    /**
     * Disables the ability to add new user accounts.
     */
    fun disableAddUser() {
        if (!isDeviceOwner()) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            Log.i(TAG, "Add user disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable add user", e)
        }
    }

    /**
     * Enables the Accessibility Service via secure settings.
     * Used to re-enable the watchdog if it was disabled by the system.
     *
     * @param serviceComponent The full component name of the accessibility service
     */
    fun enableAccessibilityService(serviceComponent: String) {
        if (!isDeviceOwner()) return
        try {
            val currentEnabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            if (!currentEnabled.contains(serviceComponent)) {
                val newValue = if (currentEnabled.isEmpty()) serviceComponent
                else "$currentEnabled:$serviceComponent"
                dpm.setSecureSetting(
                    adminComponent,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newValue
                )
                Log.i(TAG, "Accessibility service re-enabled: $serviceComponent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable accessibility service", e)
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}
