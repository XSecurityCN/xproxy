package org.jjgroup.xproxy.proxy.core

import com.github.monkeywie.proxyee.exception.HttpProxyExceptionHandle
import com.github.monkeywie.proxyee.handler.HttpProxyServerHandler
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer
import com.github.monkeywie.proxyee.proxy.ProxyConfig
import com.github.monkeywie.proxyee.server.HttpProxyServerConfig
import com.github.monkeywie.proxyee.util.ProtoUtil
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.ssl.SslContext
import java.lang.reflect.Method
import java.net.InetAddress

/**
 * 同一端口复用 HTTP/HTTPS/SOCKS5 的协议嗅探与 SOCKS5 握手支持。
 *
 * 设计:统一 Netty server 的 childHandler 最前装 [ProtocolSnifferHandler],按首字节分流——
 * - 0x05 → SOCKS5:换成 [Socks5HandshakeHandler] 走握手 + CONNECT;
 * - 其他 → HTTP/HTTPS:装 HttpServerCodec + proxyee 的 [HttpProxyServerHandler](原样复用)。
 *
 * SOCKS5 CONNECT 成功后,隧道上的字节流(TLS ClientHello 或明文 HTTP)与 HTTP CONNECT 隧道完全一致,
 * 因此换成 [SocksTunnelProxyHandler](proxyee handler 子类,手动置为"已 CONNECT"状态)接管,
 * 完整复用 proxyee 的 MITM + 拦截 + 上游转发能力,无需伪造 CONNECT、无需吞 200 响应。
 */

/** SOCKS5 协议版本字节。 */
internal const val SOCKS5_VERSION: Int = 0x05

/** TLS 记录首字节(Handshake)。 */
internal const val TLS_HANDSHAKE_BYTE: Int = 0x16

/** 按首字节分流 SOCKS5 / TLS-on-listener / 明文 HTTP 的嗅探 handler。装在 pipeline 最前。 */
internal class ProtocolSnifferHandler(
    private val serverConfig: HttpProxyServerConfig,
    private val interceptInitializer: HttpProxyInterceptInitializer,
    private val proxyConfig: ProxyConfig?,
    private val exceptionHandle: HttpProxyExceptionHandle,
    private val listenerSslContextProvider: () -> SslContext? = { null }
) : ByteToMessageDecoder() {

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        if (buf.readableBytes() < 1) {
            return
        }
        val firstByte = buf.getByte(buf.readerIndex()).toInt() and 0xFF
        when (firstByte) {
            SOCKS5_VERSION -> {
                // SOCKS5 握手,换成 SOCKS5 握手 handler;剩余字节由 Netty 传播给它。
                ctx.pipeline().replace(
                    this, "socks5Handshake",
                    Socks5HandshakeHandler(serverConfig, interceptInitializer, proxyConfig, exceptionHandle)
                )
            }
            TLS_HANDSHAKE_BYTE -> {
                val sslContext = listenerSslContextProvider()
                if (sslContext != null) {
                    // HTTPS 代理(TLS-on-listener):监听端先 TLS 握手,内层走标准 HTTP 代理协议。
                    // 顺序:SslHandler(解密) -> HttpServerCodec(解码 HTTP) -> HttpProxyServerHandler(代理)。
                    // 移除本嗅探 handler 时,ByteToMessageDecoder.handlerRemoved 会把累积的 ClientHello
                    // 字节 fire 给下一个 inbound(SslHandler),触发握手。
                    ctx.pipeline().addAfter(ctx.name(), "sslHandle", sslContext.newHandler(ctx.alloc()))
                    ctx.pipeline().addAfter(
                        "sslHandle", "httpCodec",
                        HttpServerCodec(
                            serverConfig.maxInitialLineLength,
                            serverConfig.maxHeaderSize,
                            serverConfig.maxChunkSize
                        )
                    )
                    ctx.pipeline().addAfter(
                        "httpCodec", "serverHandle",
                        HttpProxyServerHandler(serverConfig, interceptInitializer, proxyConfig, exceptionHandle)
                    )
                    ctx.pipeline().remove(this)
                } else {
                    // 未启用 SSL(无 CA 材料):无法做 TLS-on-listener,按明文 HTTP 处理(解码会失败并关闭)。
                    installHttpChain(ctx)
                }
            }
            else -> {
                // 明文 HTTP 代理:装 proxyee 的 HttpServerCodec + HttpProxyServerHandler(与原 proxyee server 一致)。
                installHttpChain(ctx)
            }
        }
    }

    private fun installHttpChain(ctx: ChannelHandlerContext) {
        ctx.pipeline().addAfter(
            ctx.name(), "httpCodec",
            HttpServerCodec(
                serverConfig.maxInitialLineLength,
                serverConfig.maxHeaderSize,
                serverConfig.maxChunkSize
            )
        )
        ctx.pipeline().addAfter(
            "httpCodec", "serverHandle",
            HttpProxyServerHandler(serverConfig, interceptInitializer, proxyConfig, exceptionHandle)
        )
        ctx.pipeline().remove(this)
    }
}

