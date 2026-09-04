package org.jjgroup.xproxy.kits.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class XappHttpRequestTest {

    @Test
    fun `fromRaw extracts host from http2 authority pseudo header`() {
        val request = XappHttpRequest.fromRaw(
            raw = "GET /v1/data HTTP/2\r\n:authority: api.example.com\r\n:user-agent: Chrome\r\n\r\n",
            fallbackHost = "",
            fallbackTls = true
        )

        assertEquals("api.example.com", request.host)
        assertEquals(443, request.port)
        assertEquals("HTTP/2", request.version)
    }

    @Test
    fun `toRaw does not leak http2 pseudo headers`() {
        val request = XappHttpRequest.fromRaw(
            raw = "GET /v1/data HTTP/2\r\n:authority: api.example.com\r\n:scheme: https\r\n:user-agent: Chrome\r\n\r\n",
            fallbackHost = "",
            fallbackTls = true
        )

        val raw = request.toRaw()
        assertEquals(false, raw.contains(":authority:", ignoreCase = true))
        assertEquals(false, raw.contains(":scheme:", ignoreCase = true))
        assertEquals(true, raw.contains("Host: api.example.com", ignoreCase = true))
    }
}
