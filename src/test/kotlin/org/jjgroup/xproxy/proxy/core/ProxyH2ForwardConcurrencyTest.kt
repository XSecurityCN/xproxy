package org.jjgroup.xproxy.proxy.core

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http2.Http2FrameCodecBuilder
import io.netty.handler.codec.http2.Http2MultiplexHandler
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolNames
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * H2 forward 异步化(P0)回归:下游 H2 多流并发请求经 xproxy 转发到 H2 上游时,上游应能并发处理
 * (handleH2Forward 已 offload 到 worker 线程池,不阻塞 Netty 事件循环)。
 *
 * 修复前 handleH2Forward 在 event loop 上同步阻塞 HttpClient.send,H2 多路复用的所有流串行化,
 * 上游 maxConcurrent=1;修复后 offload 到有界 worker 池,多流并发到达上游 maxConcurrent>=2。
 *
 * 关键设计:
 * - mock 上游用 H2 server(多路复用),否则 NativeHttp2UpstreamClient 共享 HttpClient 对 H1 上游复用单连接
 *   串行,maxConcurrent 测不出 offload 效果。
 * - 下游 H2 client 用 raw socket 手写 H2 帧(preface+SETTINGS+HEADERS),CONNECT 带 Proxy-Connection,
 *   否则 proxyee CertDownIntercept 把无此头的流当 direct 请求回 CA 页(ProtoUtil 用 Proxy-Connection 头判 proxy)。
 * - HPACK 手编码::method GET(0x82 indexed 2)、:path /(0x84 indexed 4)、:scheme https(0x87 indexed 7)、
 *   :authority(literal, name idx 1 = 0x41 + len + value)。
 */
class ProxyH2ForwardConcurrencyTest {

