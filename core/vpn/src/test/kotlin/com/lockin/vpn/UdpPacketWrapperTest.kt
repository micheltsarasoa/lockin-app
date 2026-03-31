package com.lockin.vpn

import com.lockin.vpn.packet.DnsResponseBuilder
import com.lockin.vpn.packet.UdpPacketWrapper
import org.junit.Assert.*
import org.junit.Test

class UdpPacketWrapperTest {

    // -----------------------------------------------------------------------
    // Helpers
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
        for (label in labels) {
            qname.add(label.length.toByte())
            qname.addAll(label.encodeToByteArray().toList())
        }
        qname.add(0)
        val out = mutableListOf<Byte>()
        out.add((txId.toInt() ushr 8).toByte()); out.add(txId.toByte())
        out.add(0x01); out.add(0x00)   // flags: RD=1
        out.add(0x00); out.add(0x01)   // QDCOUNT=1
        repeat(6) { out.add(0x00) }
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
        val dns = buildMinimalDnsQuery("example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(buildQueryPacket(dnsPayload = dns), nxdomain)

        assertNotNull(wrapped)
        assertEquals(20 + 8 + nxdomain.size, wrapped!!.size)
    }

    @Test
    fun `IPv4 version and IHL are correct`() {
        val dns = buildMinimalDnsQuery("example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(buildQueryPacket(dnsPayload = dns), nxdomain)!!

        assertEquals(0x45, wrapped[0].toInt() and 0xFF)
    }

    @Test
    fun `protocol field is UDP`() {
        val dns = buildMinimalDnsQuery("example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(buildQueryPacket(dnsPayload = dns), nxdomain)!!

        assertEquals(0x11, wrapped[9].toInt() and 0xFF)
    }

    @Test
    fun `src and dst IP addresses are swapped`() {
        val srcIp = byteArrayOf(10, 99, 0, 1)
        val dstIp = byteArrayOf(1, 1, 1, 3)
        val dns = buildMinimalDnsQuery("example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(
            buildQueryPacket(srcIp = srcIp, dstIp = dstIp, dnsPayload = dns), nxdomain
        )!!

        assertArrayEquals(dstIp, wrapped.copyOfRange(12, 16))  // response src = original dst
        assertArrayEquals(srcIp, wrapped.copyOfRange(16, 20))  // response dst = original src
    }

    @Test
    fun `UDP src and dst ports are swapped`() {
        val dns = buildMinimalDnsQuery("example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(
            buildQueryPacket(srcPort = 54321, dstPort = 53, dnsPayload = dns), nxdomain
        )!!

        val udpSrc = ((wrapped[20].toInt() and 0xFF) shl 8) or (wrapped[21].toInt() and 0xFF)
        val udpDst = ((wrapped[22].toInt() and 0xFF) shl 8) or (wrapped[23].toInt() and 0xFF)
        assertEquals(53, udpSrc)
        assertEquals(54321, udpDst)
    }

    @Test
    fun `IPv4 header checksum is valid`() {
        val dns = buildMinimalDnsQuery("example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(buildQueryPacket(dnsPayload = dns), nxdomain)!!

        // Re-sum the full 20-byte header including the checksum; a valid header sums to 0xFFFF
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((wrapped[i].toInt() and 0xFF) shl 8) or (wrapped[i + 1].toInt() and 0xFF)
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun `DNS payload is preserved verbatim`() {
        val dns = buildMinimalDnsQuery("blocked.example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(buildQueryPacket(dnsPayload = dns), nxdomain)!!

        assertArrayEquals(nxdomain, wrapped.copyOfRange(28, wrapped.size))
    }

    @Test
    fun `UDP length field is correct`() {
        val dns = buildMinimalDnsQuery("example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(buildQueryPacket(dnsPayload = dns), nxdomain)!!

        val udpLen = ((wrapped[24].toInt() and 0xFF) shl 8) or (wrapped[25].toInt() and 0xFF)
        assertEquals(8 + nxdomain.size, udpLen)
    }

    @Test
    fun `returns null when original packet shorter than 28 bytes`() {
        assertNull(UdpPacketWrapper.wrap(ByteArray(20), ByteArray(12)))
    }

    @Test
    fun `TTL is 64`() {
        val dns = buildMinimalDnsQuery("example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(buildQueryPacket(dnsPayload = dns), nxdomain)!!

        assertEquals(64, wrapped[8].toInt() and 0xFF)
    }

    @Test
    fun `DF flag is set`() {
        val dns = buildMinimalDnsQuery("example.com")
        val nxdomain = DnsResponseBuilder.buildNxDomain(dns)!!
        val wrapped = UdpPacketWrapper.wrap(buildQueryPacket(dnsPayload = dns), nxdomain)!!

        // Flags byte (offset 6): 0x40 = DF bit set, no MF, no fragment offset
        assertEquals(0x40, wrapped[6].toInt() and 0xFF)
    }

    @Test
    fun `ipv4HeaderChecksum returns zero for all-zero 20-byte buffer after inversion`() {
        // A checksum over zeros: sum=0, inv=0xFFFF
        val buf = ByteArray(20)
        val cs = UdpPacketWrapper.ipv4HeaderChecksum(buf, 0, 20)
        assertEquals(0xFFFF, cs)
    }
}
