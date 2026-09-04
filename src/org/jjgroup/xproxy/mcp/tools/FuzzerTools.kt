package org.jjgroup.xproxy.mcp.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.request.sendSingleRequest
import org.jjgroup.xproxy.fuzzer.ui.recordMcpExchange
import org.jjgroup.xproxy.fuzzer.ui.recordMcpVuln
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.issue.model.ReportedIssue
import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.mcp.attack.McpAttackRunner
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.server.mcpSchema
import java.nio.file.Files
import java.util.UUID
import javax.swing.SwingUtilities

/* ============================ Area 3: Intruder / Fuzzer 工具 ============================ */

internal class SendRequestTool : BaseTool() {
    override val name = "send_request"
    override val description = "Send a single raw HTTP request (repeater-style) and return the raw response. Honors the configured upstream proxy. Use to verify a finding or probe an endpoint. The sent request+response is also recorded in a per-host probe tab in the Fuzzer module (grouped by target host, with back/forward history), so a human can later review each probe like a manual repeater send. To report this probe as a confirmed vuln, call confirm_vuln with this request and the returned responseRaw -- it creates a Target issue with the request+response attached as evidence."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("request", "Full raw HTTP request (request line + headers + body). {{placeholders}} are sent literally.")
        stringProp("protocol", "Force scheme: http or https. If omitted, inferred from Host/port (443->https).")
        required("request")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val raw = args.strOpt("request") ?: return McpToolResult.error("request is required.")
        val protocolOverride = args.strOpt("protocol")
        val parsed = org.jjgroup.xproxy.mcp.attack.RawRequestParser.parse(raw, protocolOverride)
        val startedAt = System.nanoTime()
        val response = try {
            sendSingleRequest(parsed.service, raw)
        } catch (e: Throwable) {
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            recordMcpExchangeBestEffort(
                raw, "", parsed.service,
                I18n.t("fuzzer.send_error", "error" to (e.message ?: e.javaClass.simpleName)),
                0, elapsedMillis
            )
            return McpToolResult.error("Request failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        val statusLine = response.substringBefore("\r\n").substringBefore("\n")
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        val responseBytes = response.toByteArray(Charsets.ISO_8859_1).size
        val uiAvailable = XproxyAppContext.intruderUiContext() != null
        recordMcpExchangeBestEffort(raw, response, parsed.service, I18n.t("fuzzer.done"), responseBytes, elapsedMillis)
        return McpToolResult.ok(mapOf(
            "statusCode" to statusCode,
            "statusLine" to statusLine,
            "responseRaw" to response,
            "target" to mapOf("host" to parsed.host, "port" to parsed.port, "protocol" to parsed.protocol),
            "recordedInFuzzerTab" to uiAvailable
        ))
    }
}

/**
 * 把一次 send_request 的交换 best-effort 记录进 fuzzer 的 "MCP" tab(EDT 上异步执行)。
 * UI 未就绪或记录失败不影响 agent 已拿到的响应。
 */
private fun recordMcpExchangeBestEffort(
    requestRaw: String,
    responseText: String,
    target: HttpService,
    statusText: String,
    responseBytes: Int,
    elapsedMillis: Long
) {
    // 先于 UI 记录缓存(MCP 线程,线程安全):confirm_vuln 确认同一条请求时按原文回填响应,
    // 杜绝 agent 漏传 response 导致 issue 响应为空。即使 UI 未就绪也保留,供后续 confirm_vuln 回填。
    XproxyAppContext.rememberMcpExchange(requestRaw, responseText)
    val ui = XproxyAppContext.intruderUiContext() ?: return
    SwingUtilities.invokeLater {
        runCatching { ui.recordMcpExchange(requestRaw, responseText, target, statusText, responseBytes, elapsedMillis) }
            .onFailure { AppLogger.warn("MCP send_request: failed to record in fuzzer tab", it) }
    }
}

internal class ConfirmVulnTool : BaseTool() {
    override val name = "confirm_vuln"
    override val description = "Record a request that confirmed a vulnerability: creates a dedicated sub-tab in the Fuzzer module (titled '* <vuln_name>', grouped under the target host) AND by default also creates a Target issue with the probe request+response attached as evidence. Call this after send_request (or a manual probe) confirms a vuln. The sub-tab loads the confirming request+response for human review and is re-sendable like any repeater tab. Pass createIssue=false to only record the sub-tab and skip the Target issue."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("request", "The raw HTTP request that confirmed the vulnerability (request line + headers + body).")
        stringProp("vulnName", "Vulnerability name for the sub-tab title and default issue name, e.g. 'sqli注入' or 'xss'. The tab is titled '* <vulnName>'.")
        stringProp("response", "The FULL raw HTTP response that confirmed the vulnerability. Do NOT abbreviate or omit any part with '...' - keep the complete original. Becomes the issue's responseRaw and is shown verbatim in the Fuzzer sub-tab / Target detail. Pass evidence to mark key parts instead of abbreviating. If omitted, auto-filled from the most recent send_request exchange for the same request (so you can omit it after a send_request).")
        stringProp("protocol", "Force scheme: http or https. If omitted, inferred from Host/port.")
        boolProp("createIssue", "Also create a Target issue with this request+response as evidence (default true). Set false to only record the sub-tab.", default = true)
        arrayProp("evidence", "string", "Key evidence snippets to highlight in the response (e.g. the leaked secret line, the reflected payload). Pass the EXACT substrings copied from the full response - they will be highlighted in place, keeping the response complete. Do NOT use '...' abbreviations in evidence either.")
        stringProp("name", "Issue title. Defaults to vulnName.")
        stringProp("severity", "Issue severity.", enum = listOf("High", "Medium", "Low", "Information"), default = "Information")
        stringProp("confidence", "Issue confidence.", enum = listOf("Firm", "Tentative"), default = "Firm")
        stringProp("detail", "Detailed description of the finding.")
        stringProp("remediation", "Suggested fix.")
        stringProp("tags", "Comma-separated tags.")
        required("request", "vulnName")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val requestRaw = args.strOpt("request") ?: return McpToolResult.error("request is required.")
        val vulnName = args.strOpt("vulnName") ?: return McpToolResult.error("vulnName is required.")
        // response 优先显式传入;接受 responseRaw 别名(send_request 返回字段名为 responseRaw,防 agent 误用键名)。
        var responseText = args.strOpt("response") ?: args.strOpt("responseRaw") ?: ""
        // agent 漏传响应时,按请求原文回填最近一次 send_request 的响应,杜绝"确认漏洞后 issue 响应为空"。
        var responseAutoFilled = false
        if (responseText.isBlank()) {
            val filled = XproxyAppContext.lookupMcpExchange(requestRaw)
            if (!filled.isNullOrBlank()) {
                responseText = filled
                responseAutoFilled = true
            }
        }
        val responseMissing = responseText.isBlank()
        if (responseMissing) {
            AppLogger.warn("MCP confirm_vuln: response is empty (not passed and no matching send_request exchange found); issue will be created with an empty response. vulnName=$vulnName")
        }
        val protocolOverride = args.strOpt("protocol")
        val parsed = org.jjgroup.xproxy.mcp.attack.RawRequestParser.parse(requestRaw, protocolOverride)
        val responseBytes = responseText.toByteArray(Charsets.ISO_8859_1).size
        val statusText = responseText.lineSequence().firstOrNull()?.takeIf { it.startsWith("HTTP/", true) }
            ?: I18n.t("fuzzer.done")
        val uiAvailable = XproxyAppContext.intruderUiContext() != null
        // evidence:response 里要高亮的关键证据片段(原始子串,不要省略)。透传给 fuzzer 子 tab + issue。
        val evidence = args.strList("evidence")
        recordMcpVulnBestEffort(requestRaw, responseText, parsed.service, vulnName, statusText, responseBytes, 0L, evidence)

