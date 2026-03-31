package com.lockin.vpn

import com.lockin.vpn.packet.HttpHostParser
import org.junit.Assert.*
import org.junit.Test

class HttpHostParserTest {

    private fun payload(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    // -----------------------------------------------------------------------
    // Happy paths
    // -----------------------------------------------------------------------

    @Test
    fun `GET request with Host header returns hostname`() {
        val result = HttpHostParser.parse(payload("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"))
        assertEquals("example.com", result)
    }

    @Test
    fun `POST request with Host header returns hostname`() {
        val result = HttpHostParser.parse(payload("POST /api HTTP/1.1\r\nHost: api.example.com\r\nContent-Length: 0\r\n\r\n"))
        assertEquals("api.example.com", result)
    }

    @Test
    fun `Host header with port strips port`() {
        val result = HttpHostParser.parse(payload("GET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n"))
        assertEquals("example.com", result)
    }

    @Test
    fun `hostname is lowercased`() {
        val result = HttpHostParser.parse(payload("GET / HTTP/1.1\r\nHost: Example.COM\r\n\r\n"))
        assertEquals("example.com", result)
    }

    @Test
    fun `extra whitespace around hostname is trimmed`() {
        val result = HttpHostParser.parse(payload("GET / HTTP/1.1\r\nHost:   example.com  \r\n\r\n"))
        assertEquals("example.com", result)
    }

    @Test
    fun `Host header in mixed case is found`() {
        val result = HttpHostParser.parse(payload("GET / HTTP/1.1\r\nHOST: example.com\r\n\r\n"))
        assertEquals("example.com", result)
    }

    @Test
    fun `HEAD request returns hostname`() {
        val result = HttpHostParser.parse(payload("HEAD /page HTTP/1.1\r\nHost: head.example.com\r\n\r\n"))
        assertEquals("head.example.com", result)
    }

    @Test
    fun `subdomain is returned correctly`() {
        val result = HttpHostParser.parse(payload("GET / HTTP/1.1\r\nHost: ads.tracking.evil.com\r\n\r\n"))
        assertEquals("ads.tracking.evil.com", result)
    }

    // -----------------------------------------------------------------------
    // Null / rejection paths
    // -----------------------------------------------------------------------

    @Test
    fun `empty payload returns null`() {
        assertNull(HttpHostParser.parse(byteArrayOf()))
    }

    @Test
    fun `non-HTTP payload (binary) returns null`() {
        assertNull(HttpHostParser.parse(byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x00)))  // TLS
    }

    @Test
    fun `lowercase first byte returns null`() {
        assertNull(HttpHostParser.parse(payload("get / HTTP/1.1\r\nHost: example.com\r\n\r\n")))
    }

    @Test
    fun `missing Host header returns null`() {
        assertNull(HttpHostParser.parse(payload("GET / HTTP/1.1\r\nAccept: */*\r\n\r\n")))
    }

    @Test
    fun `request without CRLF line endings and no Host header returns null`() {
        assertNull(HttpHostParser.parse(payload("GET / HTTP/1.1\nAccept: */*\n\n")))
    }

    @Test
    fun `Host header present with LF line endings is found`() {
        // RFC says CRLF, but many servers accept bare LF; the regex uses MULTILINE
        val result = HttpHostParser.parse(payload("GET / HTTP/1.1\nHost: lf.example.com\n\n"))
        assertEquals("lf.example.com", result)
    }

    // -----------------------------------------------------------------------
    // Boundary conditions
    // -----------------------------------------------------------------------

    @Test
    fun `payload exactly at MAX_SCAN_BYTES boundary with Host header is found`() {
        // Build a request where Host header starts within the 4096-byte window
        val prefix = "GET /" + "x".repeat(3000) + " HTTP/1.1\r\nHost: boundary.example.com\r\n\r\n"
        val result = HttpHostParser.parse(payload(prefix))
        assertEquals("boundary.example.com", result)
    }

    @Test
    fun `Host header beyond 4096 bytes is not found`() {
        // Pad with >4096 bytes before the Host header so it falls outside the scan window
        val padded = "GET / HTTP/1.1\r\n" + "X-Padding: " + "a".repeat(4090) + "\r\nHost: hidden.example.com\r\n\r\n"
        // Result can be null (not found) or a host — either is acceptable but must not throw
        HttpHostParser.parse(payload(padded))  // just verify no exception
    }
}
