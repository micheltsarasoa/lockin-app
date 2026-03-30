package com.lockin.sync

import com.lockin.filter.bloom.BloomFilter
import com.lockin.filter.bloom.BloomFilterLoader
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the [BloomFilter] from a set of domains and hot-swaps it atomically.
 *
 * The bloom filter is rebuilt off the main thread (in WorkManager's background thread).
 * Once built, it is atomically swapped in via [AtomicReference] — the VPN packet
 * processing loop continues using the old filter until the swap completes, with no
 * locking required on the read path.
 *
 * After swapping, the new filter is persisted to disk for use after the next boot.
 */
@Singleton
class BloomFilterBuilder @Inject constructor(
    private val loader: BloomFilterLoader,
    private val bloomFilterRef: AtomicReference<BloomFilter>,
) {
    companion object {
        // Sized for ~1.5M domains with 1% FPP — gives headroom above current ~1M domain lists
        private const val EXPECTED_INSERTIONS = 1_500_000L
        private const val FALSE_POSITIVE_PROBABILITY = 0.01
    }

    /**
     * Rebuilds the bloom filter from the given domain set and atomically swaps it in.
     *
     * @param domains The complete set of domains to include in the filter
     */
    fun rebuild(domains: Set<String>) {
        val newFilter = BloomFilter(
            expectedInsertions = maxOf(EXPECTED_INSERTIONS, domains.size.toLong()),
            fpp = FALSE_POSITIVE_PROBABILITY,
        )

        domains.forEach { newFilter.add(it) }

        // Atomic swap — readers (VPN packet loop) see either old or new, never partial
        bloomFilterRef.set(newFilter)

        // Persist to disk for next boot
        loader.save(newFilter)
    }
}
