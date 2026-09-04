package org.jjgroup.xproxy.proxy.core

import com.github.monkeywie.proxyee.crt.CertUtil
import com.github.monkeywie.proxyee.exception.HttpProxyExceptionHandle
import com.github.monkeywie.proxyee.intercept.HttpProxyIntercept
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline
import com.github.monkeywie.proxyee.server.HttpProxyCACertFactory
import io.netty.channel.Channel
import io.netty.handler.codec.http.HttpRequest
import org.jjgroup.xproxy.proxy.runtime.ProxyeeRuntime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * 端到端:统一 server 同一端口上,SOCKS5 客户端握手 + CONNECT 到本地明文 HTTP 上游,
 * 验证请求被转发且被 proxyee 拦截链捕获(即 SOCKS5 复用了 MITM 代理的抓包能力)。
 */
class Socks5ProxyIntegrationTest {

    private val running = AtomicBoolean(false)
    private var runtime: ProxyeeRuntime? = null

    @AfterEach
    fun tearDown() {
        runtime?.stop()
        runtime = null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitForPort(port: Int, timeoutMs: Long = 8000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).use { return }
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        throw IllegalStateException("proxy port $port not ready in time")
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
    }

    @Test
    fun `socks5 connect then plain http is proxied and captured`() {
        // 1) 本地明文 HTTP 上游:accept 一次,读请求,回 200 + "ok"
        val upstreamReceived = CopyOnWriteArrayList<String>()
        val upstream = ServerSocket(0)
        val upstreamPort = upstream.localPort
        val upstreamThread = thread(isDaemon = true) {
            try {
                val conn = upstream.accept()
                conn.soTimeout = 5000
                val reader = conn.getInputStream()
                val sb = StringBuilder()
                val buf = ByteArray(4096)
                // 读到 headers 结束(\r\n\r\n)即可
                while (!sb.contains("\r\n\r\n")) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                }
                upstreamReceived.add(sb.toString())
                val body = "ok"
                val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                conn.getOutputStream().write(resp.toByteArray(Charsets.ISO_8859_1))
                conn.getOutputStream().flush()
                conn.close()
            } catch (_: Exception) {
            }
        }

        // 2) 拦截链:含 CertDownIntercept(与真实代理一致),验证 SOCKS5 隧道请求不被误判为"直连代理"而回 CA 页。
        val captured = CopyOnWriteArrayList<String>()
        val initializer = object : HttpProxyInterceptInitializer() {
            override fun init(pipeline: HttpProxyInterceptPipeline) {
                pipeline.addLast(com.github.monkeywie.proxyee.intercept.common.CertDownIntercept())
                pipeline.addLast(object : HttpProxyIntercept() {
                    override fun beforeRequest(clientChannel: Channel, httpRequest: HttpRequest, pipeline: HttpProxyInterceptPipeline) {
                        captured.add("${httpRequest.method()} ${httpRequest.uri()} Host=${httpRequest.headers().get("Host")}")
                        pipeline.beforeRequest(clientChannel, httpRequest)
                    }
                })
            }
        }

        // 3) 启动统一代理 server(明文,不做 MITM)
        val proxyPort = freePort()
        val runtime = ProxyeeRuntime(
            running = running,
            onStatusChanged = { _, _ -> },
            createCaCertFactory = {
                object : HttpProxyCACertFactory {
                    override fun getCACert(): X509Certificate = throw UnsupportedOperationException("no TLS in this test")
                    override fun getCAPriKey(): PrivateKey = throw UnsupportedOperationException("no TLS in this test")
                }
            },
            createInterceptInitializer = { initializer },
            configureServer = {},
            registerServerRef = {},
            listeningStatusProvider = { "listening" }
        )
        this.runtime = runtime
        thread(isDaemon = true) { runtime.start("127.0.0.1", proxyPort, handleSsl = false) }
        waitForPort(proxyPort)

        // 4) SOCKS5 客户端:握手 + CONNECT 127.0.0.1:upstreamPort
        val client = Socket("127.0.0.1", proxyPort)
        client.soTimeout = 5000
        val out = client.getOutputStream()
        val inp = client.getInputStream()

        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        val methodReply = ByteArray(2); readFully(inp, methodReply)
        assertEquals(listOf(5, 0), methodReply.map { it.toInt() and 0xFF }, "method negotiation should pick no-auth")

