package org.jjgroup.xproxy.proxy.ws

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 代理侧仍在存活的 WebSocket 连接句柄,供重放器"复用原连接"使用。
 *
 * - [serverChannel] 即 proxy->server 方向的 netty 通道(proxyChannel),重放器向其写入掩码帧即等于
 *   在原连接上向服务端发送(保留服务端会话状态,如鉴权/订阅)。
 * - 入站(服务端->客户端)帧由代理 tap 解析后回调 [notifyInbound],转发给订阅者(重放器入站帧表)。
 * - 任一方向通道关闭时 [markDead],[alive] 变 false;重放器据此提示用户手动重连(回退到独立新连接)。
 *
 * 注意:本句柄不持有 socket 生命周期(连接属代理),仅作写入与订阅;[markDead]/订阅清理由 tap 的
 * channelInactive 触发,重放器断开/重连时仅取消订阅、不关闭底层通道。
 */
class WsLiveConnection(
    val sessionId: Long,
    private val serverChannel: Channel
) {
    private val inboundSubscribers = CopyOnWriteArrayList<(WsRepeaterInboundFrame) -> Unit>()
    private val deadSubscribers = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var aliveFlag: Boolean = true

    /** 连接是否仍可用(tap 未标记死亡且 netty 通道仍开)。 */
    val alive: Boolean
        get() = aliveFlag && serverChannel.isOpen

    /** 向原连接的服务端方向写入一帧(客户端->服务端必须掩码)。返回是否已发起写入。 */
    fun sendFrame(opcode: Int, payload: ByteArray): Boolean {
        if (!alive) return false
        val masked = WsFrameCodec.encodeMaskedFrame(opcode, payload)
        val buf: ByteBuf = Unpooled.wrappedBuffer(masked)
        return try {
            serverChannel.writeAndFlush(buf)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 订阅入站帧(服务端响应);返回取消订阅句柄。 */
    fun subscribeInbound(cb: (WsRepeaterInboundFrame) -> Unit): () -> Unit {
        inboundSubscribers.add(cb)
        return { inboundSubscribers.remove(cb) }
    }

    /** 订阅连接断开事件;返回取消订阅句柄。 */
    fun subscribeDead(cb: () -> Unit): () -> Unit {
        deadSubscribers.add(cb)
        return { deadSubscribers.remove(cb) }
    }

    /** tap 解析到入站帧后调用,转发给所有订阅者。 */
    fun notifyInbound(frame: WsRepeaterInboundFrame) {
        inboundSubscribers.forEach { runCatching { it.invoke(frame) } }
    }

    /** tap 的 channelInactive 调用:标记死亡并通知订阅者。 */
    fun markDead() {
        if (!aliveFlag) return
        aliveFlag = false
        deadSubscribers.forEach { runCatching { it.invoke() } }
    }
}
