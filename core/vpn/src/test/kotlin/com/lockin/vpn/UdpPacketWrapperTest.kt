package com.lockin.vpn

import com.lockin.vpn.packet.DnsResponseBuilder
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for wrapDnsResponseInUdp() via a white-box helper approach.
 *
 * Since wrapDnsResponseInUdp() is private on LockInVpnService (an Android service),
 * we test the packet structure logic directly here by replicating the same algorithm
 * in a standalone helper and verifying the output byte-by-byte.
 *
 * This also validates that DnsResponseBuilder.buildNxDomain() produces output that
 * can be legally wrapped (correct size, flags, etc.).
 */
class UdpPacketWrapperTest {

    // -----------------------------------------------------------------------
    // Standalone replica of the wrapping logic (pure Kotlin, no Android deps)
    // -----------------------------------------------------------------------

    private fun wrapDnsResponseInUdp(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray? {
        if (originalPacket.size < 28) return null
        val ihl = (originalPacket[0].toInt() and 0x0F) * 4
        if (originalPacket.size < ihl + 8) return null

        val srcIp   = originalPacket.copyOfRange(12, 16)
        val dstIp   = originalPacket.copyOfRange(16, 20)
        val srcPort = originalPacket.copyOfRange(ihl, ihl + 2)
        val dstPort = originalPacket.copyOfRange(ihl + 2, ihl + 4)

        val totalLength = 20 + 8 + dnsResponse.size
        val packet = ByteArray(totalLength)

        packet[0]  = 0x45.toByte()
        packet[1]  = 0x00
        packet[2]  = (totalLength ushr 8).toByte()
        packet[3]  = (totalLength and 0xFF).toByte()
        packet[4]  = 0x00; packet[5] = 0x00
        packet[6]  = 0x40; packet[7] = 0x00
        packet[8]  = 0x40
        packet[9]  = 0x11
        packet[10] = 0x00; packet[11] = 0x00
        dstIp.copyInto(packet, 12)
        srcIp.copyInto(packet, 16)

        val checksum = ipv4HeaderChecksum(packet, 0, 20)
        packet[10] = (checksum ushr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        val udpStart = 20
        dstPort.copyInto(packet, udpStart)
        srcPort.copyInto(packet, udpStart + 2)
        val udpLength = 8 + dnsResponse.size
        packet[udpStart + 4] = (udpLength ushr 8).toByte()
        packet[udpStart + 5] = (udpLength and 0xFF).toByte()
        packet[udpStart + 6] = 0x00
        packet[udpStart + 7] = 0x00

        dnsResponse.copyInto(packet, 28)
        return packet
    }

    private fun ipv4HeaderChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            val word = ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    // -----------------------------------------------------------------------
    // Helpers to build a minimal IPv4/UDP/DNS query packet
    // -----------------------------------------------------------------------

    private fun buildQueryPacket(
        srcIp: ByteArray = byteArrayOf(10, 99, 0, 1),
        dstIp: ByteArray = byteArrayOf(1, 1, 1, 3),
        srcPort: Int = 54321,
        dstPort: Int = 53,
        dnsPayload: ByteArray = buildMinimalDnsQuery("example.com"),
    ): ByteArray {
        val totalLen = 20 + 8 + dnsPayload.size
        val buf = ByteArray(totalLen)
        buf[0] = 0x45.toByte()   // IPv4, IHL=5
        buf[2] = (totalLen ushr 8).toByte()
        buf[3] = (totalLen and 0xFF).toByte()
        buf[9] = 0x11             // UDP
        srcIp.copyInto(buf, 12)
        dstIp.copyInto(buf, 16)
        buf[20] = (srcPort ushr 8).toByte(); buf[21] = (srcPort and 0xFF).toByte()
        buf[22] = (dstPort ushr 8).toByte(); buf[23] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + dnsPayload.size
        buf[24] = (udpLen ushr 8).toByte(); buf[25] = (udpLen and 0xFF).toByte()
        dnsPayload.copyInto(buf, 28)
        return buf
    }

    private fun buildMinimalDnsQuery(domain: String, txId: Short = 0x1234): ByteArray {
        val labels = domain.split(".")
        val qname = mutableListOf<Byte>()
        for (label in labels) { qname.add(label.length.toByte()); qname.addAll(label.encodeToByteArray().toList()) }
        qname.add(0)
        val out = mutableListOf<Byte>()
        out.add((txId.toInt() ushr 8).toByte()); out.add(txId.toByte())
        out.add(0x01); out.add(0x00)   // flags: RD=1
        out.add(0x00); out.add(0x01)   // QDCOUNT=1
        repeat(6) { out.add(0x00) }    // ANCOUNT, NSCOUNT, ARCOUNT = 0
        out.addAll(qname)
        out.add(0x00); out.add(0x01)   // QTYPE A
        out.add(0x00); out.add(0x01)   // QCLASS IN
        return out.toByteArray()
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `wrapped packet has correct total length`() {
        val dnsPayload = buildMinimalDnsQuery("example.com")
        val queryPacket = buildQueryPacket(dnsPayload = dnsPayload)
        val nxdomain = DnsResponseBuilder.buildNxDomain(dnsPayload)!!
        val wrapped = wrapDnsResponseInUdp(queryPacket, nxdomain)

        assertNotNull(wrapped)
        assertEquals(20 + 8 + nxdomain.size, wrapped!!.size)
    }

    @Test
    fun `wrapped packet IPv4 version and IHL are correct`() {
        val dnsPayload = buildMinimalDnsQuery("example.com")
        val queryPacket = buildQueryPacket(dnsPayload = dnsPayload)
        val nxdomain = DnsResponseBuilder.buildNxDomain(dnsPayload)!!
        val wrapped = wrapDnsResponseInUdp(queryPacket, nxdomain)!!

        assertEquals(0x45, wrapped[0].toInt() and 0xFF)  // Version=4, IHL=5
    }

    @Test
    fun `wrapped packet protocol is UDP`() {
        val dnsPayload = buildMinimalDnsQuery("example.com")
        val queryPacket = buildQueryPacket(dnsPayload = dnsPayload)
        val nxdomain = DnsResponseBuilder.buildNxDomain(dnsPayload)!!
        val wrapped = wrapDnsResponseInUdp(queryPacket, nxdomain)!!

        assertEquals(0x11, wrapped[9].toInt() and 0xFF)  // UDP = 17
    }

    @Test
    fun `src and dst IP addresses are swapped`() {
        val srcIp = byteArrayOf(10, 99, 0, 1)
        val dstIp = byteArrayOf(1, 1, 1, 3)
        val dnsPayload = buildMinimalDnsQuery("example.com")
        val queryPacket = buildQueryPacket(srcIp = srcIp, dstIp = dstIp, dnsPayload = dnsPayload)
        val nxdomain = DnsResponseBuilder.buildNxDomain(dnsPayload)!!
        val wrapped = wrapDnsResponseInUdp(queryPacket, nxdomain)!!

        // In the response: src = original dst (DNS server), dst = original src (client)
        assertArrayEquals(dstIp, wrapped.copyOfRange(12, 16))
        assertArrayEquals(srcIp, wrapped.copyOfRange(16, 20))
    }

    @Test
    fun `UDP src and dst ports are swapped`() {
        val dnsPayload = buildMinimalDnsQuery("example.com")
        val queryPacket = buildQueryPacket(srcPort = 54321, dstPort = 53, dnsPayload = dnsPayload)
        val nxdomain = DnsResponseBuilder.buildNxDomain(dnsPayload)!!
        val wrapped = wrapDnsResponseInUdp(queryPacket, nxdomain)!!

        val udpSrcPort = ((wrapped[20].toInt() and 0xFF) shl 8) or (wrapped[21].toInt() and 0xFF)
        val udpDstPort = ((wrapped[22].toInt() and 0xFF) shl 8) or (wrapped[23].toInt() and 0xFF)

        assertEquals(53, udpSrcPort)       // response comes from port 53
        assertEquals(54321, udpDstPort)    // response goes to original source port
    }

    @Test
    fun `IPv4 header checksum is valid`() {
        val dnsPayload = buildMinimalDnsQuery("example.com")
        val queryPacket = buildQueryPacket(dnsPayload = dnsPayload)
        val nxdomain = DnsResponseBuilder.buildNxDomain(dnsPayload)!!
        val wrapped = wrapDnsResponseInUdp(queryPacket, nxdomain)!!

        // Re-compute checksum over the header with the checksum field included;
        // a valid header produces a one's-complement sum of 0xFFFF (i.e. checksum = 0)
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((wrapped[i].toInt() and 0xFF) shl 8) or (wrapped[i + 1].toInt() and 0xFF)
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun `DNS payload is preserved in wrapped packet`() {
        val dnsPayload = buildMinimalDnsQuery("blocked.example.com")
        val queryPacket = buildQueryPacket(dnsPayload = dnsPayload)
        val nxdomain = DnsResponseBuilder.buildNxDomain(dnsPayload)!!
        val wrapped = wrapDnsResponseInUdp(queryPacket, nxdomain)!!

        assertArrayEquals(nxdomain, wrapped.copyOfRange(28, wrapped.size))
    }

    @Test
    fun `returns null for packet shorter than 28 bytes`() {
        val result = wrapDnsResponseInUdp(ByteArray(20), ByteArray(12))
        assertNull(result)
    }

    @Test
    fun `udp length field is correct`() {
        val dnsPayload = buildMinimalDnsQuery("example.com")
        val queryPacket = buildQueryPacket(dnsPayload = dnsPayload)
        val nxdomain = DnsResponseBuilder.buildNxDomain(dnsPayload)!!
        val wrapped = wrapDnsResponseInUdp(queryPacket, nxdomain)!!

        val udpLen = ((wrapped[24].toInt() and 0xFF) shl 8) or (wrapped[25].toInt() and 0xFF)
        assertEquals(8 + nxdomain.size, udpLen)
    }
}
