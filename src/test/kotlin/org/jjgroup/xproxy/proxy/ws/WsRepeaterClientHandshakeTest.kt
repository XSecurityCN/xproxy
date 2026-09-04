package org.jjgroup.xproxy.proxy.ws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WsRepeaterClientHandshakeTest {

    private val sampleHandshake = listOf(
        "GET /ws/chat HTTP/1.1",
        "Host: example.com",
        "Upgrade: websocket",
        "Connection: Upgrade",
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
        "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits",
        "Sec-WebSocket-Protocol: chat",
        "Sec-WebSocket-Version: 13",
        "Cookie: session=abc",
        "",
        ""
    ).joinToString("\r\n")

    @Test
    fun `regenerates Sec-WebSocket-Key and strips extensions`() {
        val (bytes, key) = WsRepeaterClient.prepareHandshakeRequest(sampleHandshake)
        val text = String(bytes, Charsets.ISO_8859_1)

        // 旧 key 被替换为新生成的随机 key(且与原值不同)。
        assertFalse(text.contains("dGhlIHNhbXBsZSBub25jZQ=="), "original key must be replaced")
        assertTrue(text.contains("Sec-WebSocket-Key: $key"), "new key must be present in output")
        assertNotEquals("dGhlIHNhbXBsZSBub25jZQ==", key)

        // 扩展头被去除(重放不实现 permessage-deflate)。
        assertFalse(text.contains("Sec-WebSocket-Extensions"), "extensions must be stripped")

        // 其余握手头保留(子协议、版本、Cookie、Host、Upgrade、Connection)。
        assertTrue(text.contains("Sec-WebSocket-Protocol: chat"))
        assertTrue(text.contains("Sec-WebSocket-Version: 13"))
        assertTrue(text.contains("Cookie: session=abc"))
        assertTrue(text.contains("Host: example.com"))
        assertTrue(text.contains("Upgrade: websocket"))
        assertTrue(text.contains("Connection: Upgrade"))

        // 请求行保留。
        assertTrue(text.startsWith("GET /ws/chat HTTP/1.1\r\n"))
        // 以 CRLF CRLF 结束(空 body)。
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `adds missing Upgrade and Connection headers`() {
        val minimal = "GET /ws HTTP/1.1\r\nHost: example.com\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"
        val (bytes, _) = WsRepeaterClient.prepareHandshakeRequest(minimal)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("Upgrade: websocket"))
        assertTrue(text.contains("Connection: Upgrade"))
    }

    @Test
    fun `generated key is base64 of 16 bytes`() {
        val key = WsRepeaterClient.generateSecWebSocketKey()
        val decoded = java.util.Base64.getDecoder().decode(key)
        assertEquals(16, decoded.size)
    }
}
