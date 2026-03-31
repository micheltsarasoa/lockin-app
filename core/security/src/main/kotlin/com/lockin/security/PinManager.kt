package com.lockin.security

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * Manages the master parental PIN.
 *
 * The PIN is never stored in plain text. It is hashed with Argon2id and stored
 * in EncryptedSharedPreferences (hardware-backed AES-256-GCM on supported devices).
 *
 * PIN requirements:
 *   - Minimum 6 characters
 *   - Not all same digit (e.g. 111111)
 *   - Not sequential ascending (e.g. 123456) or descending (e.g. 654321)
 */
@Singleton
class PinManager @Inject constructor(
    private val hasher: Argon2Hasher,
    private val store: PrefsStore,
    private val bruteForceGuard: BruteForceGuard,
) {
    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val MIN_PIN_LENGTH = 6
    }

    /** Returns true if a PIN has been set. */
    fun isPinSet(): Boolean = store.contains(KEY_PIN_HASH)

    /**
     * Sets (or replaces) the master PIN. Validates the PIN first.
     * @throws IllegalArgumentException if the PIN doesn't meet requirements.
     */
    suspend fun setPin(rawPin: String) {
        validatePin(rawPin)
        val hash = hasher.hash(rawPin)
        store.putString(KEY_PIN_HASH, hash)
        bruteForceGuard.recordSuccess() // reset any lockout state on PIN change
    }

    /**
     * Verifies a PIN attempt. Returns true if correct.
     * Enforces brute-force protection — always check [isLockedOut] first.
     */
    suspend fun verifyPin(rawPin: String): Boolean {
        if (bruteForceGuard.isLockedOut()) return false

        val storedHash = store.getString(KEY_PIN_HASH) ?: return false
        val correct = hasher.verify(rawPin, storedHash)

        if (correct) {
            bruteForceGuard.recordSuccess()
        } else {
            bruteForceGuard.recordFailure()
        }

        return correct
    }

    /** Returns true if the guard is currently locked out due to too many failures. */
    fun isLockedOut(): Boolean = bruteForceGuard.isLockedOut()

    /** Remaining lockout duration, or Duration.ZERO if not locked out. */
    fun remainingLockout(): Duration = bruteForceGuard.remainingLockout()

    /** Current failure count for UI display. */
    fun failureCount(): Int = bruteForceGuard.failureCount()

    /** Removes the stored PIN (requires current PIN verification before calling). */
    fun clearPin() = store.remove(KEY_PIN_HASH)

    private fun validatePin(pin: String) {
        require(pin.length >= MIN_PIN_LENGTH) {
            "PIN must be at least $MIN_PIN_LENGTH characters"
        }
        require(!isAllSameDigit(pin)) {
            "PIN must not be all the same digit (e.g. 111111)"
        }
        require(!isSequential(pin)) {
            "PIN must not be a sequential pattern (e.g. 123456 or 654321)"
        }
    }

    private fun isAllSameDigit(pin: String): Boolean =
        pin.all { it == pin[0] }

    private fun isSequential(pin: String): Boolean {
        if (pin.length < 4) return false
        val ascending = pin.zipWithNext().all { (a, b) -> b.code - a.code == 1 }
        val descending = pin.zipWithNext().all { (a, b) -> a.code - b.code == 1 }
        return ascending || descending
    }
}
