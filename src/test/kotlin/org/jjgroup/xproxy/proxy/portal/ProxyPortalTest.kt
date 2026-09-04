package org.jjgroup.xproxy.proxy.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyPortalTest {

    @Test
    fun `matches xproxy built in host`() {
        val result = ProxyPortal.handleRequest("GET", "/", "xproxy", "127.0.0.1", 8080)
        assertNotNull(result)
    }

    @Test
    fun `matches direct listener host with port`() {
        val result = ProxyPortal.handleRequest("GET", "/", "127.0.0.1:8080", "127.0.0.1", 8080)
        assertNotNull(result)
    }

    @Test
    fun `matches hostless direct listener root request`() {
        val result = ProxyPortal.handleRequest("GET", "/", null, "127.0.0.1", 8080)

        assertNotNull(result)
        assertEquals(200, result!!.statusCode)
    }

    @Test
    fun `does not capture normal upstream host`() {
        val result = ProxyPortal.handleRequest("GET", "/", "example.com", "127.0.0.1", 8080)
        assertNull(result)
    }

    @Test
    fun `absolute form authority wins over host header`() {
        assertNotNull(ProxyPortal.handleRequest("GET", "http://xproxy/", "example.com", "127.0.0.1", 8080))
        assertNull(ProxyPortal.handleRequest("GET", "http://example.com/", "xproxy", "127.0.0.1", 8080))
    }

    @Test
    fun `normalizes trailing dot and ipv6 listener host`() {
        assertNotNull(ProxyPortal.handleRequest("GET", "/", "xproxy.", "127.0.0.1", 8080))
        assertNotNull(ProxyPortal.handleRequest("GET", "/", "[::1]:8080", "::1", 8080))
    }

    @Test
    fun `landing page includes setup and certificate links`() {
        val result = requireNotNull(ProxyPortal.handleRequest("GET", "/", "xproxy", "127.0.0.1", 8080))
        val body = result.body.toString(Charsets.UTF_8)

        assertEquals(200, result.statusCode)
        assertEquals("OK", result.reason)
        assertEquals("text/html; charset=utf-8", result.header("Content-Type"))
        assertEquals("close", result.header("Connection"))
        assertTrue(body.contains("xproxy is running"))
        assertTrue(body.contains("/cert"))
        assertTrue(body.contains("/cert/pem"))
        assertTrue(body.contains("/cert/der"))
        assertTrue(body.contains("/cert/cer"))
        assertTrue(body.contains("private keys are never exposed"))
    }

    @Test
    fun `head landing page returns headers without body`() {
        val result = requireNotNull(ProxyPortal.handleRequest("HEAD", "/", "xproxy", "127.0.0.1", 8080))

        assertEquals(200, result.statusCode)
        assertEquals("text/html; charset=utf-8", result.header("Content-Type"))
        assertEquals("close", result.header("Connection"))
        assertEquals(0, result.body.size)
    }

    @Test
    fun `head certificate route returns headers without body`() {
        val get = requireNotNull(ProxyPortal.handleRequest("GET", "/cert/pem", "xproxy", "127.0.0.1", 8080))
        val head = requireNotNull(ProxyPortal.handleRequest("HEAD", "/cert/pem", "xproxy", "127.0.0.1", 8080))

        assertEquals(get.statusCode, head.statusCode)
        assertEquals(get.reason, head.reason)
        assertEquals(get.header("Content-Type"), head.header("Content-Type"))
        assertEquals(get.header("Content-Disposition"), head.header("Content-Disposition"))
        assertEquals(0, head.body.size)
    }

    @Test
    fun `head unknown route returns not found headers without body`() {
        val result = requireNotNull(ProxyPortal.handleRequest("HEAD", "/unknown", "xproxy", "127.0.0.1", 8080))

        assertEquals(404, result.statusCode)
        assertEquals("text/html; charset=utf-8", result.header("Content-Type"))
        assertEquals(0, result.body.size)
    }

    @Test
    fun `unknown portal path returns html not found`() {
        val result = requireNotNull(ProxyPortal.handleRequest("GET", "/unknown", "xproxy", "127.0.0.1", 8080))

        assertEquals(404, result.statusCode)
        assertEquals("text/html; charset=utf-8", result.header("Content-Type"))
        assertTrue(result.body.toString(Charsets.UTF_8).contains("Not found"))
    }

    @Test
    fun `non get portal method returns method not allowed`() {
        val result = requireNotNull(ProxyPortal.handleRequest("POST", "/", "xproxy", "127.0.0.1", 8080))

        assertEquals(405, result.statusCode)
        assertEquals("text/html; charset=utf-8", result.header("Content-Type"))
    }

    @Test
    fun `certificate endpoints return public certificate downloads`() {
        val routes = listOf("/cert", "/cert/der", "/cert/cer", "/cert/pem")

        for (route in routes) {
            val result = requireNotNull(ProxyPortal.handleRequest("GET", route, "xproxy", "127.0.0.1", 8080))
            assertEquals(200, result.statusCode, route)
            assertNotNull(result.header("Content-Disposition"), route)
            assertEquals("close", result.header("Connection"), route)
            assertTrue(result.body.isNotEmpty(), route)
        }

        val pem = requireNotNull(ProxyPortal.handleRequest("GET", "/cert/pem", "xproxy", "127.0.0.1", 8080))
            .body.toString(Charsets.UTF_8)
        assertTrue(pem.contains("-----BEGIN CERTIFICATE-----"))
        assertTrue(pem.contains("-----END CERTIFICATE-----"))
    }

    @Test
    fun `error page escapes html and redacts sensitive details`() {
        val result = ProxyPortal.errorPage(
            statusCode = 502,
            reason = "Bad Gateway",
            title = "Upstream proxy error",
            phase = "h2 direct",
            cause = IllegalStateException(
                "failed <script>alert(1)</script> at /Users/name/secret/file.txt access_token=abcdefghijklmnopqrstuvwxyz0123456789"
            )
        )
        val body = result.body.toString(Charsets.UTF_8)

        assertEquals(502, result.statusCode)
        assertEquals("text/html; charset=utf-8", result.header("Content-Type"))
        assertEquals("close", result.header("Connection"))
        assertTrue(body.contains("Upstream proxy error"))
        assertTrue(body.contains("h2 direct"))
        assertTrue(body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(body.contains("[path]"))
        assertTrue(body.contains("access_token=[redacted]"))
        assertFalse(body.contains("/Users/name/secret/file.txt"))
        assertFalse(body.contains("abcdefghijklmnopqrstuvwxyz0123456789"))
    }

    @Test
    fun `error page redacts standalone base64 like secrets`() {
        val secret = "abcdefghijklmnopqrstuvwxyzABCDEF0123456789+/="
        val result = ProxyPortal.errorPage(
            statusCode = 502,
            reason = "Bad Gateway",
            title = "Upstream proxy error",
            phase = null,
            cause = RuntimeException("secret $secret")
        )
        val body = result.body.toString(Charsets.UTF_8)

        assertTrue(body.contains("[redacted]"))
        assertFalse(body.contains(secret))
    }

    @Test
    fun `error page truncates long cause details`() {
        val result = ProxyPortal.errorPage(
            statusCode = 502,
            reason = "Bad Gateway",
            title = "Upstream proxy error",
            phase = null,
            cause = RuntimeException("x".repeat(800))
        )
        val body = result.body.toString(Charsets.UTF_8)

        assertTrue(body.contains("..."))
        assertTrue(body.length < 3600)
    }

    private fun ProxyPortalResult.header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}
