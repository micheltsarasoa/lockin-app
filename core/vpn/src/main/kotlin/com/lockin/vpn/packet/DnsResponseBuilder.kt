package com.lockin.vpn.packet

/**
 * Synthesizes DNS response packets for blocked domains.
 *
 * Two modes:
 *   - NXDOMAIN: The domain "does not exist" (RCODE=3). Clean and standard.
 *   - ZERO_IP: Responds with 0.0.0.0 (A record). Some apps retry NXDOMAIN.
 *
 * The synthesized response is a valid DNS message using the original
 * transaction ID, so the OS DNS resolver accepts it normally.
 */
object DnsResponseBuilder {

    /**
     * Synthesizes an NXDOMAIN DNS response for a blocked query.
     *
     * @param queryPayload The original DNS query payload (without IP/UDP headers)
     * @return A DNS response payload ready to be written back to the TUN fd,
     *         or null if the query couldn't be parsed
     */
    fun buildNxDomain(queryPayload: ByteArray): ByteArray? {
        if (queryPayload.size < 12) return null

        // Clone the query header and set response flags
        val response = ByteArray(queryPayload.size)
        queryPayload.copyInto(response)

        // Set QR=1 (response), AA=0, TC=0, RA=1, RCODE=3 (NXDOMAIN)
        // Flags at bytes 2-3
        // Original query flags with QR=1 (0x8000), RA=1 (0x0080), RCODE=3 (0x0003)
        response[2] = 0x81.toByte()   // QR=1, OPCODE=0, AA=0, TC=0, RD=1
        response[3] = 0x83.toByte()   // RA=1, Z=0, RCODE=3 (NXDOMAIN)

        // ANCOUNT = 0 (bytes 6-7)
        response[6] = 0
        response[7] = 0
        // NSCOUNT = 0 (bytes 8-9)
        response[8] = 0
        response[9] = 0
        // ARCOUNT = 0 (bytes 10-11)
        response[10] = 0
        response[11] = 0

        return response
    }

    /**
     * Synthesizes a DNS response with 0.0.0.0 as the A record answer.
     * Some applications handle this more gracefully than NXDOMAIN.
     *
     * @param queryPayload The original DNS query payload
     * @param domain The domain being answered (for constructing the answer section)
     * @return A DNS response payload with a single A record answer (0.0.0.0)
     */
    fun buildZeroIpResponse(queryPayload: ByteArray, domain: String): ByteArray? {
        if (queryPayload.size < 12) return null

        // Build answer section: pointer to QNAME + type A + class IN + TTL + RDATA
        // Pointer to name in question section: 0xC00C (pointer to offset 12)
        val answer = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(),  // Name: pointer to offset 12 (question QNAME)
            0x00, 0x01,                     // Type: A
            0x00, 0x01,                     // Class: IN
            0x00, 0x00, 0x00, 0x3C,        // TTL: 60 seconds
            0x00, 0x04,                     // RDLENGTH: 4 bytes
            0x00, 0x00, 0x00, 0x00,        // RDATA: 0.0.0.0
        )

        val response = ByteArray(queryPayload.size + answer.size)
        queryPayload.copyInto(response)

        // Flags: QR=1, AA=1, RCODE=0 (NOERROR)
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()

        // ANCOUNT = 1
        response[6] = 0
        response[7] = 1
        // NSCOUNT = 0
        response[8] = 0; response[9] = 0
        // ARCOUNT = 0
        response[10] = 0; response[11] = 0

        answer.copyInto(response, queryPayload.size)
        return response
    }
}
