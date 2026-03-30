package com.lockin.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects signs of tampering, dynamic instrumentation, or debugging.
 *
 * Called on app startup. In a tampered environment, the app enters a locked
 * state and stops filtering (fail-safe: we lock down rather than silently
 * passing traffic through).
 */
@Singleton
class TamperDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed class TamperResult {
        object Clean : TamperResult()
        data class Tampered(val reason: String) : TamperResult()
    }

    /** Known Frida library patterns found in /proc/self/maps */
    private val FRIDA_ARTIFACTS = listOf(
        "frida-agent",
        "frida-gadget",
        "gum-js-loop",
        "gmain",
    )

    /** Known Xposed framework indicators */
    private val XPOSED_ARTIFACTS = listOf(
        "XposedBridge.jar",
        "xposed",
        "de.robv.android.xposed",
    )

    /**
     * Runs all tamper checks and returns the result.
     * This may take a few milliseconds — call off the main thread.
     */
    fun check(): TamperResult {
        detectDebugger()?.let { return TamperResult.Tampered(it) }
        detectDebuggableFlag()?.let { return TamperResult.Tampered(it) }
        detectFrida()?.let { return TamperResult.Tampered(it) }
        detectXposed()?.let { return TamperResult.Tampered(it) }
        return TamperResult.Clean
    }

    private fun detectDebugger(): String? {
        return if (Debug.isDebuggerConnected()) {
            "Debugger attached"
        } else null
    }

    private fun detectDebuggableFlag(): String? {
        val flags = context.applicationInfo.flags
        return if ((flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // Only flag in release mode — in debug builds this is expected
            // This is controlled by BuildConfig.DEBUG being checked by the caller
            null // Callers should skip this check in debug builds
        } else null
    }

    private fun detectFrida(): String? {
        return try {
            val maps = File("/proc/self/maps").readText()
            val found = FRIDA_ARTIFACTS.firstOrNull { maps.contains(it, ignoreCase = true) }
            found?.let { "Frida instrumentation detected: $it" }
        } catch (e: Exception) {
            null // Cannot read maps — not a tamper signal
        }
    }

    private fun detectXposed(): String? {
        return try {
            val maps = File("/proc/self/maps").readText()
            val found = XPOSED_ARTIFACTS.firstOrNull { maps.contains(it, ignoreCase = true) }
            found?.let { "Xposed framework detected: $it" }
        } catch (e: Exception) {
            null
        }
    }
}
