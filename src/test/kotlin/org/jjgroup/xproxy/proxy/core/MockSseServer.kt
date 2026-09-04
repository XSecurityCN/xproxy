package org.jjgroup.xproxy.proxy.core

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import java.io.Closeable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 自包含的 SSE 测试上游:在 127.0.0.1 临时端口上提供 `/sse/{n}`(发 N 个事件后干净关闭:
 * 前 N-1 个 `item`、第 N 个 `end`)与任意其它路径(持续推送 `item` 直到客户端断开),
 * 替代此前对本地 xhttp(80/443)与外部 echo.websocket.org 的依赖,让端到端 SSE 测试可离线、
 * 在 CI 上稳定运行。
 *
 * 两种传输:
 * - `start(tls = false)`:plain HTTP/1.1,chunked 流式(覆盖 fuzzer 直连 + 代理绝对 URI 转发路径)。
 * - `start(tls = true)`:TLS(SelfSignedCertificate)+ ALPN(h2, http/1.1),同一服务按代理与上游
 *   协商结果用 H2 或 H1 应答,覆盖代理 CONNECT+MITM 的 HTTPS / H2 路径。H2 分支沿用
 *   [ProxyH2ForwardConcurrencyTest] 已验证的 Http2FrameCodecBuilder.forServer + Http2MultiplexHandler
 *   + Http2StreamFrameToHttpObjectCodec 模式;一个共享 SseHandler 经 HttpObjectAggregator 聚合请求,
 *   对两种协议写出相同的 HttpResponse / HttpContent / LastHttpContent 流(H2 由 codec 转帧)。
 *
 * 事件用通道事件循环调度,使代理的 onHistoryUpdated 在时间上分次触发(验证实时刷新);
 * 连续流在 channelInactive 时取消调度。实现 Closeable,在测试 finally 中 close()。
 */
