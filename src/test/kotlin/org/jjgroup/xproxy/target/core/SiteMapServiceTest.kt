package org.jjgroup.xproxy.target.core

import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SiteMapServiceTest {

    @Test
    fun `uses https scheme for http2 history when request target is relative`() {
        val service = SiteMapService()
        val entry = ProxyHistoryEntry(
            id = 1,
            timeMillis = 1,
            method = "GET",
            host = "api.example.com",
            path = "/v1/items",
            statusCode = 200,
            length = 0,
            mimeType = "json",
            title = "",
            tls = true,
            modified = false,
            requestRaw = "GET /v1/items HTTP/2\r\nHost: api.example.com\r\n\r\n",
            responseRaw = "HTTP/2 200\r\n\r\n",
            protocol = "http/2"
        )

        val mapped = service.upsert(entry)
        assertEquals("https", mapped.protocol)
        assertEquals(443, mapped.port)
    }

    @Test
    fun `absolute target protocol still takes precedence`() {
        val service = SiteMapService()
        val entry = ProxyHistoryEntry(
            id = 2,
            timeMillis = 1,
            method = "GET",
            host = "api.example.com",
            path = "http://api.example.com/v1/items",
            statusCode = 200,
            length = 0,
            mimeType = "json",
            title = "",
            tls = false,
            modified = false,
            requestRaw = "GET http://api.example.com/v1/items HTTP/1.1\r\nHost: api.example.com\r\n\r\n",
            responseRaw = "HTTP/1.1 200 OK\r\n\r\n",
            protocol = "http/2"
        )

        val mapped = service.upsert(entry)
        assertEquals("http", mapped.protocol)
        assertEquals(80, mapped.port)
    }
}
