package org.jjgroup.xproxy.mcp.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.mcp.server.mcpSchema

/* ============================ Area 4: 元信息工具 ============================ */

internal class GetProjectInfoTool : BaseTool() {
    override val name = "get_project_info"
    override val description = "Return info about the currently loaded xproxy project (name, db path, traffic/issue counts)."
    override val inputSchema: ObjectNode = mcpSchema {}

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val project = XproxyAppContext.projectRecord()
        val history = store.loadHistoryMetadata()
        val issues = store.loadReportedIssueMetadata()
        return McpToolResult.ok(mapOf(
            "project" to mapOf(
                "id" to (project?.id ?: ""), "name" to (project?.displayName ?: ""),
                "baseName" to (project?.baseName ?: ""), "dbPath" to (project?.dbPath ?: "")
            ),
            "historyCount" to history.size,
            "issueCount" to issues.size
        ))
    }
}

internal class GetProxyStatusTool : BaseTool() {
    override val name = "get_proxy_status"
    override val description = "Return the proxy listener status (running + listening address)."
    override val inputSchema: ObjectNode = mcpSchema {}

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val proxyPanel = XproxyAppContext.proxyPanel()
            ?: return McpToolResult.ok(mapOf("running" to false, "status" to "Proxy panel not ready."))
        val controller = proxyPanel.controller
        return McpToolResult.ok(mapOf(
            "running" to controller.isRunning(),
            "status" to runCatching { controller.currentStatusText() }.getOrDefault("")
        ))
    }
}

/** Area 4 工具集。 */
fun metaTools(): List<McpTool> = listOf(
    GetProjectInfoTool(),
    GetProxyStatusTool()
)
