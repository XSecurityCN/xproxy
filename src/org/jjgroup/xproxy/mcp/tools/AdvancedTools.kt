package org.jjgroup.xproxy.mcp.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.fuzzer.request.parseHeaders
import org.jjgroup.xproxy.fuzzer.request.splitMessage
import org.jjgroup.xproxy.issue.core.ScriptIssueHub
import org.jjgroup.xproxy.issue.model.ReportedIssue
import org.jjgroup.xproxy.kits.core.scan
import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.server.mcpSchema
import org.jjgroup.xproxy.project.core.ProjectDataStore
import java.util.UUID
import javax.swing.SwingUtilities

/* ============================ 拓展工具:漏洞分析闭环 + 规则/解析 ============================ */

private data class SecretPattern(val type: String, val regex: Regex)

// 敏感信息指纹集(覆盖常见密钥/凭证泄露)。大小写不敏感;按需可在 SecretPattern 列表扩展。
private val SECRET_PATTERNS: List<SecretPattern> = listOf(
    SecretPattern("aws_access_key", Regex("AKIA[0-9A-Z]{16}")),
    SecretPattern("aws_secret", Regex("(?i)aws.{0,20}(?:secret|key).{0,20}['\"]([A-Za-z0-9/+=]{40})['\"]")),
    SecretPattern("google_api_key", Regex("AIza[0-9A-Za-z_\\-]{35}")),
    SecretPattern("stripe_key", Regex("(?:sk|pk)_(?:live|test)_[0-9a-zA-Z]{24}")),
    SecretPattern("private_key", Regex("-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----")),
    SecretPattern("jwt", Regex("eyJ[A-Za-z0-9_\\-]{8,}\\.eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}")),
    SecretPattern("slack_token", Regex("xox[abprs]-[0-9a-zA-Z\\-]{10,48}")),
    SecretPattern("github_token", Regex("gh[pousr]_[A-Za-z0-9]{36,}")),
    SecretPattern("bearer_token", Regex("(?i)bearer\\s+[A-Za-z0-9_\\-\\.=]{20,}")),
    SecretPattern("connection_string", Regex("(?:mongodb|postgres(?:ql)?|mysql|redis|amqp|wss?)://[^\\s\"'<>]+")),
    SecretPattern("generic_secret", Regex("(?i)(?:api[_-]?key|secret|token|password|passwd|pwd|client[_-]?secret|access[_-]?key|auth[_-]?token)[\"'\\s:=]{1,4}([A-Za-z0-9_\\-/+=]{8,})")),
    SecretPattern("password_in_url", Regex("(?i)[?&][^\\s\"'&]*password=[^\\s\"'&]+"))
)

/** 在一段文本中查找敏感信息,返回 (type, match, context) 三元组。 */
private fun scanTextForSecrets(text: String): List<Map<String, Any>> {
    if (text.isBlank()) return emptyList()
    val findings = ArrayList<Map<String, Any>>()
    for (p in SECRET_PATTERNS) {
        for (m in p.regex.findAll(text)) {
            val matched = m.value
            val start = m.range.first
            val ctxStart = (start - 40).coerceAtLeast(0)
            val ctxEnd = (m.range.last + 40).coerceAtMost(text.length)
            findings.add(mapOf(
                "type" to p.type,
                "match" to matched,
                "context" to text.substring(ctxStart, ctxEnd).replace(Regex("\\s+"), " ").trim()
            ))
        }
    }
    return findings
}

