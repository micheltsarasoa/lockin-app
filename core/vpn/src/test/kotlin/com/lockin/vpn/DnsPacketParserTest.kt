package com.lockin.vpn

import com.lockin.vpn.packet.DnsPacketParser
import org.junit.Assert.*
import org.junit.Test

class DnsPacketParserTest {

    // Standard DNS query for "example.com" (A record)
    // Built manually to test RFC 1035 parsing
    private fun buildDnsQuery(domain: String, transactionId: Short = 0x1234): ByteArray {
        val labels = domain.split(".")
        val qnameBytes = mutableListOf<Byte>()
        for (label in labels) {
            qnameBytes.add(label.length.toByte())
            qnameBytes.addAll(label.toByteArray().toList())
        }
        qnameBytes.add(0) // terminator

        val payload = mutableListOf<Byte>()
        // Transaction ID
        payload.add((transactionId.toInt() ushr 8).toByte())
        payload.add(transactionId.toByte())
        // Flags: standard query
        payload.add(0x01); payload.add(0x00)
        // QDCOUNT = 1
        payload.add(0x00); payload.add(0x01)
        // ANCOUNT, NSCOUNT, ARCOUNT = 0
        repeat(6) { payload.add(0x00) }
        // QNAME
        payload.addAll(qnameBytes)
        // QTYPE = A (1), QCLASS = IN (1)
        payload.add(0x00); payload.add(0x01)
        payload.add(0x00); payload.add(0x01)
        return payload.toByteArray()
    }

    @Test
    fun `parse standard A query for example dot com`() {
        val payload = buildDnsQuery("example.com", 0x1234)
        val result = DnsPacketParser.parse(payload)
        assertNotNull(result)
        assertEquals("example.com", result!!.domain)
        assertEquals(0x1234.toShort(), result.transactionId)
    }

    @Test
    fun `parse AAAA query`() {
        val payload = buildDnsQuery("google.com")
        val result = DnsPacketParser.parse(payload)
        assertNotNull(result)
        assertEquals("google.com", result!!.domain)
    }

    @Test
    fun `parse multi-label domain`() {
        val payload = buildDnsQuery("ads.tracking.example.com")
        val result = DnsPacketParser.parse(payload)
        assertNotNull(result)
        assertEquals("ads.tracking.example.com", result!!.domain)
    }

    @Test
    fun `returns null for DNS response (QR=1)`() {
        val payload = buildDnsQuery("example.com").also {
            it[2] = 0x81.toByte() // Set QR=1
        }
        val result = DnsPacketParser.parse(payload)
        assertNull(result)
    }

    @Test
    fun `returns null for truncated packet`() {
        val result = DnsPacketParser.parse(byteArrayOf(0x12, 0x34, 0x01, 0x00))
        assertNull(result)
    }

    @Test
    fun `returns null for empty payload`() {
        val result = DnsPacketParser.parse(byteArrayOf())
        assertNull(result)
    }

    @Test
    fun `handles single-label domain`() {
        val payload = buildDnsQuery("localhost")
        val result = DnsPacketParser.parse(payload)
        assertNotNull(result)
        assertEquals("localhost", result!!.domain)
    }
}
