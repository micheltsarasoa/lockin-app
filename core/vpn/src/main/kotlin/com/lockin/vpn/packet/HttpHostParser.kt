package com.lockin.vpn.packet

/**
 * Extracts the Host header from plain HTTP requests (port 80).
 *
 * Only inspects the first ~4KB of the TCP payload (HTTP headers are typically
 * small). Does not attempt to reassemble fragmented HTTP requests.
 *
 * Port is stripped: "example.com:8080" → "example.com"
 */
object HttpHostParser {

    private const val MAX_SCAN_BYTES = 4096
    private val HOST_HEADER_REGEX = Regex(
        "(?i)^Host:\\s*([^\\r\\n:]+)(?::\\d+)?\\s*$",
        setOf(RegexOption.MULTILINE)
    )

    /**
     * Attempts to extract the HTTP Host header value from a TCP payload.
     *
     * @param tcpPayload Raw TCP segment data
     * @return The hostname (without port), or null if not found
     */
    fun parse(tcpPayload: ByteArray): String? {
        if (tcpPayload.isEmpty()) return null

        // Quick check: HTTP methods start with ASCII uppercase
        val firstByte = tcpPayload[0].toInt() and 0xFF
        if (firstByte < 'A'.code || firstByte > 'Z'.code) return null

        val scanLen = minOf(tcpPayload.size, MAX_SCAN_BYTES)
        val text = try {
            String(tcpPayload, 0, scanLen, Charsets.ISO_8859_1)
        } catch (e: Exception) {
            return null
        }

        val match = HOST_HEADER_REGEX.find(text) ?: return null
        return match.groupValues[1].trim().lowercase()
    }
}