    @Test
    fun `h2 forward offload keeps upstream requests concurrent`() {
        val proxyPort = ServerSocket(0).use { it.localPort }
        val mockPort = ServerSocket(0).use { it.localPort }
        val current = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val serverLatch = CountDownLatch(3)
        val mockExecutor = Executors.newScheduledThreadPool(4) { r ->
            Thread(r, "mock-upstream").apply { isDaemon = true }
        }

        val ssc = SelfSignedCertificate()
        val alpnH2 = ApplicationProtocolConfig(
            ApplicationProtocolConfig.Protocol.ALPN,
            ApplicationProtocolConfig.SelectorFailureBehavior.FATAL_ALERT,
            ApplicationProtocolConfig.SelectedListenerFailureBehavior.FATAL_ALERT,
            ApplicationProtocolNames.HTTP_2
        )
        val serverSslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
            .applicationProtocolConfig(alpnH2)
            .build()
        val bossGroup = NioEventLoopGroup(1)
        val workerGroup = NioEventLoopGroup(2)
        val serverChannel = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<Channel>() {
                override fun initChannel(ch: Channel) {
                    ch.pipeline().addLast(serverSslCtx.newHandler(ch.alloc()))
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forServer().build())
                    ch.pipeline().addLast(Http2MultiplexHandler(object : ChannelInitializer<Channel>() {
                        override fun initChannel(stream: Channel) {
                            stream.pipeline().addLast(Http2StreamFrameToHttpObjectCodec(true))
                            stream.pipeline().addLast(object : SimpleChannelInboundHandler<FullHttpRequest>() {
                                override fun channelRead0(ctx: ChannelHandlerContext, req: FullHttpRequest) {
                                    val c = current.incrementAndGet()
                                    maxConcurrent.updateAndGet { maxOf(it, c) }
                                    mockExecutor.schedule({
                                        try {
                                            current.decrementAndGet()
                                            val body = "ok".toByteArray(Charsets.ISO_8859_1)
                                            val resp = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body))
                                            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.size)
                                            ctx.executor().execute { ctx.writeAndFlush(resp) }
                                        } catch (_: Exception) {
                                        }
                                        serverLatch.countDown()
                                    }, 800, TimeUnit.MILLISECONDS)
                                }
                            })
                        }
                    }))
                }
            }).bind(mockPort).sync().channel()

        val runningLatch = CountDownLatch(1)
        val controller = ProxyController().apply {
            onStatusChanged = { running, _ -> if (running) runningLatch.countDown() }
        }
        val proxyThread = thread(isDaemon = true) { controller.start("127.0.0.1", proxyPort, true) }

        try {
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS), "proxy did not start")
            waitForProxyPort(proxyPort, controller)

            Socket("127.0.0.1", proxyPort).use { raw ->
                raw.soTimeout = 12_000
                // CONNECT 带 Proxy-Connection(否则 proxyee CertDownIntercept 把 H2 流当 direct 请求回 CA 页)
                raw.getOutputStream().write(
                    "CONNECT localhost:$mockPort HTTP/1.1\r\nHost: localhost:$mockPort\r\nProxy-Connection: keep-alive\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                raw.getOutputStream().flush()
                val connectHeader = readHttpHeader(raw)
                assertTrue(connectHeader.startsWith("HTTP/1.1 200"), "CONNECT failed: $connectHeader")

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
                val sslSocket = sslContext.socketFactory.createSocket(raw, "localhost", mockPort, false) as SSLSocket
                sslSocket.soTimeout = 12_000
                sslSocket.sslParameters = sslSocket.sslParameters.apply { applicationProtocols = arrayOf("h2") }
                sslSocket.startHandshake()
                assertTrue("h2" == sslSocket.applicationProtocol, "ALPN not h2: ${sslSocket.applicationProtocol}")

                val out = sslSocket.getOutputStream()
                out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.ISO_8859_1)) // client preface
                out.write(byteArrayOf(0, 0, 0, 0x04, 0, 0, 0, 0, 0)) // empty SETTINGS
                val authority = "localhost:$mockPort"
                out.write(h2HeadersFrame(1, authority))
                out.write(h2HeadersFrame(3, authority))
                out.write(h2HeadersFrame(5, authority))
                out.flush()

                assertTrue(
                    serverLatch.await(10, TimeUnit.SECONDS),
                    "upstream did not receive all 3 requests (maxConcurrent=${maxConcurrent.get()})"
                )
                assertTrue(
                    maxConcurrent.get() >= 2,
                    "H2 forward not concurrent: maxConcurrent=${maxConcurrent.get()} (>=2 expected, offload failed?)"
                )
                sslSocket.close()
            }
        } finally {
            controller.stop()
            proxyThread.join(2000)
            serverChannel.close().sync()
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
            mockExecutor.shutdownNow()
            ssc.delete()
        }
    }

    /** 手编 H2 HEADERS 帧:9 字节帧头 + HPACK payload(全静态索引 + :authority literal)。 */
    private fun h2HeadersFrame(streamId: Int, authority: String): ByteArray {
        val authBytes = authority.toByteArray(Charsets.ISO_8859_1)
        // 0x82=:method GET(idx2) 0x84=:path /(idx4) 0x87=:scheme https(idx7)
        // 0x41=literal without indexing, name idx 1(:authority) + len + value
        val payload = byteArrayOf(0x82.toByte(), 0x84.toByte(), 0x87.toByte(), 0x41, authBytes.size.toByte()) + authBytes
        val len = payload.size
        return byteArrayOf(
            ((len shr 16) and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte(),
            0x01, // type HEADERS
            0x05, // flags END_HEADERS | END_STREAM (GET 无 body)
            ((streamId shr 24) and 0x7F).toByte(), ((streamId shr 16) and 0xFF).toByte(),
            ((streamId shr 8) and 0xFF).toByte(), (streamId and 0xFF).toByte()
        ) + payload
    }

    private fun readHttpHeader(socket: Socket): String {
        val input = BufferedInputStream(socket.getInputStream())
        val out = StringBuilder()
        var b0 = -1; var b1 = -1; var b2 = -1; var b3 = -1
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.append(b.toChar())
            b0 = b1; b1 = b2; b2 = b3; b3 = b
            if (b0 == '\r'.code && b1 == '\n'.code && b2 == '\r'.code && b3 == '\n'.code) break
        }
        return out.toString()
    }

    private fun waitForProxyPort(port: Int, controller: ProxyController) {
        val deadline = System.currentTimeMillis() + 5_000
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).use { return }
            } catch (ex: Exception) {
                lastError = ex
                Thread.sleep(100)
            }
        }
        throw AssertionError("proxy port did not open, status='${controller.currentStatusText()}', error='${lastError?.message}'")
    }

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
