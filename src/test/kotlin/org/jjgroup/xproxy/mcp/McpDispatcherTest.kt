package org.jjgroup.xproxy.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.mcp.attack.McpAttackRunner
import org.jjgroup.xproxy.mcp.server.McpDispatcher
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.server.McpResponse
import org.jjgroup.xproxy.mcp.server.mcpSchema
import org.jjgroup.xproxy.mcp.tools.McpTool
import org.jjgroup.xproxy.mcp.tools.McpToolContext
import org.jjgroup.xproxy.mcp.tools.McpToolRegistry
import org.jjgroup.xproxy.mcp.tools.McpToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpDispatcherTest {
    private fun newDispatcher(): Pair<McpDispatcher, McpToolRegistry> {
        val registry = McpToolRegistry()
        registry.register(object : McpTool {
            override val name = "echo"
            override val description = "Echoes the message argument."
            override val inputSchema: ObjectNode = mcpSchema {
                stringProp("message", "Text to echo.")
                required("message")
            }
            override fun invoke(args: JsonNode, ctx: McpToolContext): McpToolResult {
                val msg = args.get("message")?.asText() ?: ""
                return McpToolResult.ok(mapOf("echo" to msg))
            }
        })
        val ctx = McpToolContext(McpAttackRunner())
        return McpDispatcher(registry, ctx) to registry
    }

    private fun resultJson(body: String?): JsonNode {
        assertNotNull(body)
        return McpJson.parseOrNull(body!!)!!
    }

    @Test
    fun `initialize returns protocol version, capabilities and server info`() {
        val (dispatcher, _) = newDispatcher()
        val req = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
        val resp = dispatcher.handle(req)
        val node = resultJson(resp.body)
        assertEquals("2.0", node.get("jsonrpc").asText())
        assertEquals(1, node.get("id").asInt())
        assertEquals("2025-06-18", node.get("result").get("protocolVersion").asText())
        assertNotNull(node.get("result").get("capabilities").get("tools"))
        assertEquals("xproxy", node.get("result").get("serverInfo").get("name").asText())
    }

    @Test
    fun `tools list includes registered tool with input schema`() {
        val (dispatcher, registry) = newDispatcher()
        val resp = dispatcher.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val tools = resultJson(resp.body).get("result").get("tools")
        assertTrue(tools.size() >= 1)
        val echo = tools.firstOrNull { it.get("name").asText() == "echo" }
        assertNotNull(echo)
        assertEquals("object", echo!!.get("inputSchema").get("type").asText())
        assertEquals(registry.all().size, tools.size())
    }

    @Test
    fun `tools call executes tool and returns content`() {
        val (dispatcher, _) = newDispatcher()
        val resp = dispatcher.handle("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"message":"hi"}}}""")
        val result = resultJson(resp.body).get("result")
        assertFalse(result.get("isError").asBoolean())
        val text = result.get("content").get(0).get("text").asText()
        assertTrue(text.contains("\"echo\":\"hi\""))
    }

    @Test
    fun `tools call unknown tool yields method-not-found error`() {
        val (dispatcher, _) = newDispatcher()
        val resp = dispatcher.handle("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope","arguments":{}}}""")
        val node = resultJson(resp.body)
        assertEquals(-32601, node.get("error").get("code").asInt())
    }

    @Test
    fun `unknown method yields method-not-found`() {
        val (dispatcher, _) = newDispatcher()
        val resp = dispatcher.handle("""{"jsonrpc":"2.0","id":5,"method":"does/not/exist"}""")
        assertEquals(-32601, resultJson(resp.body).get("error").get("code").asInt())
    }

    @Test
    fun `parse error yields -32700 with null id`() {
        val (dispatcher, _) = newDispatcher()
        val resp = dispatcher.handle("not json")
        val node = resultJson(resp.body)
        assertEquals(-32700, node.get("error").get("code").asInt())
        assertTrue(node.get("id").isNull)
    }

    @Test
    fun `initialized notification produces no json-rpc body`() {
        val (dispatcher, _) = newDispatcher()
        val resp = dispatcher.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(null, resp.body)
        assertEquals(202, resp.httpStatus)
    }

    @Test
    fun `ping returns empty result`() {
        val (dispatcher, _) = newDispatcher()
        val resp = dispatcher.handle("""{"jsonrpc":"2.0","id":9,"method":"ping"}""")
        val node = resultJson(resp.body)
        assertNotNull(node.get("result"))
        assertTrue(node.get("result").isObject)
    }

    @Test
    fun `batch returns one response per request and skips notifications`() {
        val (dispatcher, _) = newDispatcher()
        val batch = """[
            {"jsonrpc":"2.0","id":1,"method":"ping"},
            {"jsonrpc":"2.0","method":"notifications/initialized"},
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"echo","arguments":{"message":"x"}}}
        ]"""
        val resp = dispatcher.handle(batch)
        val arr = McpJson.parseOrNull(resp.body!!)!!
        assertEquals(2, arr.size()) // notification skipped
        assertEquals(1, arr.get(0).get("id").asInt())
        assertEquals(2, arr.get(1).get("id").asInt())
    }

    @Test
    fun `McpResponse NOTIFICATION is 202 with null body`() {
        assertEquals(null, McpResponse.NOTIFICATION.body)
        assertEquals(202, McpResponse.NOTIFICATION.httpStatus)
    }
}
