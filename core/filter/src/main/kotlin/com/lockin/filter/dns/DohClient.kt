package com.lockin.filter.dns

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DNS-over-HTTPS client using Cloudflare's family-safe resolver (1.1.1.3).
 *
 * 1.1.1.3 blocks malware AND adult content at the DNS level, providing an
 * additional layer of protection for domains not in the local blocklist.
 *
 * IMPORTANT: The underlying OkHttpClient must use a SocketFactory that calls
 * VpnService.protect() on every socket to avoid routing loops.
 *
 * Results are cached in an LRU map (max 5,000 entries) to reduce latency.
 */
@Singleton
class DohClient @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    companion object {
        private const val DOH_URL = "https://cloudflare-dns.com/dns-query"
        private const val CACHE_SIZE = 5_000
        private const val RCODE_NXDOMAIN = 3
        private const val RCODE_REFUSED = 5  // Cloudflare family filter returns REFUSED for blocked
    }

    // Thread-safe LRU cache
    private val cache: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
                size > CACHE_SIZE
        }
    )

    /**
     * Returns true if the domain should be BLOCKED according to Cloudflare 1.1.1.3.
     * Returns false (ALLOW) if the domain resolves normally or on any error
     * (fail-open for DoH to avoid breaking connectivity).
     */
    suspend fun isBlocked(domain: String): Boolean = withContext(Dispatchers.IO) {
        cache[domain]?.let { return@withContext it }

        return@withContext try {
            val url = DOH_URL.toHttpUrl().newBuilder()
                .addQueryParameter("name", domain)
                .addQueryParameter("type", "A")
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext false

            val json = JSONObject(body)
            val rcode = json.optInt("Status", -1)
            val blocked = rcode == RCODE_NXDOMAIN || rcode == RCODE_REFUSED

            cache[domain] = blocked
            blocked
        } catch (e: Exception) {
            false // Fail open — don't block on network errors
        }
    }

    /** Clears the in-memory cache. */
    fun clearCache() = cache.clear()
}
