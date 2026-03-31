package com.lockin.vpn

import com.lockin.vpn.packet.PacketParser
import com.lockin.vpn.packet.ParsedPacket
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [PacketParser] — verifies the IPv4/IPv6 dispatch logic.
 *
 * Packet building is done manually so there are no dependencies on the Android SDK.
 */
class PacketParserTest {

    // -----------------------------------------------------------------------
    // Packet builders
    // -----------------------------------------------------------------------

    /** Builds a minimal IPv4 header (20 bytes, no options). */
    private fun ipv4Header(
        protocol: Int,
        srcIp: ByteArray = byteArrayOf(10, 0, 0, 1),
        dstIp: ByteArray = byteArrayOf(1, 1, 1, 3),
        totalLength: Int,
    ): ByteArray = ByteArray(20).also { h ->
        h[0] = 0x45.toByte()                      // version=4, IHL=5
        h[2] = (totalLength ushr 8).toByte()
        h[3] = (totalLength and 0xFF).toByte()
        h[8] = 0x40                                // TTL=64
        h[9] = protocol.toByte()
        srcIp.copyInto(h, 12)
        dstIp.copyInto(h, 16)
    }

    /** Builds a minimal UDP header (8 bytes). */
    private fun udpHeader(srcPort: Int, dstPort: Int, payloadLen: Int): ByteArray =
        ByteArray(8).also { u ->
            u[0] = (srcPort ushr 8).toByte(); u[1] = (srcPort and 0xFF).toByte()
            u[2] = (dstPort ushr 8).toByte(); u[3] = (dstPort and 0xFF).toByte()
            val len = 8 + payloadLen
            u[4] = (len ushr 8).toByte(); u[5] = (len and 0xFF).toByte()
        }

    /** Builds a minimal TCP header (20 bytes, data offset = 5). */
    private fun tcpHeader(srcPort: Int, dstPort: Int): ByteArray =
        ByteArray(20).also { t ->
            t[0] = (srcPort ushr 8).toByte(); t[1] = (srcPort and 0xFF).toByte()
            t[2] = (dstPort ushr 8).toByte(); t[3] = (dstPort and 0xFF).toByte()
            t[12] = 0x50.toByte()  // data offset = 5 (20 bytes), no flags
        }

    /** Builds a valid DNS query payload for [domain]. */
    private fun dnsQueryPayload(domain: String = "example.com"): ByteArray {
        val labels = domain.split(".")
        val qname = mutableListOf<Byte>()
        for (label in labels) {
            qname.add(label.length.toByte())
            qname.addAll(label.encodeToByteArray().toList())
        }
        qname.add(0)
        val out = mutableListOf<Byte>()
        out.add(0x12); out.add(0x34)  // TX ID
        out.add(0x01); out.add(0x00)  // flags RD=1
        out.add(0x00); out.add(0x01)  // QDCOUNT=1
        repeat(6) { out.add(0x00) }   // ANCOUNT/NSCOUNT/ARCOUNT
        out.addAll(qname)
        out.add(0x00); out.add(0x01)  // QTYPE A
        out.add(0x00); out.add(0x01)  // QCLASS IN
        return out.toByteArray()
    }

    /** Builds a minimal TLS ClientHello with an SNI extension for [sni]. */
    private fun tlsClientHelloPayload(sni: String = "secure.example.com"): ByteArray {
        val sniBytes = sni.encodeToByteArray()
        // SNI extension: type=0x0000, length, server_name_list_length,
        //   name_type=0x00, name_length, name
        val sniExt = byteArrayOf(
            0x00, 0x00,                                           // extension type: SNI
            0x00, (sniBytes.size + 5).toByte(),                   // ext data length
            0x00, (sniBytes.size + 3).toByte(),                   // server_name_list length
            0x00,                                                  // name_type: host_name
            0x00, sniBytes.size.toByte(),                         // name length
        ) + sniBytes

        // Extensions block
        val extsLen = sniExt.size
        val extLenBytes = byteArrayOf((extsLen ushr 8).toByte(), (extsLen and 0xFF).toByte())

        // ClientHello body (minimally): legacy_version(2) + random(32) +
        //   session_id_len(1) + cipher_suites_len(2) + cipher_suite(2) +
        //   compression_methods_len(1) + compression_method(1) + extensions_len(2) + extensions
        val clientHelloBody = byteArrayOf(
            0x03, 0x03,             // legacy_version: TLS 1.2
        ) + ByteArray(32) + byteArrayOf(   // random: 32 zero bytes
            0x00,                   // session_id length = 0
            0x00, 0x02,             // cipher suites length = 2
            0x00, 0x2F,             // TLS_RSA_WITH_AES_128_CBC_SHA
            0x01,                   // compression methods length = 1
            0x00,                   // null compression
        ) + extLenBytes + sniExt

        // Handshake header: type=0x01 (ClientHello), length (3 bytes)
        val hsLen = clientHelloBody.size
        val handshake = byteArrayOf(
            0x01,                                        // HandshakeType: ClientHello
            0x00, (hsLen ushr 8).toByte(), (hsLen and 0xFF).toByte(),
        ) + clientHelloBody

        // TLS record: content_type=0x16, version=0x0303, length
        val recLen = handshake.size
        return byteArrayOf(
            0x16,                                        // content type: handshake
            0x03, 0x03,                                  // TLS 1.2
            (recLen ushr 8).toByte(), (recLen and 0xFF).toByte(),
        ) + handshake
    }

