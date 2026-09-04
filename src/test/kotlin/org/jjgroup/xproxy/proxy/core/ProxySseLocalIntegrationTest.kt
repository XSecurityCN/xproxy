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
 * 经 XProxy 对自包含 [MockSseServer] 做 SSE 端到端验证,覆盖两条传输路径:
 *  - HTTP(明文,经代理的绝对 URI 请求)`http://127.0.0.1:<mockPort>/sse/3`
 *  - HTTPS(MITM,CONNECT 隧道)`https://127.0.0.1:<mockPort>/sse/3`
 * `/sse/3` 发完 3 个事件(2 item + 1 end)后干净关闭(触发 LastHttpContent -> finalize)。
 *
 * 不再依赖本地 80/443 上的 xhttp 服务:上游在临时端口由 MockSseServer 提供。
 * mockPort != proxyPort,故 ProxyPortal 的 loopback 拦截(isPortalHost 要求 port==listenerPort)不触发,请求正常转发。
 * CONNECT/代理请求带 Proxy-Connection,使 proxyee CertDownIntercept 正常转发(否则返回 CA 页)。
 */
class ProxySseLocalIntegrationTest {

    private val ssePath = "/sse/3"

    @Test
    fun `http sse via proxy against local mock upstream is captured live`() {
        val upstream = MockSseServer.start(tls = false)
        val port = ServerSocket(0).use { it.localPort }
        val runningLatch = CountDownLatch(1)
        val added = CopyOnWriteArrayList<ProxyHistoryEntry>()
        val updates = CopyOnWriteArrayList<Pair<ProxyHistoryEntry, Boolean>>()
        val controller = ProxyController().apply {
            onStatusChanged = { running, _ -> if (running) runningLatch.countDown() }
            onHistoryAdded = { entry -> if (entry.mimeType == "sse") added.add(entry) }
            onHistoryUpdated = { entry, finalized -> if (entry.mimeType == "sse") updates.add(entry to finalized) }
        }
        val proxyThread = thread(isDaemon = true) { controller.start("127.0.0.1", port, true) }

        try {
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS), "proxy did not start")
            waitForProxyPort(port, controller)

            Socket("127.0.0.1", port).use { raw ->
                raw.soTimeout = 10000
                // 绝对 URI 的 HTTP 代理请求(带 Proxy-Connection,否则 CertDownIntercept 返回 CA 页)
                val req = "GET http://${upstream.host}:${upstream.port}$ssePath HTTP/1.1\r\n" +
                    "Host: ${upstream.host}:${upstream.port}\r\n" +
                    "Proxy-Connection: keep-alive\r\n" +
                    "Accept: text/event-stream\r\n\r\n"
                raw.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                raw.getOutputStream().flush()

                val header = readHttpHeader(raw)
                val firstLine = header.lineSequence().firstOrNull().orEmpty()
                assertTrue(firstLine.startsWith("HTTP/1.1 200"), "upstream unreachable or non-200: $firstLine")
                assertTrue(header.contains("text/event-stream", ignoreCase = true), "not SSE: $header")

                val body = readBody(raw, 8000)
                assertTrue(body.contains("event: item"), "client did not receive SSE items: $body")
                assertTrue(body.contains("event: end"), "SSE stream did not close cleanly: $body")
            }

            Thread.sleep(500)
            assertCapturedLive(added, updates, ssePath)
        } finally {
            controller.stop()
            proxyThread.join(2000)
            upstream.close()
        }
    }

    @Test
    fun `https sse via proxy against local mock upstream is captured live`() {
        val upstream = MockSseServer.start(tls = true)
        val port = ServerSocket(0).use { it.localPort }
        val runningLatch = CountDownLatch(1)
        val added = CopyOnWriteArrayList<ProxyHistoryEntry>()
        val updates = CopyOnWriteArrayList<Pair<ProxyHistoryEntry, Boolean>>()
        val controller = ProxyController().apply {
            onStatusChanged = { running, _ -> if (running) runningLatch.countDown() }
            onHistoryAdded = { entry -> if (entry.mimeType == "sse") added.add(entry) }
            onHistoryUpdated = { entry, finalized -> if (entry.mimeType == "sse") updates.add(entry to finalized) }
        }
        val proxyThread = thread(isDaemon = true) { controller.start("127.0.0.1", port, true) }

        try {
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS), "proxy did not start")
            waitForProxyPort(port, controller)

            Socket("127.0.0.1", port).use { raw ->
                raw.soTimeout = 10000
                raw.getOutputStream().write(
                    "CONNECT ${upstream.host}:${upstream.port} HTTP/1.1\r\nHost: ${upstream.host}:${upstream.port}\r\nProxy-Connection: keep-alive\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                )
                raw.getOutputStream().flush()
                val connectHeader = readHttpHeader(raw)
                assertTrue(connectHeader.startsWith("HTTP/1.1 200"), "CONNECT failed: $connectHeader")

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
                val sslSocket = sslContext.socketFactory.createSocket(raw, upstream.host, upstream.port, false) as SSLSocket
                sslSocket.soTimeout = 10000
                sslSocket.sslParameters = sslSocket.sslParameters.apply { applicationProtocols = arrayOf("http/1.1") }
                sslSocket.startHandshake()

                val req = "GET $ssePath HTTP/1.1\r\nHost: ${upstream.host}:${upstream.port}\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n"
                sslSocket.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                sslSocket.getOutputStream().flush()

                val header = readHttpHeader(sslSocket)
                val firstLine = header.lineSequence().firstOrNull().orEmpty()
                assertTrue(firstLine.startsWith("HTTP/1.1 200"), "upstream non-200 (TLS trust?): $firstLine")
                assertTrue(header.contains("text/event-stream", ignoreCase = true), "not SSE: $header")

                val body = readBody(sslSocket, 8000)
                assertTrue(body.contains("event: item"), "client did not receive SSE items: $body")
                assertTrue(body.contains("event: end"), "SSE stream did not close cleanly: $body")
                sslSocket.close()
            }

            Thread.sleep(500)
            assertCapturedLive(added, updates, ssePath)
        } finally {
            controller.stop()
            proxyThread.join(2000)
            upstream.close()
        }
    }

    private fun assertCapturedLive(
        added: List<ProxyHistoryEntry>,
        updates: List<Pair<ProxyHistoryEntry, Boolean>>,
        path: String
    ) {
        assertTrue(added.isNotEmpty(), "no SSE history entry was added")
        val initial = added.first()
        assertEquals("sse", initial.mimeType)
        assertEquals(200, initial.statusCode)
        assertEquals(path, initial.path)

        assertTrue(updates.isNotEmpty(), "no live SSE updates fired")
        val lengths = updates.map { it.first.length }
        for (i in 1 until lengths.size) {
            assertTrue(lengths[i] >= lengths[i - 1], "SSE body length should be non-decreasing: $lengths")
        }
        val lastBody = updates.last().first.responseRaw
        assertTrue(lastBody.contains("text/event-stream", ignoreCase = true), "captured response should contain SSE headers")
        assertTrue(lastBody.contains("event: item"), "captured body should contain SSE events: ${lastBody.take(200)}")
        assertNotNull(updates.firstOrNull { it.second }, "SSE stream was not finalized")
    }

    private fun readBody(socket: Socket, timeoutMs: Int): String {
        val input = BufferedInputStream(socket.getInputStream())
        val sb = StringBuilder()
        val buf = ByteArray(2048)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            }
        } catch (_: java.net.SocketTimeoutException) {
        }
        return sb.toString()
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
