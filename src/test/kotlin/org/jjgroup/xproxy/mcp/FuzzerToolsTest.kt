package org.jjgroup.xproxy.mcp

import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.mcp.attack.McpAttackRunner
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.tools.McpToolContext
import org.jjgroup.xproxy.mcp.tools.fuzzerTools
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * fuzzerTools 的参数校验与工具注册测试(纯逻辑,不依赖 UI / 网络)。
 *
 * send_request / run_attack 的可见化路径(sendSingleRequest 发包、经 fuzzer tab 记录、经 UI 弹窗发起攻击)
 * 需要 EDT + 真实网络 / Jython,不在单测覆盖;此处只验证入参校验与未知 attackId 的错误返回。
 */
class FuzzerToolsTest {

    private fun tool(name: String) = fuzzerTools().first { it.name == name }
    private val ctx = McpToolContext(McpAttackRunner())

    // confirm_vuln 默认建 issue 会读写 XproxyAppContext 全局态;每个用例从干净状态开始并清理。
    @BeforeEach
    fun resetAppContext() = XproxyAppContext.clear()

    @AfterEach
    fun clearAppContext() = XproxyAppContext.clear()

    private fun newStore(): ProjectDataStore {
        val dbPath = Files.createTempFile("xproxy-fuzzer-tools", ".db")
        val record = ProjectRecord(
            id = "test",
            displayName = "Test",
            baseName = "test",
            createdDate = "2026-07-19",
            projectDir = dbPath.parent.toString(),
            dbPath = dbPath.toAbsolutePath().toString(),
            createdAtMillis = 0L,
            lastOpenedMillis = 0L
        )
        return ProjectDataStore(record)
    }

    @Test
    fun `fuzzerTools registers nine tools`() {
        val names = fuzzerTools().map { it.name }
        assertEquals(9, names.size)
        assertTrue(names.containsAll(listOf(
            "send_request",
            "confirm_vuln",
            "run_attack",
            "get_attack_status",
            "get_attack_results",
            "get_attack_result_detail",
            "stop_attack",
            "list_attacks",
            "get_stored_attack_results"
        )))
    }

    @Test
    fun `confirm_vuln requires request`() {
        val result = tool("confirm_vuln").invoke(McpJson.obj().put("vulnName", "sqli"), ctx)
        assertTrue(result.isError)
        assertTrue(result.content.first().text.contains("request is required", ignoreCase = true))
    }

