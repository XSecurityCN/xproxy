package org.jjgroup.xproxy.proxy.runtime

import org.jjgroup.xproxy.proxy.portal.ProxyPortal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket

class NativeProxyPortalTest {

    @Test
    fun `serializes portal result as http response`() {
        val result = requireNotNull(ProxyPortal.handleRequest("GET", "/", "xproxy", "127.0.0.1", 8080))

        val raw = NativeProxyRuntime.serializePortalResultForTest(result).toString(Charsets.ISO_8859_1)

        assertTrue(raw.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(raw.contains("Content-Type: text/html; charset=utf-8\r\n"))
        assertTrue(raw.contains("Connection: close\r\n"))
        assertTrue(raw.contains("Content-Length: "))
        assertTrue(raw.contains("xproxy is running"))
    }

    @Test
    fun `native runtime serves direct portal request`() {
        val runtime = NativeProxyRuntime()
        val proxyPort = ServerSocket(0).use { it.localPort }
        runtime.start("127.0.0.1", proxyPort, true)
        try {
            val response = Socket("127.0.0.1", proxyPort).use { client ->
                val request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:$proxyPort\r\nConnection: close\r\n\r\n"
                client.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                client.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            }

            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(response.contains("Content-Type: text/html; charset=utf-8\r\n"))
            assertTrue(response.contains("Connection: close\r\n"))
            assertTrue(response.contains("xproxy is running"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun `native simple error response uses html portal shell`() {
        val raw = NativeProxyRuntime.simpleResponseForTest(400, "Bad Request", "Unable to resolve upstream target")
            .toString(Charsets.ISO_8859_1)

        assertTrue(raw.startsWith("HTTP/1.1 400 Bad Request\r\n"))
        assertTrue(raw.contains("Content-Type: text/html; charset=utf-8\r\n"))
        assertTrue(raw.contains("Connection: close\r\n"))
        assertTrue(raw.contains("Unable to resolve upstream target"))
    }

    @Test
    fun `native portal matcher does not capture normal upstream host`() {
        val result = ProxyPortal.handleRequest("GET", "/", "example.com", "127.0.0.1", 8080)

        assertEquals(null, result)
    }
}
