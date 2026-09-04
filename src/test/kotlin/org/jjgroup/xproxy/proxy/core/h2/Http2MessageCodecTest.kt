package org.jjgroup.xproxy.proxy.core.h2

import org.jjgroup.xproxy.proxy.model.BodyRef
import org.jjgroup.xproxy.proxy.model.HttpProtocol
import org.jjgroup.xproxy.proxy.model.MessageDirection
import org.jjgroup.xproxy.proxy.model.MessageMetadata
import org.jjgroup.xproxy.proxy.model.UnifiedHttpMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Http2MessageCodecTest {
    @Test
    fun `validates required request pseudo headers`() {
        val message = UnifiedHttpMessage(
            protocol = HttpProtocol.H2,
            direction = MessageDirection.REQUEST,
            streamId = 1,
            pseudoHeaders = mapOf(":method" to "GET", ":path" to "/"),
            headers = emptyList(),
            trailers = emptyList(),
            bodyRef = null,
            metadata = MessageMetadata(tls = true)
        )

        val result = Http2MessageCodec.validate(message)

        assertFalse(result.valid)
        assertTrue(result.message.contains(":scheme"))
        assertTrue(result.message.contains(":authority"))
    }

    @Test
    fun `strips hop by hop headers when building upstream headers`() {
        val message = UnifiedHttpMessage(
            protocol = HttpProtocol.H2,
            direction = MessageDirection.REQUEST,
            streamId = 1,
            pseudoHeaders = linkedMapOf(
                ":method" to "POST",
                ":path" to "/submit",
                ":scheme" to "https",
                ":authority" to "api.example.com"
            ),
            headers = listOf("connection" to "close", "accept" to "*/*", "transfer-encoding" to "chunked"),
            trailers = emptyList(),
            bodyRef = BodyRef("hello".toByteArray(Charsets.ISO_8859_1)),
            metadata = MessageMetadata(tls = true)
        )

        val headers = Http2MessageCodec.toHeaderPairs(message)

        assertEquals(listOf(":method", ":path", ":scheme", ":authority", "accept"), headers.map { it.first })
        assertEquals("*/*", headers.last().second)
    }
}