    @Test
    fun `confirm_vuln requires vulnName`() {
        val args = McpJson.obj().put("request", "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        val result = tool("confirm_vuln").invoke(args, ctx)
        assertTrue(result.isError)
        assertTrue(result.content.first().text.contains("vulnName is required", ignoreCase = true))
    }

    @Test
    fun `confirm_vuln records a star-prefixed sub-tab`() {
        // 无 UI + 无项目加载:recordMcpVulnBestEffort 静默跳过(recorded=false);createIssue 默认 true 但
        // store 为空 -> best-effort 跳过建 issue(issueCreated=false),不抛错。tabTitle 仍按规范返回。
        val args = McpJson.obj()
            .put("request", "GET /?id=1' HTTP/1.1\r\nHost: example.com\r\n\r\n")
            .put("vulnName", "sqli注入")
        val result = tool("confirm_vuln").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals(false, node.get("recorded").asBoolean())
        assertEquals("* sqli注入", node.get("tabTitle").asText())
        assertEquals(false, node.get("issueCreated").asBoolean())
        assertFalse(node.has("issueId"), "issueId must be absent when no store")
    }

    @Test
    fun `confirm_vuln createIssue false skips issue even with store`() {
        XproxyAppContext.bind(null, newStore(), null, null, null, null)
        val args = McpJson.obj()
            .put("request", "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
            .put("vulnName", "xss")
            .put("createIssue", false)
        val result = tool("confirm_vuln").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals(false, node.get("issueCreated").asBoolean())
        assertFalse(node.has("issueId"))
    }

    @Test
    fun `confirm_vuln creates issue with probe request and response as evidence`() {
        val store = newStore()
        XproxyAppContext.bind(null, store, null, null, null, null)
        val args = McpJson.obj()
            .put("request", "POST /login HTTP/1.1\r\nHost: example.com\r\nContent-Length: 2\r\n\r\n{}")
            .put("vulnName", "auth bypass")
            .put("response", "HTTP/1.1 200 OK\r\n\r\nok")
            .put("severity", "High")
        args.set<com.fasterxml.jackson.databind.JsonNode>("evidence", McpJson.arr().add("HTTP/1.1 200 OK").add("ok"))
        val result = tool("confirm_vuln").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals(true, node.get("issueCreated").asBoolean())
        val issueId = node.get("issueId").asText()

        // 落库且 raw 非空:request 必填故 requestRaw 一定有(杜绝"请求响应都空"的根因)。
        val issues = store.loadReportedIssues()
        assertEquals(1, issues.size)
        val issue = issues.first { it.issueId == issueId }
        assertEquals("auth bypass", issue.name)
        assertEquals("mcp", issue.source)
        assertEquals("High", issue.severity)
        assertEquals("Firm", issue.confidence)
        assertEquals("POST", issue.method)
        assertEquals("example.com", issue.host)
        assertEquals("/login", issue.path)
        assertTrue(issue.requestRaw.startsWith("POST /login"), "requestRaw must hold the probe request")
        assertEquals("HTTP/1.1 200 OK\r\n\r\nok", issue.responseRaw)
        // evidence 数组以 \n join 持久化,响应保持完整原始(不省略)。
        assertEquals("HTTP/1.1 200 OK\nok", issue.evidenceCsv)
    }

    @Test
    fun `confirm_vuln auto-fills response from last send_request when response omitted`() {
        val store = newStore()
        XproxyAppContext.bind(null, store, null, null, null, null)
        val request = "POST /login HTTP/1.1\r\nHost: example.com\r\nContent-Length: 2\r\n\r\n{}"
        val response = "HTTP/1.1 200 OK\r\n\r\nok"
        // 模拟 agent 先 send_request 拿到响应(MCP 线程会 rememberMcpExchange 缓存)。
        XproxyAppContext.rememberMcpExchange(request, response)

        // confirm_vuln 不传 response:应按请求原文回填 send_request 的响应,杜绝 issue 响应为空。
        val args = McpJson.obj()
            .put("request", request)
            .put("vulnName", "auth bypass")
        val result = tool("confirm_vuln").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals(true, node.get("responseAutoFilled").asBoolean(), "response should be auto-filled")
        assertEquals(false, node.get("responseMissing").asBoolean())
        val issueId = node.get("issueId").asText()
        val issue = store.loadReportedIssues().first { it.issueId == issueId }
        assertEquals(response, issue.responseRaw, "auto-filled response must be persisted on the issue")
    }

    @Test
    fun `confirm_vuln accepts responseRaw alias for response`() {
        // send_request 返回字段名为 responseRaw;agent 可能误用该键名传给 confirm_vuln,应被接受。
        val store = newStore()
        XproxyAppContext.bind(null, store, null, null, null, null)
        val args = McpJson.obj()
            .put("request", "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
            .put("vulnName", "xss")
            .put("responseRaw", "HTTP/1.1 200 OK\r\n\r\n<body>")
        val result = tool("confirm_vuln").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals(false, node.get("responseAutoFilled").asBoolean(), "explicit alias should not count as auto-filled")
        assertEquals(false, node.get("responseMissing").asBoolean())
        val issueId = node.get("issueId").asText()
        val issue = store.loadReportedIssues().first { it.issueId == issueId }
        assertEquals("HTTP/1.1 200 OK\r\n\r\n<body>", issue.responseRaw)
    }

    @Test
    fun `confirm_vuln flags missing response when no matching send_request`() {
        val store = newStore()
        XproxyAppContext.bind(null, store, null, null, null, null)
        val args = McpJson.obj()
            .put("request", "GET /never-sent HTTP/1.1\r\nHost: example.com\r\n\r\n")
            .put("vulnName", "xss")
        val result = tool("confirm_vuln").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals(true, node.get("responseMissing").asBoolean(), "responseMissing must be flagged")
        val issueId = node.get("issueId").asText()
        val issue = store.loadReportedIssues().first { it.issueId == issueId }
        assertEquals("", issue.responseRaw)
    }

    @Test
    fun `send_request requires request`() {
        val result = tool("send_request").invoke(McpJson.obj(), ctx)
        assertTrue(result.isError)
        assertTrue(result.content.first().text.contains("request is required", ignoreCase = true))
    }

    @Test
    fun `send_request rejects blank request`() {
        val result = tool("send_request").invoke(McpJson.obj().put("request", "   "), ctx)
        assertTrue(result.isError)
        assertTrue(result.content.first().text.contains("request is required", ignoreCase = true))
    }

    @Test
    fun `run_attack requires rawRequest`() {
        val result = tool("run_attack").invoke(McpJson.obj(), ctx)
        assertTrue(result.isError)
        assertTrue(result.content.first().text.contains("rawRequest is required", ignoreCase = true))
    }

    @Test
    fun `run_attack requires script or scriptKey`() {
        val args = McpJson.obj().put("rawRequest", "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
        val result = tool("run_attack").invoke(args, ctx)
        assertTrue(result.isError)
        assertTrue(result.content.first().text.contains("script", ignoreCase = true))
    }

    @Test
    fun `run_attack without UI returns a helpful non-headless error`() {
        // 无应用 UI 时,可见路径无法发起,应返回错误(而非静默 headless 执行)。
        val args = McpJson.obj()
            .put("rawRequest", "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n")
            .put("script", "def queue_requests(target, wordlists):\n    pass\n")
        val result = tool("run_attack").invoke(args, ctx)
        assertTrue(result.isError)
        val msg = result.content.first().text
        assertTrue(msg.contains("UI", ignoreCase = true), "expected UI-not-ready error, got: $msg")
    }

    @Test
    fun `list_attacks initially empty`() {
        val result = tool("list_attacks").invoke(McpJson.obj(), ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals(0, node.get("attacks").size())
    }

    @Test
    fun `get_attack_status unknown attackId errors`() {
        val args = McpJson.obj().put("attackId", "does-not-exist")
        val result = tool("get_attack_status").invoke(args, ctx)
        assertTrue(result.isError)
    }

    @Test
    fun `stop_attack unknown attackId errors`() {
        val args = McpJson.obj().put("attackId", "does-not-exist")
        val result = tool("stop_attack").invoke(args, ctx)
        assertTrue(result.isError)
    }

    @Test
    fun `get_attack_results unknown attackId errors`() {
        val args = McpJson.obj().put("attackId", "does-not-exist")
        val result = tool("get_attack_results").invoke(args, ctx)
        assertTrue(result.isError)
    }

    @Test
    fun `get_stored_attack_results requires baseRequestHash`() {
        val result = tool("get_stored_attack_results").invoke(McpJson.obj(), ctx)
        assertTrue(result.isError)
        assertTrue(result.content.first().text.contains("baseRequestHash", ignoreCase = true))
    }
}
