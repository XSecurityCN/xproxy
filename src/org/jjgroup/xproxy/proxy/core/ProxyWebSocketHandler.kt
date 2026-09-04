package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH
import io.netty.util.AttributeKey
import java.util.HexFormat

// 二进制 WS 帧转十六进制预览用;HexFormat.of().formatHex 单次零分配,等价于逐字节 "%02x"(同为小写)。
private val WS_BINARY_HEX_FORMAT = HexFormat.of()

internal data class WsParsedFrame(
    val opcode: Int,
    val messageType: String,
    val payloadBytesLength: Int,
    val payloadText: String,
    val payload: ByteArray
)

/**
 * WebSocket 帧解析的跨 chunk 累积缓冲。
 *
 * 原实现 `var pending: ByteArray` + `pending = pending + chunk` 每次追加都新建数组拷贝全部历史字节,
 * 大消息分多 chunk 到达时为 O(n²)(1MB 消息分 10×100KB ≈ 5.5MB 拷贝)。这里用可增长内部缓冲:
 * append 摊销 O(chunk),drop 原地搬移未解析尾部到头部,解析全程按索引直读 backing array,零中转拷贝。
 */
internal class WsFrameTapState {
    private var buf: ByteArray = ByteArray(64)
    private var logicalSize: Int = 0

    /** 当前已累积、待解析的字节数。 */
    val size: Int get() = logicalSize

    /** 按索引读取已累积字节(不拷贝)。 */
    fun byteAt(index: Int): Byte = buf[index]

    /** 追加一个 chunk,容量不足时按 2× 扩容(摊销 O(chunk))。 */
    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        ensureCapacity(logicalSize + chunk.size)
        chunk.copyInto(buf, logicalSize)
        logicalSize += chunk.size
    }

    /** 拷贝出 [fromIndex, toIndex) 区间的字节(用于取 mask key / payload)。 */
    fun copyOfRange(fromIndex: Int, toIndex: Int): ByteArray = buf.copyOfRange(fromIndex, toIndex)

    /** 丢弃前 offset 字节,保留未解析尾部;offset >= size 则清空。 */
    fun drop(offset: Int) {
        if (offset >= logicalSize) {
            logicalSize = 0
        } else if (offset > 0) {
            System.arraycopy(buf, offset, buf, 0, logicalSize - offset)
            logicalSize -= offset
        }
    }

    fun clear() {
        logicalSize = 0
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity <= buf.size) return
        var newCap = buf.size
        while (newCap < minCapacity) newCap = newCap * 2 + 2
        buf = buf.copyOf(newCap)
    }
}

internal fun isWebSocketUpgradeRequest(request: HttpRequest): Boolean {
    val hasWsUpgrade = headerHasToken(request.headers().getAll(HttpHeaderNames.UPGRADE), HttpHeaderValues.WEBSOCKET.toString())
    val hasConnectionUpgrade = headerHasToken(request.headers().getAll(HttpHeaderNames.CONNECTION), HttpHeaderValues.UPGRADE.toString())
    val hasWsKey = !request.headers().get("Sec-WebSocket-Key").isNullOrBlank()
    val hasWsVersion = !request.headers().get("Sec-WebSocket-Version").isNullOrBlank()
    return (hasWsUpgrade && hasConnectionUpgrade)
        || (hasWsUpgrade && hasWsKey)
        || (hasWsKey && hasWsVersion)
}

internal fun isWebSocketUpgradeResponse(request: HttpRequest, response: HttpResponse): Boolean {
    if (response.status().code() != 101) {
        return false
    }
    if (!isWebSocketUpgradeRequest(request)) {
        return false
    }
    return true
}

internal fun headerHasToken(values: List<String>?, token: String): Boolean {
    if (values.isNullOrEmpty()) {
        return false
    }
    return values.asSequence()
        .flatMap { it.split(',').asSequence() }
        .map { it.trim() }
        .any { it.equals(token, ignoreCase = true) }
}

internal fun ProxyController.recordWsHandshakeRequest(sessionId: Long, host: String, path: String, requestRaw: String) {
    val id = wsHistoryId.incrementAndGet()
    val preview = requestRaw.lineSequence().firstOrNull()?.trim().orEmpty()
    onWsHistoryAdded?.invoke(
        ProxyWsHistoryEntry(
            id = id,
            timeMillis = System.currentTimeMillis(),
            host = host,
            path = path,
            direction = "C -> S",
            messageType = "Handshake Request",
            mimeType = "other",
            length = requestRaw.length,
            preview = preview,
            payload = requestRaw,
            sessionId = sessionId
        )
    )
}

