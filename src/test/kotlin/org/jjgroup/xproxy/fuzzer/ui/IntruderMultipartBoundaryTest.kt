package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.ui.http.RequestBodyEncodingTarget
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntruderMultipartBoundaryTest {
    @Test
    fun `to multipart uses a Chrome-style WebKitFormBoundary in header and body`() {
        val request = (
            "POST /api HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 7\r\n\r\n" +
                "a=1&b=two"
            )

        val result = convertRequestBodyEncoding(request, RequestBodyEncodingTarget.MULTIPART)
            ?: error("form body should convert to multipart")

        val contentTypeLine = result.lineSequence()
            .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?: error("missing Content-Type header")
        val match = Regex("boundary=(----WebKitFormBoundary[A-Za-z0-9]{16})").find(contentTypeLine)
            ?: error("expected Chrome-style boundary, got: $contentTypeLine")
        val boundary = match.groupValues[1]

        // Header boundary is unquoted (matches Chrome).
        assertFalse(contentTypeLine.contains("\""), "boundary should be unquoted like Chrome")

        // Body delimiters use the same boundary with the -- prefix.
        assertTrue(result.contains("--$boundary\r\n"), "opening delimiter must match header boundary")
        assertTrue(result.contains("--$boundary--\r\n"), "closing delimiter must match header boundary")

        // Parts carry the standard form-data disposition.
        assertTrue(result.contains("Content-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n"))
        assertTrue(result.contains("Content-Disposition: form-data; name=\"b\"\r\n\r\ntwo\r\n"))

        // Old tool fingerprint must be gone.
        assertFalse(result.contains("xproxy-"), "xproxy- timestamp boundary should not appear")
    }

    @Test
    fun `successive conversions yield distinct boundaries`() {
        val request = "POST /api HTTP/1.1\r\nHost: example.com\r\nContent-Length: 3\r\n\r\nx=1"
        val a = convertRequestBodyEncoding(request, RequestBodyEncodingTarget.MULTIPART)
        val b = convertRequestBodyEncoding(request, RequestBodyEncodingTarget.MULTIPART)
        assertNotNull(a)
        assertNotNull(b)
        assertTrue(a != b, "boundaries should be random per conversion")
    }
}
