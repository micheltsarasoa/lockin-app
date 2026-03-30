package com.lockin.filter

/**
 * Central content-filtering arbiter.
 *
 * API contract:
 *   Input:  A domain string (normalized, lowercase, no trailing dot)
 *   Output: FilterResult — ALLOW | BLOCK | FALLBACK_DNS
 *   Latency SLA: p99 < 10ms
 *
 * The three-tier lookup strategy:
 *   1. Bloom filter (RAM) — O(1), < 1ms. False negatives impossible.
 *      A bloom-miss means the domain is definitely NOT blocked → ALLOW immediately.
 *   2. SQLCipher DB — O(log N), < 5ms. Exact lookup to confirm bloom hit.
 *   3. Cloud DoH — ~50ms. Used for domains that are uncertain (not in local DB).
 *
 * Subdomain matching: "ads.example.com" is blocked if "ads.example.com" OR
 * "example.com" is in the blocklist.
 *
 * Allowlist wins: If a domain is in the parent-managed allowlist, it is always
 * returned as ALLOW regardless of blocklist status.
 */
interface FilterEngine {
    /**
     * Returns the filter verdict for a domain.
     * Must be called from a coroutine (suspends during DB/network access).
     */
    suspend fun verdict(domain: String): FilterResult

    /** Initializes the engine (loads bloom filter, opens DB). Must complete before verdict(). */
    suspend fun initialize()

    /** Returns true if the engine has been initialized and is ready to serve verdicts. */
    fun isReady(): Boolean
}