    /** Builds a minimal HTTP GET request with a Host header. */
    private fun httpGetPayload(host: String = "example.com"): ByteArray =
        "GET / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

    /** Assembles a full IPv4/UDP/DNS query packet. */
    private fun udpDnsPacket(domain: String = "example.com", dstPort: Int = 53): ByteArray {
        val dns = dnsQueryPayload(domain)
        val udp = udpHeader(54321, dstPort, dns.size)
        val ip = ipv4Header(17, totalLength = 20 + udp.size + dns.size)
        return ip + udp + dns
    }

    /** Assembles a full IPv4/TCP/TLS packet. */
    private fun tcpTlsPacket(sni: String = "secure.example.com", dstPort: Int = 443): ByteArray {
        val tls = tlsClientHelloPayload(sni)
        val tcp = tcpHeader(12345, dstPort)
        val ip = ipv4Header(6, totalLength = 20 + tcp.size + tls.size)
        return ip + tcp + tls
    }

    /** Assembles a full IPv4/TCP/HTTP packet. */
    private fun tcpHttpPacket(host: String = "example.com", dstPort: Int = 80): ByteArray {
        val http = httpGetPayload(host)
        val tcp = tcpHeader(12345, dstPort)
        val ip = ipv4Header(6, totalLength = 20 + tcp.size + http.size)
        return ip + tcp + http
    }

    // -----------------------------------------------------------------------
    // Empty / unknown
    // -----------------------------------------------------------------------

    @Test
    fun `empty packet returns Unknown`() {
        assertTrue(PacketParser.parse(byteArrayOf()) is ParsedPacket.Unknown)
    }

    @Test
    fun `unknown IP version returns Unknown`() {
        val packet = ByteArray(40); packet[0] = 0x50.toByte() // version=5
        assertTrue(PacketParser.parse(packet) is ParsedPacket.Unknown)
    }

    @Test
    fun `IPv4 non-TCP non-UDP protocol returns Unknown`() {
        val icmp = ipv4Header(1, totalLength = 28) + ByteArray(8)
        assertTrue(PacketParser.parse(icmp) is ParsedPacket.Unknown)
    }

    // -----------------------------------------------------------------------
    // IPv4 UDP → DNS
    // -----------------------------------------------------------------------

    @Test
    fun `IPv4 UDP port 53 is parsed as DnsQuery`() {
        val packet = udpDnsPacket("example.com")
        val result = PacketParser.parse(packet)
        assertTrue(result is ParsedPacket.DnsQuery)
        assertEquals("example.com", (result as ParsedPacket.DnsQuery).domain)
    }

    @Test
    fun `IPv4 UDP non-53 port returns Unknown`() {
        assertTrue(PacketParser.parse(udpDnsPacket(dstPort = 5353)) is ParsedPacket.Unknown)
    }

    @Test
    fun `IPv4 UDP packet too short for DNS returns Unknown`() {
        val ip = ipv4Header(17, totalLength = 20 + 8 + 2)
        val udp = udpHeader(54321, 53, 2)
        val truncated = ip + udp + byteArrayOf(0x12, 0x34) // not a valid DNS query
        assertTrue(PacketParser.parse(truncated) is ParsedPacket.Unknown)
    }

