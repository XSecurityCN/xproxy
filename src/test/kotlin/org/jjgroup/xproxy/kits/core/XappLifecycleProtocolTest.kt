package org.jjgroup.xproxy.kits.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class XappLifecycleProtocolTest {

    @Test
    fun `infer history protocol from request raw http2`() {
        val raw = "GET /chat HTTP/2\r\nHost: chat.baidu.com\r\n\r\n"
        assertEquals("http/2", inferHistoryProtocolFromRequestRaw(raw))
    }

    @Test
    fun `infer history protocol from request raw http11`() {
        val raw = "GET /chat HTTP/1.1\r\nHost: chat.baidu.com\r\n\r\n"
        assertEquals("http/1.1", inferHistoryProtocolFromRequestRaw(raw))
    }

    @Test
    fun `infer history protocol from pseudo-header only raw`() {
        val raw = ":method: GET\r\n:path: /chat\r\n:authority: chat.baidu.com\r\n\r\n"
        assertEquals("http/2", inferHistoryProtocolFromRequestRaw(raw))
    }
}
