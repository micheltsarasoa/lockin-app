package com.lockin.security

import com.lockin.test.FakeEncryptedPrefsStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class BruteForceGuardTest {

    private lateinit var store: FakeEncryptedPrefsStore
    private lateinit var guard: BruteForceGuard

    @Before
    fun setUp() {
        store = FakeEncryptedPrefsStore()
        guard = BruteForceGuard(store)
    }

    // -----------------------------------------------------------------------
    // Initial state
    // -----------------------------------------------------------------------

    @Test
    fun `fresh guard is not locked out`() {
        assertFalse(guard.isLockedOut())
    }

    @Test
    fun `fresh guard has zero failures`() {
        assertEquals(0, guard.failureCount())
    }

    @Test
    fun `fresh guard remaining lockout is zero`() {
        assertEquals(0.seconds, guard.remainingLockout())
    }

    // -----------------------------------------------------------------------
    // No lockout for first 3 failures
    // -----------------------------------------------------------------------

    @Test
    fun `1 failure does not lock out`() {
        guard.recordFailure()
        assertFalse(guard.isLockedOut())
    }

    @Test
    fun `2 failures do not lock out`() {
        repeat(2) { guard.recordFailure() }
        assertFalse(guard.isLockedOut())
    }

    @Test
    fun `3 failures do not lock out`() {
        repeat(3) { guard.recordFailure() }
        assertFalse(guard.isLockedOut())
    }

    // -----------------------------------------------------------------------
    // Lockout activates at the 4th failure
    // -----------------------------------------------------------------------

    @Test
    fun `4 failures trigger 30-second lockout`() {
        repeat(4) { guard.recordFailure() }
        assertTrue(guard.isLockedOut())
        assertTrue(guard.remainingLockout() > 0.seconds)
        assertTrue(guard.remainingLockout() <= 30.seconds)
    }

    @Test
    fun `5 failures trigger 2-minute lockout`() {
        repeat(5) { guard.recordFailure() }
        assertTrue(guard.isLockedOut())
        assertTrue(guard.remainingLockout() > 30.seconds)
    }

    @Test
    fun `6 failures trigger 10-minute lockout`() {
        repeat(6) { guard.recordFailure() }
        assertTrue(guard.isLockedOut())
        assertTrue(guard.remainingLockout() > 2.seconds * 60)
    }

    @Test
    fun `8 plus failures are capped at 1-hour lockout`() {
        repeat(10) { guard.recordFailure() }
        assertTrue(guard.isLockedOut())
        // Remaining should be at most 1 hour (+ 1s tolerance for test execution time)
        assertTrue(guard.remainingLockout() <= (3600 + 1).seconds)
    }

    // -----------------------------------------------------------------------
    // Failure count tracking
    // -----------------------------------------------------------------------

    @Test
    fun `failure count increments on each recordFailure`() {
        repeat(5) { i ->
            guard.recordFailure()
            assertEquals(i + 1, guard.failureCount())
        }
    }

    // -----------------------------------------------------------------------
    // recordSuccess resets everything
    // -----------------------------------------------------------------------

    @Test
    fun `recordSuccess after failures resets lockout`() {
        repeat(5) { guard.recordFailure() }
        assertTrue(guard.isLockedOut())
        guard.recordSuccess()
        assertFalse(guard.isLockedOut())
    }

    @Test
    fun `recordSuccess resets failure count to zero`() {
        repeat(5) { guard.recordFailure() }
        guard.recordSuccess()
        assertEquals(0, guard.failureCount())
    }

    @Test
    fun `recordSuccess clears remaining lockout`() {
        repeat(5) { guard.recordFailure() }
        guard.recordSuccess()
        assertEquals(0.seconds, guard.remainingLockout())
    }

    // -----------------------------------------------------------------------
    // Persistence: state survives reconstruction (same store)
    // -----------------------------------------------------------------------

    @Test
    fun `lockout state persists across guard reconstruction`() {
        repeat(5) { guard.recordFailure() }
        // Reconstruct with the same store (simulates app restart)
        val guard2 = BruteForceGuard(store)
        assertTrue(guard2.isLockedOut())
        assertEquals(5, guard2.failureCount())
    }

    @Test
    fun `success state persists across guard reconstruction`() {
        repeat(5) { guard.recordFailure() }
        guard.recordSuccess()
        val guard2 = BruteForceGuard(store)
        assertFalse(guard2.isLockedOut())
        assertEquals(0, guard2.failureCount())
    }
}
