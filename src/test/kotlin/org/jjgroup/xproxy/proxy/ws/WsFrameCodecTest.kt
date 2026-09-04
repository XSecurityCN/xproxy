package org.jjgroup.xproxy.proxy.ws

import org.jjgroup.xproxy.proxy.core.WsFrameTapState
import org.jjgroup.xproxy.proxy.core.parseWebSocketFrames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WsFrameCodecTest {

    @Test
    fun `masked text frame round-trips through parseWebSocketFrames`() {
        val payload = "hello, 世界!".toByteArray(Charsets.UTF_8)
        val frame = WsFrameCodec.encodeMaskedFrame(WsFrameCodec.OPCODE_TEXT, payload)

        // 客户端帧必须置位 mask 位(b1 高位为 1)且 FIN 置位。
        assertEquals(0x80.toByte(), (frame[0].toInt() and 0xF0).toByte())
        assertTrue((frame[1].toInt() and 0x80) != 0)

        // 解码:parseWebSocketFrames 接受原始字节(含掩码),应还原出原始 payload 文本与 Text 类型。
        val parsed = parseWebSocketFrames(frame, WsFrameTapState())
        assertEquals(1, parsed.size)
        assertEquals("Text", parsed[0].messageType)
        assertEquals(payload.size, parsed[0].payloadBytesLength)
        assertEquals(String(payload, Charsets.UTF_8), parsed[0].payloadText)
    }

    @Test
    fun `two frames with same payload use different masks`() {
        val payload = "x".toByteArray()
        val a = WsFrameCodec.encodeMaskedFrame(WsFrameCodec.OPCODE_TEXT, payload)
        val b = WsFrameCodec.encodeMaskedFrame(WsFrameCodec.OPCODE_TEXT, payload)
        // 帧头相同(6 字节),但掩码密钥(2..5)不同 -> 掩码后的 payload 字节也不同。
        assertNotEquals(a.toList().subList(2, 6), b.toList().subList(2, 6))
        assertEquals(6 + 1, a.size)
    }

    @Test
    fun `medium payload uses 16-bit extended length`() {
        val payload = ByteArray(200) { (it and 0xFF).toByte() }
        val frame = WsFrameCodec.encodeMaskedFrame(WsFrameCodec.OPCODE_BINARY, payload)
        // 126 => 扩展 2 字节长度;头长 4(mask 前)+ 4(mask)= 8。
        assertEquals(126, frame[1].toInt() and 0x7F)
        assertEquals(8 + 200, frame.size)
        val parsed = parseWebSocketFrames(frame, WsFrameTapState())
        assertEquals(200, parsed[0].payloadBytesLength)
        assertEquals("Binary", parsed[0].messageType)
    }

    @Test
    fun `large payload uses 64-bit extended length`() {
        val payload = ByteArray(70_000) { 0x41 }
        val frame = WsFrameCodec.encodeMaskedFrame(WsFrameCodec.OPCODE_BINARY, payload)
        assertEquals(127, frame[1].toInt() and 0x7F)
        assertEquals(14 + 70_000, frame.size)
        val parsed = parseWebSocketFrames(frame, WsFrameTapState())
        assertEquals(70_000, parsed[0].payloadBytesLength)
    }

    @Test
    fun `opcode names are stable`() {
        assertEquals("Text", WsFrameCodec.opcodeName(WsFrameCodec.OPCODE_TEXT))
        assertEquals("Binary", WsFrameCodec.opcodeName(WsFrameCodec.OPCODE_BINARY))
        assertEquals("Ping", WsFrameCodec.opcodeName(WsFrameCodec.OPCODE_PING))
        assertEquals("Pong", WsFrameCodec.opcodeName(WsFrameCodec.OPCODE_PONG))
        assertEquals("Close", WsFrameCodec.opcodeName(WsFrameCodec.OPCODE_CLOSE))
    }

    // 以下两个用例覆盖"单帧跨多次 write 到达"(TCP 分片)路径:WsFrameTapState 必须跨调用累积未解析尾部,
    // 不能因每次只拿到部分字节而丢帧。原 WsFrameCodecTest 仅整帧单块喂入,未覆盖累积缓冲。
    @Test
    fun `frame split across arbitrary chunk boundaries reassembles correctly`() {
        val payload = ByteArray(200) { (it and 0xFF).toByte() }
        val frame = WsFrameCodec.encodeMaskedFrame(WsFrameCodec.OPCODE_BINARY, payload)
        // 切成多种粒度的 chunk 模拟分片;同一个 state 跨多次 parseWebSocketFrames 调用累积。
        val boundaries = listOf(1, 3, 6, 10, 50, 100, 150, frame.size)
        val state = WsFrameTapState()
        var parsed: List<org.jjgroup.xproxy.proxy.core.WsParsedFrame> = emptyList()
        var start = 0
        for (end in boundaries) {
            // 中间 chunk 不完整时不应产出帧,只在最后一 chunk 帧完整时产出。
            parsed = parseWebSocketFrames(frame.copyOfRange(start, end), state)
            start = end
        }
        assertEquals(1, parsed.size)
        assertEquals("Binary", parsed[0].messageType)
        assertEquals(200, parsed[0].payloadBytesLength)
        assertTrue(payload.contentEquals(parsed[0].payload))
    }

    @Test
    fun `multiple frames split across chunks parse incrementally`() {
        val p1 = "hello".toByteArray(Charsets.UTF_8)
        val p2 = "world!".toByteArray(Charsets.UTF_8)
        val f1 = WsFrameCodec.encodeMaskedFrame(WsFrameCodec.OPCODE_TEXT, p1)
        val f2 = WsFrameCodec.encodeMaskedFrame(WsFrameCodec.OPCODE_TEXT, p2)
        val combined = f1 + f2
        val state = WsFrameTapState()
        // chunk1 = 完整 f1 + f2 前 3 字节;chunk2 = f2 剩余。验证 drop 保留 f2 尾部、下一调用续解析。
        val splitAt = f1.size + 3
        val first = parseWebSocketFrames(combined.copyOfRange(0, splitAt), state)
        val second = parseWebSocketFrames(combined.copyOfRange(splitAt, combined.size), state)
        assertEquals(1, first.size)
        assertEquals("hello", first[0].payloadText)
        assertEquals(1, second.size)
        assertEquals("world!", second[0].payloadText)
    }
}
