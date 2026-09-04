package org.jjgroup.xproxy.proxy.ws

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.request.openProxyAwareSocket
import org.jjgroup.xproxy.proxy.core.WsFrameTapState
import org.jjgroup.xproxy.proxy.core.parseWebSocketFrames
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * 重放目标:承载一次 WebSocket 握手所需的全部上下文(由 [org.jjgroup.xproxy.proxy.model.WsSession] 派生)。
 */
data class WsRepeaterTarget(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val path: String,
    val handshakeRequest: String
)

/**
 * 一条入站(服务端->客户端)帧;payload 为已去掩码的原始字节。
 */
data class WsRepeaterInboundFrame(
    val opcode: Int,
    val payload: ByteArray,
    val fin: Boolean
) {
    val opcodeName: String get() = WsFrameCodec.opcodeName(opcode)

    /** 文本帧按 UTF-8 展示,二进制/控制帧按十六进制展示(与代理侧 WS 历史预览一致)。 */
    val displayText: String
        get() = when (opcode) {
            WsFrameCodec.OPCODE_TEXT, WsFrameCodec.OPCODE_CONTINUATION ->
                String(payload, Charsets.UTF_8)
            else -> HexFormat.of().formatHex(payload)
        }
}

/**
 * WebSocket 重放连接:封装一次已完成的握手(101)后的底层 socket,支持发送掩码帧、读取入站帧、
 * 自动应答 Ping、处理 Close。非线程安全:发送与读取由调用方串行调度(重放器内部单线程执行器)。
 */
class WsRepeaterConnection internal constructor(
    internal val socket: Socket,
    internal val target: WsRepeaterTarget,
    val handshakeResponseRaw: String
) {
    private val input: InputStream = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val inboundState = WsFrameTapState()
    @Volatile
    var closed: Boolean = false
        private set

    fun sendFrame(opcode: Int, payload: ByteArray) {
        if (closed) {
            throw ConnectException("WebSocket connection is closed")
        }
        val frame = WsFrameCodec.encodeMaskedFrame(opcode, payload)
        output.write(frame)
        output.flush()
    }

    /**
     * 读取入站帧直至:idle 超时(一段时间无新数据)、总时长超限、收到 Close、连接 EOF 或 [shouldCancel] 为真。
     * 每读到一帧回调 [onFrame];Ping 帧自动应答 Pong,不向调用方回调控制帧以外的事件(仍回调,便于展示)。
     */
    fun readInboundFrames(
        onFrame: (WsRepeaterInboundFrame) -> Unit,
        shouldCancel: () -> Boolean,
        idleTimeoutMs: Long = 600L,
        maxDurationMs: Long = 3000L
    ) {
        val start = System.currentTimeMillis()
        var lastActivity = start
        socket.soTimeout = 200
        val buffer = ByteArray(8192)
        try {
            while (true) {
                if (shouldCancel()) return
                val now = System.currentTimeMillis()
                if (now - start > maxDurationMs) return
                if (now - lastActivity > idleTimeoutMs) return
                val read = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (read <= 0) {
                    // EOF:服务端关闭
                    closed = true
                    return
                }
                lastActivity = System.currentTimeMillis()
                val chunk = ByteArray(read)
                System.arraycopy(buffer, 0, chunk, 0, read)
                val frames = parseWebSocketFrames(chunk, inboundState)
                for (frame in frames) {
                    val opcode = opcodeFromMessageName(frame.messageType)
                    val payloadBytes = decodePayload(frame.payloadText, opcode)
                    // Ping 自动应答 Pong(同 payload),Close 回 Close 并结束,均不阻塞调用方展示。
                    when (opcode) {
                        WsFrameCodec.OPCODE_PING -> {
                            runCatching { sendFrame(WsFrameCodec.OPCODE_PONG, payloadBytes) }
                        }
                        WsFrameCodec.OPCODE_CLOSE -> {
                            runCatching { sendFrame(WsFrameCodec.OPCODE_CLOSE, ByteArray(0)) }
                            closed = true
                            onFrame(WsRepeaterInboundFrame(opcode, payloadBytes, true))
                            return
                        }
                    }
                    onFrame(WsRepeaterInboundFrame(opcode, payloadBytes, true))
                }
            }
        } catch (_: SocketTimeoutException) {
            // 兜底:不应到达(soTimeout 已在循环内捕获)
        }
    }

    fun close() {
        closed = true
        runCatching {
            if (!socket.isClosed) {
                socket.close()
            }
        }
    }

    private fun opcodeFromMessageName(name: String): Int = when (name) {
        "Continuation" -> WsFrameCodec.OPCODE_CONTINUATION
        "Text" -> WsFrameCodec.OPCODE_TEXT
        "Binary" -> WsFrameCodec.OPCODE_BINARY
        "Close" -> WsFrameCodec.OPCODE_CLOSE
        "Ping" -> WsFrameCodec.OPCODE_PING
        "Pong" -> WsFrameCodec.OPCODE_PONG
        else -> WsFrameCodec.OPCODE_BINARY
    }

    /** parseWebSocketFrames 对二进制帧返回十六进制串,文本帧返回 UTF-8 串;此处反向还原为原始字节。 */
    private fun decodePayload(payloadText: String, opcode: Int): ByteArray {
        return when (opcode) {
            WsFrameCodec.OPCODE_TEXT, WsFrameCodec.OPCODE_CONTINUATION ->
                payloadText.toByteArray(Charsets.UTF_8)
            else -> {
                runCatching { HexFormat.of().parseHex(payloadText) }.getOrElse { ByteArray(0) }
            }
        }
    }
}

