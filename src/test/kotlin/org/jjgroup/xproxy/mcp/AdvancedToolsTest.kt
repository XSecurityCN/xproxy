package org.jjgroup.xproxy.mcp

import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.mcp.attack.McpAttackRunner
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.tools.McpToolContext
import org.jjgroup.xproxy.mcp.tools.advancedTools
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectRecord
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files

class AdvancedToolsTest {

    private fun tool(name: String) = advancedTools().first { it.name == name }
    private val ctx = McpToolContext(McpAttackRunner())

    // add_issue 读写 XproxyAppContext 全局态(requireDataStore);每个用例从干净状态开始并清理。
    @BeforeEach
    fun resetAppContext() = XproxyAppContext.clear()

    @AfterEach
    fun clearAppContext() = XproxyAppContext.clear()

    private fun newStore(): ProjectDataStore {
        val dbPath = Files.createTempFile("xproxy-advanced-tools", ".db")
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
    fun `advancedTools registers six tools`() {
        val names = advancedTools().map { it.name }
        assertEquals(6, names.size)
        assertTrue(names.containsAll(listOf(
            "find_secrets", "parse_http_message", "add_issue", "delete_issue", "run_xapp_scan", "get_proxy_rules"
        )))
    }

    @Test
    fun `parse_http_message parses a raw request`() {
        val raw = "POST /api/login HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"
        val args = McpJson.obj().put("raw", raw)
        val result = tool("parse_http_message").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals("request", node.get("kind").asText())
        assertEquals("POST", node.get("method").asText())
        assertEquals("/api/login", node.get("path").asText())
        assertEquals("example.com", node.get("headers").get("host").asText())
        assertEquals("{}", node.get("body").asText())
    }

    @Test
    fun `parse_http_message parses a raw response`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html>hi</html>"
        val args = McpJson.obj().put("raw", raw)
        val result = tool("parse_http_message").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals("response", node.get("kind").asText())
        assertEquals(200, node.get("statusCode").asInt())
        assertEquals("OK", node.get("reason").asText())
        assertEquals("text/html", node.get("headers").get("content-type").asText())
        assertEquals("<html>hi</html>", node.get("body").asText())
    }

    @Test
    fun `find_secrets detects common credential patterns in text`() {
        val text = """
            config: aws_key=AKIAIOSFODNN7EXAMPLE and token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.SflKxwRJSMeKKF2QT4f
            db: mongodb://admin:s3cr3t@db.host:27017/auth
            key: -----BEGIN RSA PRIVATE KEY-----
            MIIE...
            leak: api_key=sk_live_abcdefghijklmnopqrstuvwxyz1234567890
        """.trimIndent()
        val args = McpJson.obj().put("text", text)
        val result = tool("find_secrets").invoke(args, ctx)
        assertFalse(result.isError, "text mode must not require a loaded project")
        val node = McpJson.parseOrNull(result.content.first().text)!!
        val types = node.get("findings").map { it.get("type").asText() }
        assertTrue(types.contains("aws_access_key"), "expected aws_access_key in $types")
        assertTrue(types.contains("jwt"), "expected jwt in $types")
        assertTrue(types.contains("connection_string"), "expected connection_string in $types")
        assertTrue(types.contains("private_key"), "expected private_key in $types")
        assertTrue(node.get("total").asInt() >= 4)
    }

    @Test
    fun `find_secrets returns empty for clean text`() {
        val args = McpJson.obj().put("text", "just some boring log line with no secrets here")
        val result = tool("find_secrets").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals(0, node.get("total").asInt())
    }

    @Test
    fun `add_issue rejects when no raw evidence and no historyId`() {
        XproxyAppContext.bind(null, newStore(), null, null, null, null)
        val args = McpJson.obj().put("name", "analysis-only finding")
        val result = tool("add_issue").invoke(args, ctx)
        assertTrue(result.isError, "add_issue must reject without requestRaw or historyId (no empty-evidence issues)")
        assertTrue(result.content.first().text.contains("requestRaw", ignoreCase = true) ||
            result.content.first().text.contains("historyId", ignoreCase = true))
    }

    @Test
    fun `add_issue historyId not found errors`() {
        XproxyAppContext.bind(null, newStore(), null, null, null, null)
        val args = McpJson.obj().put("name", "x").put("historyId", 999)
        val result = tool("add_issue").invoke(args, ctx)
        assertTrue(result.isError)
        assertTrue(result.content.first().text.contains("historyId not found", ignoreCase = true))
    }

    @Test
    fun `add_issue historyId backfills raw and metadata`() {
        val store = newStore()
        XproxyAppContext.bind(null, store, null, null, null, null)
        store.saveHistory(
            ProxyHistoryEntry(
                id = 1L, timeMillis = 1L, method = "GET", host = "example.com", path = "/api",
                statusCode = 200, length = 2, mimeType = "text/html", title = "", tls = true, modified = false,
                requestRaw = "GET /api HTTP/1.1\r\nHost: example.com\r\n\r\n",
                responseRaw = "HTTP/1.1 200 OK\r\n\r\nok"
            )
        )
        val args = McpJson.obj().put("name", "version leak").put("historyId", 1)
        args.set<com.fasterxml.jackson.databind.JsonNode>("evidence", McpJson.arr().add("HTTP/1.1 200 OK").add("ok"))
        val result = tool("add_issue").invoke(args, ctx)
        assertFalse(result.isError)
        val node = McpJson.parseOrNull(result.content.first().text)!!
        assertEquals(true, node.get("reported").asBoolean())

        val issues = store.loadReportedIssues()
        assertEquals(1, issues.size)
        val issue = issues.first()
        assertEquals("GET", issue.method)
        assertEquals("example.com", issue.host)
        assertEquals("/api", issue.path)
        assertEquals("https://example.com/api", issue.url)
        assertEquals("GET /api HTTP/1.1\r\nHost: example.com\r\n\r\n", issue.requestRaw)
        assertEquals("HTTP/1.1 200 OK\r\n\r\nok", issue.responseRaw)
        // evidence 数组以 \n join 持久化。
        assertEquals("HTTP/1.1 200 OK\nok", issue.evidenceCsv)
    }
}
