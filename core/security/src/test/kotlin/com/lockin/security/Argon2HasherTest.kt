package com.lockin.security

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class Argon2HasherTest {

    private lateinit var hasher: Argon2Hasher

    @Before
    fun setUp() {
        hasher = Argon2Hasher()
    }

    @Test
    fun `hash returns PHC format string`() {
        val hash = hasher.hash("correct-horse-battery")
        assertTrue(hash.startsWith("\$argon2id\$v=19\$"))
    }

    @Test
    fun `verify returns true for correct pin`() {
        val hash = hasher.hash("myPin123")
        assertTrue(hasher.verify("myPin123", hash))
    }

    @Test
    fun `verify returns false for wrong pin`() {
        val hash = hasher.hash("myPin123")
        assertFalse(hasher.verify("wrongPin", hash))
    }

    @Test
    fun `two hashes of same pin are different (different salts)`() {
        val hash1 = hasher.hash("samePin")
        val hash2 = hasher.hash("samePin")
        assertNotEquals(hash1, hash2)
        // But both should verify correctly
        assertTrue(hasher.verify("samePin", hash1))
        assertTrue(hasher.verify("samePin", hash2))
    }

    @Test
    fun `hash never stores raw pin`() {
        val rawPin = "secret1234"
        val hash = hasher.hash(rawPin)
        assertFalse(hash.contains(rawPin))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hash throws on empty pin`() {
        hasher.hash("")
    }

    @Test
    fun `verify returns false for malformed hash`() {
        assertFalse(hasher.verify("anyPin", "not-a-valid-hash"))
    }
}
