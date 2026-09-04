package org.jjgroup.xproxy.proxy.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 回归:抖音登录二维码 XHR(/passport/web/get_qrcode/)携带大 Cookie + 浏览器标准头,请求头总字节超过
 * proxyee 默认 maxHeaderSize=8192,会被下游 HttpServerCodec 判为 TooLongHttpHeaderException -> 431
 * 直接回浏览器(请求根本不转发,二维码刷不出,CORS 报错是连带现象)。修复见 ProxyLifecycle 把
 * maxHeaderSize/maxInitialLineLength 抬到 64KB(DownstreamMaxHeaderSize),与本测试同源。
 *
 * 本测试构造总头 > 8192(旧默认)但 < 65536(新上限)的请求,经 xproxy 纯 HTTP 代理(绝对 URI)转发到
 * 本地 mock 上游,断言:不再 431、上游确实收到请求(含大 Cookie)。
 * 走纯 HTTP 绝对 URI 是因为 proxyee 初始 pipeline 的 HttpServerCodec 与 MITM 后的 codec 都用
 * serverConfig.maxHeaderSize(HttpProxyServer$1 / HttpProxyServerHandler:216-219),可等价覆盖下游解码限制。
 */
class ProxyLargeHeaderIntegrationTest {

    @Test
    fun `large request headers under 64kb are forwarded not rejected as 431`() {
        val proxyPort = ServerSocket(0).use { it.localPort }
        val upstreamPortRef = AtomicReference<Int>()
        val upstreamBound = CountDownLatch(1)
        val receivedRequest = AtomicReference<String>()
        val upstreamReceived = CountDownLatch(1)

        // mock 上游:先 bind 暴露端口,accept 一个连接,读请求头,回 200,记录收到的请求
        val upstreamThread = thread(isDaemon = true) {
            try {
                ServerSocket(0).use { server ->
                    upstreamPortRef.set(server.localPort)
                    upstreamBound.countDown()
                    server.soTimeout = 10_000
                    val client = server.accept()
                    try {
                        val raw = readUntilHeaderEnd(BufferedInputStream(client.getInputStream()))
                        receivedRequest.set(raw)
                        upstreamReceived.countDown()
                        val body = "ok".toByteArray(Charsets.ISO_8859_1)
                        val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().write(resp.toByteArray(Charsets.ISO_8859_1))
                        client.getOutputStream().write(body)
                        client.getOutputStream().flush()
                    } finally {
                        client.close()
                    }
                }
            } catch (_: Exception) {
            }
        }

        val runningLatch = CountDownLatch(1)
        val controller = ProxyController().apply {
            onStatusChanged = { running, _ -> if (running) runningLatch.countDown() }
        }
        val proxyThread = thread(isDaemon = true) { controller.start("127.0.0.1", proxyPort, true) }

        try {
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS), "proxy did not start")
            assertTrue(upstreamBound.await(5, TimeUnit.SECONDS), "mock upstream did not bind")
            waitForProxyPort(proxyPort, controller)
            val upstreamPort = upstreamPortRef.get()

            // Cookie 值 10000 字节,加其余头总 > 10KB,远超旧默认 8192,但远低于新上限 65536。
            val hugeCookie = "k=" + "a".repeat(10_000)
            assertTrue(hugeCookie.length > 8192, "test premise: cookie must exceed legacy 8192 limit")

            Socket("127.0.0.1", proxyPort).use { raw ->
                raw.soTimeout = 8_000
                val req = "GET http://127.0.0.1:$upstreamPort/passport/web/get_qrcode/ HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$upstreamPort\r\n" +
                    "Proxy-Connection: keep-alive\r\n" +
                    "User-Agent: Mozilla/5.0 (xproxy-large-header-test)\r\n" +
                    "Accept: */*\r\n" +
                    "Cookie: $hugeCookie\r\n" +
                    "\r\n"
                raw.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                raw.getOutputStream().flush()

                val header = readHttpHeader(raw)
                val firstLine = header.lineSequence().firstOrNull().orEmpty()
                // 旧默认 maxHeaderSize=8192 会在此处返回 431;修复后应转发到上游得 200。
                assertFalse(firstLine.contains(" 431 "), "large headers were rejected with 431 (header limit too small): $firstLine")
                assertTrue(firstLine.startsWith("HTTP/1.1 200"), "expected 200 from upstream, got: $firstLine")
            }

            assertTrue(upstreamReceived.await(5, TimeUnit.SECONDS), "upstream never received the forwarded request")
            val upstreamRaw = receivedRequest.get() ?: ""
            assertTrue(upstreamRaw.contains("GET /passport/web/get_qrcode/"), "upstream got wrong request line: ${upstreamRaw.take(200)}")
            assertTrue(upstreamRaw.contains("Cookie: k="), "upstream did not receive the large Cookie header")
        } finally {
            controller.stop()
            proxyThread.join(2_000)
            upstreamThread.join(2_000)
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String {
        val sb = StringBuilder()
        var b0 = -1; var b1 = -1; var b2 = -1; var b3 = -1
        while (true) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
            b0 = b1; b1 = b2; b2 = b3; b3 = b
            if (b0 == '\r'.code && b1 == '\n'.code && b2 == '\r'.code && b3 == '\n'.code) break
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
}
