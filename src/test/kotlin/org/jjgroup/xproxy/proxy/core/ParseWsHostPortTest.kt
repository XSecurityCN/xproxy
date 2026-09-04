package org.jjgroup.xproxy.proxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ParseWsHostPortTest {

    @Test
    fun `host without port defaults to 443 on tls`() {
        val (host, port) = parseWsHostPort("example.com", true)
        assertEquals("example.com", host)
        assertEquals(443, port)
    }

    @Test
    fun `host without port defaults to 80 on plain`() {
        val (host, port) = parseWsHostPort("example.com", false)
        assertEquals("example.com", host)
        assertEquals(80, port)
    }

    @Test
    fun `explicit port is parsed`() {
        val (host, port) = parseWsHostPort("example.com:8443", true)
        assertEquals("example.com", host)
        assertEquals(8443, port)
    }

    @Test
    fun `ipv6 literal with port`() {
        val (host, port) = parseWsHostPort("[::1]:9000", false)
        assertEquals("::1", host)
        assertEquals(9000, port)
    }

    @Test
    fun `ipv6 literal without port defaults`() {
        val (host, port) = parseWsHostPort("[::1]", true)
        assertEquals("::1", host)
        assertEquals(443, port)
    }
}
