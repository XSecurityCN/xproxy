package org.jjgroup.xproxy.proxy.core.mutation

import org.jjgroup.xproxy.proxy.model.BodyRef
import org.jjgroup.xproxy.proxy.model.HttpProtocol
import org.jjgroup.xproxy.proxy.model.MessageDirection
import org.jjgroup.xproxy.proxy.model.MessageMetadata
import org.jjgroup.xproxy.proxy.model.UnifiedHttpMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RawHttpMessageCodecTest {
    @Test
    fun `renders HTTP2 request using Host as editable authority and preserves repeated headers`() {
        val message = UnifiedHttpMessage(
            protocol = HttpProtocol.H2,
            direction = MessageDirection.REQUEST,
            streamId = 11,
            pseudoHeaders = linkedMapOf(
                ":method" to "POST",
                ":path" to "/v1/items",
                ":scheme" to "https",
                ":authority" to "api.example.com"
            ),
            headers = listOf("cookie" to "a=1", "cookie" to "b=2", "content-type" to "text/plain"),
            trailers = emptyList(),
            bodyRef = BodyRef("héllo".toByteArray(Charsets.ISO_8859_1)),
            metadata = MessageMetadata(tls = true, host = "api.example.com", port = 443, method = "POST", path = "/v1/items")
        )

        val raw = RawHttpMessageCodec.toRaw(message)

        assertTrue(raw.startsWith("POST /v1/items HTTP/2\r\n"))
        assertTrue(raw.contains("Host: api.example.com\r\n"))
        assertEquals(2, Regex("(?im)^cookie:").findAll(raw).count())
        assertTrue(raw.endsWith("\r\n\r\nhéllo"))
    }

    @Test
    fun `parses edited HTTP2 request and derives target from edited Host`() {
        val parsed = RawHttpMessageCodec.parseRequest(
            raw = "GET /new HTTP/2\r\nHost: other.example:8443\r\nAccept: */*\r\n\r\n",
            fallback = MessageMetadata(tls = true, host = "api.example.com", port = 443, streamId = 5)
        )

        val target = DerivedTarget.fromRequest(parsed, fallbackScheme = "https")

        assertEquals(HttpProtocol.H2, parsed.protocol)
        assertEquals("GET", parsed.pseudoHeaders[":method"])
        assertEquals("/new", parsed.pseudoHeaders[":path"])
        assertEquals("other.example:8443", parsed.pseudoHeaders[":authority"])
        assertEquals("other.example", target.host)
        assertEquals(8443, target.port)
        assertEquals("other.example:8443", target.authority)
    }
}
