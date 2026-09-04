package org.jjgroup.xproxy.proxy.runtime

import org.jjgroup.xproxy.proxy.core.ProtocolPolicy
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NativeProxyRuntimeHistoryTest {

    @Test
    fun `native runtime emits history entry for forwarded http request`() {
        val upstreamServer = ServerSocket(0)
        val upstreamPort = upstreamServer.localPort
        val upstreamThread = thread(isDaemon = true) {
            upstreamServer.use { server ->
                val socket = server.accept()
                socket.use { s ->
                    val input = s.getInputStream()
                    val marker = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
                    val buffer = ByteArray(1)
                    var matched = 0
                    while (matched < marker.size) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        if (buffer[0] == marker[matched]) {
                            matched++
                        } else {
                            matched = if (buffer[0] == marker[0]) 1 else 0
                        }
                    }
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    s.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
                    s.getOutputStream().flush()
                }
            }
        }

        val captured = CopyOnWriteArrayList<ProxyHistoryEntry>()
        val latch = CountDownLatch(1)
        val runtime = NativeProxyRuntime(
            protocolPolicyProvider = { ProtocolPolicy.preserve() },
            onHistoryAdded = {
                captured += it
                latch.countDown()
            },
            nextHistoryId = { 1L }
        )

        val proxyPort = ServerSocket(0).use { it.localPort }
        runtime.start("127.0.0.1", proxyPort, true)
        try {
            Socket("127.0.0.1", proxyPort).use { client ->
                val request = "GET http://127.0.0.1:$upstreamPort/health HTTP/1.1\r\nHost: 127.0.0.1:$upstreamPort\r\nConnection: close\r\n\r\n"
                client.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                client.getInputStream().readBytes()
            }

            assertTrue(latch.await(3, TimeUnit.SECONDS), "history callback was not invoked")
            assertEquals(1, captured.size)
            val entry = captured.first()
            assertEquals("GET", entry.method)
            assertEquals("127.0.0.1:$upstreamPort", entry.host)
            assertEquals("http/1.1", entry.protocol)
            assertEquals(200, entry.statusCode)
            assertTrue(entry.requestRaw.contains("/health"))
            assertTrue(entry.responseRaw.contains("HTTP/1.1 200 OK"))
        } finally {
            runtime.stop()
            upstreamThread.join(1000)
        }
    }
}