internal class FindSecretsTool : BaseTool() {
    override val name = "find_secrets"
    override val description = "Scan for leaked secrets/credentials (AWS keys, private keys, JWT, API keys, tokens, connection strings, etc.) in captured traffic. Pass historyId to scan one entry, text to scan arbitrary text, or omit both to scan all captured history (capped). Returns type/match/context per finding."
    override val inputSchema: ObjectNode = mcpSchema {
        intProp("historyId", "Proxy history entry id to scan (request+response).")
        stringProp("text", "Arbitrary text to scan.")
        intProp("maxFindings", "Max findings when scanning all history (default 200).", default = 200)
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val historyId = args.intOpt("historyId")?.toLong()
        val text = args.strOpt("text")
        val maxFindings = args.intOr("maxFindings", 200).coerceIn(1, 2000)
        val findings = ArrayList<Map<String, Any?>>()

        when {
            historyId != null -> {
                val store = XproxyAppContext.requireDataStore()
                val entry = store.loadHistoryById(historyId)
                    ?: return McpToolResult.error("History entry not found: $historyId")
                scanTextForSecrets(entry.requestRaw).forEach { f -> findings.add(f + ("entryId" to historyId) + ("location" to "request")) }
                scanTextForSecrets(entry.responseRaw).forEach { f -> findings.add(f + ("entryId" to historyId) + ("location" to "response")) }
            }
            text != null -> {
                // 纯文本扫描,不依赖项目加载。
                scanTextForSecrets(text).forEach { f -> findings.add(f + ("entryId" to null) + ("location" to "text")) }
            }
            else -> {
                val store = XproxyAppContext.requireDataStore()
                // 流式扫全表 raw,O(1) 内存;命中即收集,达上限提前终止。
                var truncated = false
                store.scanHistoryDetails { id, _, _, requestRaw, responseRaw ->
                    scanTextForSecrets(requestRaw).forEach { f -> findings.add(f + ("entryId" to id) + ("location" to "request")) }
                    scanTextForSecrets(responseRaw).forEach { f -> findings.add(f + ("entryId" to id) + ("location" to "response")) }
                    if (findings.size >= maxFindings) { truncated = true; false } else true
                }
                return McpToolResult.ok(mapOf("findings" to findings.take(maxFindings), "total" to findings.size, "truncated" to truncated))
            }
        }
        return McpToolResult.ok(mapOf("findings" to findings, "total" to findings.size, "truncated" to false))
    }
}

internal class ParseHttpMessageTool : BaseTool() {
    override val name = "parse_http_message"
    override val description = "Parse a raw HTTP request or response into structured fields: method/status, path, httpVersion, headers (map), body. Auto-detects request vs response by the start line."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("raw", "Raw HTTP message text (request line/status line + headers + body).")
        required("raw")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val raw = args.strOpt("raw") ?: return McpToolResult.error("raw is required.")
        val parsed = splitMessage(raw)
        val headerLines = parsed.headers.split("\r\n", "\n")
        val startLine = headerLines.firstOrNull().orEmpty()
        val headers = parseHeaders(parsed.headers)
        val isResponse = startLine.startsWith("HTTP/", ignoreCase = true)
        val result = LinkedHashMap<String, Any?>()
        result["kind"] = if (isResponse) "response" else "request"
        if (isResponse) {
            val parts = startLine.split(' ', limit = 3)
            result["httpVersion"] = parts.getOrNull(0) ?: ""
            result["statusCode"] = parts.getOrNull(1)?.toIntOrNull()
            result["reason"] = parts.getOrNull(2) ?: ""
        } else {
            val parts = startLine.split(' ', limit = 3)
            result["method"] = parts.getOrNull(0) ?: ""
            result["path"] = parts.getOrNull(1) ?: ""
            result["httpVersion"] = parts.getOrNull(2) ?: ""
        }
        result["headers"] = headers
        result["body"] = parsed.body
        return McpToolResult.ok(result)
    }
}

/**
 * 把 agent 上报的 issue 落库 + 经 [ScriptIssueHub] 通知 UI 订阅者(ingestReportedIssue + 再落库,幂等)。
 * 供 add_issue / confirm_vuln 复用,保证两条上报路径的落库 + 通知语义一致。best-effort:落库失败仅告警,
 * 不抛出(避免单条 issue 落库失败影响 agent 已构造的结果返回)。
 */
internal fun persistMcpIssue(issue: ReportedIssue, store: ProjectDataStore) {
    runCatching { store.saveReportedIssue(issue) }
        .onFailure { AppLogger.warn("MCP: saveReportedIssue failed", it) }
    ScriptIssueHub.publish(issue)
}

