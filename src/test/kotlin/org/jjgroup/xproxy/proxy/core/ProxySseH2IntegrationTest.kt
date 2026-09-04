package org.jjgroup.xproxy.proxy.core

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http2.DefaultHttp2Headers
import io.netty.handler.codec.http2.Http2DataFrame
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2Headers
import io.netty.handler.codec.http2.Http2HeadersFrame
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamChannel
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap
import io.netty.handler.codec.http2.Http2StreamFrame
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.CharsetUtil
import io.netty.util.concurrent.DefaultPromise
import io.netty.util.concurrent.GlobalEventExecutor
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真实 H2 客户端(Netty Http2FrameCodec,自行控制 CONNECT 头)经 XProxy 访问本地自包含 [MockSseServer]
 * (TLS+ALPN h2)的 SSE,验证 H2 SSE 不再走阻塞的 handleH2Forward:无 502、按 chunk 实时刷新。
 * 不再依赖本地 443 上的 xhttp 服务。
 *
 * CONNECT 带 Proxy-Connection(模拟浏览器,使 proxyee CertDownIntercept 视为代理流量转发,而非返回 CA 页)。
 */
class ProxySseH2IntegrationTest {

    @Test
    fun `h2 sse via proxy streams live without 502`() {
        val upstream = MockSseServer.start(tls = true)
        val authority = "${upstream.host}:${upstream.port}"
        val proxyPort = ServerSocket(0).use { it.localPort }
        val runningLatch = CountDownLatch(1)
        val added = CopyOnWriteArrayList<ProxyHistoryEntry>()
        val updates = CopyOnWriteArrayList<Pair<ProxyHistoryEntry, Boolean>>()
        val controller = ProxyController().apply {
            onStatusChanged = { running, _ -> if (running) runningLatch.countDown() }
            onHistoryAdded = { entry -> if (entry.mimeType == "sse") added.add(entry) }
            onHistoryUpdated = { entry, finalized -> if (entry.mimeType == "sse") updates.add(entry to finalized) }
        }
        val proxyThread = Thread({ controller.start("127.0.0.1", proxyPort, true) }, "xproxy-h2-sse").apply { isDaemon = true }
        proxyThread.start()

        val group: EventLoopGroup = NioEventLoopGroup(1)
        try {
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS), "proxy did not start")
            // wait for port
            val deadline = System.currentTimeMillis() + 5000
            var opened = false
            while (System.currentTimeMillis() < deadline && !opened) {
                try {
                    java.net.Socket("127.0.0.1", proxyPort).use { opened = true }
                } catch (_: Exception) {
                    Thread.sleep(100)
                }
            }

