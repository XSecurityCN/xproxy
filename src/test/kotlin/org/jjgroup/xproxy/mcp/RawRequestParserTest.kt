package org.jjgroup.xproxy.mcp

import org.jjgroup.xproxy.mcp.attack.RawRequestParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RawRequestParserTest {
    @Test
    fun `parses method path and host with explicit https port`() {
        val raw = "GET /api/v1/users?id=1 HTTP/1.1\r\nHost: example.com:443\r\nUser-Agent: test\r\n\r\n"
        val parsed = RawRequestParser.parse(raw)
        assertEquals("GET", parsed.method)
        assertEquals("/api/v1/users?id=1", parsed.path)
        assertEquals("example.com", parsed.host)
        assertEquals(443, parsed.port)
        assertEquals("https", parsed.protocol)
    }

    @Test
    fun `infers http for port 80`() {
        val raw = "POST /login HTTP/1.1\r\nHost: example.com:80\r\nContent-Length: 0\r\n\r\n"
        val parsed = RawRequestParser.parse(raw)
        assertEquals(80, parsed.port)
        assertEquals("http", parsed.protocol)
    }

    @Test
    fun `defaults port by scheme when host has no port`() {
        val raw = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
        val parsed = RawRequestParser.parse(raw)
        assertEquals(443, parsed.port) // default https
        assertEquals("https", parsed.protocol)
    }

    @Test
    fun `protocol override wins over port inference`() {
        val raw = "GET / HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
        val parsed = RawRequestParser.parse(raw, "http")
        assertEquals("http", parsed.protocol)
        assertEquals(443, parsed.port)
    }

    @Test
    fun `falls back to localhost when host header missing`() {
        val raw = "GET / HTTP/1.1\r\n\r\n"
        val parsed = RawRequestParser.parse(raw)
        assertEquals("localhost", parsed.host)
        assertEquals("/", parsed.path)
    }
}