/**
 * proxyee [HttpProxyServerHandler] 的子类:SOCKS5 CONNECT 成功后,手动进入"已 CONNECT"状态,
 * 使后续隧道字节直接走 proxyee 的 ByteBuf 分支(TLS MITM / 明文 HTTP 拦截 / 盲转发)。
 */
internal class SocksTunnelProxyHandler(
    serverConfig: HttpProxyServerConfig,
    interceptInitializer: HttpProxyInterceptInitializer,
    proxyConfig: ProxyConfig?,
    exceptionHandle: HttpProxyExceptionHandle
) : HttpProxyServerHandler(serverConfig, interceptInitializer, proxyConfig, exceptionHandle) {

    /** 进入"已建立隧道"状态:status=2(对应 proxyee CONNECT 分支处理后的状态),requestProto=SOCKS5 目标。 */
    fun enterTunnel(host: String, port: Int) {
        status = 2
        // proxy=true:SOCKS5 隧道里的请求是"经代理转发到上游",而非"直连代理本身"。proxyee 的
        // CertDownIntercept 据 requestProto.getProxy() 区分二者--false 时回 CA 下载页(参见
        // [[proxy-connect-tunnel-needs-proxy-connection-header]])。HTTP-CONNECT 流程靠 CONNECT 请求带
        // Proxy-Connection 头让 ProtoUtil.getRequestProto 置 proxy=true;SOCKS5 无此阶段,必须手动置真。
        requestProto = ProtoUtil.RequestProto(host, port, false).apply { proxy = true }
    }

    /** 此连接已进入纯隧道盲转发(非标准 TLS,如微信 mmtls)。置真后后续字节直接转发到上游,不再判协议。 */
    private var rawTunnel = false

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // SOCKS5 隧道建立后(status==2),明文 HTTP 字节流需手动 addFirst httpCodec + fireChannelRead。
        // 直接走 super(proxyee)的 isHttp 分支虽也是 addFirst+fireChannelRead,但 re-entrant 解码出的
        // HttpRequest 不会回到本 handler(经反复对比:同等代码在子类 override 内调用可正常 re-entrant,
        // 在 proxyee super 内调用则解码结果丢失),导致明文 HTTP over SOCKS5 不转发、不抓包。
        if (msg is ByteBuf && status == 2) {
            // 已进入纯隧道(mmtls 等):直接走 proxyee 盲转发(首次建上游连接,后续直写字节)。
            if (rawTunnel) {
                forwardRawTunnel(ctx, msg)
                return
            }
            val httpCodecPresent = ctx.pipeline().get("httpCodec") != null
            if (httpCodecPresent) {
                // 明文 HTTP 续包:httpCodec 已在,直接喂给它累积解码。
                ctx.pipeline().fireChannelRead(msg)
                return
            }
            val firstByte = if (msg.readableBytes() > 0) msg.getByte(msg.readerIndex()).toInt() and 0xFF else -1
            if (firstByte != 0x16) {
                // 明文 HTTP 首包:装 httpCodec 再驱动解码,后续 HttpRequest/HttpContent 回到 super 走拦截链。
                ctx.pipeline().addFirst(
                    "httpCodec",
                    HttpServerCodec(
                        serverConfig.maxInitialLineLength,
                        serverConfig.maxHeaderSize,
                        serverConfig.maxChunkSize
                    )
                )
                ctx.pipeline().fireChannelRead(msg)
                return
            }
            // 0x16:区分标准 TLS(交 super 走 SslHandler MITM)与非标准 TLS(微信 mmtls,盲转发)。
            // 标准记录层版本号高字节恒为 0x03(SSL3 0x0300 ~ TLS1.3 记录层仍 0x0303);微信 mmtls 也以 0x16
            // 开头但版本号为 0xf1 0x04,走 MITM 必 NotSslRecordException 关连接 -> 微信 fallback,443 流量全丢
            // (实测 25 次 NotSslRecordException,history 只剩 80 端口短连接)。故非标准 TLS 直接盲转发。
            val isStandardTls = msg.readableBytes() >= 2 &&
                (msg.getByte(msg.readerIndex() + 1).toInt() and 0xFF) == 0x03
            if (!isStandardTls) {
                rawTunnel = true
                forwardRawTunnel(ctx, msg)
                return
            }
        }
        super.channelRead(ctx, msg)
    }

    /**
     * 反射调 proxyee 私有 `handleProxyData(channel, msg, isHttp=false)`:首次调用建立到上游的纯隧道
     * (TunnelProxyInitializer,双向 relay + 上游代理配置),后续调用直接转发字节(cf 已建直写,未建缓冲到
     * requestList)。等价于 super 在 `doMitm()==false && !isHttp` 时的盲转发分支,但绕过了 doMitm 对非标准
     * TLS 强装 SslHandler 的失败路径。反射复用 proxyee 连接/relay 逻辑,避免重写;若签名变更,catch 后 fallback。
     */
    private fun forwardRawTunnel(ctx: ChannelHandlerContext, msg: Any) {
        try {
            handleProxyDataMethod.invoke(this, ctx.channel(), msg, false)
        } catch (e: Throwable) {
            // 反射失败(proxyee 升级等极端情况):fallback super,可能 MITM 失败关连接,但不劣于现状。
            super.channelRead(ctx, msg)
        }
    }

    companion object {
        private val handleProxyDataMethod: Method by lazy {
            HttpProxyServerHandler::class.java
                .getDeclaredMethod("handleProxyData", Channel::class.java, Any::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }
    }
}

