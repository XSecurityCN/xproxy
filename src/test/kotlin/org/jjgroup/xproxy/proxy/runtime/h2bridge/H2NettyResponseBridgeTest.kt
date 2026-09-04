package org.jjgroup.xproxy.proxy.runtime.h2bridge

import org.jjgroup.xproxy.proxy.runtime.native.h2.NativeHttp2ExchangeResult
import org.jjgroup.xproxy.settings.core.UpstreamProxyConfig
import org.jjgroup.xproxy.settings.core.UpstreamProxyProtocol
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import io.netty.handler.codec.http.HttpVersion
import org.junit.jupiter.api.Test

class H2NettyResponseBridgeTest {

    @Test
    fun `normalize fallback request downgrades protocol and strips pseudo headers`() {
        val bridge = H2NettyResponseBridge()
        val raw = "GET /img.png HTTP/2\r\n:authority: static.example.com\r\naccept: image/webp\r\n\r\n"

        val normalized = bridge.normalizeRequestForHttp11Fallback(raw, "static.example.com:443")

        assertTrue(normalized.startsWith("GET /img.png HTTP/1.1\r\n"))
        assertTrue(!normalized.contains(":authority:"))
        assertTrue(normalized.contains("Host: static.example.com:443\r\n"))
    }

    @Test
    fun `normalize fallback request strips h2 hop by hop headers and closes h1 connection`() {
        val bridge = H2NettyResponseBridge()
        val raw = (
            "GET https://static.example.com/assets/app.js HTTP/2\r\n" +
                ":authority: static.example.com\r\n" +
                "Host: static.example.com\r\n" +
                "Connection: keep-alive\r\n" +
                "Proxy-Connection: keep-alive\r\n" +
                "Keep-Alive: timeout=5\r\n" +
                "Upgrade: h2c\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "TE: gzip\r\n" +
                "X-Http2-Stream-Id: 7\r\n" +
                "accept: */*\r\n\r\n"
            )

        val normalized = bridge.normalizeRequestForHttp11Fallback(raw, "static.example.com:443")

        assertTrue(normalized.startsWith("GET /assets/app.js HTTP/1.1\r\n"))
        assertTrue(!normalized.contains("\r\n:authority:"))
        assertTrue(!normalized.contains("\r\nProxy-Connection:", ignoreCase = true))
        assertTrue(!normalized.contains("\r\nKeep-Alive:", ignoreCase = true))
        assertTrue(!normalized.contains("\r\nUpgrade:", ignoreCase = true))
        assertTrue(!normalized.contains("\r\nTransfer-Encoding:", ignoreCase = true))
        assertTrue(!normalized.contains("\r\nTE: gzip", ignoreCase = true))
        assertTrue(!normalized.contains("\r\nX-Http2-Stream-Id:", ignoreCase = true))
        assertTrue(normalized.contains("\r\nConnection: close\r\n"))
    }

    @Test
    fun `normalize fallback request preserves encoded utf8 query`() {
        val bridge = H2NettyResponseBridge()
        val raw = (
            "GET https://www.example.com/search?q=%E5%95%8A%E5%95%8A%E5%95%8A HTTP/2\r\n" +
                ":authority: www.example.com\r\n\r\n"
            )

        val normalized = bridge.normalizeRequestForHttp11Fallback(raw, "www.example.com:443")

        assertTrue(normalized.startsWith("GET /search?q=%E5%95%8A%E5%95%8A%E5%95%8A HTTP/1.1\r\n"))
        assertTrue(!normalized.contains("%25E5%2595%258A"))
    }

    @Test
    fun `normalize fallback request preserves multipart body CRLF bytes`() {
        val bridge = H2NettyResponseBridge()
        val boundary = "----WebKitFormBoundaryUpload"
        val body = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"filename\"; filename=\"9527.log\"\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "\r\n" +
            "9527\r\n" +
            "--$boundary--\r\n"
        val raw = "POST /cgi-bin/mmwebwx-bin/webwxuploadmedia HTTP/2\r\n" +
            "Host: file.wx.qq.com\r\n" +
            "Content-Type: multipart/form-data; boundary=$boundary\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.ISO_8859_1).size}\r\n" +
            "\r\n" +
            body

        val normalized = bridge.normalizeRequestForHttp11Fallback(raw, "file.wx.qq.com:443")

        assertTrue(normalized.startsWith("POST /cgi-bin/mmwebwx-bin/webwxuploadmedia HTTP/1.1\r\n"))
        assertEquals(body, normalized.substringAfter("\r\n\r\n"))
    }

