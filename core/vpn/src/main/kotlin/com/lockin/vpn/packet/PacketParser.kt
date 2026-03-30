package com.lockin.vpn.packet

/**
 * Top-level IP packet dispatcher.
 *
 * Reads raw IP packets from the TUN interface and dispatches to sub-parsers
 * based on protocol and port number.
 *
 * Supports:
 *   - IPv4 (version 4) and IPv6 (version 6)
 *   - UDP port 53 → DNS
 *   - TCP port 443 → TLS SNI
 *   - TCP port 80  → HTTP Host
 *   - Everything else → Unknown (forward as-is)
 */
object PacketParser {

    /**
     * Parses a raw IP packet and returns the appropriate [ParsedPacket].
     *
     * @param rawPacket The raw bytes read from the TUN file descriptor
     * @return A [ParsedPacket] representing the packet's content
     */
    fun parse(rawPacket: ByteArray): ParsedPacket {
        if (rawPacket.isEmpty()) return ParsedPacket.Unknown(rawPacket)

        val version = (rawPacket[0].toInt() and 0xFF) shr 4

        return when (version) {
            4 -> parseIPv4(rawPacket)
            6 -> parseIPv6(rawPacket)
            else -> ParsedPacket.Unknown(rawPacket)
        }
    }

    private fun parseIPv4(packet: ByteArray): ParsedPacket {
        if (packet.size < 20) return ParsedPacket.Unknown(packet)

        val ihl = (packet[0].toInt() and 0x0F) * 4  // IP header length in bytes
        if (packet.size < ihl) return ParsedPacket.Unknown(packet)

        val protocol = packet[9].toInt() and 0xFF
        val destIp = ((packet[16].toInt() and 0xFF) shl 24) or
                ((packet[17].toInt() and 0xFF) shl 16) or
                ((packet[18].toInt() and 0xFF) shl 8) or
                (packet[19].toInt() and 0xFF)

        return when (protocol) {
            17 -> parseUdp(packet, ihl, destIp)   // UDP
            6 -> parseTcp(packet, ihl, destIp)    // TCP
            else -> ParsedPacket.Unknown(packet)
        }
    }

    private fun parseIPv6(packet: ByteArray): ParsedPacket {
        if (packet.size < 40) return ParsedPacket.Unknown(packet)

        val nextHeader = packet[6].toInt() and 0xFF
        val ipHeaderLen = 40  // Fixed IPv6 header size

        return when (nextHeader) {
            17 -> parseUdp(packet, ipHeaderLen, 0)
            6 -> parseTcp(packet, ipHeaderLen, 0)
            else -> ParsedPacket.Unknown(packet)
        }
    }

    private fun parseUdp(packet: ByteArray, ipHeaderLen: Int, destIp: Int): ParsedPacket {
        val udpStart = ipHeaderLen
        if (packet.size < udpStart + 8) return ParsedPacket.Unknown(packet)

        val destPort = ((packet[udpStart + 2].toInt() and 0xFF) shl 8) or
                (packet[udpStart + 3].toInt() and 0xFF)

        if (destPort != 53) return ParsedPacket.Unknown(packet)

        val dnsPayloadStart = udpStart + 8
        if (dnsPayloadStart >= packet.size) return ParsedPacket.Unknown(packet)

        val dnsPayload = packet.copyOfRange(dnsPayloadStart, packet.size)
        val result = DnsPacketParser.parse(dnsPayload) ?: return ParsedPacket.Unknown(packet)

        return ParsedPacket.DnsQuery(
            transactionId = result.transactionId,
            domain = result.domain,
            rawBytes = packet,
        )
    }

    private fun parseTcp(packet: ByteArray, ipHeaderLen: Int, destIp: Int): ParsedPacket {
        val tcpStart = ipHeaderLen
        if (packet.size < tcpStart + 20) return ParsedPacket.Unknown(packet)

        val destPort = ((packet[tcpStart + 2].toInt() and 0xFF) shl 8) or
                (packet[tcpStart + 3].toInt() and 0xFF)

        val dataOffset = ((packet[tcpStart + 12].toInt() and 0xFF) shr 4) * 4
        val tcpPayloadStart = tcpStart + dataOffset
        if (tcpPayloadStart >= packet.size) return ParsedPacket.Unknown(packet)

        val tcpPayload = packet.copyOfRange(tcpPayloadStart, packet.size)

        return when (destPort) {
            443 -> {
                val sni = TlsClientHelloParser.parse(tcpPayload)
                    ?: return ParsedPacket.Unknown(packet)
                ParsedPacket.TlsClientHello(
                    domain = sni,
                    rawBytes = packet,
                    destIp = destIp,
                    destPort = destPort,
                )
            }
            80 -> {
                val host = HttpHostParser.parse(tcpPayload)
                    ?: return ParsedPacket.Unknown(packet)
                ParsedPacket.HttpRequest(host = host, rawBytes = packet)
            }
            else -> ParsedPacket.Unknown(packet)
        }
    }
}
