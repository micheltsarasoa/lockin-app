package com.lockin.security

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Enforces exponential backoff lockout after repeated PIN failures.
 *
 * Lockout schedule (cumulative failures → lockout duration):
 *   1–3  → no lockout
 *   4    → 30 seconds
 *   5    → 2 minutes
 *   6    → 10 minutes
 *   7    → 30 minutes
 *   8+   → 1 hour (capped)
 *
 * State persists in EncryptedSharedPreferences so it survives app restarts.
 */
@Singleton
class BruteForceGuard @Inject constructor(
    private val store: PrefsStore
) {
    companion object {
        private const val KEY_FAIL_COUNT = "brute_force_fail_count"
        private const val KEY_LOCKOUT_UNTIL = "brute_force_lockout_until"

        private val LOCKOUT_SCHEDULE = listOf(
            0,  // 1 failure  → no lockout
            0,  // 2 failures → no lockout
            0,  // 3 failures → no lockout
            30, // 4 failures → 30 seconds
            120, // 5 failures → 2 minutes
            600, // 6 failures → 10 minutes
            1800, // 7 failures → 30 minutes
            3600, // 8+ failures → 1 hour (max)
        )
    }

    /** Returns true if the guard is currently locked out. */
    fun isLockedOut(): Boolean {
        val lockoutUntil = store.getLong(KEY_LOCKOUT_UNTIL, 0L)
        return System.currentTimeMillis() < lockoutUntil
    }

    /** Returns remaining lockout duration, or Duration.ZERO if not locked out. */
    fun remainingLockout(): Duration {
        val lockoutUntil = store.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val remaining = lockoutUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining.milliseconds else Duration.ZERO
    }


    /** Call this when a PIN attempt fails. Applies the appropriate lockout. */
    fun recordFailure() {
        val failCount = store.getInt(KEY_FAIL_COUNT, 0) + 1
        store.putInt(KEY_FAIL_COUNT, failCount)

        val lockoutSeconds = LOCKOUT_SCHEDULE.getOrElse(failCount - 1) { LOCKOUT_SCHEDULE.last() }
        if (lockoutSeconds > 0) {
            val lockoutUntil = System.currentTimeMillis() + lockoutSeconds * 1000L
            store.putLong(KEY_LOCKOUT_UNTIL, lockoutUntil)
        }
    }

    /** Call this when a PIN attempt succeeds. Resets all counters. */
    fun recordSuccess() {
        store.putInt(KEY_FAIL_COUNT, 0)
        store.putLong(KEY_LOCKOUT_UNTIL, 0L)
    }

    /** Current failure count (for UI display). */
    fun failureCount(): Int = store.getInt(KEY_FAIL_COUNT, 0)

}
