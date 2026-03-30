package com.lockin.filter.bloom

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and saves the BloomFilter from/to the app's internal storage.
 *
 * The bloom filter is stored at: [filesDir]/bloom.bin
 * Writes are atomic: new data is written to bloom.bin.tmp, then renamed.
 */
@Singleton
class BloomFilterLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val BLOOM_FILE = "bloom.bin"
        private const val BLOOM_TMP = "bloom.bin.tmp"

        /** Default empty filter used on first launch before any sync. */
        fun empty(): BloomFilter = BloomFilter(expectedInsertions = 1_000_000L, fpp = 0.01)
    }

    private val bloomFile: File get() = File(context.filesDir, BLOOM_FILE)
    private val bloomTmpFile: File get() = File(context.filesDir, BLOOM_TMP)

    /**
     * Loads the bloom filter from disk. Returns an empty filter if none exists.
     */
    fun load(): BloomFilter {
        if (!bloomFile.exists()) return empty()
        return try {
            DataInputStream(FileInputStream(bloomFile).buffered()).use { inp ->
                BloomFilter.deserialize(inp)
            }
        } catch (e: Exception) {
            // Corrupt file — return empty and let next sync rebuild it
            bloomFile.delete()
            empty()
        }
    }

    /**
     * Atomically saves the bloom filter to disk.
     * Safe to call while the existing filter is serving traffic.
     */
    fun save(filter: BloomFilter) {
        DataOutputStream(FileOutputStream(bloomTmpFile).buffered()).use { out ->
            filter.serialize(out)
        }
        // Atomic rename
        bloomTmpFile.renameTo(bloomFile)
    }

    /** Returns true if a bloom filter file exists on disk. */
    fun exists(): Boolean = bloomFile.exists()
}