        val portHi = (upstreamPort shr 8).toByte()
        val portLo = (upstreamPort and 0xFF).toByte()
        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, portHi, portLo)); out.flush()
        val connectReply = ByteArray(10); readFully(inp, connectReply)
        assertEquals(0, connectReply[1].toInt() and 0xFF, "CONNECT should succeed")

        // 5) 隧道上发明文 HTTP(origin-form)
        out.write("GET /hello HTTP/1.1\r\nHost: 127.0.0.1:$upstreamPort\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()

        // 6) 读响应(应来自上游的 200 ok)
        val upDeadline = System.currentTimeMillis() + 3000
        while (upstreamReceived.isEmpty() && System.currentTimeMillis() < upDeadline) Thread.sleep(20)
        val respBuf = ByteArray(4096)
        val n = try { inp.read(respBuf) } catch (e: Exception) { -1 }
        val resp = if (n > 0) String(respBuf, 0, n, Charsets.ISO_8859_1) else ""
        assertTrue(resp.contains("200"), "client should receive upstream 200, got: ${resp.take(80)}")

        // 7) 验证上游确实收到请求,且 proxyee 拦截链捕获到
        val deadline = System.currentTimeMillis() + 3000
        while (upstreamReceived.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(upstreamReceived.any { it.contains("/hello") }, "upstream should receive GET /hello")
        assertTrue(captured.any { it.contains("/hello") }, "intercept chain should capture the request")

        client.close()
        upstream.close()
        upstreamThread.join(2000)
    }

    @Test
    fun `socks5 connect then tls clienthello is tunneled to upstream`() {
        // SOCKS5 -> TLS:首字节 0x16 的 ClientHello 应经 super 的 ByteBuf 分支盲转发到上游
        // (handleSsl=false,不做 MITM)。验证隧道能承载 TLS 字节流,不卡死、不丢包。
        val upstreamReceived = CopyOnWriteArrayList<ByteArray>()
        val upstream = ServerSocket(0)
        val upstreamPort = upstream.localPort
        val upstreamThread = thread(isDaemon = true) {
            try {
                val conn = upstream.accept()
                conn.soTimeout = 5000
                val buf = ByteArray(4096)
                val n = conn.getInputStream().read(buf)
                if (n > 0) {
                    upstreamReceived.add(buf.copyOf(n))
                }
                // 回一个最小的 TLS-ish 字节,让客户端 read 不阻塞(本测试只验上行转发)
                conn.getOutputStream().write(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x02, 0x00, 0x00))
                conn.getOutputStream().flush()
                conn.close()
            } catch (_: Exception) {
            }
        }

        val proxyPort = freePort()
        val runtime = ProxyeeRuntime(
            running = running,
            onStatusChanged = { _, _ -> },
            createCaCertFactory = {
                object : HttpProxyCACertFactory {
                    override fun getCACert(): X509Certificate = throw UnsupportedOperationException("no TLS in this test")
                    override fun getCAPriKey(): PrivateKey = throw UnsupportedOperationException("no TLS in this test")
                }
            },
            createInterceptInitializer = { HttpProxyInterceptInitializer() },
            configureServer = {},
            registerServerRef = {},
            listeningStatusProvider = { "listening" }
        )
        this.runtime = runtime
        thread(isDaemon = true) { runtime.start("127.0.0.1", proxyPort, handleSsl = false) }
        waitForPort(proxyPort)

        val client = Socket("127.0.0.1", proxyPort)
        client.soTimeout = 5000
        val out = client.getOutputStream()
        val inp = client.getInputStream()

        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        readFully(inp, ByteArray(2))

        val portHi = (upstreamPort shr 8).toByte()
        val portLo = (upstreamPort and 0xFF).toByte()
        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, portHi, portLo)); out.flush()
        val connectReply = ByteArray(10); readFully(inp, connectReply)
        assertEquals(0, connectReply[1].toInt() and 0xFF, "CONNECT should succeed")

        // 隧道上发 TLS ClientHello(0x16 0x03 0x03 ...),应被盲转发到上游
        val clientHello = byteArrayOf(
            0x16, 0x03, 0x01, 0x00, 0x20,
            0x01, 0x00, 0x00, 0x1C, 0x03, 0x03,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x00, 0x00, 0x02, 0x00, 0x2B
        )
        out.write(clientHello); out.flush()

        val upDeadline = System.currentTimeMillis() + 3000
        while (upstreamReceived.isEmpty() && System.currentTimeMillis() < upDeadline) Thread.sleep(20)
        assertEquals(0x16, upstreamReceived.firstOrNull()?.get(0)?.toInt()?.and(0xFF), "upstream should receive TLS ClientHello (0x16)")
        assertEquals(0x03, upstreamReceived.firstOrNull()?.get(1)?.toInt()?.and(0xFF), "upstream should receive TLS record")

        client.close()
        upstream.close()
        upstreamThread.join(2000)
    }

    /**
     * 端到端:HTTPS 代理(TLS-on-listener)。客户端用 TLS 连到监听端口,内层走标准 HTTP 代理协议
     * (绝对 URI 形式的 GET),验证监听端 TLS 握手成功、请求被转发到上游且被拦截链捕获。
     * 对应用例:curl -k -x https://127.0.0.1:port http://upstream/path
     */
    @Test
    fun `https proxy tls on listener forwards absolute uri request to upstream`() {
        // 1) 上游:本地明文 HTTP,回 200 + 标记
        val upstreamReceived = CopyOnWriteArrayList<String>()
        val upstream = ServerSocket(0)
        val upstreamPort = upstream.localPort
        val upstreamThread = thread(isDaemon = true) {
            try {
                while (true) {
                    val conn = upstream.accept()
                    val br = conn.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val sb = StringBuilder()
                    var line = br.readLine()
                    while (line != null) {
                        sb.append(line).append('\n')
                        if (line.isEmpty()) break
                        line = br.readLine()
                    }
                    upstreamReceived.add(sb.toString())
                    conn.getOutputStream().write("HTTP/1.1 200 ok\r\nContent-Length: 7\r\n\r\nTLS-OK\n".toByteArray(Charsets.ISO_8859_1))
                    conn.getOutputStream().flush()
                    conn.close()
                }
            } catch (_: Exception) {
            }
        }

        // 2) 拦截链:捕获请求
        val captured = CopyOnWriteArrayList<String>()
        val initializer = object : HttpProxyInterceptInitializer() {
            override fun init(pipeline: HttpProxyInterceptPipeline) {
                pipeline.addLast(object : HttpProxyIntercept() {
                    override fun beforeRequest(clientChannel: Channel, httpRequest: HttpRequest, pipeline: HttpProxyInterceptPipeline) {
                        captured.add("${httpRequest.method()} ${httpRequest.uri()} Host=${httpRequest.headers().get("Host")}")
                        super.beforeRequest(clientChannel, httpRequest, pipeline)
                    }
                })
            }
        }

        // 3) 真实 CA:用 proxyee CertUtil 现场生成(让监听端 SslContext 能签出 localhost 证书)
        val caKeyPair = CertUtil.genKeyPair()
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1))
        val caCert = CertUtil.genCACert("CN=xproxy-test", notBefore, notAfter, caKeyPair)

        // 4) 启动统一代理 server(handleSsl=true,监听端支持 TLS-on-listener)
        val proxyPort = freePort()
        val runtime = ProxyeeRuntime(
            running = running,
            onStatusChanged = { _, _ -> },
            createCaCertFactory = {
                object : HttpProxyCACertFactory {
                    override fun getCACert(): X509Certificate = caCert
                    override fun getCAPriKey(): PrivateKey = caKeyPair.private
                }
            },
            createInterceptInitializer = { initializer },
            configureServer = {},
            registerServerRef = {},
            listeningStatusProvider = { "listening" }
        )
        this.runtime = runtime
        thread(isDaemon = true) { runtime.start("127.0.0.1", proxyPort, handleSsl = true) }
        waitForPort(proxyPort)

        // 5) TLS 客户端:信任所有(等价 curl -k),层叠到已连接的裸 socket 上
        val clientSslContext = SSLContext.getInstance("TLS")
        clientSslContext.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), null)
        val raw = Socket("127.0.0.1", proxyPort)
        raw.soTimeout = 8000
        val client = clientSslContext.socketFactory.createSocket(raw, "localhost", raw.port, true) as SSLSocket
        client.startHandshake()

        // 6) TLS 内层:绝对 URI 形式的代理请求(带 Proxy-Connection,避免被当成直连 CA 下载页)
        val out = client.getOutputStream()
        out.write(
            "GET http://127.0.0.1:$upstreamPort/marker HTTP/1.1\r\nHost: 127.0.0.1:$upstreamPort\r\nProxy-Connection: close\r\n\r\n"
                .toByteArray(Charsets.ISO_8859_1)
        )
        out.flush()

        // 7) 读响应(应来自上游的 200 TLS-OK)
        val inp = client.getInputStream()
        val respBuf = ByteArray(4096)
        val n = try { inp.read(respBuf) } catch (e: Exception) { -1 }
        val resp = if (n > 0) String(respBuf, 0, n, Charsets.ISO_8859_1) else ""
        assertTrue(resp.contains("200"), "client should receive upstream 200 over TLS, got: ${resp.take(120)}")

        // 8) 验证上游收到请求,且拦截链捕获到
        val deadline = System.currentTimeMillis() + 3000
        while (upstreamReceived.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(upstreamReceived.any { it.contains("/marker") }, "upstream should receive GET /marker via TLS proxy")
        assertTrue(captured.any { it.contains("/marker") }, "intercept chain should capture the request through TLS listener")

        client.close()
        upstream.close()
        upstreamThread.join(2000)
    }

    /**
     * SOCKS5 隧道里收到微信 mmtls(0x16 开头但记录层版本号非 0x03,如 0xf1 0x04)时,不能走标准 TLS MITM
     * (SslHandler 会 NotSslRecordException 关连接),而应纯隧道盲转发到上游。对应用户场景:proxifier 把
     * 微信 443 流量经 SOCKS5 转给 XProxy,原先 25 次 NotSslRecordException,history 只剩 80 端口短连接。
     */
    @Test
    fun `socks5 connect then mmtls nonstandard tls is raw forwarded not mitm`() {
        // 1) 上游:记录收到的原始字节,并回写一个字节验证双向隧道
        val upstreamReceived = CopyOnWriteArrayList<ByteArray>()
        val upstream = ServerSocket(0)
        val upstreamPort = upstream.localPort
        val upstreamThread = thread(isDaemon = true) {
            try {
                while (true) {
                    val conn = upstream.accept()
                    val ins = conn.getInputStream()
                    val buf = ByteArray(4096)
                    val n = ins.read(buf)
                    if (n > 0) upstreamReceived.add(buf.copyOf(n))
                    conn.getOutputStream().write(0xAA)
                    conn.getOutputStream().flush()
                    conn.close()
                }
            } catch (_: Exception) {
            }
        }

        // 2) 代理:handleSsl=true(真实 CA),SOCKS5
        val caKeyPair = CertUtil.genKeyPair()
        val caCert = CertUtil.genCACert(
            "CN=xproxy-test", Date(), Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)), caKeyPair
        )
        val proxyPort = freePort()
        val runtime = ProxyeeRuntime(
            running = running,
            onStatusChanged = { _, _ -> },
            createCaCertFactory = {
                object : HttpProxyCACertFactory {
                    override fun getCACert(): X509Certificate = caCert
                    override fun getCAPriKey(): PrivateKey = caKeyPair.private
                }
            },
            createInterceptInitializer = { HttpProxyInterceptInitializer() },
            configureServer = {},
            registerServerRef = {},
            listeningStatusProvider = { "listening" }
        )
        this.runtime = runtime
        thread(isDaemon = true) { runtime.start("127.0.0.1", proxyPort, handleSsl = true) }
        waitForPort(proxyPort)

        // 3) SOCKS5 握手 + CONNECT 上游
        val client = Socket("127.0.0.1", proxyPort)
        client.soTimeout = 8000
        val out = client.getOutputStream()
        val inp = client.getInputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        val methodReply = ByteArray(2); readFully(inp, methodReply)
        val portHi = (upstreamPort shr 8).toByte()
        val portLo = (upstreamPort and 0xFF).toByte()
        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, portHi, portLo)); out.flush()
        val connectReply = ByteArray(10); readFully(inp, connectReply)
        assertEquals(0, connectReply[1].toInt() and 0xFF, "CONNECT should succeed")

        // 4) 发微信 mmtls 风格的非标准 TLS 字节(0x16 + 版本号 0xf1 0x04,非标准 0x03)
        val mmtls = byteArrayOf(0x16, 0xf1.toByte(), 0x04, 0x01, 0x6e, 0x00, 0x00, 0x01, 0x6a, 0x01, 0x04)
        out.write(mmtls); out.flush()

        // 5) 验证上游收到原字节(盲转发),而非被 MITM 拦截(若 MITM 会 NotSslRecordException,上游收不到)
        val deadline = System.currentTimeMillis() + 5000
        while (upstreamReceived.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val received = upstreamReceived.firstOrNull()
        assertTrue(received != null && received.size >= 2 && received[0] == 0x16.toByte() && received[1] == 0xf1.toByte(),
            "upstream should receive raw mmtls bytes (raw forward, not MITM), got: ${received?.toList()?.take(8)}")

        // 6) 验证隧道双向通:客户端收到上游回写的 0xAA(若连接被 MITM 关闭,读会失败)
        val respByte = ByteArray(1)
        val n = try { inp.read(respByte) } catch (e: Exception) { -1 }
        assertTrue(n == 1 && respByte[0] == 0xAA.toByte(),
            "client should receive upstream echo through tunnel (connection alive, not closed by MITM failure)")

        client.close()
        upstream.close()
        upstreamThread.join(2000)
    }
}