        // 默认同时建 Target issue,把确认请求 + 响应作为证据(避免 agent 再调 add_issue 重传 raw,杜绝空证据 issue)。
        // best-effort:项目未加载(store 为空)时跳过建 issue,不影响子 tab 记录与结果返回。
        val createIssue = args.boolOr("createIssue", true)
        var issueId: String? = null
        var issueCreated = false
        if (createIssue) {
            val store = XproxyAppContext.projectDataStore()
            if (store != null) {
                val name = args.str("name").ifBlank { vulnName }
                val portSuffix = when {
                    parsed.protocol == "https" && parsed.port == 443 -> ""
                    parsed.protocol == "http" && parsed.port == 80 -> ""
                    else -> ":${parsed.port}"
                }
                val url = "${parsed.protocol}://${parsed.host}$portSuffix${parsed.path}"
                val issue = ReportedIssue(
                    issueId = UUID.randomUUID().toString(),
                    source = "mcp",
                    name = name,
                    severity = args.str("severity", "Information"),
                    confidence = args.str("confidence", "Firm"),
                    detail = args.str("detail"),
                    remediation = args.str("remediation"),
                    url = url,
                    host = parsed.host,
                    path = parsed.path,
                    method = parsed.method,
                    requestRaw = requestRaw,
                    responseRaw = responseText,
                    tagsCsv = args.str("tags"),
                    evidenceCsv = evidence.joinToString("\n"),
                    createdAtMillis = System.currentTimeMillis()
                )
                persistMcpIssue(issue, store)
                issueId = issue.issueId
                issueCreated = true
            }
        }

