package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.proxy.core.h2.Http2InboundEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyHttp2BridgeTest {

    @Test
    fun `resolve metadata marks downgrade when request is h2 but response is h1`() {
        val metadata = ProxyHttp2Bridge.resolveMetadata(
            requestRaw = "GET /api HTTP/2\r\nHost: example.com\r\n\r\n",
            responseRaw = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
            tls = true,
            streamIdHint = 7
        )

        assertEquals("http/1.1", metadata.protocol)
        assertEquals(7, metadata.streamId)
        assertTrue(metadata.wasDowngraded)
    }

    @Test
    fun `recorded request raw follows downgraded http1 protocol`() {
        val recorded = ProxyHttp2Bridge.requestRawForRecordedProtocol(
            requestRaw = "GET /api HTTP/2\r\nHost: example.com\r\n\r\n",
            protocol = "http/1.1"
        )

        assertTrue(recorded.startsWith("GET /api HTTP/1.1\r\n"))
    }

    @Test
    fun `request events build headers and data for h2 raw message`() {
        val events = ProxyHttp2Bridge.requestEvents(
            streamId = 3,
            requestRaw = "POST /submit HTTP/2\r\nHost: demo.local\r\nContent-Type: text/plain\r\n\r\nhello"
        )

        assertEquals(2, events.size)
        val headers = events[0] as Http2InboundEvent.Headers
        assertEquals(3, headers.streamId)
        assertTrue(headers.headers.any { it.first == ":method" && it.second == "POST" })
        assertTrue(headers.headers.any { it.first == ":path" && it.second == "/submit" })
        assertTrue(headers.headers.any { it.first == ":authority" && it.second == "demo.local" })
        val data = events[1] as Http2InboundEvent.Data
        assertEquals("hello", String(data.chunk, Charsets.ISO_8859_1))
        assertTrue(data.endStream)
    }
}
