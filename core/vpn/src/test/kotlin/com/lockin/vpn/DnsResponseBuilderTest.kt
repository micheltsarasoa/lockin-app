package com.lockin.vpn

import com.lockin.vpn.packet.DnsResponseBuilder
import org.junit.Assert.*
import org.junit.Test

class DnsResponseBuilderTest {

    // Minimal valid DNS query payload for "example.com" A record
    private fun buildQuery(txId: Short = 0x1234): ByteArray {
        // Header (12 bytes) + QNAME + QTYPE + QCLASS
        val qname = byteArrayOf(
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
            'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0
        )
        return byteArrayOf(
            (txId.toInt() ushr 8).toByte(), txId.toByte(), // Transaction ID
            0x01, 0x00,                                      // Flags: RD=1
            0x00, 0x01,                                      // QDCOUNT=1
            0x00, 0x00,                                      // ANCOUNT=0
            0x00, 0x00,                                      // NSCOUNT=0
            0x00, 0x00,                                      // ARCOUNT=0
        ) + qname + byteArrayOf(0x00, 0x01, 0x00, 0x01)    // QTYPE=A, QCLASS=IN
    }

    // -----------------------------------------------------------------------
    // buildNxDomain
    // -----------------------------------------------------------------------

    @Test
    fun `buildNxDomain preserves transaction ID`() {
        val query = buildQuery(txId = 0x5A3C)
        val response = DnsResponseBuilder.buildNxDomain(query)!!

        assertEquals(0x5A.toByte(), response[0])
        assertEquals(0x3C.toByte(), response[1])
    }

    @Test
    fun `buildNxDomain sets QR=1 response bit`() {
        val response = DnsResponseBuilder.buildNxDomain(buildQuery())!!
        // Byte 2 flags: bit 7 = QR; 0x81 = QR=1, RD=1
        assertEquals(0x81.toByte(), response[2])
    }

    @Test
    fun `buildNxDomain sets RCODE=3 (NXDOMAIN)`() {
        val response = DnsResponseBuilder.buildNxDomain(buildQuery())!!
        // Byte 3 lower nibble = RCODE; 0x83 = RA=1, RCODE=3
        assertEquals(0x83.toByte(), response[3])
        assertEquals(3, response[3].toInt() and 0x0F)
    }

    @Test
    fun `buildNxDomain sets RA=1`() {
        val response = DnsResponseBuilder.buildNxDomain(buildQuery())!!
        // Byte 3 bit 7 = RA
        assertTrue((response[3].toInt() and 0x80) != 0)
    }

    @Test
    fun `buildNxDomain zeroes ANCOUNT`() {
        val response = DnsResponseBuilder.buildNxDomain(buildQuery())!!
        assertEquals(0, response[6].toInt())
        assertEquals(0, response[7].toInt())
    }

    @Test
    fun `buildNxDomain zeroes NSCOUNT`() {
        val response = DnsResponseBuilder.buildNxDomain(buildQuery())!!
        assertEquals(0, response[8].toInt())
        assertEquals(0, response[9].toInt())
    }

    @Test
    fun `buildNxDomain zeroes ARCOUNT`() {
        val response = DnsResponseBuilder.buildNxDomain(buildQuery())!!
        assertEquals(0, response[10].toInt())
        assertEquals(0, response[11].toInt())
    }

    @Test
    fun `buildNxDomain response is same size as query`() {
        val query = buildQuery()
        val response = DnsResponseBuilder.buildNxDomain(query)!!
        assertEquals(query.size, response.size)
    }

    @Test
    fun `buildNxDomain preserves QNAME verbatim`() {
        val query = buildQuery()
        val response = DnsResponseBuilder.buildNxDomain(query)!!
        // Bytes 12 onwards are the question section; should be unchanged
        assertArrayEquals(query.copyOfRange(12, query.size), response.copyOfRange(12, response.size))
    }

    @Test
    fun `buildNxDomain returns null for payload shorter than 12 bytes`() {
        assertNull(DnsResponseBuilder.buildNxDomain(ByteArray(11)))
        assertNull(DnsResponseBuilder.buildNxDomain(ByteArray(0)))
    }

    // -----------------------------------------------------------------------
    // buildZeroIpResponse
    // -----------------------------------------------------------------------

    @Test
    fun `buildZeroIpResponse response is larger than query`() {
        val query = buildQuery()
        val response = DnsResponseBuilder.buildZeroIpResponse(query, "example.com")!!
        // Answer section (16 bytes) appended
        assertEquals(query.size + 16, response.size)
    }

    @Test
    fun `buildZeroIpResponse sets ANCOUNT=1`() {
        val response = DnsResponseBuilder.buildZeroIpResponse(buildQuery(), "example.com")!!
        assertEquals(0, response[6].toInt())
        assertEquals(1, response[7].toInt())
    }

    @Test
    fun `buildZeroIpResponse sets RCODE=0 (NOERROR)`() {
        val response = DnsResponseBuilder.buildZeroIpResponse(buildQuery(), "example.com")!!
        assertEquals(0, response[3].toInt() and 0x0F)
    }

    @Test
    fun `buildZeroIpResponse answer contains 0_0_0_0`() {
        val query = buildQuery()
        val response = DnsResponseBuilder.buildZeroIpResponse(query, "example.com")!!
        // RDATA is the last 4 bytes of the 16-byte answer section
        val rdataOffset = query.size + 12
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0),
            response.copyOfRange(rdataOffset, rdataOffset + 4)
        )
    }

    @Test
    fun `buildZeroIpResponse answer TTL is 60 seconds`() {
        val query = buildQuery()
        val response = DnsResponseBuilder.buildZeroIpResponse(query, "example.com")!!
        // TTL is 4 bytes at answer offset 6 (after 2-byte name ptr + 2 type + 2 class)
        val ttlOffset = query.size + 6
        val ttl = ((response[ttlOffset].toInt() and 0xFF) shl 24) or
                  ((response[ttlOffset + 1].toInt() and 0xFF) shl 16) or
                  ((response[ttlOffset + 2].toInt() and 0xFF) shl 8) or
                  (response[ttlOffset + 3].toInt() and 0xFF)
        assertEquals(60, ttl)
    }

    @Test
    fun `buildZeroIpResponse answer name is compression pointer to offset 12`() {
        val query = buildQuery()
        val response = DnsResponseBuilder.buildZeroIpResponse(query, "example.com")!!
        // Pointer: 0xC00C = offset 12
        assertEquals(0xC0.toByte(), response[query.size])
        assertEquals(0x0C.toByte(), response[query.size + 1])
    }

    @Test
    fun `buildZeroIpResponse preserves transaction ID`() {
        val query = buildQuery(txId = 0xABCD.toShort())
        val response = DnsResponseBuilder.buildZeroIpResponse(query, "example.com")!!
        assertEquals(0xAB.toByte(), response[0])
        assertEquals(0xCD.toByte(), response[1])
    }

    @Test
    fun `buildZeroIpResponse returns null for too-short query`() {
        assertNull(DnsResponseBuilder.buildZeroIpResponse(ByteArray(11), "example.com"))
    }
}
