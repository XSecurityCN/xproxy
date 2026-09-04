package org.jjgroup.xproxy.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.mcp.attack.McpAttackRunner
import org.jjgroup.xproxy.mcp.server.McpDispatcher
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.server.McpServer
import org.jjgroup.xproxy.mcp.server.mcpSchema
import org.jjgroup.xproxy.mcp.tools.McpTool
import org.jjgroup.xproxy.mcp.tools.McpToolContext
import org.jjgroup.xproxy.mcp.tools.McpToolRegistry
import org.jjgroup.xproxy.mcp.tools.McpToolResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 端到端:启动真实 Netty MCP 服务端,用 JDK HttpClient 验证 鉴权 / initialize / tools/list / tools/call / health。
 */
class McpServerIntegrationTest {
    private lateinit var server: McpServer
    private lateinit var dispatcher: McpDispatcher
    private val client = HttpClient.newHttpClient()
    private val token = "integration-test-token-1234567890"

    @BeforeEach
    fun setUp() {
        // 隔离:测试期间 Settings.setX 不写盘,避免 token 等污染生产 Java Preferences。
        org.jjgroup.xproxy.core.Settings.setFlushHookForTests { }
        // 直接构造 dispatcher + 一个 echo 工具,不依赖应用上下文。
        val registry = McpToolRegistry()
        registry.register(object : McpTool {
            override val name = "echo"
            override val description = "Echoes the message."
            override val inputSchema: ObjectNode = mcpSchema {
                stringProp("message", "Text to echo.")
                required("message")
            }
            override fun invoke(args: JsonNode, ctx: McpToolContext): McpToolResult {
                return McpToolResult.ok(mapOf("echo" to args.get("message")?.asText("")))
            }
        })
        dispatcher = McpDispatcher(registry, McpToolContext(McpAttackRunner()))
        // 鉴权强制开启:固定 token。
        org.jjgroup.xproxy.settings.core.McpSettings.setAuthToken(token)
        server = McpServer("127.0.0.1", 0, dispatcher)
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.stop() }
        org.jjgroup.xproxy.settings.core.McpSettings.setAuthToken("")
        org.jjgroup.xproxy.core.Settings.setFlushHookForTests(null)
    }

    private fun post(body: String, auth: Boolean = true): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:${server.boundPort()}/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (auth) builder.header("Authorization", "Bearer $token")
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `request without bearer token is rejected with 401`() {
        val resp = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""", auth = false)
        assertEquals(401, resp.statusCode())
    }

    @Test
    fun `initialize over http with auth succeeds`() {
        val resp = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertEquals(200, resp.statusCode())
        val node = McpJson.parseOrNull(resp.body())!!
        assertEquals("2025-06-18", node.get("result").get("protocolVersion").asText())
        assertEquals("xproxy", node.get("result").get("serverInfo").get("name").asText())
    }

    @Test
    fun `tools list and call work end to end`() {
        val listResp = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        assertEquals(200, listResp.statusCode())
        val tools = McpJson.parseOrNull(listResp.body())!!.get("result").get("tools")
        assertTrue(tools.any { it.get("name").asText() == "echo" })

        val callResp = post("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hello-mcp"}}}""")
        assertEquals(200, callResp.statusCode())
        val result = McpJson.parseOrNull(callResp.body())!!.get("result")
        assertFalse(result.get("isError").asBoolean())
        assertTrue(result.get("content").get(0).get("text").asText().contains("hello-mcp"))
    }

    @Test
    fun `get health endpoint returns server info`() {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:${server.boundPort()}/health"))
            .GET().build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, resp.statusCode())
        val node = McpJson.parseOrNull(resp.body())!!
        assertEquals("xproxy", node.get("server").asText())
        assertEquals(true, node.get("authRequired").asBoolean())
        assertNotNull(node.get("protocolVersion"))
    }
}
