package org.jjgroup.xproxy.proxy.core.h2

import org.jjgroup.xproxy.proxy.model.MessageMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpstreamH2TransportTest {

    @Test
    fun `maps request into pseudo headers and tracks stream correlation`() {
        val transport = UpstreamH2Transport()
        val prepared = transport.prepare(
            correlationId = "req-1",
            rawRequest = "GET /api HTTP/2\r\nHost: example.com\r\n\r\n",
            metadata = MessageMetadata(wasDowngraded = false)
        )

        assertTrue(prepared.headers.any { it.first == ":method" && it.second == "GET" })
        assertTrue(prepared.headers.any { it.first == ":path" && it.second == "/api" })
        assertEquals(prepared.streamId, transport.lookupStreamId("req-1"))
    }

    @Test
    fun `allocates odd stream ids and preserves metadata`() {
        val transport = UpstreamH2Transport()
        val first = transport.prepare("c1", "GET /a HTTP/2\r\nHost: ex.com\r\n\r\n", MessageMetadata(wasDowngraded = true))
        val second = transport.prepare("c2", "GET /b HTTP/2\r\nHost: ex.com\r\n\r\n", MessageMetadata(wasDowngraded = false))

        assertEquals(1, first.streamId)
        assertEquals(3, second.streamId)
        assertTrue(first.metadata.wasDowngraded)
        assertTrue(!second.metadata.wasDowngraded)
    }
}