internal class AddIssueTool : BaseTool() {
    override val name = "add_issue"
    override val description = "Report a security issue (finding) that shows in the Target panel and persists. REQUIRES request evidence: pass requestRaw (the FULL original, no '...' abbreviations) or historyId (auto-fills from captured traffic). For findings you confirmed with a probe, prefer confirm_vuln -- it auto-creates an issue and attaches the probe request+response. Pass evidence to highlight key parts of the response in place (keeps the response complete). source is set to 'mcp'."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("name", "Short issue title.")
        stringProp("severity", "High/Medium/Low/Information.", enum = listOf("High", "Medium", "Low", "Information"), default = "Information")
        stringProp("confidence", "Firm/Tentative.", enum = listOf("Firm", "Tentative"), default = "Tentative")
        stringProp("detail", "Detailed description of the finding.")
        stringProp("remediation", "Suggested fix.")
        stringProp("url", "Affected URL.")
        stringProp("host", "Affected host.")
        stringProp("path", "Affected path.")
        stringProp("method", "HTTP method.")
        stringProp("requestRaw", "Raw request evidence. REQUIRED unless historyId is given. Pass the FULL original - do NOT abbreviate with '...'.")
        stringProp("responseRaw", "Raw response evidence. Keep the FULL original - do NOT abbreviate; pass evidence to mark key parts.")
        intProp("historyId", "Proxy history entry id (from search_proxy_history / get_proxy_history_entry). When requestRaw is omitted, auto-fills raw + host/path/method/url from this captured entry. Use for findings discovered by inspecting captured traffic.")
        arrayProp("evidence", "string", "Key evidence snippets to highlight in the response. Pass the EXACT substrings copied from the full response (not summaries, not '...'). Highlighted in place in the Target detail / Fuzzer sub-tab.")
        stringProp("tags", "Comma-separated tags.")
        required("name")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val name = args.str("name").takeIf { it.isNotBlank() } ?: return McpToolResult.error("name is required.")

        // 显式传入的字段优先;缺省时若给了 historyId,从代理历史回填 raw + 元数据(覆盖"看代理历史发现漏洞"场景)。
        var requestRaw = args.str("requestRaw")
        var responseRaw = args.str("responseRaw")
        var host = args.str("host")
        var path = args.str("path")
        var method = args.str("method")
        var url = args.str("url")
        val historyId = args.intOpt("historyId")?.toLong()
        if (historyId != null) {
            val entry = store.loadHistoryById(historyId)
                ?: return McpToolResult.error("historyId not found: $historyId")
            if (requestRaw.isEmpty()) requestRaw = entry.requestRaw
            if (responseRaw.isEmpty()) responseRaw = entry.responseRaw
            if (host.isEmpty()) host = entry.host
            if (path.isEmpty()) path = entry.path
            if (method.isEmpty()) method = entry.method
            if (url.isEmpty()) {
                val scheme = if (entry.tls) "https" else "http"
                url = "$scheme://${entry.host}${entry.path}"
            }
        }

        // 强制证据:无 request_raw 即拒绝(杜绝空证据 issue)。分析型 finding 应从代理历史找对应请求传 historyId;
        // 探测型 finding 用 confirm_vuln(自动带 request+response)。
        if (requestRaw.isEmpty()) {
            return McpToolResult.error(
                "add_issue requires request evidence: pass requestRaw, or pass historyId " +
                    "(from search_proxy_history/get_proxy_history_entry) to auto-fill from captured traffic. " +
                    "For probe-confirmed findings use confirm_vuln instead."
            )
        }

        val evidence = args.strList("evidence")
        val issue = ReportedIssue(
            issueId = UUID.randomUUID().toString(),
            source = "mcp",
            name = name,
            severity = args.str("severity", "Information"),
            confidence = args.str("confidence", "Tentative"),
            detail = args.str("detail"),
            remediation = args.str("remediation"),
            url = url,
            host = host,
            path = path,
            method = method,
            requestRaw = requestRaw,
            responseRaw = responseRaw,
            tagsCsv = args.str("tags"),
            evidenceCsv = evidence.joinToString("\n"),
            createdAtMillis = System.currentTimeMillis()
        )
        persistMcpIssue(issue, store)