    @Test
    fun `decode chunked body removes chunk framing`() {
        val bridge = H2NettyResponseBridge()
        val raw = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        val decoded = bridge.decodeChunkedBody(raw)

        assertArrayEquals("Wikipedia".toByteArray(Charsets.ISO_8859_1), decoded)
    }

    @Test
    fun `decode chunked body keeps original on malformed input`() {
        val bridge = H2NettyResponseBridge()
        val raw = "5\r\nabc\r\n".toByteArray(Charsets.ISO_8859_1)

        val decoded = bridge.decodeChunkedBody(raw)

        assertArrayEquals(raw, decoded)
    }

    @Test
    fun `send options carry explicit upstream proxy without reading globals`() {
        val proxy = UpstreamProxyConfig("proxy.local", 9000, UpstreamProxyProtocol.HTTP, "", "")

        val options = H2NettyForwardOptions(
            requestRaw = "GET / HTTP/2\r\nHost: api.example.com\r\n\r\n",
            authority = "api.example.com",
            upstreamProxy = proxy
        )

        assertEquals(proxy, options.upstreamProxy)
        assertEquals("api.example.com", options.authority)
    }

    @Test
    fun `derive authority uses edited Host header`() {
        val authority = H2NettyResponseBridge.deriveAuthority(
            requestRaw = "GET / HTTP/2\r\nHost: edited.example:9443\r\n\r\n",
            fallbackAuthority = "original.example:443"
        )

        assertEquals("edited.example:9443", authority)
    }

    @Test
    fun `netty response body buffer allows response replacement growth`() {
        val response = H2NettyResponseBridge.toNettyResponseForTest(
            NativeHttp2ExchangeResult(
                statusCode = 200,
                headers = listOf("content-type" to "text/plain"),
                body = "short-token".toByteArray(Charsets.ISO_8859_1),
                negotiatedProtocol = "HTTP_2"
            )
        )

        response.content().clear()
        response.content().writeBytes("short-token-expanded".toByteArray(Charsets.ISO_8859_1))

        assertEquals("short-token-expanded", response.content().toString(Charsets.ISO_8859_1))
    }

    @Test
    fun `netty response version follows negotiated protocol`() {
        val h1Response = H2NettyResponseBridge.toNettyResponseForTest(
            NativeHttp2ExchangeResult(
                statusCode = 200,
                headers = listOf("content-type" to "text/plain"),
                body = "ok".toByteArray(Charsets.ISO_8859_1),
                negotiatedProtocol = "HTTP_1_1"
            )
        )
        val h2Response = H2NettyResponseBridge.toNettyResponseForTest(
            NativeHttp2ExchangeResult(
                statusCode = 200,
                headers = listOf("content-type" to "text/plain"),
                body = "ok".toByteArray(Charsets.ISO_8859_1),
                negotiatedProtocol = "HTTP_2"
            )
        )

        assertEquals(HttpVersion.HTTP_1_1, h1Response.protocolVersion())
        assertEquals(HttpVersion.valueOf("HTTP/2.0"), h2Response.protocolVersion())
    }

    @Test
    fun `upstream client is shared per proxy config for connection reuse`() {
        H2NettyResponseBridge.clearSharedClientsForTest()
        try {
            val bridge = H2NettyResponseBridge()
            // 同 upstreamProxy(含 DIRECT/null)多次取返回同一实例,复用上游 TLS+H2 连接池
            val directA = bridge.createClientForTest(null)
            val directB = bridge.createClientForTest(null)
            assertSame(directA, directB)
            assertEquals(1, H2NettyResponseBridge.sharedClientCountForTest())

            // 不同 upstreamProxy 分别缓存
            val proxy = UpstreamProxyConfig("proxy.local", 9000, UpstreamProxyProtocol.HTTP, "", "")
            val proxied = bridge.createClientForTest(proxy)
            assertNotSame(directA, proxied)
            assertEquals(2, H2NettyResponseBridge.sharedClientCountForTest())

            // 相同配置的不同实例(data class equals 相等)复用同一 client
            val proxySameValue = UpstreamProxyConfig("proxy.local", 9000, UpstreamProxyProtocol.HTTP, "", "")
            val proxiedAgain = bridge.createClientForTest(proxySameValue)
            assertSame(proxied, proxiedAgain)
            assertEquals(2, H2NettyResponseBridge.sharedClientCountForTest())
        } finally {
            H2NettyResponseBridge.clearSharedClientsForTest()
        }
    }
}
