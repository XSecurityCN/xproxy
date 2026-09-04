package org.jjgroup.xproxy.proxy.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeProxyParsingTest {

    @Test
    fun `resolve absolute http request to upstream host and origin form`() {
        val line = NativeProxyParsing.parseRequestLine("GET http://example.com:8080/a/b?q=1 HTTP/1.1")
        val target = NativeProxyParsing.resolveUpstreamTarget(
            line!!,
            listOf("Host" to "example.com:8080")
        )

        assertNotNull(target)
        assertEquals("example.com", target?.host)
        assertEquals(8080, target?.port)
        assertEquals("http", target?.scheme)
        assertEquals("/a/b?q=1", target?.originForm)
    }

    @Test
    fun `resolve origin-form using host header`() {
        val line = NativeProxyParsing.parseRequestLine("POST /submit HTTP/1.1")
        val target = NativeProxyParsing.resolveUpstreamTarget(
            line!!,
            listOf("Host" to "api.example.com")
        )

        assertEquals("api.example.com", target?.host)
        assertEquals(80, target?.port)
        assertEquals("/submit", target?.originForm)
    }

    @Test
    fun `invalid connect authority returns null`() {
        assertNull(NativeProxyParsing.parseConnectAuthority("bad::443"))
        assertNull(NativeProxyParsing.parseConnectAuthority(""))
    }

    @Test
    fun `parse raw request keeps request line headers and body`() {
        val parsed = NativeProxyParsing.parseRawRequest(
            "POST /submit HTTP/1.1\r\nHost: api.example.com\r\nContent-Type: text/plain\r\n\r\nhello"
        )
        assertNotNull(parsed)
        assertEquals("POST", parsed?.first?.method)
        assertEquals("/submit", parsed?.first?.target)
        assertEquals("HTTP/1.1", parsed?.first?.version)
        assertEquals("api.example.com", parsed?.second?.headers?.first { it.first == "Host" }?.second)
        assertEquals("hello", String(parsed?.second?.body ?: ByteArray(0), Charsets.ISO_8859_1))
    }
}
