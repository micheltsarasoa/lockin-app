package com.lockin.filter

import com.lockin.filter.bloom.BloomFilter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class BloomFilterTest {

    @Test
    fun `zero false negatives for 100k domains`() {
        val bf = BloomFilter(expectedInsertions = 100_000L, fpp = 0.01)
        val domains = (1..100_000).map { "domain$it.com" }
        domains.forEach { bf.add(it) }
        // Every added domain must return true
        domains.forEach { domain ->
            assertTrue("False negative for $domain", bf.mightContain(domain))
        }
    }

    @Test
    fun `false positive rate stays below 2 percent for 1000 non-added domains`() {
        val bf = BloomFilter(expectedInsertions = 100_000L, fpp = 0.01)
        (1..100_000).forEach { bf.add("blocked$it.com") }

        var falsePositives = 0
        val testSize = 1000
        (1..testSize).forEach { i ->
            if (bf.mightContain("notblocked$i.net")) falsePositives++
        }
        val fppActual = falsePositives.toDouble() / testSize
        assertTrue("FPP $fppActual exceeds 2%", fppActual < 0.02)
    }

    @Test
    fun `serialize and deserialize preserves contains results`() {
        val bf = BloomFilter(expectedInsertions = 1000L, fpp = 0.01)
        bf.add("example.com")
        bf.add("blocked.org")

        val baos = ByteArrayOutputStream()
        bf.serialize(DataOutputStream(baos))

        val bais = ByteArrayInputStream(baos.toByteArray())
        val restored = BloomFilter.deserialize(DataInputStream(bais))

        assertTrue(restored.mightContain("example.com"))
        assertTrue(restored.mightContain("blocked.org"))
        assertFalse(restored.mightContain("notadded.xyz"))
    }

    @Test
    fun `insertion count is tracked`() {
        val bf = BloomFilter(expectedInsertions = 1000L, fpp = 0.01)
        repeat(50) { bf.add("domain$it.com") }
        assertEquals(50L, bf.insertionCount())
    }

    @Test
    fun `empty filter never returns true`() {
        val bf = BloomFilter(expectedInsertions = 1000L, fpp = 0.01)
        assertFalse(bf.mightContain("example.com"))
    }
}
