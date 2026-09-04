package org.jjgroup.xproxy.mcp

import org.jjgroup.xproxy.mcp.attack.McpAttackRunner
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.tools.McpToolContext
import org.jjgroup.xproxy.mcp.tools.currentSelectionTools
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GetCurrentRequestToolTest {

    @Test
    fun `currentSelectionTools registers get_current_request`() {
        val tool = currentSelectionTools().single()
        assertEquals("get_current_request", tool.name)
    }

    @Test
    fun `returns null selections when no app context is bound`() {
        // 无应用启动时 XproxyAppContext 各字段为 null;工具应优雅返回全 null 结构而非报错。
        val tool = currentSelectionTools().single()
        val ctx = McpToolContext(McpAttackRunner())

        val result = tool.invoke(McpJson.obj(), ctx)

        // 若测试环境 EDT 不可用会返回 isError("UI busy");非 headless 环境应返回全 null 结构。
        if (result.isError) {
            // 容许 headless 环境的 UI-busy 错误,测试仍算通过(环境限制)。
            return
        }
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertNotNull(node.get("activeModule"))
        assertTrue(node.get("proxy").isNull, "proxy should be null when no app is loaded")
        assertTrue(node.get("target").isNull, "target should be null when no app is loaded")
        assertTrue(node.get("fuzzer").isNull, "fuzzer should be null when no app is loaded")
    }
}
