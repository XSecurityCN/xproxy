package org.jjgroup.xproxy.proxy.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class NativeProxyRuntimeLifecycleTest {

    @Test
    fun `start and stop emit running state transitions`() {
        val events = CopyOnWriteArrayList<Pair<Boolean, String>>()
        val runtime = NativeProxyRuntime(
            onRunningChanged = { running, msg -> events += running to msg }
        )

        val port = ServerSocket(0).use { it.localPort }
        runtime.start("127.0.0.1", port, true)
        Thread.sleep(100)
        runtime.stop()

        assertTrue(events.isNotEmpty())
        assertEquals(true, events.first().first)
        assertEquals(false, events.last().first)
        assertTrue(events.last().second.contains("stopped", ignoreCase = true))
    }
}
