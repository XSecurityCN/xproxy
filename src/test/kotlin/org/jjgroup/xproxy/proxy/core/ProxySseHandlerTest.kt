package org.jjgroup.xproxy.proxy.core

import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxySseHandlerTest {

    private fun response(contentType: String?): DefaultHttpResponse {
        val r = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        if (contentType != null) {
            r.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
        }
        return r
    }

    @Test
    fun `isSseResponse detects text event-stream`() {
        assertTrue(isSseResponse(response("text/event-stream")))
        assertTrue(isSseResponse(response("text/event-stream; charset=utf-8")))
        assertTrue(isSseResponse(response("TEXT/EVENT-STREAM")))
    }

    @Test
    fun `isSseResponse rejects non-sse content types`() {
        assertFalse(isSseResponse(response("text/html")))
        assertFalse(isSseResponse(response("application/json")))
        assertFalse(isSseResponse(response("text/plain")))
        assertFalse(isSseResponse(response(null)))
    }

    private fun state(): SseStreamState = SseStreamState(
        entryId = 1,
        timeMillis = 100,
        responseHeadersRaw = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n",
        requestRaw = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
        originalRequestRaw = "",
        method = "GET",
        host = "example.com",
        path = "/",
        statusCode = 200,
        tls = false,
        protocol = "http/1.1",
        streamId = null
    )

    private fun bytes(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `appendChunk accumulates body and responseRaw`() {
        val s = state()
        s.appendChunk(bytes("data: hello\n\n"))
        s.appendChunk(bytes("data: world\n\n"))

        assertEquals("data: hello\n\ndata: world\n\n".length, s.bodyLength())
        assertTrue(s.responseRaw().endsWith("data: hello\n\ndata: world\n\n"))
        assertFalse(s.finalized)
        assertFalse(s.truncated)
    }

    @Test
    fun `appendChunk caps at max bytes and marks truncated`() {
        val s = state()
        val big = ByteArray(SSE_CAPTURE_MAX_BYTES + 10) { 'x'.code.toByte() }
        s.appendChunk(big)

        assertEquals(SSE_CAPTURE_MAX_BYTES, s.bodyLength())
        assertTrue(s.truncated)
        assertTrue(s.responseRaw().contains("truncated"))
    }

    @Test
    fun `appendChunk across multiple chunks caps correctly`() {
        val s = state()
        val half = ByteArray(SSE_CAPTURE_MAX_BYTES / 2) { 'a'.code.toByte() }
        s.appendChunk(half)
        assertFalse(s.truncated)
        assertEquals(SSE_CAPTURE_MAX_BYTES / 2, s.bodyLength())

        s.appendChunk(ByteArray(SSE_CAPTURE_MAX_BYTES / 2 + 5) { 'b'.code.toByte() })
        assertTrue(s.truncated)
        assertEquals(SSE_CAPTURE_MAX_BYTES, s.bodyLength())
    }

    @Test
    fun `appendChunk after finalized is ignored`() {
        val s = state()
        s.appendChunk(bytes("data: a\n\n"))
        s.markFinalized()
        s.appendChunk(bytes("data: b\n\n"))

        assertFalse(s.responseRaw().contains("data: b"))
        assertTrue(s.finalized)
    }

    @Test
    fun `appendChunk after truncated is ignored`() {
        val s = state()
        s.appendChunk(ByteArray(SSE_CAPTURE_MAX_BYTES + 1) { 'x'.code.toByte() })
        assertTrue(s.truncated)
        val lengthAfterCap = s.bodyLength()

        s.appendChunk(bytes("data: more\n\n"))

        assertEquals(lengthAfterCap, s.bodyLength())
    }
}
