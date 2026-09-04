package org.jjgroup.xproxy.proxy.runtime.native.h2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jjgroup.xproxy.settings.core.UpstreamProxyConfig
import org.jjgroup.xproxy.settings.core.UpstreamProxyProtocol
import javax.net.ssl.SSLContext

class NativeHttp2UpstreamClientTest {

    @Test
    fun `client supports injected ssl context for upstream requests`() {
        val sslContext = SSLContext.getDefault()
        val client = NativeHttp2UpstreamClient(sslContext = sslContext)
        assertTrue(client !== null)
    }

    @Test
    fun `parse raw request extracts method path headers and body`() {
        val parsed = parseRawHttpRequest(
            "POST /v1/items HTTP/2\r\nHost: api.example.com\r\nContent-Type: application/json\r\n\r\n{\"a\":1}"
        )

        assertEquals("POST", parsed.method)
        assertEquals("/v1/items", parsed.path)
        assertEquals("api.example.com", parsed.headers.first { it.first == "Host" }.second)
        assertEquals("application/json", parsed.headers.first { it.first == "Content-Type" }.second)
        assertEquals("{\"a\":1}", String(parsed.body, Charsets.ISO_8859_1))
    }

    @Test
    fun `parse raw request preserves multipart body CRLF bytes`() {
        val boundary = "----WebKitFormBoundaryUpload"
        val bodyBytes = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"lastModifiedDate\"\r\n" +
                "\r\n"
            ).toByteArray(Charsets.ISO_8859_1) +
            "Mon Mar 10 2025 20:42:37 GMT+0800 (中国标准时间)".toByteArray(Charsets.UTF_8) +
            (
                "\r\n" +
                    "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"filename\"; filename=\"9527.log\"\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "\r\n" +
                    "9527\r\n" +
                    "--$boundary--\r\n"
                ).toByteArray(Charsets.ISO_8859_1)
        val body = String(bodyBytes, Charsets.ISO_8859_1)
        val raw = "POST /upload HTTP/2\r\n" +
            "Host: file.wx.qq.com\r\n" +
            "Content-Type: multipart/form-data; boundary=$boundary\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "\r\n" +
            body

        val parsed = parseRawHttpRequest(raw)

