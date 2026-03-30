package com.lockin.test

/**
 * Fake DeviceOwnerManager for use in unit tests.
 * Records which policies were applied.
 */
class FakeDeviceOwnerManager {
    var isDeviceOwner: Boolean = false
    var alwaysOnVpnSet: Boolean = false
    var usbDebuggingDisabled: Boolean = false
    var safeBootBlocked: Boolean = false
    var factoryResetProtected: Boolean = false
    val suspendedPackages = mutableSetOf<String>()

    fun reset() {
        isDeviceOwner = false
        alwaysOnVpnSet = false
        usbDebuggingDisabled = false
        safeBootBlocked = false
        factoryResetProtected = false
        suspendedPackages.clear()
    }
}
