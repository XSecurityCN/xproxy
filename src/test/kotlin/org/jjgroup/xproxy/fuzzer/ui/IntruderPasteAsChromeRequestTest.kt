package org.jjgroup.xproxy.fuzzer.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class IntruderPasteAsChromeRequestTest {
    private fun uri(url: String): URI = URI(url)

    @Test
    fun `simple seed request is rebuilt with full Chrome navigation headers`() {
        val seed = (
            "GET /?user={{user}} HTTP/1.1\r\n" +
                "Host: ipwho.is\r\n" +
                "User-Agent: curl/7.61.0\r\n" +
                "Accept: */*\r\n" +
                "Content-Length: 0\r\n\r\n"
            )

        val updated = applyUrlToRequestAsChrome(seed, uri("https://api.example.com/v1/data?foo=bar"))
            ?: error("paste should rebuild the request")

        val lines = updated.split("\r\n")
        assertEquals("GET /v1/data?foo=bar HTTP/1.1", lines.first())
        assertTrue(updated.contains("Host: api.example.com\r\n"), "Host should be derived from pasted URL")
        assertTrue(updated.contains("Chrome/138.0.0.0 Safari/537.36"), "User-Agent should be Chrome")
        assertTrue(updated.contains("sec-ch-ua: "), "client hints should be present")
        assertTrue(updated.contains("Sec-Fetch-Site: none"), "navigation request uses Sec-Fetch-Site: none")
        assertTrue(updated.contains("Sec-Fetch-Dest: document"))
        assertTrue(updated.contains("Upgrade-Insecure-Requests: 1"))
        assertTrue(updated.contains("Accept-Encoding: gzip, deflate, br, zstd"))
        assertFalse(updated.contains("curl/7.61.0"), "curl UA must be replaced")
        assertFalse(updated.contains("Content-Length"), "GET without body should drop Content-Length")
        assertFalse(updated.contains("Origin:"), "GET navigation should not send Origin")
        // ends with a proper empty line (no body)
        assertTrue(updated.endsWith("\r\n\r\n"))
    }

    @Test
    fun `non-default port is reflected in Host and Origin`() {
        val updated = applyUrlToRequestAsChrome(
            "GET / HTTP/1.1\r\nHost: x\r\n\r\n",
            uri("http://localhost:8080/api/items")
        ) ?: error("paste should rebuild the request")

        assertTrue(updated.contains("Host: localhost:8080\r\n"))
        assertTrue(updated.startsWith("GET /api/items HTTP/1.1\r\n"))
    }

    @Test
    fun `POST request keeps body, content-type and session headers, switches to cors fetch metadata`() {
        val post = (
            "POST /old HTTP/1.1\r\n" +
                "Host: old.example.com\r\n" +
                "User-Agent: curl/7.61.0\r\n" +
                "Content-Type: application/json\r\n" +
                "Authorization: Bearer secret-token\r\n" +
                "Cookie: sid=abc; theme=dark\r\n" +
                "X-CSRF-Token: t123\r\n" +
                "Content-Length: 13\r\n\r\n" +
                "{\"k\":\"v\"}"
            )

        val updated = applyUrlToRequestAsChrome(post, uri("https://api.example.com/v1/submit"))
            ?: error("paste should rebuild the request")

        assertTrue(updated.startsWith("POST /v1/submit HTTP/1.1\r\n"))
        assertTrue(updated.contains("Host: api.example.com\r\n"))
        assertTrue(updated.contains("Content-Type: application/json\r\n"))
        assertTrue(updated.contains("Authorization: Bearer secret-token"), "session auth header preserved")
        assertTrue(updated.contains("Cookie: sid=abc; theme=dark"), "cookie preserved")
        assertTrue(updated.contains("X-CSRF-Token: t123"), "custom header preserved")
        assertTrue(updated.contains("Sec-Fetch-Mode: cors"), "non-GET uses cors fetch mode")
        assertTrue(updated.contains("Sec-Fetch-Dest: empty"))
        assertTrue(updated.contains("Origin: https://api.example.com\r\n"))
        assertTrue(updated.contains("Referer: https://api.example.com/\r\n"))
        assertFalse(updated.contains("Upgrade-Insecure-Requests"), "POST should not carry navigation-only header")
        // Content-Length recomputed from the (unchanged) body bytes.
        val body = "{\"k\":\"v\"}"
        assertTrue(updated.contains("Content-Length: ${body.toByteArray(Charsets.ISO_8859_1).size}\r\n"))
        assertTrue(updated.endsWith("\r\n\r\n$body"))
    }

    @Test
    fun `empty editor still produces a full Chrome GET request`() {
        val updated = applyUrlToRequestAsChrome("", uri("https://api.example.com/"))
        assertNotNull(updated)
        assertTrue(updated!!.startsWith("GET / HTTP/1.1\r\n"))
        assertTrue(updated.contains("Host: api.example.com\r\n"))
        assertTrue(updated.contains("Chrome/138.0.0.0"))
    }

    @Test
    fun `uri without host returns null`() {
        val updated = applyUrlToRequestAsChrome("GET / HTTP/1.1\r\nHost: x\r\n\r\n", uri("https:///nohost/path"))
        assertNull(updated)
    }
}
