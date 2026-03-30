package com.lockin.filter

/**
 * Result of a FilterEngine.verdict() call.
 *
 * - Allow: packet should be forwarded to the internet
 * - Block: packet should be dropped; synthesize NXDOMAIN (DNS) or RST (TCP)
 * - FallbackDns: domain not in local DB; forward DNS query to filtering resolver (1.1.1.3)
 */
sealed class FilterResult {
    object Allow : FilterResult()
    object Block : FilterResult()
    object FallbackDns : FilterResult()

    override fun toString(): String = when (this) {
        is Allow -> "ALLOW"
        is Block -> "BLOCK"
        is FallbackDns -> "FALLBACK_DNS"
    }
}