internal fun ProxyController.recordWsHandshakeResponse(sessionId: Long, host: String, path: String, responseRaw: String) {
    val id = wsHistoryId.incrementAndGet()
    val preview = responseRaw.lineSequence().firstOrNull()?.trim().orEmpty()
    onWsHistoryAdded?.invoke(
        ProxyWsHistoryEntry(
            id = id,
            timeMillis = System.currentTimeMillis(),
            host = host,
            path = path,
            direction = "S -> C",
            messageType = "Handshake Response",
            mimeType = "other",
            length = responseRaw.length,
            preview = preview,
            payload = responseRaw,
            sessionId = sessionId
        )
    )
}

internal fun ProxyController.recordWsHandshakeHttpHistory(
    request: HttpRequest,
    requestRaw: String,
    responseRaw: String,
    response: HttpResponse,
    pipeline: HttpProxyInterceptPipeline
) {
    val responseLength = if (response is FullHttpResponse) {
        try {
            response.content().readableBytes()
        } catch (_: Exception) {
            0
        }
    } else {
        response.headers().get(CONTENT_LENGTH)?.toIntOrNull() ?: 0
    }
    onHistoryAdded?.invoke(
        ProxyHistoryEntry(
            id = historyId.incrementAndGet(),
            timeMillis = System.currentTimeMillis(),
            method = request.method().name(),
            host = request.headers().get("Host") ?: "",
            path = request.uri(),
            statusCode = response.status().code(),
            length = responseLength,
            mimeType = "text",
            title = "",
            tls = isTlsRequest(request, pipeline),
            modified = false,
            tool = "proxy",
            requestRaw = requestRaw,
            responseRaw = responseRaw,
            originalRequestRaw = "",
            originalResponseRaw = ""
        )
    )
}

internal fun ProxyController.installWebSocketTaps(clientChannel: Channel, proxyChannel: Channel, host: String, path: String, sessionId: Long) {
    // 为该会话建立"原连接"句柄:重放器复用它向 server 方向(proxyChannel)写帧、订阅 client 方向的入站响应。
    val liveConnection = org.jjgroup.xproxy.proxy.ws.WsLiveConnection(sessionId, proxyChannel)
    wsLiveConnections[sessionId] = liveConnection
    // clientChannel 的 write 拦截的是 proxy->client(服务端响应):通知 liveConnection 的入站订阅者。
    installWebSocketTap(
        channel = clientChannel,
        installedAttr = ProxyController.C2S_TAP_INSTALLED,
        handlerName = "xproxy-ws-tap-c2s",
        handlerFactory = { buildWsTapHandler(host, path, "C -> S", sessionId, liveConnection) }
    )
    // proxyChannel 的 write 拦截的是 proxy->server(客户端发送):仅记录,不通知(重放器自行记录出站帧)。
    installWebSocketTap(
        channel = proxyChannel,
        installedAttr = ProxyController.S2C_TAP_INSTALLED,
        handlerName = "xproxy-ws-tap-s2c",
        handlerFactory = { buildWsTapHandler(host, path, "S -> C", sessionId, null) }
    )
}

internal fun installWebSocketTap(
    channel: Channel,
    installedAttr: AttributeKey<Boolean>,
    handlerName: String,
    handlerFactory: () -> ChannelDuplexHandler
) {
    if (channel.attr(installedAttr).get() == true) {
        return
    }
    val installer = Runnable {
        if (channel.attr(installedAttr).get() == true) {
            return@Runnable
        }
        if (channel.pipeline().get(handlerName) == null) {
            channel.pipeline().addLast(handlerName, handlerFactory.invoke())
        }
        channel.attr(installedAttr).set(true)
    }
    if (channel.eventLoop().inEventLoop()) {
        installer.run()
    } else {
        channel.eventLoop().execute(installer)
    }
}

internal fun ProxyController.buildWsTapHandler(host: String, path: String, direction: String, sessionId: Long, liveConnection: org.jjgroup.xproxy.proxy.ws.WsLiveConnection?): ChannelDuplexHandler {
    val controller = this
    val state = WsFrameTapState()
    return object : ChannelDuplexHandler() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is ByteBuf) {
                try {
                    val bytes = ByteArray(msg.readableBytes())
                    msg.getBytes(msg.readerIndex(), bytes)
                    val frames = parseWebSocketFrames(bytes, state)
                    for (frame in frames) {
                        controller.recordWsMessage(host, path, direction, frame, sessionId)
                        // 仅 client 方向(响应)tap 持有 liveConnection:把入站帧推给重放器订阅者。
                        liveConnection?.notifyInbound(
                            org.jjgroup.xproxy.proxy.ws.WsRepeaterInboundFrame(frame.opcode, frame.payload, true)
                        )
                    }
                } catch (_: Exception) {
                }
            }
            ctx.write(msg, promise)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            state.clear()
            // 原连接任一方向关闭:标记死亡并清理句柄,重放器据此提示用户重连。
            liveConnection?.markDead()
            if (liveConnection != null) {
                controller.wsLiveConnections.remove(sessionId)
            }
            ctx.fireChannelInactive()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Utils.err("WebSocket tap error [$direction $host$path]: ${cause.message}")
            ctx.fireExceptionCaught(cause)
        }
    }
}

