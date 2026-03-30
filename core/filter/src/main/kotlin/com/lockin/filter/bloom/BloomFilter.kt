package com.lockin.filter.bloom

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Space-efficient probabilistic set using a bit-vector and multiple hash functions.
 *
 * Properties:
 *   - Zero false negatives: if a domain was added, mightContain() always returns true.
 *   - Configurable false positive rate (fpp).
 *   - Thread-safe reads; NOT thread-safe for concurrent writes.
 *     (Writes only happen during sync; use AtomicReference swap for hot-reload.)
 *
 * Hash strategy: Double-hashing with MurmurHash3. Two independent seeds produce
 * h1 and h2; then k probes use positions (h1 + i*h2) % bitCount.
 *
 * @param expectedInsertions Approximate number of items to be added.
 * @param fpp Desired false positive probability (e.g. 0.01 = 1%).
 */
class BloomFilter(
    val expectedInsertions: Long,
    val fpp: Double = 0.01,
) {
    companion object {
        private const val SERIALIZATION_VERSION = 1

        /** Compute optimal bit-count: m = -n * ln(p) / (ln2)^2 */
        fun optimalBitCount(n: Long, p: Double): Long =
            ceil(-n * ln(p) / (ln(2.0) * ln(2.0))).toLong()

        /** Compute optimal hash count: k = (m/n) * ln(2) */
        fun optimalHashCount(m: Long, n: Long): Int =
            maxOf(1, (m.toDouble() / n * ln(2.0)).roundToInt())
    }

    private val bitCount: Long = optimalBitCount(expectedInsertions, fpp)
    private val hashCount: Int = optimalHashCount(bitCount, expectedInsertions)
    // Round up to nearest Long boundary
    private val bits: LongArray = LongArray(((bitCount + 63) / 64).toInt())

    private var insertionCount: Long = 0

    /** Adds a domain string to the filter. */
    fun add(domain: String) {
        val (h1, h2) = hash(domain)
        for (i in 0 until hashCount) {
            val bitIndex = ((h1 + i.toLong() * h2) and Long.MAX_VALUE) % bitCount
            setBit(bitIndex)
        }
        insertionCount++
    }

    /**
     * Returns true if the domain MIGHT be in the set (could be a false positive).
     * Returns false if the domain is DEFINITELY NOT in the set.
     */
    fun mightContain(domain: String): Boolean {
        val (h1, h2) = hash(domain)
        for (i in 0 until hashCount) {
            val bitIndex = ((h1 + i.toLong() * h2) and Long.MAX_VALUE) % bitCount
            if (!getBit(bitIndex)) return false
        }
        return true
    }

    /** Number of items added. */
    fun insertionCount(): Long = insertionCount

    /** Approximate current false positive rate given actual insertions. */
    fun currentFpp(): Double {
        // (1 - e^(-k*n/m))^k
        val k = hashCount.toDouble()
        val n = insertionCount.toDouble()
        val m = bitCount.toDouble()
        return Math.pow(1 - Math.exp(-k * n / m), k)
    }

    /** Serializes the filter state to a DataOutputStream. */
    fun serialize(out: DataOutputStream) {
        out.writeInt(SERIALIZATION_VERSION)
        out.writeLong(expectedInsertions)
        out.writeDouble(fpp)
        out.writeLong(insertionCount)
        out.writeInt(bits.size)
        for (word in bits) out.writeLong(word)
    }

    private fun setBit(index: Long) {
        val wordIndex = (index / 64).toInt()
        val bitOffset = (index % 64).toInt()
        bits[wordIndex] = bits[wordIndex] or (1L shl bitOffset)
    }

    private fun getBit(index: Long): Boolean {
        val wordIndex = (index / 64).toInt()
        val bitOffset = (index % 64).toInt()
        return (bits[wordIndex] shr bitOffset) and 1L == 1L
    }

    /**
     * Double-hashing via MurmurHash3 (32-bit, two seeds).
     * Returns (h1, h2) as Longs (always non-negative after masking).
     */
    private fun hash(key: String): Pair<Long, Long> {
        val bytes = key.toByteArray(Charsets.UTF_8)
        val h1 = murmur3_32(bytes, seed = 0x9747b28cL.toInt()).toLong() and 0xFFFFFFFFL
        val h2 = murmur3_32(bytes, seed = 0x5a4bcde3L.toInt()).toLong() and 0xFFFFFFFFL
        return Pair(h1, h2)
    }

    /** MurmurHash3 (32-bit) — Austin Appleby's algorithm. */
    private fun murmur3_32(data: ByteArray, seed: Int): Int {
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593.toInt()
        var h = seed
        val len = data.size
        val nBlocks = len / 4

        for (i in 0 until nBlocks) {
            var k = data[i * 4].toInt() and 0xFF or
                    ((data[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((data[i * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((data[i * 4 + 3].toInt() and 0xFF) shl 24)
            k *= c1; k = k.rotateLeft(15); k *= c2
            h = h xor k; h = h.rotateLeft(13); h = h * 5 + 0xe6546b64.toInt()
        }

        var tail = 0
        val tailStart = nBlocks * 4
        when (len and 3) {
            3 -> tail = tail or ((data[tailStart + 2].toInt() and 0xFF) shl 16)
            2 -> tail = tail or ((data[tailStart + 1].toInt() and 0xFF) shl 8)
            1 -> tail = tail or (data[tailStart].toInt() and 0xFF)
        }
        if (len and 3 != 0) {
            tail *= c1; tail = tail.rotateLeft(15); tail *= c2; h = h xor tail
        }

        h = h xor len
        h = h xor (h ushr 16); h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13); h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h
    }

    companion object {
        /** Deserializes a BloomFilter from a DataInputStream. */
        fun deserialize(inp: DataInputStream): BloomFilter {
            val version = inp.readInt()
            check(version == SERIALIZATION_VERSION) { "Unknown bloom filter version: $version" }
            val expectedInsertions = inp.readLong()
            val fpp = inp.readDouble()
            val insertionCount = inp.readLong()
            val wordsCount = inp.readInt()
            val filter = BloomFilter(expectedInsertions, fpp)
            for (i in 0 until wordsCount) filter.bits[i] = inp.readLong()
            filter.insertionCount = insertionCount
            return filter
        }
    }
}