        val result = LinkedHashMap<String, Any?>()
        result["recorded"] = uiAvailable
        result["tabTitle"] = "* ${vulnName.trim()}"
        result["target"] = mapOf("host" to parsed.host, "port" to parsed.port, "protocol" to parsed.protocol)
        result["issueCreated"] = issueCreated
        if (issueId != null) result["issueId"] = issueId
        result["responseAutoFilled"] = responseAutoFilled
        result["responseMissing"] = responseMissing
        return McpToolResult.ok(result)
    }
}

/**
 * 为确认漏洞的请求 best-effort 建一个 `* <漏洞名>` 子 tab(EDT 上异步执行)。
 * UI 未就绪或创建失败不影响 agent 的确认结果返回。
 */
private fun recordMcpVulnBestEffort(
    requestRaw: String,
    responseText: String,
    target: HttpService,
    vulnName: String,
    statusText: String,
    responseBytes: Int,
    elapsedMillis: Long,
    evidence: List<String> = emptyList()
) {
    val ui = XproxyAppContext.intruderUiContext() ?: return
    SwingUtilities.invokeLater {
        runCatching { ui.recordMcpVuln(requestRaw, responseText, target, vulnName, statusText, responseBytes, elapsedMillis, evidence) }
            .onFailure { AppLogger.warn("MCP confirm_vuln: failed to create vuln sub-tab", it) }
    }
}

internal class RunAttackTool : BaseTool() {
    override val name = "run_attack"
    override val description = "Launch a visible intruder/attack (Jython) in the Fuzzer module: a results window pops up showing live attack rows, exactly like clicking Attack manually (non-headless). The base request template is placed in a new Fuzzer tab and the script is loaded into the shared script editor so a human can follow what the agent is running. Provide an inline script OR a script_key of an existing attack script. The raw_request template uses {{placeholder}} markers. Returns an attackId immediately; poll with get_attack_status / get_attack_results."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("rawRequest", "Base request template with {{placeholder}} markers.")
        stringProp("script", "Inline attack script source (queue_requests/handle_response contract).")
        stringProp("scriptKey", "Key of an existing attack script to load (alternative to script).")
        stringProp("baseInput", "Original text replaced by the placeholder (passed to target.base_input).")
        stringProp("protocol", "Force scheme: http or https (else inferred).")
        required("rawRequest")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val rawRequest = args.strOpt("rawRequest") ?: return McpToolResult.error("rawRequest is required.")
        val inlineScript = args.strOpt("script")
        val scriptKey = args.strOpt("scriptKey")
        if (inlineScript.isNullOrBlank() && scriptKey.isNullOrBlank()) {
            return McpToolResult.error("Provide either 'script' (inline source) or 'scriptKey' (existing script).")
        }
        val scriptCode = inlineScript?.takeIf { it.isNotBlank() } ?: run {
            val mgr = XproxyAppContext.requireKitsPanel().intruderScriptManager
            val s = mgr.loadScripts().firstOrNull { it.key == scriptKey }
                ?: return McpToolResult.error("attack script not found: $scriptKey")
            runCatching { Files.readString(s.scriptPath) }.getOrElse { return McpToolResult.error("Failed to read script: ${it.message}") }
        }
        val baseInput = args.str("baseInput")
        val protocolOverride = args.strOpt("protocol")
        val attackId = ctx.attackRunner.startAttack(rawRequest, scriptCode, baseInput, protocolOverride)
        val baseRequestHash = McpAttackRunner.hashBaseRequest(rawRequest)
        return McpToolResult.ok(mapOf(
            "attackId" to attackId,
            "started" to true,
            "visible" to true,
            "baseRequestHash" to baseRequestHash
        ))
    }
}

