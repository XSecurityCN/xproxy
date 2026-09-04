package org.jjgroup.xproxy.mcp.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.mcp.attack.McpAttackRunner
import org.jjgroup.xproxy.mcp.server.McpJson

/**
 * 单个 MCP 工具:名称、描述、JSON Schema 入参,以及同步执行体(在 worker 线程上运行,允许阻塞)。
 *
 * 工具通过 [XproxyAppContext][org.jjgroup.xproxy.mcp.XproxyAppContext] 访问活跃应用状态,
 * 通过 [McpToolContext.attackRunner] 启动/查询攻击。返回 [McpToolResult],由 dispatcher 序列化为
 * MCP `tools/call` 结果(content 数组 + isError)。
 */
interface McpTool {
    val name: String
    val description: String
    val inputSchema: ObjectNode

    fun invoke(args: JsonNode, ctx: McpToolContext): McpToolResult
}

/** 工具执行上下文:提供攻击运行器(线程安全单例)与共享 mapper。 */
class McpToolContext(
    val attackRunner: McpAttackRunner
)

/** 工具产出的一块内容。MCP 当前只用 text(把结构化结果序列化为 JSON 字符串放进 text)。 */
data class McpText(val text: String)

data class McpToolResult(
    val content: List<McpText>,
    val isError: Boolean = false
) {
    companion object {
        /** 正常结果:把 [value] 序列化为 JSON 文本返回。 */
        fun ok(value: Any?): McpToolResult =
            McpToolResult(listOf(McpText(McpJson.stringify(value))))

        /** 正常结果:直接返回纯文本。 */
        fun text(message: String): McpToolResult =
            McpToolResult(listOf(McpText(message)))

        /** 工具级错误(不是协议错误):isError=true,客户端据此向模型反馈。 */
        fun error(message: String): McpToolResult =
            McpToolResult(listOf(McpText(message)), isError = true)
    }
}

/**
 * 工具注册表。dispatcher 的 `tools/list` 遍历它;`tools/call` 按名查找。
 * 注册顺序即 `tools/list` 返回顺序,按区域分组便于 agent 浏览。
 */
class McpToolRegistry {
    private val tools = LinkedHashMap<String, McpTool>()

    fun register(tool: McpTool) {
        tools[tool.name] = tool
    }

    fun registerAll(tools: List<McpTool>) = tools.forEach { register(it) }

    fun get(name: String): McpTool? = tools[name]

    fun all(): List<McpTool> = tools.values.toList()
}
