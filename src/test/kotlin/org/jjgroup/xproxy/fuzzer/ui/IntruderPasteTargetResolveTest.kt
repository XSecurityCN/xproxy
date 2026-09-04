package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class IntruderPasteTargetResolveTest {
    @Test
    fun `isTargetBlank true when host and protocol blank and port zero`() {
        assertTrue(isTargetBlank(HttpService("", 0, "")))
    }

    @Test
    fun `isTargetBlank false when host set`() {
        assertFalse(isTargetBlank(HttpService("example.com", 0, "")))
    }

    @Test
    fun `isTargetBlank false when port set`() {
        assertFalse(isTargetBlank(HttpService("", 443, "")))
    }

    @Test
    fun `isTargetBlank false when protocol set`() {
        assertFalse(isTargetBlank(HttpService("", 0, "https")))
    }

    @Test
    fun `resolveTargetForPaste fills host port protocol when target blank`() {
        val resolved = resolveTargetForPaste(
            HttpService("", 0, ""),
            URI("https://api.example.com:8443/v1/data"),
            "GET /v1/data HTTP/1.1\r\nHost: api.example.com:8443\r\n\r\n"
        )
        assertEquals(HttpService("api.example.com", 8443, "https"), resolved)
    }

    @Test
    fun `resolveTargetForPaste defaults https port to 443 when uri omits port`() {
        val resolved = resolveTargetForPaste(
            HttpService("", 0, ""),
            URI("https://api.example.com/"),
            "GET / HTTP/1.1\r\nHost: api.example.com\r\n\r\n"
        )
        assertEquals(HttpService("api.example.com", 443, "https"), resolved)
    }

    @Test
    fun `resolveTargetForPaste preserves manually set target`() {
        val existing = HttpService("manual.host", 8080, "http")
        val resolved = resolveTargetForPaste(
            existing,
            URI("https://api.example.com/v1/data"),
            "GET /v1/data HTTP/1.1\r\nHost: api.example.com\r\n\r\n"
        )
        assertEquals(existing, resolved)
    }

    @Test
    fun `inferTargetForBlankFromRequest fills http default for plain request without scheme`() {
        val raw = "GET /?user={{user}} HTTP/1.1\r\nHost: ipwho.is\r\nUser-Agent: curl/7.61.0\r\nAccept: */*\r\nContent-Length: 0\r\n\r\n"
        val inferred = inferTargetForBlankFromRequest(raw, HttpService("", 0, ""))
        assertEquals(HttpService("ipwho.is", 80, "http"), inferred)
    }

    @Test
    fun `inferTargetForBlankFromRequest keeps https from absolute request uri`() {
        val raw = "GET https://api.example.com/v1/data HTTP/1.1\r\nHost: api.example.com\r\n\r\n"
        val inferred = inferTargetForBlankFromRequest(raw, HttpService("", 0, ""))
        assertEquals(HttpService("api.example.com", 443, "https"), inferred)
    }

    @Test
    fun `inferTargetForBlankFromRequest infers https when host header port is 443`() {
        val raw = "GET / HTTP/1.1\r\nHost: secure.example.com:443\r\n\r\n"
        val inferred = inferTargetForBlankFromRequest(raw, HttpService("", 0, ""))
        assertEquals(HttpService("secure.example.com", 443, "https"), inferred)
    }

    @Test
    fun `inferTargetForBlankFromRequest infers https when port ends with 443 like 8443`() {
        val raw = "GET / HTTP/1.1\r\nHost: ip.sb:8443\r\n\r\n"
        val inferred = inferTargetForBlankFromRequest(raw, HttpService("", 0, ""))
        assertEquals(HttpService("ip.sb", 8443, "https"), inferred)
    }

    @Test
    fun `inferTargetForBlankFromRequest keeps http for non-443-like port`() {
        val raw = "GET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n"
        val inferred = inferTargetForBlankFromRequest(raw, HttpService("", 0, ""))
        assertEquals(HttpService("example.com", 8080, "http"), inferred)
    }

    @Test
    fun `inferTargetForBlankFromRequest defaults to port 80 http when host has no port`() {
        val raw = "GET / HTTP/1.1\r\nHost: ip.sb\r\n\r\n"
        val inferred = inferTargetForBlankFromRequest(raw, HttpService("", 0, ""))
        assertEquals(HttpService("ip.sb", 80, "http"), inferred)
    }

    @Test
    fun `inferTargetForBlankFromRequest returns fallback when no host clue`() {
        val fallback = HttpService("", 0, "")
        val inferred = inferTargetForBlankFromRequest("GET / HTTP/1.1\r\n\r\n", fallback)
        assertEquals(fallback, inferred)
    }

    @Test
    fun `buildBlankRequestFromTarget omits default http port`() {
        assertEquals(
            "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
            buildBlankRequestFromTarget(HttpService("example.com", 80, "http"))
        )
    }

    @Test
    fun `buildBlankRequestFromTarget omits default https port`() {
        assertEquals(
            "GET / HTTP/1.1\r\nHost: secure.example.com\r\n\r\n",
            buildBlankRequestFromTarget(HttpService("secure.example.com", 443, "https"))
        )
    }

    @Test
    fun `buildBlankRequestFromTarget keeps non-default port`() {
        assertEquals(
            "GET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n",
            buildBlankRequestFromTarget(HttpService("example.com", 8080, "http"))
        )
    }
}