    // -----------------------------------------------------------------------
    // IPv4 TCP port 443 → TLS SNI
    // -----------------------------------------------------------------------

    @Test
    fun `IPv4 TCP port 443 with ClientHello is parsed as TlsClientHello`() {
        val packet = tcpTlsPacket("secure.example.com")
        val result = PacketParser.parse(packet)
        assertTrue(result is ParsedPacket.TlsClientHello)
        assertEquals("secure.example.com", (result as ParsedPacket.TlsClientHello).domain)
    }

    @Test
    fun `IPv4 TCP port 443 without SNI returns Unknown`() {
        // Build TCP payload that looks like TLS but has no SNI extension
        val tls = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x02, 0x00, 0x00) // TLS stub, no SNI
        val tcp = tcpHeader(12345, 443)
        val ip = ipv4Header(6, totalLength = 20 + tcp.size + tls.size)
        assertTrue(PacketParser.parse(ip + tcp + tls) is ParsedPacket.Unknown)
    }

    // -----------------------------------------------------------------------
    // IPv4 TCP port 80 → HTTP Host
    // -----------------------------------------------------------------------

    @Test
    fun `IPv4 TCP port 80 with Host header is parsed as HttpRequest`() {
        val packet = tcpHttpPacket("example.com")
        val result = PacketParser.parse(packet)
        assertTrue(result is ParsedPacket.HttpRequest)
        assertEquals("example.com", (result as ParsedPacket.HttpRequest).host)
    }

    @Test
    fun `IPv4 TCP port 80 without Host header returns Unknown`() {
        val http = "GET / HTTP/1.1\r\n\r\n".toByteArray()
        val tcp = tcpHeader(12345, 80)
        val ip = ipv4Header(6, totalLength = 20 + tcp.size + http.size)
        assertTrue(PacketParser.parse(ip + tcp + http) is ParsedPacket.Unknown)
    }

    // -----------------------------------------------------------------------
    // IPv4 TCP other ports → Unknown
    // -----------------------------------------------------------------------

    @Test
    fun `IPv4 TCP port 8080 returns Unknown`() {
        assertTrue(PacketParser.parse(tcpHttpPacket(dstPort = 8080)) is ParsedPacket.Unknown)
    }

    // -----------------------------------------------------------------------
    // IPv6
    // -----------------------------------------------------------------------

    @Test
    fun `IPv6 UDP port 53 is parsed as DnsQuery`() {
        val dns = dnsQueryPayload("ipv6.example.com")
        val udp = udpHeader(54321, 53, dns.size)
        // IPv6 header: version=6, next-header=UDP(17)
        val ipv6 = ByteArray(40).also { h ->
            h[0] = 0x60.toByte()  // version=6
            h[6] = 0x11           // next header: UDP
            h[7] = 64             // hop limit
        }
        val packet = ipv6 + udp + dns
        val result = PacketParser.parse(packet)
        assertTrue(result is ParsedPacket.DnsQuery)
        assertEquals("ipv6.example.com", (result as ParsedPacket.DnsQuery).domain)
    }

    @Test
    fun `IPv6 packet shorter than 40 bytes returns Unknown`() {
        val short = ByteArray(20); short[0] = 0x60.toByte()
        assertTrue(PacketParser.parse(short) is ParsedPacket.Unknown)
    }

    @Test
    fun `IPv6 non-UDP non-TCP next-header returns Unknown`() {
        val ipv6 = ByteArray(40).also { it[0] = 0x60.toByte(); it[6] = 0x3A } // ICMPv6
        val packet = ipv6 + ByteArray(8)
        assertTrue(PacketParser.parse(packet) is ParsedPacket.Unknown)
    }

    // -----------------------------------------------------------------------
    // rawBytes is always the original packet
    // -----------------------------------------------------------------------

    @Test
    fun `DnsQuery rawBytes is the original full packet`() {
        val packet = udpDnsPacket("example.com")
        val result = PacketParser.parse(packet) as ParsedPacket.DnsQuery
        assertArrayEquals(packet, result.rawBytes)
    }

    @Test
    fun `TlsClientHello rawBytes is the original full packet`() {
        val packet = tcpTlsPacket("example.com")
        val result = PacketParser.parse(packet) as ParsedPacket.TlsClientHello
        assertArrayEquals(packet, result.rawBytes)
    }
}