internal fun parseWebSocketFrames(chunk: ByteArray, state: WsFrameTapState): List<WsParsedFrame> {
    state.append(chunk)
    val frames = ArrayList<WsParsedFrame>()
    var offset = 0

    while (true) {
        if (state.size - offset < 2) {
            break
        }

        val b0 = state.byteAt(offset).toInt() and 0xFF
        val b1 = state.byteAt(offset + 1).toInt() and 0xFF
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var payloadLen = (b1 and 0x7F).toLong()
        var headerLen = 2

        if (payloadLen == 126L) {
            if (state.size - offset < headerLen + 2) {
                break
            }
            payloadLen = ((state.byteAt(offset + 2).toInt() and 0xFF) shl 8 or (state.byteAt(offset + 3).toInt() and 0xFF)).toLong()
            headerLen += 2
        } else if (payloadLen == 127L) {
            if (state.size - offset < headerLen + 8) {
                break
            }
            payloadLen = 0L
            for (i in 0 until 8) {
                payloadLen = (payloadLen shl 8) or (state.byteAt(offset + 2 + i).toInt() and 0xFF).toLong()
            }
            headerLen += 8
        }

        val maskKey: ByteArray? = if (masked) {
            if (state.size - offset < headerLen + 4) {
                break
            }
            val key = state.copyOfRange(offset + headerLen, offset + headerLen + 4)
            headerLen += 4
            key
        } else {
            null
        }

        val totalLen = headerLen + payloadLen
        if (payloadLen > Int.MAX_VALUE || state.size - offset < totalLen) {
            break
        }

        val payload = state.copyOfRange(offset + headerLen, offset + totalLen.toInt())
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        val messageType = when (opcode) {
            0x0 -> "Continuation"
            0x1 -> "Text"
            0x2 -> "Binary"
            0x8 -> "Close"
            0x9 -> "Ping"
            0xA -> "Pong"
            else -> "Opcode-$opcode"
        }
        val payloadText = when (opcode) {
            0x1, 0x0 -> payload.toString(Charsets.UTF_8)
            else -> WS_BINARY_HEX_FORMAT.formatHex(payload)
        }
        frames.add(WsParsedFrame(opcode, messageType, payload.size, payloadText, payload))
        offset += totalLen.toInt()
    }

    state.drop(offset)
    return frames
}

internal fun ProxyController.recordWsMessage(host: String, path: String, direction: String, frame: WsParsedFrame, sessionId: Long) {
    val payload = if (frame.payloadText.length > 8192) frame.payloadText.substring(0, 8192) else frame.payloadText
    val preview = payload.replace("\r", " ").replace("\n", " ").take(200)
    val mimeType = when (frame.messageType) {
        "Text", "Continuation" -> {
            val trimmed = payload.trimStart()
            when {
                trimmed.startsWith("{") || trimmed.startsWith("[") -> "json"
                trimmed.startsWith("<?xml", true) || trimmed.startsWith("<") -> "xml"
                else -> "text"
            }
        }
        "Binary" -> "bin"
        else -> "other"
    }
    onWsHistoryAdded?.invoke(
        ProxyWsHistoryEntry(
            id = wsHistoryId.incrementAndGet(),
            timeMillis = System.currentTimeMillis(),
            host = host,
            path = path,
            direction = direction,
            messageType = frame.messageType,
            mimeType = mimeType,
            length = frame.payloadBytesLength,
            preview = preview,
            payload = payload,
            sessionId = sessionId
        )
    )
}

/**
 * 从 WebSocket 握手请求的 Host 头解析 host/port,用于构造 [WsSession]。
 * 支持 IPv6 字面量([::1]:8080 / [::1])与常规 host:port;端口缺省时按 tls 取默认值(443/80)。
 */
internal fun parseWsHostPort(hostHeader: String, tls: Boolean): Pair<String, Int> {
    val raw = hostHeader.trim()
    if (raw.startsWith("[")) {
        val end = raw.indexOf(']')
        if (end > 0) {
            val h = raw.substring(1, end)
            val rest = raw.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
            return h to (port ?: if (tls) 443 else 80)
        }
    }
    val idx = raw.lastIndexOf(':')
    if (idx > 0 && raw.indexOf(':') == idx) {
        val port = raw.substring(idx + 1).toIntOrNull()
        if (port != null) {
            return raw.substring(0, idx) to port
        }
    }
    return raw to (if (tls) 443 else 80)
}
