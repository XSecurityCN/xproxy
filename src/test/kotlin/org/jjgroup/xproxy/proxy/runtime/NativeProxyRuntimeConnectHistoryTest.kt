package org.jjgroup.xproxy.proxy.runtime

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

class NativeProxyRuntimeConnectHistoryTest {

    @Test
    fun `connect tunnel emits history entry`() {
        val upstreamServer = ServerSocket(0)
        val upstreamPort = upstreamServer.localPort
        val upstreamThread = thread(isDaemon = true) {
            upstreamServer.use { server ->
                server.accept().use { socket ->
                    Thread.sleep(200)
                    socket.close()
                }
            }
        }

        val captured = CopyOnWriteArrayList<ProxyHistoryEntry>()
        val latch = CountDownLatch(1)
        val runtime = NativeProxyRuntime(
            onHistoryAdded = {
                captured += it
                latch.countDown()
            },
            nextHistoryId = { 10L }
        )

        val proxyPort = ServerSocket(0).use { it.localPort }
        runtime.start("127.0.0.1", proxyPort, true)
        try {
            Socket("127.0.0.1", proxyPort).use { client ->
                val req = "CONNECT 127.0.0.1:$upstreamPort HTTP/1.1\r\nHost: 127.0.0.1:$upstreamPort\r\n\r\n"
                client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                Thread.sleep(100)
            }

            assertTrue(latch.await(3, TimeUnit.SECONDS), "CONNECT history callback not invoked")
            val entry = captured.first()
            assertEquals("CONNECT", entry.method)
            assertEquals("127.0.0.1:$upstreamPort", entry.host)
            assertEquals(200, entry.statusCode)
            assertTrue(entry.responseRaw.contains("200 Connection Established"))
        } finally {
            runtime.stop()
            upstreamThread.join(1000)
        }
    }
}
