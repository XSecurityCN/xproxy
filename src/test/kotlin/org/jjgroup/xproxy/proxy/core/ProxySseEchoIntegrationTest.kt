package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * 端到端 SSE 测试:启动 XProxy,经其 CONNECT 到本地自包含 [MockSseServer](TLS)的持续 SSE 端点,
 * 校验 SSE 响应被识别为 sse 类型、按 chunk 实时刷新(onHistoryUpdated 多次、length 单调增长)、
 * 连接关闭后 finalize。不再依赖外部网络与 echo.websocket.org 端点。
 */
class ProxySseEchoIntegrationTest {

    @Test
    fun `sse stream through proxy is captured live with sse mime type`() {
        val upstream = MockSseServer.start(tls = true)
        val ssePath = "/sse/events"
        val port = ServerSocket(0).use { it.localPort }
        val runningLatch = CountDownLatch(1)
        val added = CopyOnWriteArrayList<ProxyHistoryEntry>()
        val updates = CopyOnWriteArrayList<Pair<ProxyHistoryEntry, Boolean>>()
        val controller = ProxyController().apply {
            protocolPolicy = ProtocolPolicy.allowDowngrade()
            onStatusChanged = { running, _ -> if (running) runningLatch.countDown() }
            onHistoryAdded = { entry -> if (entry.mimeType == "sse") added.add(entry) }
            onHistoryUpdated = { entry, finalized -> if (entry.mimeType == "sse") updates.add(entry to finalized) }
        }

        val proxyThread = thread(isDaemon = true) {
            controller.start("127.0.0.1", port, true)
        }

        try {
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS), "proxy did not start")
            waitForProxyPort(port, controller)

            Socket("127.0.0.1", port).use { raw ->
                raw.soTimeout = 8000
                // 1) CONNECT 隧道(带 Proxy-Connection,使 proxyee 视为代理流量,否则 CertDownIntercept 会把隧道内请求当作直接访问并返回 CA 页)
                raw.getOutputStream().write(
                    "CONNECT ${upstream.host}:${upstream.port} HTTP/1.1\r\nHost: ${upstream.host}:${upstream.port}\r\nProxy-Connection: keep-alive\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                raw.getOutputStream().flush()
                val connectHeader = readHttpHeader(raw)
                assertTrue(connectHeader.startsWith("HTTP/1.1 200"), "CONNECT failed: $connectHeader")

                // 2) TLS 握手(MITM 证书由 trustAllManager 接受);强制 HTTP/1.1 走 SSE 实时路径。
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
                val sslSocket = sslContext.socketFactory.createSocket(raw, upstream.host, upstream.port, false) as SSLSocket
                sslSocket.soTimeout = 8000
                sslSocket.sslParameters = sslSocket.sslParameters.apply {
                    applicationProtocols = arrayOf("http/1.1")
                }
                sslSocket.startHandshake()

                // 3) 发送 SSE 请求
                val req = "GET $ssePath HTTP/1.1\r\nHost: ${upstream.host}:${upstream.port}\r\nAccept: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n"
                sslSocket.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                sslSocket.getOutputStream().flush()

                // 4) 读取响应首部
                val responseHeader = readHttpHeader(sslSocket)
                val firstLine = responseHeader.lineSequence().firstOrNull().orEmpty()
                assertTrue(firstLine.startsWith("HTTP/1.1 200"), "endpoint not 200: $firstLine")
                assertTrue(
                    responseHeader.contains("text/event-stream", ignoreCase = true),
                    "not an SSE response: $responseHeader"
                )

                // 5) 读取若干秒的 SSE 事件(读取会因 soTimeout 结束)
                val input = BufferedInputStream(sslSocket.getInputStream())
                val body = StringBuilder()
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 5000
                try {
                    while (System.currentTimeMillis() < deadline) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n > 0) body.append(String(buf, 0, n, Charsets.ISO_8859_1))
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // 期望:流持续推送,soTimeout 超时属正常
                }
                // 客户端确实收到了 SSE 事件
                assertTrue(body.contains("event:") || body.contains("data:"), "client did not receive SSE events: ${body.toString().take(200)}")
                // 关闭连接,触发 finalize(channelInactive 路径)
                sslSocket.close()
            }

            // 6) 等待 finalize 落地
            Thread.sleep(800)

            // 断言:SSE 历史条目已记录
            assertTrue(added.isNotEmpty(), "no SSE history entry was added")
            val initial = added.first()
            assertEquals("sse", initial.mimeType)
            assertEquals(200, initial.statusCode)
            assertEquals(ssePath, initial.path)

            // 断言:实时刷新发生(onHistoryUpdated 多次,length 单调增长)
            assertTrue(updates.isNotEmpty(), "no live SSE updates fired")
            val lengths = updates.map { it.first.length }
            for (i in 1 until lengths.size) {
                assertTrue(lengths[i] >= lengths[i - 1], "SSE body length should be non-decreasing: $lengths")
            }
            val lastBody = updates.last().first.responseRaw
            assertTrue(lastBody.contains("text/event-stream", ignoreCase = true), "captured response should contain SSE headers")
            assertTrue(lastBody.contains("event:", ignoreCase = true) || lastBody.contains("data:", ignoreCase = true), "captured body should contain SSE events: ${lastBody.take(300)}")

            // 断言:最终态 finalize 被触发
            assertNotNull(updates.firstOrNull { it.second }, "SSE stream was not finalized")
        } finally {
            controller.stop()
            proxyThread.join(2000)
            upstream.close()
        }
    }

    private fun readHttpHeader(socket: Socket): String {
        val input = BufferedInputStream(socket.getInputStream())
        val out = StringBuilder()
        var b0 = -1
        var b1 = -1
        var b2 = -1
        var b3 = -1
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
        val deadline = System.currentTimeMillis() + 5000
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
