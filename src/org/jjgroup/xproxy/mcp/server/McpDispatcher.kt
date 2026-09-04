package org.jjgroup.xproxy.mcp.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.Info
import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.mcp.tools.McpToolContext
import org.jjgroup.xproxy.mcp.tools.McpToolRegistry

/** 协议版本:2025-06-18(Streamable HTTP)。 */
internal const val MCP_PROTOCOL_VERSION = "2025-06-18"

/** dispatcher 处理一段请求体后产出的 HTTP 响应。body 为 null 表示纯通知(HTTP 202)。 */
data class McpResponse(val body: String?, val httpStatus: Int = 200) {
    companion object {
        val NOTIFICATION: McpResponse = McpResponse(null, 202)
    }
}

/**
 * JSON-RPC 2.0 派发器:解析请求(单条或批量) -> 路由 MCP 方法 -> 产出 JSON-RPC 响应。
 *
 * 设计为同步阻塞执行(由 Netty handler 在 worker 线程上调用),工具内可做 DB 查询/发请求。
 * 通知(method 以 `notifications/` 开头或 id 缺失)不产生 JSON-RPC 响应;若整批均为通知则返回 HTTP 202。
 *
 * 协议错误(parse/invalid-request/method-not-found/invalid-params/internal)用标准 JSON-RPC error 代码;
 * 工具执行失败用 `tools/call` 结果的 `isError:true`(由 [org.jjgroup.xproxy.mcp.tools.McpTool] 决定)。
 */
class McpDispatcher(
    private val registry: McpToolRegistry,
    private val ctx: McpToolContext
) {
    fun handle(requestBody: String): McpResponse {
        val root = McpJson.parseOrNull(requestBody)
            ?: return McpResponse(jsonRpcError(null, PARSE_ERROR, "Parse error"))

        return if (root.isArray) {
            handleBatch(root)
        } else {
            handleSingle(root) ?: McpResponse.NOTIFICATION
        }
    }

    private fun handleBatch(root: JsonNode): McpResponse {
        val responses = mutableListOf<ObjectNode>()
        for (element in root) {
            val response = handleSingle(element)
            if (response != null) responses.add(parseBodyToObject(response.body))
        }
        if (responses.isEmpty()) return McpResponse.NOTIFICATION
        return McpResponse(McpJson.stringify(McpJson.arr().apply { responses.forEach { add(it) } }))
    }

    private fun parseBodyToObject(body: String?): ObjectNode {
        if (body == null) error("notification body parsed as response")
        return McpJson.parseOrNull(body) as? ObjectNode
            ?: error("response body was not a JSON object")
    }

    /** 返回单条请求的 JSON-RPC 响应 JSON;若为通知则返回 null。 */
    private fun handleSingle(request: JsonNode): McpResponse? {
        val id = request.get("id")
        val isNotification = id == null || id.isNull
        val method = request.get("method")?.asText()
        if (method == null) {
            if (isNotification) return null
            return McpResponse(jsonRpcError(id, INVALID_REQUEST, "Missing method"))
        }

        // 通知:不回 JSON-RPC 响应。
        if (isNotification && method.startsWith("notifications/")) {
            return null
        }

        val params = request.get("params") ?: McpJson.obj()
        val result: ObjectNode = try {
            dispatch(method, params)
        } catch (e: McpProtocolException) {
            return McpResponse(jsonRpcError(id, e.code, e.message ?: "Protocol error"))
        } catch (e: Throwable) {
            AppLogger.error("MCP dispatch failed for method=$method", e)
            return McpResponse(jsonRpcError(id, INTERNAL_ERROR, (e.message ?: e.javaClass.simpleName)))
        }
        return McpResponse(jsonRpcSuccess(id, result))
    }

    private fun dispatch(method: String, params: JsonNode): ObjectNode = when (method) {
        "initialize" -> initialize(params)
        "ping" -> McpJson.obj()
        "tools/list" -> listTools()
        "tools/call" -> callTool(params)
        "resources/list" -> emptyResult("resources")
        "resources/read" -> emptyResult("contents")
        "prompts/list" -> emptyResult("prompts")
        "resources/subscribe", "resources/unsubscribe", "notifications/initialized" -> McpJson.obj()
        else -> throw McpProtocolException(METHOD_NOT_FOUND, "Method not found: $method")
    }

    private fun emptyResult(field: String): ObjectNode {
        val out = McpJson.obj()
        out.set<JsonNode>(field, McpJson.arr())
        return out
    }

    private fun initialize(params: JsonNode): ObjectNode {
        val result = McpJson.obj()
        result.put("protocolVersion", MCP_PROTOCOL_VERSION)
        val capabilities = McpJson.obj()
        capabilities.set<JsonNode>("tools", McpJson.obj().put("listChanged", false))
        capabilities.set<JsonNode>("resources", McpJson.obj().put("listChanged", false))
        capabilities.set<JsonNode>("prompts", McpJson.obj().put("listChanged", false))
        result.set<JsonNode>("capabilities", capabilities)
        val serverInfo = McpJson.obj()
        serverInfo.put("name", "xproxy")
        serverInfo.put("version", Info.version)
        result.set<JsonNode>("serverInfo", serverInfo)
        return result
    }

    private fun listTools(): ObjectNode {
        val tools = McpJson.arr()
        registry.all().forEach { tool ->
            val t = McpJson.obj()
            t.put("name", tool.name)
            t.put("description", tool.description)
            t.set<JsonNode>("inputSchema", tool.inputSchema)
            tools.add(t)
        }
        val out = McpJson.obj()
        out.set<JsonNode>("tools", tools)
        return out
    }

    private fun callTool(params: JsonNode): ObjectNode {
        val name = params.get("name")?.asText()
            ?: throw McpProtocolException(INVALID_PARAMS, "Missing tool name")
        val tool = registry.get(name)
            ?: throw McpProtocolException(METHOD_NOT_FOUND, "Unknown tool: $name")
        val arguments = params.get("arguments") ?: McpJson.obj()
        val result = tool.invoke(arguments, ctx)
        val content = McpJson.arr()
        result.content.forEach { piece ->
            content.add(McpJson.obj().put("type", "text").put("text", piece.text))
        }
        val out = McpJson.obj()
        out.set<JsonNode>("content", content)
        out.put("isError", result.isError)
        return out
    }

    private fun jsonRpcSuccess(id: JsonNode?, result: ObjectNode): String {
        val resp = McpJson.obj()
        resp.put("jsonrpc", "2.0")
        if (id != null && !id.isNull) resp.set<JsonNode>("id", id) else resp.putNull("id")
        resp.set<JsonNode>("result", result)
        return McpJson.stringify(resp)
    }

    private fun jsonRpcError(id: JsonNode?, code: Int, message: String): String {
        val resp = McpJson.obj()
        resp.put("jsonrpc", "2.0")
        if (id != null && !id.isNull) resp.set<JsonNode>("id", id) else resp.putNull("id")
        val err = McpJson.obj()
        err.put("code", code)
        err.put("message", message)
        resp.set<JsonNode>("error", err)
        return McpJson.stringify(resp)
    }
}

private class McpProtocolException(val code: Int, message: String) : RuntimeException(message)

internal const val PARSE_ERROR = -32700
internal const val INVALID_REQUEST = -32600
internal const val METHOD_NOT_FOUND = -32601
internal const val INVALID_PARAMS = -32602
internal const val INTERNAL_ERROR = -32603
