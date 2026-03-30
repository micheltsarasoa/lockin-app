package com.lockin.filter

import com.lockin.filter.bloom.BloomFilter
import com.lockin.filter.db.DomainDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class FilterEngineTest {

    private lateinit var domainDao: DomainDao
    private lateinit var bloomFilter: BloomFilter
    private lateinit var engine: FilterEngineImpl

    @Before
    fun setUp() {
        domainDao = mockk()
        bloomFilter = BloomFilter(expectedInsertions = 1000L, fpp = 0.01)
        engine = FilterEngineImpl(domainDao, AtomicReference(bloomFilter))
    }

    @Test
    fun `domain in blocklist returns BLOCK`() = runTest {
        bloomFilter.add("adult.com")
        coEvery { domainDao.isAllowlisted(any()) } returns false
        coEvery { domainDao.isBlocked("adult.com") } returns true

        assertEquals(FilterResult.Block, engine.verdict("adult.com"))
    }

    @Test
    fun `domain not in bloom filter returns ALLOW (fast path)`() = runTest {
        // Domain not added to bloom filter → fast path skip
        coEvery { domainDao.isAllowlisted(any()) } returns false

        assertEquals(FilterResult.Allow, engine.verdict("innocent.com"))
    }

    @Test
    fun `subdomain of blocked domain returns BLOCK`() = runTest {
        bloomFilter.add("example.com")
        coEvery { domainDao.isAllowlisted(any()) } returns false
        coEvery { domainDao.isBlocked("ads.example.com") } returns false
        coEvery { domainDao.isBlocked("example.com") } returns true

        assertEquals(FilterResult.Block, engine.verdict("ads.example.com"))
    }

    @Test
    fun `allowlisted domain returns ALLOW even if in blocklist`() = runTest {
        bloomFilter.add("allowme.com")
        coEvery { domainDao.isAllowlisted("allowme.com") } returns true

        assertEquals(FilterResult.Allow, engine.verdict("allowme.com"))
    }

    @Test
    fun `bloom false positive that is not in DB returns ALLOW`() = runTest {
        bloomFilter.add("false-positive.com")
        coEvery { domainDao.isAllowlisted(any()) } returns false
        coEvery { domainDao.isBlocked("false-positive.com") } returns false

        assertEquals(FilterResult.Allow, engine.verdict("false-positive.com"))
    }

    @Test
    fun `empty domain returns ALLOW`() = runTest {
        assertEquals(FilterResult.Allow, engine.verdict(""))
    }

    @Test
    fun `trailing dot is normalized`() = runTest {
        bloomFilter.add("example.com")
        coEvery { domainDao.isAllowlisted(any()) } returns false
        coEvery { domainDao.isBlocked("example.com") } returns true

        assertEquals(FilterResult.Block, engine.verdict("example.com."))
    }

    @Test
    fun `domain variant generation stops at second-level domain`() = runTest {
        // "a.b.example.com" should check "a.b.example.com", "b.example.com", "example.com"
        // but NOT "com" alone
        bloomFilter.add("example.com")
        coEvery { domainDao.isAllowlisted(any()) } returns false
        coEvery { domainDao.isBlocked("a.b.example.com") } returns false
        coEvery { domainDao.isBlocked("b.example.com") } returns false
        coEvery { domainDao.isBlocked("example.com") } returns true

        assertEquals(FilterResult.Block, engine.verdict("a.b.example.com"))
    }
}
