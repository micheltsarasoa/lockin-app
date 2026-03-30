package com.lockin.test

import com.lockin.filter.FilterEngine
import com.lockin.filter.FilterResult

/**
 * Fake FilterEngine for use in unit tests.
 * Allows tests to configure specific domains as blocked or allowed.
 */
class FakeFilterEngine : FilterEngine {

    private val blockedDomains = mutableSetOf<String>()
    private var ready = true

    fun blockDomain(domain: String) { blockedDomains.add(domain.lowercase()) }
    fun clearBlocklist() { blockedDomains.clear() }
    fun setReady(value: Boolean) { ready = value }

    override suspend fun verdict(domain: String): FilterResult {
        val normalized = domain.lowercase().trimEnd('.')
        // Check exact domain and all parent domains
        val variants = buildList {
            var d = normalized
            while (d.contains('.')) {
                add(d)
                d = d.substringAfter('.')
            }
        }
        return if (variants.any { it in blockedDomains }) {
            FilterResult.Block
        } else {
            FilterResult.Allow
        }
    }

    override suspend fun initialize() { /* no-op */ }
    override fun isReady(): Boolean = ready
}
