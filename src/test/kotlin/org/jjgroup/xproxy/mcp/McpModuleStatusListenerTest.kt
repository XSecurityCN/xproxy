package org.jjgroup.xproxy.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McpModuleStatusListenerTest {

    @Test
    fun `status listeners are notified and removable`() {
        var called = 0
        val remove = McpModule.addStatusListener { called++ }
        McpModule.notifyStatusListenersForTests()
        assertEquals(1, called)
        McpModule.notifyStatusListenersForTests()
        assertEquals(2, called)
        // 取消注册后不再被通知。
        remove()
        McpModule.notifyStatusListenersForTests()
        assertEquals(2, called)
    }

    @Test
    fun `a failing listener does not block others`() {
        var called = false
        val remove1 = McpModule.addStatusListener { called = true }
        // 注册一个会抛异常的监听器在前,不应阻断后续监听器(notifyStatusListeners 用 runCatching 隔离)。
        val remove2 = McpModule.addStatusListener { error("boom") }
        try {
            McpModule.notifyStatusListenersForTests()
            assertEquals(true, called)
        } finally {
            remove1()
            remove2()
        }
    }
}
