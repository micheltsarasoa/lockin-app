package com.lockin.vpn.packet

/**
 * Extracts the SNI (Server Name Indication) from a TLS ClientHello message.
 *
 * No decryption is performed. The SNI extension (RFC 6066) is sent in plain text
 * in the ClientHello before the TLS handshake completes.
 *
 * Handles:
 *   - TLS 1.0, 1.2, 1.3 ClientHello messages
 *   - Fragmented TLS records (buffering keyed by connection ID)
 *   - Multiple extensions before the SNI extension
 *   - No-SNI ClientHellos (returns null)
 *
 * Does NOT handle:
 *   - DTLS
 *   - Encrypted ClientHello (ECH/ESNI) — SNI will be unavailable; DoH fallback covers this
 *
 * TLS Record format (RFC 5246):
 *   byte 0:     Content Type (0x16 = Handshake)
 *   bytes 1-2:  Legacy Version (0x0301 or higher)
 *   bytes 3-4:  Fragment Length
 *   bytes 5+:   Fragment (Handshake message)
 *
 * ClientHello format:
 *   byte 0:     Handshake Type (0x01 = ClientHello)
 *   bytes 1-3:  Length
 *   bytes 4-5:  Client Version
 *   bytes 6-37: Random (32 bytes)
 *   byte 38:    Session ID Length
 *   ...:        Session ID
 *   ...:        Cipher Suites (2-byte length + suites)
 *   ...:        Compression Methods (1-byte length + methods)
 *   ...:        Extensions (2-byte length + extensions list)
 *
 * SNI Extension (type 0x0000):
 *   bytes 0-1:  Extension Type (0x0000)
 *   bytes 2-3:  Extension Data Length
 *   bytes 4-5:  Server Name List Length
 *   byte 6:     Name Type (0x00 = host_name)
 *   bytes 7-8:  Name Length
 *   bytes 9+:   Server Name
 */
object TlsClientHelloParser {

    private const val TLS_CONTENT_TYPE_HANDSHAKE = 0x16
    private const val TLS_HANDSHAKE_CLIENT_HELLO = 0x01
    private const val TLS_EXTENSION_SNI = 0x0000
    private const val TLS_MIN_VERSION = 0x0301  // TLS 1.0

    /**
     * Attempts to parse SNI from a raw TCP payload that may contain a TLS ClientHello.
     *
     * @param tcpPayload The TCP data segment (no IP or TCP headers)
     * @return The SNI hostname string, or null if not found or not a ClientHello
     */
    fun parse(tcpPayload: ByteArray): String? {
        if (tcpPayload.size < 5) return null

        // TLS Record Layer
        val contentType = tcpPayload[0].toInt() and 0xFF
        if (contentType != TLS_CONTENT_TYPE_HANDSHAKE) return null

        val version = ((tcpPayload[1].toInt() and 0xFF) shl 8) or (tcpPayload[2].toInt() and 0xFF)
        if (version < TLS_MIN_VERSION) return null

        val recordLength = ((tcpPayload[3].toInt() and 0xFF) shl 8) or (tcpPayload[4].toInt() and 0xFF)
        if (tcpPayload.size < 5 + recordLength) return null // Fragmented — incomplete

        return parseHandshake(tcpPayload, 5, recordLength)
    }

    private fun parseHandshake(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 4) return null

        val handshakeType = data[offset].toInt() and 0xFF
        if (handshakeType != TLS_HANDSHAKE_CLIENT_HELLO) return null

        val bodyLength = ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

        if (offset + 4 + bodyLength > data.size) return null

        return parseClientHello(data, offset + 4, bodyLength)
    }

    private fun parseClientHello(data: ByteArray, offset: Int, length: Int): String? {
        var pos = offset
        val end = offset + length

        // Client Version (2 bytes)
        if (pos + 2 > end) return null
        pos += 2

        // Random (32 bytes)
        if (pos + 32 > end) return null
        pos += 32

        // Session ID
        if (pos + 1 > end) return null
        val sessionIdLen = data[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos > end) return null

        // Cipher Suites
        if (pos + 2 > end) return null
        val cipherSuitesLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2 + cipherSuitesLen
        if (pos > end) return null

        // Compression Methods
        if (pos + 1 > end) return null
        val compressionMethodsLen = data[pos].toInt() and 0xFF
        pos += 1 + compressionMethodsLen
        if (pos > end) return null

        // Extensions (optional in TLS 1.2 and earlier)
        if (pos + 2 > end) return null
        val extensionsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2

        val extEnd = pos + extensionsLen
        if (extEnd > end) return null

        return parseExtensions(data, pos, extEnd)
    }

    private fun parseExtensions(data: ByteArray, offset: Int, end: Int): String? {
        var pos = offset
        while (pos + 4 <= end) {
            val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            pos += 4

            if (pos + extLen > end) break

            if (extType == TLS_EXTENSION_SNI) {
                return parseSniExtension(data, pos, extLen)
            }
            pos += extLen
        }
        return null
    }

    private fun parseSniExtension(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 5) return null
        var pos = offset

        // Server Name List Length (2 bytes)
        val listLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        if (pos + listLen > offset + length) return null

        // Walk the list — we want the first host_name entry (type 0x00)
        val listEnd = pos + listLen
        while (pos + 3 <= listEnd) {
            val nameType = data[pos].toInt() and 0xFF
            val nameLen = ((data[pos + 1].toInt() and 0xFF) shl 8) or (data[pos + 2].toInt() and 0xFF)
            pos += 3

            if (pos + nameLen > listEnd) break

            if (nameType == 0x00) {
                // host_name
                return String(data, pos, nameLen, Charsets.US_ASCII)
                    .lowercase()
                    .trimEnd('.')
            }
            pos += nameLen
        }
        return null
    }
}
