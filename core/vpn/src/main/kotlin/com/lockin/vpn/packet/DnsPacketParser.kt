package com.lockin.vpn.packet

/**
 * Parses DNS wire-format queries (RFC 1035) to extract the queried domain name.
 *
 * Only processes DNS QUERY messages (QR bit = 0, OPCODE = QUERY).
 * Returns null for responses, invalid packets, or unsupported formats.
 *
 * Supports label compression (pointer bytes 0xC0..).
 * Guards against degenerate input: max label 63 bytes, max domain 253 chars,
 * max compression pointer depth 5.
 */
object DnsPacketParser {

    private const val DNS_HEADER_SIZE = 12
    private const val MAX_DOMAIN_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63
    private const val MAX_POINTER_DEPTH = 5
    private const val FLAG_QR = 0x8000.toShort()        // bit 15: query(0) / response(1)
    private const val FLAG_OPCODE_MASK = 0x7800.toShort() // bits 14-11

    /**
     * Parses a DNS UDP payload (without IP/UDP headers) and returns the queried domain,
     * or null if this is not a valid DNS query.
     *
     * @param payload The DNS message bytes (starting at transaction ID)
     * @return The fully-qualified domain name queried, or null
     */
    fun parse(payload: ByteArray): DnsParseResult? {
        if (payload.size < DNS_HEADER_SIZE) return null

        val transactionId = ((payload[0].toInt() and 0xFF) shl 8 or (payload[1].toInt() and 0xFF)).toShort()
        val flags = ((payload[2].toInt() and 0xFF) shl 8 or (payload[3].toInt() and 0xFF)).toShort()
        val qdCount = (payload[4].toInt() and 0xFF) shl 8 or (payload[5].toInt() and 0xFF)

        // Must be a query (QR=0), standard query (OPCODE=0), with at least one question
        if (flags.toInt() and 0x8000 != 0) return null  // it's a response
        if (flags.toInt() and 0x7800 != 0) return null  // non-standard opcode
        if (qdCount == 0) return null

        var offset = DNS_HEADER_SIZE
        val domain = extractDomain(payload, offset) ?: return null

        return DnsParseResult(transactionId, domain)
    }

    /**
     * Extracts a domain name from [data] starting at [startOffset].
     * Handles label compression pointers.
     */
    fun extractDomain(data: ByteArray, startOffset: Int): String? {
        val labels = mutableListOf<String>()
        var offset = startOffset
        var pointerDepth = 0
        var totalLength = 0

        while (offset < data.size) {
            val labelLen = data[offset].toInt() and 0xFF

            when {
                labelLen == 0 -> {
                    // End of domain name
                    break
                }
                labelLen and 0xC0 == 0xC0 -> {
                    // Compression pointer
                    if (offset + 1 >= data.size) return null
                    if (++pointerDepth > MAX_POINTER_DEPTH) return null
                    val pointerOffset = ((labelLen and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    if (pointerOffset >= data.size) return null
                    offset = pointerOffset
                    continue
                }
                labelLen > MAX_LABEL_LENGTH -> return null
                else -> {
                    // Normal label
                    if (offset + 1 + labelLen > data.size) return null
                    val label = String(data, offset + 1, labelLen, Charsets.US_ASCII)
                    totalLength += label.length + 1  // +1 for dot
                    if (totalLength > MAX_DOMAIN_LENGTH) return null
                    labels.add(label)
                    offset += 1 + labelLen
                }
            }
        }

        return if (labels.isEmpty()) null else labels.joinToString(".")
    }

    data class DnsParseResult(val transactionId: Short, val domain: String)
}