            val responsePromise = DefaultPromise<SseResult>(GlobalEventExecutor.INSTANCE)
            val ch = Bootstrap()
                .group(group)
                .channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel) {
                        ch.pipeline().addLast("httpCodec", HttpClientCodec())
                        ch.pipeline().addLast(object : SimpleChannelInboundHandler<Any>() {
                            override fun channelRead0(ctx: ChannelHandlerContext, msg: Any) {
                                if (msg is HttpResponse) {
                                    // CONNECT 200 received -> upgrade to TLS+H2
                                    ctx.pipeline().remove("httpCodec")
                                    ctx.pipeline().remove(this)
                                    val sslCtx = SslContextBuilder.forClient()
                                        .sslProvider(SslProvider.JDK)
                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                        .applicationProtocolConfig(
                                            ApplicationProtocolConfig(
                                                ApplicationProtocolConfig.Protocol.ALPN,
                                                ApplicationProtocolConfig.SelectorFailureBehavior.FATAL_ALERT,
                                                ApplicationProtocolConfig.SelectedListenerFailureBehavior.FATAL_ALERT,
                                                ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1
                                            )
                                        ).build()
                                    val ssl = sslCtx.newHandler(ch.alloc(), upstream.host, upstream.port)
                                    ctx.pipeline().addLast("ssl", ssl)
                                    ctx.pipeline().addLast("alpn", object : ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
                                        override fun configurePipeline(ctx: ChannelHandlerContext, protocol: String) {
                                            if (protocol != ApplicationProtocolNames.HTTP_2) {
                                                responsePromise.tryFailure(IllegalStateException("negotiated $protocol, not h2"))
                                                return
                                            }
                                            ctx.pipeline().addLast("h2codec", Http2FrameCodecBuilder.forClient().build())
                                            ctx.pipeline().addLast("h2multiplex", Http2MultiplexHandler(object : ChannelInitializer<Channel>() {
                                                override fun initChannel(stream: Channel) {}
                                            }))
                                            // h2codec 被加到已 active 的通道(经 CONNECT+TLS),不会自动收到 channelActive,
                                            // 也就不会发送 H2 客户端连接前言;手动调用其 channelActive 以发送前言+SETTINGS。
                                            val h2ctx = ctx.pipeline().context("h2codec")
                                            runCatching {
                                                (h2ctx?.handler() as? io.netty.channel.ChannelInboundHandler)?.channelActive(h2ctx)
                                            }.onFailure { responsePromise.tryFailure(it) }
                                            // 在事件循环上不能用 sync();用 listener 发起请求流
                                            Http2StreamChannelBootstrap(ctx.channel())
                                                .handler(object : ChannelInitializer<Channel>() {
                                                    override fun initChannel(s: Channel) {
                                                        s.pipeline().addLast(SseResponseHandler(responsePromise))
                                                    }
                                                }).open().addListener { future ->
                                                    if (!future.isSuccess) {
                                                        responsePromise.tryFailure(future.cause())
                                                        return@addListener
                                                    }
                                                    val streamCh = future.getNow() as Http2StreamChannel
                                                    val headers = DefaultHttp2Headers()
                                                        .method("GET")
                                                        .path("/sse/3")
                                                        .authority(authority)
                                                        .scheme("https")
                                                        .set("accept", "text/event-stream")
                                                    streamCh.writeAndFlush(io.netty.handler.codec.http2.DefaultHttp2HeadersFrame(headers, true, 0))
                                                }
                                        }
                                    })
                                }
                            }
                        })
                    }
                })
                .connect(InetSocketAddress("127.0.0.1", proxyPort)).sync().channel()

            // send CONNECT (with Proxy-Connection, like a browser)
            val connectReq = io.netty.handler.codec.http.DefaultFullHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.valueOf("CONNECT"),
                authority
            )
            connectReq.headers().set(io.netty.handler.codec.http.HttpHeaderNames.HOST, authority)
            connectReq.headers().set("Proxy-Connection", "keep-alive")
            ch.writeAndFlush(connectReq)

            val result = responsePromise.get(15, TimeUnit.SECONDS)
            // status 200 (not 502)
            assertEquals(200, result.status, "expected 200 SSE (502 = H2 blocking not fixed?)")
            assertTrue(result.contentType.contains("text/event-stream"), "not SSE: ${result.contentType}")
            assertTrue(result.body.contains("event: item"), "client did not receive SSE events: ${result.body.take(200)}")
            assertTrue(result.body.contains("event: end"), "SSE did not close cleanly: ${result.body.take(200)}")

            Thread.sleep(800)
            assertTrue(added.isNotEmpty(), "no SSE history entry captured via H2 path")
            assertEquals("sse", added.first().mimeType)
            assertTrue(updates.size >= 2, "expected live updates, got ${updates.size}")
            assertNotNull(updates.firstOrNull { it.second }, "SSE stream was not finalized")

            ch.close().sync()
        } finally {
            group.shutdownGracefully()
            controller.stop()
            proxyThread.join(2000)
            upstream.close()
        }
    }

    private data class SseResult(val status: Int, val contentType: String, val body: String)

    private class SseResponseHandler(private val promise: DefaultPromise<SseResult>) : SimpleChannelInboundHandler<Http2StreamFrame>() {
        private val body = StringBuilder()
        private var status = -1
        private var contentType = ""
        private var gotHeaders = false

        override fun channelRead0(ctx: ChannelHandlerContext, msg: Http2StreamFrame) {
            when (msg) {
                is Http2HeadersFrame -> {
                    val s = msg.headers().status()
                    status = s?.toString()?.substringBefore(' ')?.toIntOrNull() ?: 200
                    contentType = msg.headers().get("content-type")?.toString() ?: ""
                    gotHeaders = true
                }
                is Http2DataFrame -> {
                    if (msg.content().isReadable) {
                        body.append(msg.content().toString(CharsetUtil.ISO_8859_1))
                    }
                    if (msg.isEndStream) {
                        promise.trySuccess(SseResult(status, contentType, body.toString()))
                        ctx.close()
                    }
                }
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            promise.tryFailure(cause)
            ctx.close()
        }
    }
}
