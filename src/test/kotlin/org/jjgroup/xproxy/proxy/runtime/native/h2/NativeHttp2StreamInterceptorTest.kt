package org.jjgroup.xproxy.proxy.runtime.native.h2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class NativeHttp2StreamInterceptorTest {

    @Test
    fun `build request from pseudo headers and render raw`() {
        val request = NativeHttp2StreamInterceptor.fromPseudoHeadersAndBody(
            streamId = 9,
            pseudoHeaders = listOf(
                ":method" to "GET",
                ":path" to "/health",
                ":authority" to "api.example.com",
                "accept" to "*/*"
            ),
            body = ByteArray(0)
        )
        assertNotNull(request)
        val raw = request!!.toRawRequest()
        assertEquals(true, raw.startsWith("GET /health HTTP/2"))
        assertEquals(true, raw.contains("Host: api.example.com"))
        assertEquals(true, raw.contains("accept: */*"))
    }

    @Test
    fun `apply edited raw updates stream request fields`() {
        val original = NativeHttp2StreamRequest(
            streamId = 3,
            method = "GET",
            path = "/a",
            authority = "api.example.com",
            headers = listOf("accept" to "*/*"),
            body = ByteArray(0)
        )
        val edited = original.applyEditedRaw(
            "POST /b HTTP/2\r\nHost: api.example.com\r\nContent-Type: text/plain\r\n\r\nhello"
        )

        assertEquals("POST", edited?.method)
        assertEquals("/b", edited?.path)
        assertEquals("api.example.com", edited?.authority)
        assertEquals("hello", String(edited?.body ?: ByteArray(0), Charsets.ISO_8859_1))
    }
}
