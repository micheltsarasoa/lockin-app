package com.lockin.sync

import android.util.Log

/**
 * Parses hosts-format blocklists into a set of domains.
 *
 * Supported formats:
 *   0.0.0.0 example.com          # Standard adblock hosts format
 *   127.0.0.1 example.com        # Alternative format
 *   example.com                  # Domain-only format
 *   # This is a comment          # Skipped
 *   (blank lines)                # Skipped
 *
 * Filtering:
 *   - Skips localhost, broadcasthost, 0.0.0.0, local (always-valid domains)
 *   - Skips lines with invalid domain characters
 *   - Normalizes to lowercase
 *   - Trims trailing dots
 *   - Handles Windows CRLF line endings
 *
 * Performance: Processes ~1M lines in ~3-5 seconds on modern hardware.
 */
object HostsFileParser {

    private const val TAG = "HostsFileParser"

    private val ALWAYS_ALLOWED = setOf(
        "localhost", "broadcasthost", "local", "0.0.0.0", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
    )

    // Validates domain names: only letters, digits, hyphens, dots
    // Labels must not start/end with hyphen, total length <= 253
    private val VALID_DOMAIN_REGEX = Regex("""^[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9\-]{0,61}[a-z0-9])?)*$""")

    /**
     * Parses a hosts file string and returns a set of normalized domain names.
     *
     * @param content The raw content of the hosts file
     * @return Set of lowercase domain names ready for insertion into the blocklist DB
     */
    fun parse(content: String): Set<String> {
        val domains = mutableSetOf<String>()
        var lineCount = 0
        var skipCount = 0

        content.lineSequence().forEach { rawLine ->
            lineCount++
            val line = rawLine.trimEnd('\r').trim()

            // Skip empty lines and comments
            if (line.isEmpty() || line.startsWith('#')) return@forEach

            // Strip inline comments
            val withoutComment = line.substringBefore('#').trim()
            if (withoutComment.isEmpty()) return@forEach

            val parts = withoutComment.split(Regex("\\s+"))
            val domain: String = when {
                parts.size >= 2 -> {
                    // "0.0.0.0 domain.com" or "127.0.0.1 domain.com" format
                    parts[1].lowercase().trimEnd('.')
                }
                parts.size == 1 -> {
                    // "domain.com" format (domain only)
                    parts[0].lowercase().trimEnd('.')
                }
                else -> return@forEach
            }

            if (domain.isEmpty() || domain in ALWAYS_ALLOWED) {
                skipCount++
                return@forEach
            }

            if (domain.length > 253 || !VALID_DOMAIN_REGEX.matches(domain)) {
                skipCount++
                return@forEach
            }

            domains.add(domain)
        }

        Log.d(TAG, "Parsed $lineCount lines → ${domains.size} domains (skipped $skipCount)")
        return domains
    }
}
