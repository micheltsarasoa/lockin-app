package com.lockin.vpn.packet

/**
 * Wraps a DNS response payload in an IPv4/UDP packet ready to be written back to the TUN fd.
 *
 * The original query packet supplies all addressing context:
 *   - Source IP/port → become the response destination (reply to the querying device)
 *   - Destination IP/port → become the response source (DNS server side)
 *
 * Structure: IPv4 header (20 bytes) + UDP header (8 bytes) + DNS payload.
 * IPv4 checksum is computed per RFC 791. UDP checksum is left as 0x0000 (optional
 * for IPv4/UDP per RFC 768).
 */
object UdpPacketWrapper {

    /**
     * Wraps [dnsResponse] in an IPv4/UDP packet sourced from the original query's
     * destination address/port, addressed to the original query's source address/port.
     *
     * @param originalPacket The raw IPv4/UDP packet containing the DNS query
     * @param dnsResponse    The DNS wire-format response payload (without IP/UDP headers)
     * @return The fully framed IPv4/UDP/DNS response, or null if [originalPacket] is malformed
     */
    fun wrap(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray? {
        if (originalPacket.size < 28) return null
        val ihl = (originalPacket[0].toInt() and 0x0F) * 4
        if (originalPacket.size < ihl + 8) return null

        val srcIp   = originalPacket.copyOfRange(12, 16)
        val dstIp   = originalPacket.copyOfRange(16, 20)
        val srcPort = originalPacket.copyOfRange(ihl, ihl + 2)
        val dstPort = originalPacket.copyOfRange(ihl + 2, ihl + 4)

        val totalLength = 20 + 8 + dnsResponse.size
        val packet = ByteArray(totalLength)

        // IPv4 header
        packet[0]  = 0x45.toByte()                        // Version=4, IHL=5 (20 bytes)
        packet[1]  = 0x00                                  // DSCP/ECN
        packet[2]  = (totalLength ushr 8).toByte()
        packet[3]  = (totalLength and 0xFF).toByte()
        packet[4]  = 0x00; packet[5] = 0x00               // Identification
        packet[6]  = 0x40; packet[7] = 0x00               // Flags=DF, Fragment Offset=0
        packet[8]  = 0x40                                  // TTL = 64
        packet[9]  = 0x11                                  // Protocol = UDP (17)
        packet[10] = 0x00; packet[11] = 0x00              // Header checksum (computed below)
        dstIp.copyInto(packet, 12)                         // src = original dst (DNS server)
        srcIp.copyInto(packet, 16)                         // dst = original src (device)

        val checksum = ipv4HeaderChecksum(packet, 0, 20)
        packet[10] = (checksum ushr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        // UDP header
        val udpStart = 20
        dstPort.copyInto(packet, udpStart)                 // src port = 53
        srcPort.copyInto(packet, udpStart + 2)             // dst port = original query src port
        val udpLength = 8 + dnsResponse.size
        packet[udpStart + 4] = (udpLength ushr 8).toByte()
        packet[udpStart + 5] = (udpLength and 0xFF).toByte()
        packet[udpStart + 6] = 0x00                        // checksum = 0 (optional for IPv4/UDP)
        packet[udpStart + 7] = 0x00

        dnsResponse.copyInto(packet, 28)
        return packet
    }

    /**
     * Computes the RFC 791 one's-complement checksum for a 20-byte IPv4 header.
     * The checksum field (bytes 10-11) must be zeroed before calling this.
     */
    fun ipv4HeaderChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }
}
