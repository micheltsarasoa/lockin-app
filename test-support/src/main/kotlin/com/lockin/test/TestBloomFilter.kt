package com.lockin.test

import com.lockin.filter.bloom.BloomFilter

/**
 * Pre-populated BloomFilter for use in tests.
 */
object TestBloomFilter {

    /** Creates a BloomFilter pre-populated with the given domains. */
    fun withDomains(vararg domains: String): BloomFilter {
        val bf = BloomFilter(expectedInsertions = maxOf(domains.size.toLong(), 1000L), fpp = 0.01)
        domains.forEach { bf.add(it.lowercase()) }
        return bf
    }

    /** Creates an empty BloomFilter sized for tests. */
    fun empty(): BloomFilter = BloomFilter(expectedInsertions = 1000L, fpp = 0.01)
}
