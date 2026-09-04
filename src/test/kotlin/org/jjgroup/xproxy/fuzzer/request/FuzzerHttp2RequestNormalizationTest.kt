package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuzzerHttp2RequestNormalizationTest {

    @Test
    fun `normalization converts absolute URI to origin-form path`() {
        val request = "GET https://api.apifox.com/v1/test?q=1 HTTP/2\r\nHost: api.apifox.com\r\n\r\n"
        val normalized = normalizeHttp2RequestForEngine(HttpService("api.apifox.com", 443, "https"), request)

        assertTrue(normalized.startsWith("GET /v1/test?q=1 HTTP/2"))
    }

    @Test
    fun `normalization strips hop-by-hop headers for h2`() {
        val request = (
            "GET / HTTP/2\r\n" +
                "Host: api.apifox.com\r\n" +
                "Connection: keep-alive\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Upgrade: h2c\r\n\r\n"
            )
        val normalized = normalizeHttp2RequestForEngine(HttpService("api.apifox.com", 443, "https"), request)

        assertFalse(normalized.contains("\r\nConnection:", ignoreCase = true))
        assertFalse(normalized.contains("\r\nProxy-Connection:", ignoreCase = true))
        assertFalse(normalized.contains("\r\nUpgrade:", ignoreCase = true))
    }
}