        val result = LinkedHashMap<String, Any?>()
        result["issueId"] = issue.issueId
        result["source"] = issue.source
        result["name"] = issue.name
        result["reported"] = true
        return McpToolResult.ok(result)
    }
}

internal class DeleteIssueTool : BaseTool() {
    override val name = "delete_issue"
    override val description = "Delete a reported issue by issueId (removes from DB and the live Target panel)."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("issueId", "Issue id (UUID).")
        required("issueId")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val issueId = args.str("issueId").takeIf { it.isNotBlank() } ?: return McpToolResult.error("issueId is required.")
        store.deleteReportedIssue(issueId)
        // 从 Target 详情面板的内存映射移除(须 EDT)。
        val detailPanel = runCatching { XproxyAppContext.targetPanel()?.detailPanel() }.getOrNull()
        if (detailPanel != null) {
            SwingUtilities.invokeLater { runCatching { detailPanel.reportedIssuesById.remove(issueId) } }
        }
        return McpToolResult.ok(mapOf("issueId" to issueId, "deleted" to true))
    }
}

internal class RunXappScanTool : BaseTool() {
    override val name = "run_xapp_scan"
    override val description = "Run enabled xapp plugins' passive scan against a specific captured history entry (on_proxy_http_message / on_http_message). Issues reported by plugins flow into list_issues. Scan is asynchronous on a background executor."
    override val inputSchema: ObjectNode = mcpSchema {
        intProp("historyId", "Proxy history entry id to scan.")
        required("historyId")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val id = args.intOpt("historyId")?.toLong()
            ?: return McpToolResult.error("historyId is required.")
        val entry = store.loadHistoryById(id)
            ?: return McpToolResult.error("History entry not found: $id")
        val mgr = XproxyAppContext.requireKitsPanel().xappManager
        val enabled = mgr.plugins.count { it.enabled }
        if (enabled == 0) return McpToolResult.error("No enabled xapp plugins. Enable plugins via set_xapp_enabled first.")
        // scan 异步在 scanExecutor 上跑;上报的 issue 经 ScriptIssueHub -> list_issues 可查。
        runCatching { mgr.scan(entry) }
            .onFailure { return McpToolResult.error("scan failed: ${it.message ?: it.javaClass.simpleName}") }
        return McpToolResult.ok(mapOf("entryId" to id, "enabledPlugins" to enabled, "submitted" to true,
            "note" to "Scan runs async; poll list_issues for newly reported findings."))
    }
}

internal class GetProxyRulesTool : BaseTool() {
    override val name = "get_proxy_rules"
    override val description = "Read the proxy match/replace rules and intercept rules currently in effect."
    override val inputSchema: ObjectNode = mcpSchema {}

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val matchReplace = store.loadProxyMatchReplaceRules().map {
            mapOf(
                "ruleId" to it.ruleId, "enabled" to it.enabled, "name" to it.name,
                "scope" to it.scope.name, "mode" to it.mode.name, "action" to it.action.name,
                "matchText" to it.matchText, "replaceText" to it.replaceText
            )
        }
        val intercept = store.loadProxyInterceptRules().map {
            mapOf(
                "ruleId" to it.ruleId, "enabled" to it.enabled, "name" to it.name,
                "mode" to it.mode.name, "matchText" to it.matchText, "action" to it.action.name,
                "matchRequestHeader" to it.matchRequestHeader, "matchRequestBody" to it.matchRequestBody,
                "matchResponseHeader" to it.matchResponseHeader, "matchResponseBody" to it.matchResponseBody
            )
        }
        return McpToolResult.ok(mapOf("matchReplaceRules" to matchReplace, "interceptRules" to intercept))
    }
}

/** 拓展工具集:漏洞分析闭环 + HTTP 解析 + 代理规则。 */
fun advancedTools(): List<McpTool> = listOf(
    FindSecretsTool(),
    ParseHttpMessageTool(),
    AddIssueTool(),
    DeleteIssueTool(),
    RunXappScanTool(),
    GetProxyRulesTool()
)