class MockSseServer private constructor(
    val host: String,
    val port: Int,
    private val serverChannel: Channel,
    private val bossGroup: EventLoopGroup,
    private val workerGroup: EventLoopGroup,
    private val ssc: SelfSignedCertificate?
) : Closeable {

    override fun close() {
        runCatching { serverChannel.close().sync() }
        runCatching { bossGroup.shutdownGracefully() }
        runCatching { workerGroup.shutdownGracefully() }
        runCatching { ssc?.delete() }
    }

    companion object {
        /** 启动 SSE 测试上游;[tls]=true 走 TLS+ALPN(h2/http1.1),否则 plain HTTP/1.1。 */
        fun start(tls: Boolean = false): MockSseServer {
            val bossGroup = NioEventLoopGroup(1)
            val workerGroup = NioEventLoopGroup(2)
            val ssc = if (tls) SelfSignedCertificate() else null
            val sslCtx = ssc?.let {
                SslContextBuilder.forServer(it.certificate(), it.privateKey())
                    .applicationProtocolConfig(
                        ApplicationProtocolConfig(
                            ApplicationProtocolConfig.Protocol.ALPN,
                            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                            ApplicationProtocolNames.HTTP_2,
                            ApplicationProtocolNames.HTTP_1_1
                        )
                    ).build()
            }

            val serverChannel = ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) {
                        if (sslCtx != null) {
                            ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()))
                            ch.pipeline().addLast(SseAlpnHandler())
                        } else {
                            ch.pipeline().addLast(HttpServerCodec())
                            ch.pipeline().addLast(HttpObjectAggregator(1024 * 1024))
                            ch.pipeline().addLast(SseHandler(chunked = true))
                        }
                    }
                })
                .bind("127.0.0.1", 0)
                .sync()
                .channel()

            val port = serverChannel.localAddress() as java.net.InetSocketAddress
            return MockSseServer(
                host = "127.0.0.1",
                port = port.port,
                serverChannel = serverChannel,
                bossGroup = bossGroup,
                workerGroup = workerGroup,
                ssc = ssc
            )
        }
    }

    /** ALPN 协商:按结果装配 H2(每流 codec+聚合+SseHandler)或 H1(codec+聚合+SseHandler)管线。 */
    private class SseAlpnHandler :
        ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
        override fun configurePipeline(ctx: ChannelHandlerContext, protocol: String) {
            when (protocol) {
                ApplicationProtocolNames.HTTP_2 -> {
                    ctx.pipeline().addLast(Http2FrameCodecBuilder.forServer().build())
                    ctx.pipeline().addLast(
                        Http2MultiplexHandler(object : ChannelInitializer<Channel>() {
                            override fun initChannel(stream: Channel) {
                                stream.pipeline().addLast(Http2StreamFrameToHttpObjectCodec(true))
                                stream.pipeline().addLast(HttpObjectAggregator(1024 * 1024))
                                stream.pipeline().addLast(SseHandler(chunked = false))
                            }
                        })
                    )
                }

                else -> {
                    ctx.pipeline().addLast(HttpServerCodec())
                    ctx.pipeline().addLast(HttpObjectAggregator(1024 * 1024))
                    ctx.pipeline().addLast(SseHandler(chunked = true))
                }
            }
        }
    }

    private enum class SseMode { FINITE, CONTINUOUS }

    /**
     * SSE 应答处理器。[chunked]=true 时(H1)在响应头加 Transfer-Encoding: chunked;
     * H2 由 Http2StreamFrameToHttpObjectCodec 自动剥离 hop-by-hop 头并按帧转发。
     */
    private class SseHandler(private val chunked: Boolean) :
        SimpleChannelInboundHandler<FullHttpRequest>() {

        private val scheduled = mutableListOf<ScheduledFuture<*>>()
        @Volatile private var stopped = false
        private val finitePath = Regex("/sse/(\\d+)")

        override fun channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest) {
            // SimpleChannelInboundHandler 会在 channelRead0 返回后自动 release,这里不要手动 release(否则双重释放)。
            val path = req.uri().substringBefore('?')
            val match = finitePath.matchEntire(path) ?: finitePath.find(path)
            val mode = if (match != null) SseMode.FINITE else SseMode.CONTINUOUS
            val count = match?.groupValues?.get(1)?.toIntOrNull() ?: 0

            val resp: HttpResponse = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
            resp.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
            if (chunked) {
                resp.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
            }
            ctx.write(resp)

            when (mode) {
                SseMode.FINITE -> scheduleFinite(ctx, maxOf(count, 1))
                SseMode.CONTINUOUS -> scheduleContinuous(ctx)
            }
        }

        private fun scheduleFinite(ctx: ChannelHandlerContext, count: Int) {
            for (i in 1..count) {
                val delayMs = i.toLong() * 120
                val last = i == count
                val event = if (last) "end" else "item"
                val future = ctx.executor().schedule({
                    if (stopped) return@schedule
                    val payload = sseEvent(i, event, "{\"index\":$i}")
                    if (last) {
                        ctx.writeAndFlush(DefaultLastHttpContent(Unpooled.wrappedBuffer(payload)))
                            .addListener { runCatching { ctx.close() } }
                    } else {
                        ctx.writeAndFlush(DefaultHttpContent(Unpooled.wrappedBuffer(payload)))
                    }
                }, delayMs, TimeUnit.MILLISECONDS)
                scheduled.add(future)
            }
        }

        private fun scheduleContinuous(ctx: ChannelHandlerContext) {
            var i = 1
            val future = ctx.executor().scheduleWithFixedDelay({
                if (stopped) return@scheduleWithFixedDelay
                val payload = sseEvent(i, "item", "{\"index\":$i}")
                ctx.writeAndFlush(DefaultHttpContent(Unpooled.wrappedBuffer(payload)))
                i++
            }, 0, 250, TimeUnit.MILLISECONDS)
            scheduled.add(future)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            stopped = true
            scheduled.forEach { it.cancel(false) }
            ctx.fireChannelInactive()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            stopped = true
            scheduled.forEach { it.cancel(false) }
            runCatching { ctx.close() }
        }

        private fun sseEvent(id: Int, event: String, data: String): ByteArray =
            ("id: $id\nevent: $event\ndata: $data\n\n").toByteArray(Charsets.UTF_8)
    }
}