/** SOCKS5 握手(method negotiation + CONNECT 命令)handler。成功后换成 [SocksTunnelProxyHandler]。 */
internal class Socks5HandshakeHandler(
    private val serverConfig: HttpProxyServerConfig,
    private val interceptInitializer: HttpProxyInterceptInitializer,
    private val proxyConfig: ProxyConfig?,
    private val exceptionHandle: HttpProxyExceptionHandle
) : ByteToMessageDecoder() {

    private enum class Step { METHOD_NEGOTIATION, CONNECT_REQUEST }

    private var step = Step.METHOD_NEGOTIATION

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        when (step) {
            Step.METHOD_NEGOTIATION -> handleMethodNegotiation(ctx, buf)
            Step.CONNECT_REQUEST -> handleConnectRequest(ctx, buf)
        }
    }

    // [VER, NMETHODS, METHODS...]
    private fun handleMethodNegotiation(ctx: ChannelHandlerContext, buf: ByteBuf) {
        if (buf.readableBytes() < 2) {
            return
        }
        val version = buf.getByte(buf.readerIndex()).toInt() and 0xFF
        val nmethods = buf.getByte(buf.readerIndex() + 1).toInt() and 0xFF
        if (buf.readableBytes() < 2 + nmethods) {
            return
        }
        if (version != SOCKS5_VERSION) {
            ctx.close()
            return
        }
        var noAuthOffered = false
        for (i in 0 until nmethods) {
            val method = buf.getByte(buf.readerIndex() + 2 + i).toInt() and 0xFF
            if (method == 0x00) {
                noAuthOffered = true
                break
            }
        }
        buf.skipBytes(2 + nmethods)
        if (!noAuthOffered) {
            // [VER, METHOD=0xFF] 无可用认证方法
            ctx.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(SOCKS5_VERSION.toByte(), 0xFF.toByte())))
            ctx.close()
            return
        }
        // [VER, METHOD=0x00] 选择 no-auth
        ctx.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(SOCKS5_VERSION.toByte(), 0x00.toByte())))
        step = Step.CONNECT_REQUEST
    }

    // [VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT]
    private fun handleConnectRequest(ctx: ChannelHandlerContext, buf: ByteBuf) {
        if (buf.readableBytes() < 4) {
            return
        }
        val version = buf.getByte(buf.readerIndex()).toInt() and 0xFF
        val cmd = buf.getByte(buf.readerIndex() + 1).toInt() and 0xFF
        if (version != SOCKS5_VERSION) {
            ctx.close()
            return
        }
        if (cmd != 0x01) {
            // 仅支持 CONNECT(0x01);BIND/UDP ASSOCIATE 回 0x07(命令不支持)
            writeSocks5Reply(ctx, 0x07)
            ctx.close()
            return
        }
        val parsed = parseSocks5ConnectAddress(buf) ?: run {
            // 地址类型非法(解析失败且字节够)回 0x08;字节不够则等更多数据(parse 返回 null 时已区分)
            if (isAddressTypeUnsupported(buf)) {
                writeSocks5Reply(ctx, 0x08)
                ctx.close()
            }
            return
        }
        val (host, port, consumed) = parsed
        buf.skipBytes(consumed)
        // 回成功,再换成隧道 handler;剩余字节(若有)由 Netty 传播给它。
        writeSocks5Reply(ctx, 0x00)
        val tunnelHandler = SocksTunnelProxyHandler(serverConfig, interceptInitializer, proxyConfig, exceptionHandle)
        tunnelHandler.enterTunnel(host, port)
        ctx.pipeline().replace(this, "serverHandle", tunnelHandler)
    }

    private fun writeSocks5Reply(ctx: ChannelHandlerContext, rep: Int) {
        // [VER, REP, RSV=0x00, ATYP=0x01, BND.ADDR=0.0.0.0, BND.PORT=0]
        val reply = byteArrayOf(
            SOCKS5_VERSION.toByte(), rep.toByte(), 0x00, 0x01,
            0, 0, 0, 0, 0, 0
        )
        ctx.writeAndFlush(Unpooled.wrappedBuffer(reply))
    }

    private fun isAddressTypeUnsupported(buf: ByteBuf): Boolean {
        if (buf.readableBytes() < 4) {
            return false
        }
        val atyp = buf.getByte(buf.readerIndex() + 3).toInt() and 0xFF
        return atyp != 0x01 && atyp != 0x03 && atyp != 0x04
    }

    /**
     * 解析 CONNECT 命令的目标地址。返回 (host, port, 整个命令消费的字节数);字节不足或地址类型不支持返回 null。
     */
    private fun parseSocks5ConnectAddress(buf: ByteBuf): Triple<String, Int, Int>? {
        val base = buf.readerIndex()
        val atyp = buf.getByte(base + 3).toInt() and 0xFF
        return when (atyp) {
            0x01 -> { // IPv4
                val need = 4 + 4 + 2
                if (buf.readableBytes() < need) return null
                val host = (0 until 4).joinToString(".") { (buf.getByte(base + 4 + it).toInt() and 0xFF).toString() }
                val port = buf.getUnsignedShort(base + 8)
                Triple(host, port, need)
            }
            0x03 -> { // DOMAIN
                if (buf.readableBytes() < 5) return null
                val len = buf.getByte(base + 4).toInt() and 0xFF
                val need = 4 + 1 + len + 2
                if (buf.readableBytes() < need) return null
                val hostBytes = ByteArray(len)
                buf.getBytes(base + 5, hostBytes)
                val host = String(hostBytes, Charsets.US_ASCII)
                val port = buf.getUnsignedShort(base + 5 + len)
                Triple(host, port, need)
            }
            0x04 -> { // IPv6
                val need = 4 + 16 + 2
                if (buf.readableBytes() < need) return null
                val addrBytes = ByteArray(16)
                buf.getBytes(base + 4, addrBytes)
                val host = InetAddress.getByAddress(addrBytes).hostAddress
                val port = buf.getUnsignedShort(base + 20)
                Triple(host, port, need)
            }
            else -> null
        }
    }
}
