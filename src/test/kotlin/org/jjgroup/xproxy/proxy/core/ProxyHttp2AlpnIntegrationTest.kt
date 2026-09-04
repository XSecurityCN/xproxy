package org.jjgroup.xproxy.proxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * 验证 MITM TLS 的 ALPN 协商:CONNECT 隧道建好后,客户端与代理(MITM 证书)握手,
 * 按下游 H2 开关协商出 h2。上游用本地自包含 [MockSseServer](TLS),不再依赖 example.com:443
 * 的外部可达性;ALPN 协商发生在客户端↔代理之间,与上游无关。
 */
class ProxyHttp2AlpnIntegrationTest {

    private lateinit var upstream: MockSseServer

    @BeforeEach
    fun setUp() {
        upstream = MockSseServer.start(tls = true)
    }

    @AfterEach
    fun tearDown() {
        upstream.close()
    }

    private fun authority() = "${upstream.host}:${upstream.port}"

    @Test
    fun `mitm tls negotiates h2 via alpn when explicitly enabled`() {
        val old = System.getProperty("xproxy.proxy.downstreamH2")
        System.setProperty("xproxy.proxy.downstreamH2", "true")
        val port = ServerSocket(0).use { it.localPort }
        val runningLatch = CountDownLatch(1)
        val controller = ProxyController().apply {
            protocolPolicy = ProtocolPolicy.allowDowngrade()
            onStatusChanged = { running, _ ->
                if (running) {
                    runningLatch.countDown()
                }
            }
        }

        val proxyThread = thread(isDaemon = true) {
            controller.start("127.0.0.1", port, true)
        }

        try {
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS), "proxy did not start")
            waitForProxyPort(port, controller)

            Socket("127.0.0.1", port).use { raw ->
                raw.soTimeout = 5000
                val connectReq = "CONNECT ${authority()} HTTP/1.1\r\nHost: ${authority()}\r\n\r\n"
                raw.getOutputStream().write(connectReq.toByteArray(Charsets.ISO_8859_1))
                raw.getOutputStream().flush()

                val header = readHttpHeader(raw)
                assertTrue(header.startsWith("HTTP/1.1 200"), "CONNECT failed: $header")

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
                val sslSocket = sslContext.socketFactory.createSocket(raw, upstream.host, upstream.port, false) as SSLSocket
                sslSocket.sslParameters = sslSocket.sslParameters.apply {
                    applicationProtocols = arrayOf("h2", "http/1.1")
                }
                sslSocket.startHandshake()
                val negotiated = sslSocket.applicationProtocol
                assertEquals("h2", negotiated)
                sslSocket.close()
            }
        } finally {
            controller.stop()
            proxyThread.join(2000)
            if (old == null) System.clearProperty("xproxy.proxy.downstreamH2") else System.setProperty("xproxy.proxy.downstreamH2", old)
        }
    }


    @Test
    fun `default mitm tls negotiates h2 when browser offers it`() {
        val port = ServerSocket(0).use { it.localPort }
        val runningLatch = CountDownLatch(1)
        val controller = ProxyController().apply {
            onStatusChanged = { running, _ ->
                if (running) runningLatch.countDown()
            }
        }
        val proxyThread = thread(isDaemon = true) {
            controller.start("127.0.0.1", port, true)
        }

        try {
            assertTrue(runningLatch.await(5, TimeUnit.SECONDS), "proxy did not start")
            waitForProxyPort(port, controller)

            Socket("127.0.0.1", port).use { raw ->
                raw.soTimeout = 5000
                raw.getOutputStream().write("CONNECT ${authority()} HTTP/1.1\r\nHost: ${authority()}\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                raw.getOutputStream().flush()
                val header = readHttpHeader(raw)
                assertTrue(header.startsWith("HTTP/1.1 200"), "CONNECT failed: $header")

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
                val sslSocket = sslContext.socketFactory.createSocket(raw, upstream.host, upstream.port, false) as SSLSocket
                sslSocket.sslParameters = sslSocket.sslParameters.apply {
                    applicationProtocols = arrayOf("h2", "http/1.1")
                }
                sslSocket.startHandshake()
                assertEquals("h2", sslSocket.applicationProtocol)
                sslSocket.close()
            }
        } finally {
            controller.stop()
            proxyThread.join(2000)
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
            b0 = b1
            b1 = b2
            b2 = b3
            b3 = b
            if (b0 == '\r'.code && b1 == '\n'.code && b2 == '\r'.code && b3 == '\n'.code) {
                break
            }
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
