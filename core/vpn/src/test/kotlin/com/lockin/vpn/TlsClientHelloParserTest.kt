package com.lockin.vpn

import com.lockin.vpn.packet.TlsClientHelloParser
import org.junit.Assert.*
import org.junit.Test

class TlsClientHelloParserTest {

    /**
     * Builds a minimal TLS ClientHello with a specified SNI.
     * This closely mirrors the wire format per RFC 5246 / RFC 6066.
     */
    private fun buildClientHello(sni: String?): ByteArray {
        val sniExtension: ByteArray = if (sni != null) {
            val sniBytes = sni.toByteArray(Charsets.US_ASCII)
            val nameLen = sniBytes.size
            val listLen = 1 + 2 + nameLen  // name_type(1) + name_len(2) + name
            val extDataLen = 2 + listLen   // list_len(2) + list

            byteArrayOf(
                0x00, 0x00,                              // Extension Type: SNI
                (extDataLen ushr 8).toByte(), extDataLen.toByte(),  // Ext length
                (listLen ushr 8).toByte(), listLen.toByte(),        // List length
                0x00,                                    // Name type: host_name
                (nameLen ushr 8).toByte(), nameLen.toByte(),        // Name length
                *sniBytes
            )
        } else byteArrayOf()

        val extensionsTotal = sniExtension.size
        val extLenPrefix = if (extensionsTotal > 0) byteArrayOf(
            (extensionsTotal ushr 8).toByte(), extensionsTotal.toByte()
        ) else byteArrayOf()

        val sessionId = byteArrayOf()  // empty
        val cipherSuites = byteArrayOf(0x00, 0x02, 0xC0.toByte(), 0x2B.toByte()) // len=2, one suite
        val compression = byteArrayOf(0x01, 0x00) // len=1, null compression

        val chBody = byteArrayOf(
            0x03, 0x03,                      // Client Version: TLS 1.2
            *ByteArray(32),                   // Random: 32 zero bytes
            sessionId.size.toByte(),          // Session ID Length: 0
            *sessionId,
            *cipherSuites,
            *compression,
            *extLenPrefix,
            *sniExtension
        )

        val chLen = chBody.size
        val handshake = byteArrayOf(
            0x01,                             // Handshake Type: ClientHello
            (chLen ushr 16).toByte(), (chLen ushr 8).toByte(), chLen.toByte(),
            *chBody
        )

        val recordLen = handshake.size
        return byteArrayOf(
            0x16,                             // Content Type: Handshake
            0x03, 0x01,                       // Legacy Version: TLS 1.0
            (recordLen ushr 8).toByte(), recordLen.toByte(),
            *handshake
        )
    }

    @Test
    fun `parse ClientHello with SNI returns domain`() {
        val data = buildClientHello("example.com")
        val result = TlsClientHelloParser.parse(data)
        assertEquals("example.com", result)
    }

    @Test
    fun `parse ClientHello without SNI returns null`() {
        val data = buildClientHello(null)
        val result = TlsClientHelloParser.parse(data)
        assertNull(result)
    }

    @Test
    fun `returns null for non-TLS packet`() {
        val data = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        val result = TlsClientHelloParser.parse(data)
        assertNull(result)
    }

    @Test
    fun `returns null for empty payload`() {
        val result = TlsClientHelloParser.parse(byteArrayOf())
        assertNull(result)
    }

    @Test
    fun `returns null for truncated TLS record`() {
        // Valid TLS record header but claims more data than present
        val data = byteArrayOf(0x16, 0x03, 0x01, 0x01, 0x00) // claims 256 bytes but has none
        val result = TlsClientHelloParser.parse(data)
        assertNull(result)
    }

    @Test
    fun `SNI is lowercased`() {
        val data = buildClientHello("Example.COM")
        val result = TlsClientHelloParser.parse(data)
        assertEquals("example.com", result)
    }
}
