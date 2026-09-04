package org.jjgroup.xproxy.proxy.ws

import java.security.SecureRandom

/**
 * WebSocket 帧编解码常量与客户端帧编码(RFC 6455)。
 *
 * 重放路径上客户端->服务端帧必须带掩码(mask);服务端->客户端帧不带掩码,解码复用
 * [org.jjgroup.xproxy.proxy.core.parseWebSocketFrames],这里只负责"构造待发送的掩码帧"。
 */
object WsFrameCodec {
    const val OPCODE_CONTINUATION = 0x0
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    private val RNG = SecureRandom()

    fun opcodeName(opcode: Int): String = when (opcode) {
        OPCODE_CONTINUATION -> "Continuation"
        OPCODE_TEXT -> "Text"
        OPCODE_BINARY -> "Binary"
        OPCODE_CLOSE -> "Close"
        OPCODE_PING -> "Ping"
        OPCODE_PONG -> "Pong"
        else -> "Opcode-$opcode"
    }

    /**
     * 构造一个客户端发送的掩码帧(FIN=1, RSV=0)。
     * 掩码密钥随机生成,payload 按 RFC 6455 Section 5.3 与掩码异或。
     */
    fun encodeMaskedFrame(opcode: Int, payload: ByteArray, fin: Boolean = true): ByteArray {
        val b0 = (if (fin) 0x80 else 0x00) or (opcode and 0x0F)

        val maskKey = ByteArray(4)
        RNG.nextBytes(maskKey)

        val maskedPayload = payload.copyOf()
        for (i in maskedPayload.indices) {
            maskedPayload[i] = (maskedPayload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }

        val headerLen: Int
        val payloadLen = payload.size
        when {
            payloadLen <= 125 -> {
                headerLen = 6
                val out = ByteArray(headerLen + payloadLen)
                out[0] = b0.toByte()
                out[1] = (0x80 or payloadLen).toByte()
                System.arraycopy(maskKey, 0, out, 2, 4)
                System.arraycopy(maskedPayload, 0, out, 6, payloadLen)
                return out
            }
            payloadLen <= 0xFFFF -> {
                headerLen = 8
                val out = ByteArray(headerLen + payloadLen)
                out[0] = b0.toByte()
                out[1] = (0x80 or 126).toByte()
                out[2] = ((payloadLen ushr 8) and 0xFF).toByte()
                out[3] = (payloadLen and 0xFF).toByte()
                System.arraycopy(maskKey, 0, out, 4, 4)
                System.arraycopy(maskedPayload, 0, out, 8, payloadLen)
                return out
            }
            else -> {
                headerLen = 14
                val out = ByteArray(headerLen + payloadLen)
                out[0] = b0.toByte()
                out[1] = (0x80 or 127).toByte()
                for (i in 0 until 8) {
                    out[2 + i] = ((payloadLen.toLong() ushr ((7 - i) * 8)) and 0xFF).toByte()
                }
                System.arraycopy(maskKey, 0, out, 10, 4)
                System.arraycopy(maskedPayload, 0, out, 14, payloadLen)
                return out
            }
        }
    }
}
