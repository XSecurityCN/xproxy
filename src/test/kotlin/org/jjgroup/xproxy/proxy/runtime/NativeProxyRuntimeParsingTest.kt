package org.jjgroup.xproxy.proxy.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeProxyRuntimeParsingTest {

    @Test
    fun `parse request line accepts http absolute-form`() {
        val parsed = NativeProxyParsing.parseRequestLine("GET http://example.com:8080/a/b?q=1 HTTP/1.1")
        assertNotNull(parsed)
        assertEquals("GET", parsed!!.method)
        assertEquals("http://example.com:8080/a/b?q=1", parsed.target)
        assertEquals("HTTP/1.1", parsed.version)
    }

    @Test
    fun `resolve upstream target rewrites absolute-form to origin-form`() {
        val line = NativeProxyParsing.parseRequestLine("GET http://example.com:8080/a/b?q=1 HTTP/1.1")!!
        val target = NativeProxyParsing.resolveUpstreamTarget(line, headers = emptyList())
        assertNotNull(target)
        assertEquals("example.com", target!!.host)
        assertEquals(8080, target.port)
        assertEquals("/a/b?q=1", target.originForm)
    }

    @Test
    fun `parse request line rejects malformed input`() {
        val parsed = NativeProxyParsing.parseRequestLine("GET /only-two-parts")
        assertNull(parsed)
    }
}