object WsRepeaterClient {

    private val RNG = SecureRandom()

    /**
     * 建立到 [target] 的 WebSocket 连接:打开(可能经上游代理/SSL)socket -> 发送(重生成的)握手请求 -> 读取并校验 101。
     * 握手请求中 Sec-WebSocket-Key 替换为新的随机值,Sec-WebSocket-Extensions 去除(重放路径不实现 permessage-deflate)。
     */
    fun connect(
        target: WsRepeaterTarget,
        shouldCancel: () -> Boolean = { false }
    ): WsRepeaterConnection {
        val socket = openProxyAwareSocket(target.host, target.port, target.tls)
        socket.soTimeout = 10000
        socket.tcpNoDelay = true
        try {
            if (shouldCancel()) {
                throw InterruptedException("Handshake cancelled")
            }
            val (handshakeBytes, _) = prepareHandshakeRequest(target.handshakeRequest)
            socket.getOutputStream().write(handshakeBytes)
            socket.getOutputStream().flush()
            val responseRaw = readHandshakeResponse(socket)
            val firstLine = responseRaw.lineSequence().firstOrNull()?.trim().orEmpty()
            if (!firstLine.startsWith("HTTP/1.1 101") && !firstLine.startsWith("HTTP/1.0 101")) {
                throw ConnectException("WebSocket handshake failed: $firstLine")
            }
            return WsRepeaterConnection(socket, target, responseRaw)
        } catch (ex: Exception) {
            runCatching { socket.close() }
            throw ex
        }
    }

    /**
     * 规整握手请求:替换 Sec-WebSocket-Key 为新生成的随机值;去除 Sec-WebSocket-Extensions;
     * 缺失 Connection/Upgrade/Sec-WebSocket-Key 时补齐。返回 (待发送字节, 新生成的 key 明文)。
     */
    internal fun prepareHandshakeRequest(rawHandshake: String): Pair<ByteArray, String> {
        val normalized = rawHandshake.replace("\r\n", "\n")
        val lines = normalized.split("\n").toMutableList()
        // 去除可能存在的尾随空行,稍后统一追加 CRLF 结束。
        while (lines.isNotEmpty() && lines.last().isBlank()) {
            lines.removeAt(lines.lastIndex)
        }

        val newKey = generateSecWebSocketKey()
        var hasKey = false
        var hasUpgrade = false
        var hasConnection = false
        val retained = ArrayList<String>(lines.size)
        for (line in lines) {
            val name = line.substringBefore(':', "").trim().lowercase()
            when {
                name == "sec-websocket-extensions" -> continue // 重放不实现压缩扩展
                name == "sec-websocket-key" -> {
                    retained.add("Sec-WebSocket-Key: $newKey")
                    hasKey = true
                }
                name == "upgrade" -> {
                    retained.add(line)
                    hasUpgrade = true
                }
                name == "connection" -> {
                    retained.add(line)
                    hasConnection = true
                }
                else -> retained.add(line)
            }
        }
        if (!hasKey) {
            retained.add("Sec-WebSocket-Key: $newKey")
        }
        if (!hasUpgrade) {
            retained.add("Upgrade: websocket")
        }
        if (!hasConnection) {
            retained.add("Connection: Upgrade")
        }
        val out = retained.joinToString("\r\n") + "\r\n\r\n"
        return out.toByteArray(Charsets.ISO_8859_1) to newKey
    }

    internal fun generateSecWebSocketKey(): String {
        val bytes = ByteArray(16)
        RNG.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun readHandshakeResponse(socket: Socket): String {
        val input = socket.getInputStream()
        val out = ByteArrayOutputStream()
        var b0 = -1
        var b1 = -1
        var b2 = -1
        var b3 = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                throw ConnectException("Unexpected EOF while reading WebSocket handshake response")
            }
            out.write(b)
            b0 = b1
            b1 = b2
            b2 = b3
            b3 = b
            if (b0 == '\r'.code && b1 == '\n'.code && b2 == '\r'.code && b3 == '\n'.code) {
                break
            }
            if (out.size() > 65536) {
                throw ConnectException("WebSocket handshake response headers too large")
            }
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }
}
