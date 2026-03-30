package com.lockin.sync

import org.junit.Assert.*
import org.junit.Test

class HostsFileParserTest {

    @Test
    fun `parse standard 0-0-0-0 format`() {
        val content = "0.0.0.0 example.com"
        val domains = HostsFileParser.parse(content)
        assertTrue(domains.contains("example.com"))
    }

    @Test
    fun `parse 127-0-0-1 format`() {
        val content = "127.0.0.1 tracker.io"
        val domains = HostsFileParser.parse(content)
        assertTrue(domains.contains("tracker.io"))
    }

    @Test
    fun `skip comment lines`() {
        val content = """
            # This is a comment
            0.0.0.0 example.com
            # Another comment
        """.trimIndent()
        val domains = HostsFileParser.parse(content)
        assertFalse(domains.any { it.startsWith("#") })
        assertTrue(domains.contains("example.com"))
    }

    @Test
    fun `skip blank lines`() {
        val content = "\n\n0.0.0.0 example.com\n\n"
        val domains = HostsFileParser.parse(content)
        assertEquals(1, domains.size)
        assertTrue(domains.contains("example.com"))
    }

    @Test
    fun `skip localhost and broadcasthost`() {
        val content = """
            0.0.0.0 localhost
            0.0.0.0 broadcasthost
            0.0.0.0 example.com
        """.trimIndent()
        val domains = HostsFileParser.parse(content)
        assertFalse(domains.contains("localhost"))
        assertFalse(domains.contains("broadcasthost"))
        assertTrue(domains.contains("example.com"))
    }

    @Test
    fun `handle Windows CRLF line endings`() {
        val content = "0.0.0.0 example.com\r\n0.0.0.0 blocked.org\r\n"
        val domains = HostsFileParser.parse(content)
        assertTrue(domains.contains("example.com"))
        assertTrue(domains.contains("blocked.org"))
    }

    @Test
    fun `normalize to lowercase`() {
        val content = "0.0.0.0 Example.COM"
        val domains = HostsFileParser.parse(content)
        assertTrue(domains.contains("example.com"))
        assertFalse(domains.contains("Example.COM"))
    }

    @Test
    fun `strip inline comments`() {
        val content = "0.0.0.0 example.com # this is blocked"
        val domains = HostsFileParser.parse(content)
        assertTrue(domains.contains("example.com"))
        assertEquals(1, domains.size)
    }

    @Test
    fun `reject invalid domain with consecutive dots`() {
        val content = "0.0.0.0 invalid..domain.com"
        val domains = HostsFileParser.parse(content)
        assertTrue(domains.isEmpty())
    }

    @Test
    fun `reject domain starting with hyphen`() {
        val content = "0.0.0.0 -invalid.com"
        val domains = HostsFileParser.parse(content)
        assertTrue(domains.isEmpty())
    }

    @Test
    fun `parse large file efficiently`() {
        val sb = StringBuilder()
        repeat(100_000) { i -> sb.appendLine("0.0.0.0 domain$i.com") }
        val domains = HostsFileParser.parse(sb.toString())
        assertEquals(100_000, domains.size)
    }

    @Test
    fun `deduplicate domains`() {
        val content = """
            0.0.0.0 example.com
            0.0.0.0 example.com
            127.0.0.1 example.com
        """.trimIndent()
        val domains = HostsFileParser.parse(content)
        assertEquals(1, domains.filter { it == "example.com" }.size)
    }
}
