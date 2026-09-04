package org.jjgroup.xproxy.proxy.core

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyRequestHandlerFormattingTest {

    @Test
    fun `request formatting displays HTTP2 version token`() {
        val request = DefaultFullHttpRequest(HttpVersion.valueOf("HTTP/2.0"), HttpMethod.GET, "/hello")
        request.headers().set("Host", "example.com")

        val raw = formatRequestRaw(request)
        val headersOnly = formatRequestHeadersOnly(request)

        assertTrue(raw.startsWith("GET /hello HTTP/2\r\n"))
        assertTrue(headersOnly.startsWith("GET /hello HTTP/2\r\n"))
    }

    @Test
    fun `response formatting displays HTTP2 version token`() {
        val response = DefaultFullHttpResponse(
            HttpVersion.valueOf("HTTP/2.0"),
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer("ok".toByteArray(Charsets.ISO_8859_1))
        )
        response.headers().set("Content-Type", "text/plain")

        val raw = formatResponseRawWithBodyLimit(response, Int.MAX_VALUE)
        assertTrue(raw.startsWith("HTTP/2 200 OK\r\n"))
    }

    @Test
    fun `edited request accepts HTTP2 request line token`() {
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")

        applyEditedRequest(
            request,
            "GET /v1/items HTTP/2\r\nHost: api.example.com\r\n\r\n"
        )

        assertEquals("HTTP/2.0", request.protocolVersion().text())
        assertEquals("/v1/items", request.uri())
        assertEquals("api.example.com", request.headers().get("Host"))
    }

    @Test
    fun `edited response accepts HTTP2 status line token`() {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

        applyEditedResponse(
            response,
            "HTTP/2 201 Created\r\nContent-Type: application/json\r\n\r\n{}"
        )

        assertEquals("HTTP/2.0", response.protocolVersion().text())
        assertEquals(201, response.status().code())
        assertEquals("application/json", response.headers().get("Content-Type"))
    }

    @Test
    fun `edited response removes transfer encoding when content length is set`() {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

        applyEditedResponse(
            response,
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: image/png\r\n\r\nPNGDATA"
        )

        assertEquals(null, response.headers().get("Transfer-Encoding"))
        assertEquals("7", response.headers().get("Content-Length"))
    }

    @Test
    fun `edited response preserves body bytes containing crlf`() {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        val body = byteArrayOf(0x41, 0x0d, 0x0a, 0x42).toString(Charsets.ISO_8859_1)

        applyEditedResponse(
            response,
            "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n$body"
        )

        val actual = ByteArray(response.content().readableBytes())
        response.content().getBytes(response.content().readerIndex(), actual)
        assertEquals(listOf(0x41, 0x0d, 0x0a, 0x42), actual.map { it.toInt() and 0xff })
    }

    @Test
    fun `edited encoded text response removes stale content encoding`() {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

        applyEditedResponse(
            response,
            "HTTP/1.1 200 OK\r\nContent-Type: text/html;charset=utf-8\r\nContent-Encoding: br\r\n\r\n<!DOCTYPE html>patched"
        )

        assertEquals(null, response.headers().get("Content-Encoding"))
        assertEquals("22", response.headers().get("Content-Length"))
        assertEquals("<!DOCTYPE html>patched", response.content().toString(Charsets.ISO_8859_1))
    }

    @Test
    fun `normalize mime classifies text event-stream as sse`() {
        assertEquals("sse", normalizeMimeType("text/event-stream"))
        assertEquals("sse", normalizeMimeType("TEXT/EVENT-STREAM"))
    }

    @Test
    fun `normalize mime keeps non-sse text types as text`() {
        assertEquals("text", normalizeMimeType("text/plain"))
        assertEquals("html", normalizeMimeType("text/html"))
        assertEquals("json", normalizeMimeType("application/json"))
    }

    @Test
    fun `detect mime returns sse for event-stream response`() {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set("Content-Type", "text/event-stream; charset=utf-8")

        assertEquals("sse", detectMimeType(response, ""))
    }

    @Test
    fun `format response headers only excludes body`() {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set("Content-Type", "text/event-stream")

        val headers = formatResponseHeadersOnly(response)
        assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(headers.contains("Content-Type: text/event-stream\r\n"))
        assertTrue(headers.endsWith("\r\n\r\n"))
    }
}