internal class GetAttackStatusTool : BaseTool() {
    override val name = "get_attack_status"
    override val description = "Get the status/progress of a running or finished attack."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("attackId", "Attack id from run_attack.")
        required("attackId")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val attackId = args.str("attackId").takeIf { it.isNotBlank() } ?: return McpToolResult.error("attackId is required.")
        val status = ctx.attackRunner.getStatus(attackId) ?: return McpToolResult.error("attack not found: $attackId")
        return McpToolResult.ok(status)
    }
}

internal class GetAttackResultsTool : BaseTool() {
    override val name = "get_attack_results"
    override val description = "Fetch a page of attack result summaries (status, length, payload, anomaly rank). Use get_attack_result_detail for full raw request/response."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("attackId", "Attack id.")
        intProp("limit", "Max results (default 50, max 500).", default = 50)
        intProp("offset", "Skip first N (pagination).", default = 0)
        required("attackId")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val attackId = args.str("attackId").takeIf { it.isNotBlank() } ?: return McpToolResult.error("attackId is required.")
        val limit = args.intOr("limit", 50).coerceIn(1, 500)
        val offset = args.intOr("offset", 0).coerceAtLeast(0)
        val results = ctx.attackRunner.getResults(attackId, limit, offset)
            ?: return McpToolResult.error("attack not found: $attackId")
        return McpToolResult.ok(mapOf("results" to results, "limit" to limit, "offset" to offset))
    }
}

internal class GetAttackResultDetailTool : BaseTool() {
    override val name = "get_attack_result_detail"
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("attackId", "Attack id.")
        intProp("index", "Result index (0-based, as returned by get_attack_results).")
        required("attackId", "index")
    }
    override val description = "Fetch full raw request and response for one attack result by index."

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val attackId = args.str("attackId").takeIf { it.isNotBlank() } ?: return McpToolResult.error("attackId is required.")
        val index = args.intOr("index", -1)
        val detail = ctx.attackRunner.getResultDetail(attackId, index)
            ?: return McpToolResult.error("result not found (attackId=$attackId index=$index)")
        return McpToolResult.ok(detail)
    }
}

internal class StopAttackTool : BaseTool() {
    override val name = "stop_attack"
    override val description = "Cancel a running attack."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("attackId", "Attack id.")
        required("attackId")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val attackId = args.str("attackId").takeIf { it.isNotBlank() } ?: return McpToolResult.error("attackId is required.")
        val ok = ctx.attackRunner.stop(attackId)
        return if (ok) McpToolResult.ok(mapOf("attackId" to attackId, "stopped" to true))
        else McpToolResult.error("attack not found: $attackId")
    }
}

internal class ListAttacksTool : BaseTool() {
    override val name = "list_attacks"
    override val description = "List all attacks launched via MCP (running and finished) with their status."
    override val inputSchema: ObjectNode = mcpSchema {}

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        return McpToolResult.ok(mapOf("attacks" to ctx.attackRunner.listAttacks()))
    }
}

internal class GetStoredAttackResultsTool : BaseTool() {
    override val name = "get_stored_attack_results"
    override val description = "Load persisted attack results from the project DB by base request hash (results of attacks launched from the app or MCP that were saved)."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("baseRequestHash", "SHA-256 hash of the base request template.")
        intProp("limit", "Max results (default 50, max 500).", default = 50)
        required("baseRequestHash")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val hash = args.str("baseRequestHash").takeIf { it.isNotBlank() } ?: return McpToolResult.error("baseRequestHash is required.")
        val store = XproxyAppContext.requireDataStore()
        val limit = args.intOr("limit", 50).coerceIn(1, 500)
        val results = store.loadFuzzerResults(hash).take(limit).map { req ->
            mapOf(
                "queueId" to req.id, "status" to req.code, "length" to req.length,
                "wordcount" to req.wordcount, "timeMicros" to req.time, "label" to req.label,
                "payload" to req.words, "anomalyRank" to req.anomalyRank,
                "requestRaw" to req.getRequest(), "responseRaw" to req.response
            )
        }
        return McpToolResult.ok(mapOf("baseRequestHash" to hash, "results" to results))
    }
}

/** Area 3 工具集。 */
fun fuzzerTools(): List<McpTool> = listOf(
    SendRequestTool(),
    ConfirmVulnTool(),
    RunAttackTool(),
    GetAttackStatusTool(),
    GetAttackResultsTool(),
    GetAttackResultDetailTool(),
    StopAttackTool(),
    ListAttacksTool(),
    GetStoredAttackResultsTool()
)
