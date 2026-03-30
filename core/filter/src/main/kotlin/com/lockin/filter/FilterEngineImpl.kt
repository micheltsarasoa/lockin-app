package com.lockin.filter

import com.lockin.filter.bloom.BloomFilter
import com.lockin.filter.db.DomainDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FilterEngineImpl @Inject constructor(
    private val domainDao: DomainDao,
    private val bloomFilterRef: AtomicReference<BloomFilter>,
) : FilterEngine {

    private val initialized = AtomicBoolean(false)

    override suspend fun initialize() {
        // BloomFilter is loaded by FilterModule on app start via BloomFilterLoader.
        // DB is opened lazily by Room. Just mark ready.
        initialized.set(true)
    }

    override fun isReady(): Boolean = initialized.get()

    override suspend fun verdict(domain: String): FilterResult = withContext(Dispatchers.IO) {
        val normalized = domain.lowercase().trimEnd('.')
        if (normalized.isEmpty()) return@withContext FilterResult.Allow

        // Allowlist check first — parent-approved domains always pass
        if (domainDao.isAllowlisted(normalized)) return@withContext FilterResult.Allow

        // Generate domain variants for subdomain matching:
        // "ads.example.com" → ["ads.example.com", "example.com"]
        // (We don't check TLDs like "com" — they're never in the blocklist)
        val variants = domainVariants(normalized)
        val bloomFilter = bloomFilterRef.get()

        for (variant in variants) {
            // Bloom filter fast path — definite miss means ALLOW
            if (!bloomFilter.mightContain(variant)) continue

            // Bloom hit — do exact DB lookup to confirm
            if (domainDao.isBlocked(variant)) return@withContext FilterResult.Block
        }

        FilterResult.Allow
    }

    /**
     * Generates all meaningful domain variants for subdomain blocking.
     * Stops at second-level domain (never checks bare TLDs).
     *
     * Example: "a.b.example.com" → ["a.b.example.com", "b.example.com", "example.com"]
     */
    private fun domainVariants(domain: String): List<String> {
        val variants = mutableListOf<String>()
        var current = domain
        while (true) {
            variants.add(current)
            val dotIndex = current.indexOf('.')
            if (dotIndex == -1) break
            val parent = current.substring(dotIndex + 1)
            // Don't add bare TLDs (no dot means it's just "com", "net", etc.)
            if (!parent.contains('.')) break
            current = parent
        }
        return variants
    }
}
