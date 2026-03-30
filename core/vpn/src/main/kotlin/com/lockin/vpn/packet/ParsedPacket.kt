package com.lockin.vpn.packet

/**
 * Sealed hierarchy representing the result of parsing a raw IP packet
 * from the TUN interface.
 *
 * Only packets relevant to content filtering are fully parsed.
 * Everything else is returned as [Unknown] and forwarded unchanged.
 */
sealed class ParsedPacket {

    /** UDP DNS query (port 53). Domain has been extracted from the QNAME. */
    data class DnsQuery(
        val transactionId: Short,
        val domain: String,
        val rawBytes: ByteArray,
    ) : ParsedPacket()

    /**
     * TLS ClientHello with SNI extracted. No decryption occurs.
     * destIp and destPort allow forwarding the packet after an ALLOW verdict.
     */
    data class TlsClientHello(
        val domain: String,
        val rawBytes: ByteArray,
        val destIp: Int,
        val destPort: Int,
    ) : ParsedPacket()

    /** Plain HTTP request. Host header has been extracted. */
    data class HttpRequest(
        val host: String,
        val rawBytes: ByteArray,
    ) : ParsedPacket()

    /** Any other packet — not inspected, will be forwarded as-is. */
    data class Unknown(val rawBytes: ByteArray) : ParsedPacket()
}
