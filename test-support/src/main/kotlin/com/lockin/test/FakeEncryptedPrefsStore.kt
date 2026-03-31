package com.lockin.test

import com.lockin.security.PrefsStore

/**
 * In-memory fake for [PrefsStore] for use in unit tests.
 *
 * Requires no Android context or EncryptedSharedPreferences.
 * All values are stored in plain hash maps, making it safe to use in pure JVM unit tests.
 */
class FakeEncryptedPrefsStore : PrefsStore {

    private val strings  = mutableMapOf<String, String>()
    private val ints     = mutableMapOf<String, Int>()
    private val longs    = mutableMapOf<String, Long>()
    private val booleans = mutableMapOf<String, Boolean>()

    override fun putString(key: String, value: String)           { strings[key] = value }
    override fun getString(key: String, default: String?)        = strings.getOrDefault(key, default)

    override fun putInt(key: String, value: Int)                 { ints[key] = value }
    override fun getInt(key: String, default: Int)               = ints.getOrDefault(key, default)

    override fun putLong(key: String, value: Long)               { longs[key] = value }
    override fun getLong(key: String, default: Long)             = longs.getOrDefault(key, default)

    override fun putBoolean(key: String, value: Boolean)         { booleans[key] = value }
    override fun getBoolean(key: String, default: Boolean)       = booleans.getOrDefault(key, default)

    override fun remove(key: String) {
        strings.remove(key); ints.remove(key); longs.remove(key); booleans.remove(key)
    }

    override fun contains(key: String) =
        strings.containsKey(key) || ints.containsKey(key) ||
        longs.containsKey(key)   || booleans.containsKey(key)

    fun clear() { strings.clear(); ints.clear(); longs.clear(); booleans.clear() }
}
