package com.lockin.security

/**
 * Abstraction over a key-value store used by [BruteForceGuard] and [PinManager].
 *
 * Implemented by [EncryptedPrefsStore] in production.
 * Implemented by a simple in-memory fake in unit tests (no Android context required).
 */
interface PrefsStore {
    fun putString(key: String, value: String)
    fun getString(key: String, default: String? = null): String?

    fun putInt(key: String, value: Int)
    fun getInt(key: String, default: Int = 0): Int

    fun putLong(key: String, value: Long)
    fun getLong(key: String, default: Long = 0L): Long

    fun putBoolean(key: String, value: Boolean)
    fun getBoolean(key: String, default: Boolean = false): Boolean

    fun remove(key: String)
    fun contains(key: String): Boolean
}