        assertArrayEquals(bodyBytes, parsed.body)
    }

    @Test
    fun `raw response rendering keeps status headers and body`() {
        val raw = NativeHttp2ExchangeResult(
            statusCode = 200,
            headers = listOf("content-type" to "text/plain"),
            body = "ok".toByteArray(Charsets.ISO_8859_1),
            negotiatedProtocol = "HTTP_2"
        ).toRawResponse()

        assertTrue(raw.startsWith("HTTP/2.0 200"))
        assertTrue(raw.contains("content-type: text/plain"))
        assertTrue(raw.endsWith("ok"))
    }

    @Test
    fun `raw response rendering uses negotiated http1 status line when upstream falls back`() {
        val result = NativeHttp2ExchangeResult(
            statusCode = 200,
            headers = listOf("content-type" to "text/plain"),
            body = "ok".toByteArray(Charsets.ISO_8859_1),
            negotiatedProtocol = "HTTP_1_1"
        )
        val raw = result.toRawResponse()

        assertTrue(raw.startsWith("HTTP/1.1 200"))
        assertEquals("http/1.1", result.historyProtocol())
        assertTrue(result.recordedRequestRaw("GET / HTTP/2\r\nHost: example.com\r\n\r\n").startsWith("GET / HTTP/1.1\r\n"))
    }

    @Test
    fun `recorded request raw uses negotiated http2 when upstream succeeds as h2`() {
        val result = NativeHttp2ExchangeResult(
            statusCode = 200,
            headers = emptyList(),
            body = ByteArray(0),
            negotiatedProtocol = "HTTP_2"
        )

        assertTrue(result.recordedRequestRaw("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n").startsWith("GET / HTTP/2\r\n"))
    }

    @Test
    fun `raw response rendering drops pseudo headers`() {
        val raw = NativeHttp2ExchangeResult(
            statusCode = 200,
            headers = listOf(":status" to "200", "content-type" to "text/plain"),
            body = ByteArray(0),
            negotiatedProtocol = "HTTP_2"
        ).toRawResponse()

        assertTrue(raw.contains("content-type: text/plain"))
        assertTrue(!raw.contains(":status"))
    }

    @Test
    fun `require http2 rejects downgraded response`() {
        val client = NativeHttp2UpstreamClient()
        val downgraded = NativeHttp2ExchangeResult(
            statusCode = 200,
            headers = emptyList(),
            body = ByteArray(0),
            negotiatedProtocol = "HTTP_1_1"
        )
        kotlin.test.assertFailsWith<IllegalStateException> {
            client.requireHttp2(downgraded)
        }
    }

    @Test
    fun `restricted request header detection covers content length`() {
        assertTrue(isRestrictedRequestHeader("content-length"))
        assertTrue(isRestrictedRequestHeader("host"))
        assertTrue(!isRestrictedRequestHeader("user-agent"))
    }

    @Test
    fun `restricted request header detection covers h2 fallback hop by hop headers`() {
        assertTrue(isRestrictedRequestHeader("connection"))
        assertTrue(isRestrictedRequestHeader("proxy-connection"))
        assertTrue(isRestrictedRequestHeader("keep-alive"))
        assertTrue(isRestrictedRequestHeader("transfer-encoding"))
        assertTrue(isRestrictedRequestHeader("upgrade"))
        assertTrue(isRestrictedRequestHeader("te"))
        assertTrue(isRestrictedRequestHeader("x-http2-stream-id"))
    }

    @Test
    fun `build target uri supports absolute form with pipe in query`() {
        val uri = buildTargetUri(
            scheme = "https",
            authority = "fonts.googleapis.com",
            target = "https://fonts.googleapis.com/css?family=Open+Sans:400,700|Source+Code+Pro:300,600"
        )
        assertEquals("https", uri.scheme)
        assertEquals("fonts.googleapis.com", uri.host)
        assertEquals("/css", uri.path)
        assertTrue(!uri.rawQuery.contains("|"))
        assertTrue(uri.rawQuery.contains("family="))
    }

    @Test
    fun `build target uri supports origin form`() {
        val uri = buildTargetUri(
            scheme = "https",
            authority = "api.example.com",
            target = "/v1/search?q=a|b"
        )
        assertEquals("https", uri.scheme)
        assertEquals("api.example.com", uri.host)
        assertEquals("/v1/search", uri.path)
        assertTrue(!uri.rawQuery.contains("|"))
        assertTrue(uri.rawQuery.startsWith("q="))
    }

    @Test
    fun `build target uri preserves already encoded utf8 query`() {
        val uri = buildTargetUri(
            scheme = "https",
            authority = "www.example.com",
            target = "/search?q=%E5%95%8A%E5%95%8A%E5%95%8A"
        )

        assertEquals("/search", uri.rawPath)
        assertEquals("q=%E5%95%8A%E5%95%8A%E5%95%8A", uri.rawQuery)
    }

    @Test
    fun `build target uri preserves already encoded utf8 query in absolute form`() {
        val uri = buildTargetUri(
            scheme = "https",
            authority = "fallback.example.com",
            target = "https://www.example.com/search?q=%E5%95%8A%E5%95%8A%E5%95%8A"
        )

        assertEquals("www.example.com", uri.host)
        assertEquals("/search", uri.rawPath)
        assertEquals("q=%E5%95%8A%E5%95%8A%E5%95%8A", uri.rawQuery)
    }

    @Test
    fun `build target uri encodes raw utf8 query once`() {
        val uri = buildTargetUri(
            scheme = "https",
            authority = "www.example.com",
            target = "/search?q=啊啊啊"
        )

        assertEquals("q=啊啊啊", uri.rawQuery)
        assertTrue(uri.toASCIIString().contains("q=%E5%95%8A%E5%95%8A%E5%95%8A"))
        assertTrue(!uri.toASCIIString().contains("%25E5%2595%258A"))
    }

    @Test
    fun `proxy selector uses configured http upstream`() {
        val selector = proxySelectorFor(
            UpstreamProxyConfig(
                host = "127.0.0.1",
                port = 8888,
                protocol = UpstreamProxyProtocol.HTTP,
                username = "",
                password = ""
            )
        )
        assertNotNull(selector)
        val proxies = selector!!.select(java.net.URI("https://example.com/test"))
        assertEquals(1, proxies.size)
        assertEquals(java.net.Proxy.Type.HTTP, proxies[0].type())
        val address = proxies[0].address() as java.net.InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(8888, address.port)
    }

    @Test
    fun `proxy selector uses configured socks5 upstream`() {
        val selector = proxySelectorFor(
            UpstreamProxyConfig(
                host = "localhost",
                port = 1080,
                protocol = UpstreamProxyProtocol.SOCKS5,
                username = "",
                password = ""
            )
        )
        assertNotNull(selector)
        val proxies = selector!!.select(java.net.URI("https://example.com/test"))
        assertEquals(java.net.Proxy.Type.SOCKS, proxies[0].type())
        val address = proxies[0].address() as java.net.InetSocketAddress
        assertEquals(1080, address.port)
    }

    @Test
    fun `proxy authenticator is absent when no credentials`() {
        val proxy = UpstreamProxyConfig(
            host = "127.0.0.1",
            port = 8888,
            protocol = UpstreamProxyProtocol.HTTP,
            username = "",
            password = ""
        )
        assertNull(proxyAuthenticatorFor(proxy))
    }

    @Test
    fun `proxy authenticator is provided when credentials configured`() {
        val proxy = UpstreamProxyConfig(
            host = "127.0.0.1",
            port = 8888,
            protocol = UpstreamProxyProtocol.HTTP,
            username = "alice",
            password = "secret"
        )
        assertNotNull(proxyAuthenticatorFor(proxy))
    }
}
