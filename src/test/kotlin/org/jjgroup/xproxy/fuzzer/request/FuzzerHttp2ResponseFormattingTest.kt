package org.jjgroup.xproxy.fuzzer.request

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuzzerHttp2ResponseFormattingTest {

    @Test
    fun `formats pseudo status to http2 status line`() {
        val raw = ":status: 200\r\ncontent-type: application/json\r\n\r\n{\"ok\":true}"
        val formatted = formatHttp2ResponseForDisplay(raw)

        assertTrue(formatted.startsWith("HTTP/2 200 OK\r\n"))
        assertFalse(formatted.contains(":status:"))
        assertTrue(formatted.contains("\r\n\r\n{\"ok\":true}"))
    }

    @Test
    fun `keeps response unchanged when no pseudo status exists`() {
        val raw = "HTTP/1.1 200 OK\r\ncontent-type: text/plain\r\n\r\nhello"
        val formatted = formatHttp2ResponseForDisplay(raw)
        assertTrue(formatted == raw)
    }
}
