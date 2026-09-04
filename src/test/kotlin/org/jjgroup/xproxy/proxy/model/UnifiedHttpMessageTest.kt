package org.jjgroup.xproxy.proxy.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnifiedHttpMessageTest {

    @Test
    fun `preserves pseudo headers independently`() {
        val message = UnifiedHttpMessage(
            protocol = HttpProtocol.H2,
            direction = MessageDirection.REQUEST,
            streamId = 3,
            pseudoHeaders = linkedMapOf(":method" to "GET", ":path" to "/v1/a"),
            headers = listOf("accept" to "*/*"),
            trailers = emptyList(),
            bodyRef = null,
            metadata = MessageMetadata()
        )

        assertEquals("GET", message.pseudoHeaders[":method"])
        assertEquals("/v1/a", message.pseudoHeaders[":path"])
    }

    @Test
    fun `preserves repeated headers`() {
        val message = UnifiedHttpMessage(
            protocol = HttpProtocol.H2,
            direction = MessageDirection.REQUEST,
            streamId = 5,
            pseudoHeaders = linkedMapOf(":method" to "GET", ":path" to "/"),
            headers = listOf("cookie" to "a=1", "cookie" to "b=2"),
            trailers = emptyList(),
            bodyRef = null,
            metadata = MessageMetadata()
        )

        val cookies = message.headers.filter { it.first == "cookie" }.map { it.second }
        assertEquals(listOf("a=1", "b=2"), cookies)
        assertTrue(message.headers.count { it.first == "cookie" } == 2)
    }
}
