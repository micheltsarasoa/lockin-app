package com.lockin.security

import com.lockin.test.FakeEncryptedPrefsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [PinManager].
 *
 * [Argon2Hasher] is mocked to avoid Bouncy Castle / Base64 setup;
 * crypto correctness is tested separately in [Argon2HasherTest].
 *
 * [EncryptedPrefsStore] is replaced with [FakeEncryptedPrefsStore] — a pure
 * JVM in-memory implementation that requires no Android context.
 */
class PinManagerTest {

    private lateinit var store: FakeEncryptedPrefsStore
    private lateinit var hasher: Argon2Hasher
    private lateinit var bruteForceGuard: BruteForceGuard
    private lateinit var pinManager: PinManager

    @Before
    fun setUp() {
        store = FakeEncryptedPrefsStore()
        hasher = mockk()
        bruteForceGuard = BruteForceGuard(store)
        pinManager = PinManager(hasher, store, bruteForceGuard)
    }

    // -----------------------------------------------------------------------
    // isPinSet
    // -----------------------------------------------------------------------

    @Test
    fun `isPinSet returns false initially`() {
        assertFalse(pinManager.isPinSet())
    }

    @Test
    fun `isPinSet returns true after setPin`() = runTest {
        coEvery { hasher.hash(any()) } returns "fake_hash"
        pinManager.setPin("secure123")
        assertTrue(pinManager.isPinSet())
    }

    // -----------------------------------------------------------------------
    // PIN validation — setPin throws on invalid input
    // -----------------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `setPin throws for PIN shorter than 6 chars`() = runTest {
        pinManager.setPin("12345")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setPin throws for all-same-digit PIN`() = runTest {
        pinManager.setPin("111111")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setPin throws for ascending sequential PIN`() = runTest {
        pinManager.setPin("123456")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setPin throws for descending sequential PIN`() = runTest {
        pinManager.setPin("654321")
    }

    @Test
    fun `setPin accepts 6-digit non-sequential non-uniform PIN`() = runTest {
        coEvery { hasher.hash(any()) } returns "fake_hash"
        pinManager.setPin("193847")  // no exception expected
    }

    @Test
    fun `setPin accepts alphanumeric PIN`() = runTest {
        coEvery { hasher.hash(any()) } returns "fake_hash"
        pinManager.setPin("Ab1!Xy")
    }

    @Test
    fun `setPin accepts 4-char non-sequential PIN (below 4 sequential threshold)`() = runTest {
        // isSequential returns false for length < 4, so short sequences like "ab" are fine
        // but our min length is 6, so a 4-char PIN would throw due to length, not sequential check
        coEvery { hasher.hash(any()) } returns "fake_hash"
        try {
            pinManager.setPin("abcd")  // 4 chars — should throw for length
            fail("Expected IllegalArgumentException for short PIN")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("6") == true)
        }
    }

    // -----------------------------------------------------------------------
    // setPin stores hashed value, not raw PIN
    // -----------------------------------------------------------------------

    @Test
    fun `setPin stores hash not raw PIN`() = runTest {
        val rawPin = "secret1"
        coEvery { hasher.hash(rawPin) } returns "HASHED_VALUE"

        pinManager.setPin(rawPin)

        val stored = store.getString("pin_hash")
        assertEquals("HASHED_VALUE", stored)
        assertNotEquals(rawPin, stored)
    }

    @Test
    fun `setPin calls hasher with exact raw PIN`() = runTest {
        val rawPin = "mypin99"
        val captured = slot<String>()
        coEvery { hasher.hash(capture(captured)) } returns "hash"

        pinManager.setPin(rawPin)

        assertEquals(rawPin, captured.captured)
    }

    // -----------------------------------------------------------------------
    // verifyPin
    // -----------------------------------------------------------------------

    @Test
    fun `verifyPin returns true for correct PIN`() = runTest {
        coEvery { hasher.hash("correct") } returns "stored_hash"
        coEvery { hasher.verify("correct", "stored_hash") } returns true
        pinManager.setPin("correct")

        assertTrue(pinManager.verifyPin("correct"))
    }

    @Test
    fun `verifyPin returns false for wrong PIN`() = runTest {
        coEvery { hasher.hash("correct") } returns "stored_hash"
        coEvery { hasher.verify("wrong!!", "stored_hash") } returns false
        pinManager.setPin("correct")

        assertFalse(pinManager.verifyPin("wrong!!"))
    }

    @Test
    fun `verifyPin returns false when no PIN is set`() = runTest {
        assertFalse(pinManager.verifyPin("anything"))
    }

    @Test
    fun `verifyPin returns false when locked out`() = runTest {
        coEvery { hasher.hash(any()) } returns "hash"
        pinManager.setPin("correct1")

        // Trigger lockout by recording 5 failures directly
        repeat(5) { bruteForceGuard.recordFailure() }

        // verifyPin should short-circuit and return false without calling hasher.verify
        assertFalse(pinManager.verifyPin("correct1"))
        coVerify(exactly = 0) { hasher.verify(any(), any()) }
    }

    @Test
    fun `verifyPin increments failure count on wrong attempt`() = runTest {
        coEvery { hasher.hash("correct1") } returns "hash"
        coEvery { hasher.verify("wrong!!!", "hash") } returns false
        pinManager.setPin("correct1")

        pinManager.verifyPin("wrong!!!")

        assertEquals(1, pinManager.failureCount())
    }

    @Test
    fun `verifyPin resets failure count on correct attempt`() = runTest {
        coEvery { hasher.hash("correct1") } returns "hash"
        coEvery { hasher.verify("wrong!!!", "hash") } returns false
        coEvery { hasher.verify("correct1", "hash") } returns true
        pinManager.setPin("correct1")

        repeat(3) { pinManager.verifyPin("wrong!!!") }
        assertEquals(3, pinManager.failureCount())

        pinManager.verifyPin("correct1")
        assertEquals(0, pinManager.failureCount())
    }

    // -----------------------------------------------------------------------
    // clearPin
    // -----------------------------------------------------------------------

    @Test
    fun `clearPin removes stored PIN`() = runTest {
        coEvery { hasher.hash(any()) } returns "hash"
        pinManager.setPin("secure12")
        assertTrue(pinManager.isPinSet())

        pinManager.clearPin()
        assertFalse(pinManager.isPinSet())
    }

    // -----------------------------------------------------------------------
    // isLockedOut / remainingLockout delegate to BruteForceGuard
    // -----------------------------------------------------------------------

    @Test
    fun `isLockedOut returns false initially`() {
        assertFalse(pinManager.isLockedOut())
    }

    @Test
    fun `remainingLockout returns zero initially`() {
        assertEquals(0, pinManager.remainingLockout().inWholeSeconds)
    }
}
