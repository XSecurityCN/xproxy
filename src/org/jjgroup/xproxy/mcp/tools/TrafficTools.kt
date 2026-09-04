package org.jjgroup.xproxy.mcp.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.server.mcpSchema
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry

/* ============================ Area 1: 流量 / JS 分析工具 ============================ */

/** 工具基类:把执行异常转成 isError 工具结果(符合 MCP:工具失败用 isError,而非 JSON-RPC 错误)。 */
abstract class BaseTool : McpTool {
    final override fun invoke(args: JsonNode, ctx: McpToolContext): McpToolResult = runCatching {
        run(args, ctx)
    }.getOrElse { e ->
        AppLogger.error("MCP tool '$name' failed", e)
        McpToolResult.error("Tool '$name' failed: ${e.message ?: e.javaClass.simpleName}")
    }

    protected abstract fun run(args: JsonNode, ctx: McpToolContext): McpToolResult
}

private fun historyMeta(e: ProxyHistoryEntry): Map<String, Any?> = mapOf(
    "id" to e.id,
    "timeMillis" to e.timeMillis,
    "method" to e.method,
    "host" to e.host,
    "path" to e.path,
    "statusCode" to e.statusCode,
    "length" to e.length,
    "mimeType" to e.mimeType,
    "title" to e.title,
    "tls" to e.tls,
    "protocol" to e.protocol,
    "tool" to e.tool,
    "modified" to e.modified
)

private fun wsMeta(e: ProxyWsHistoryEntry): Map<String, Any?> = mapOf(
    "id" to e.id,
    "timeMillis" to e.timeMillis,
    "host" to e.host,
    "path" to e.path,
    "direction" to e.direction,
    "messageType" to e.messageType,
    "mimeType" to e.mimeType,
    "length" to e.length,
    "preview" to e.preview,
    "sessionId" to e.sessionId
)

internal class SearchProxyHistoryTool : BaseTool() {
    override val name = "search_proxy_history"
    override val description = "Query captured proxy traffic (HTTP history) with filters. Returns metadata only (no raw bodies). Use get_proxy_history_entry to fetch full raw request/response by id. Supports content keyword search (scans raw bodies)."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("host", "Substring match on host (case-insensitive).")
        stringProp("path", "Substring match on request path.")
        stringProp("method", "HTTP method exact match (e.g. GET, POST).")
        prop("statusCode", "integer", "Exact status code (e.g. 200).")
        stringProp("statusClass", "Status class: 1xx/2xx/3xx/4xx/5xx.")
        arrayProp("mime", "string", "Mime buckets to include: text/json/xml/html/script/css/image/sse/bin/other.", listOf("text", "json", "xml", "html", "script", "css", "image", "sse", "bin", "other"))
        stringProp("keyword", "Case-insensitive content keyword; when set, scans raw request+response bodies (slower, full-table scan).")
        intProp("limit", "Max results (default 50, max 500).", default = 50)
        intProp("offset", "Skip first N results (for pagination).", default = 0)
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val host = args.str("host").lowercase()
        val path = args.str("path").lowercase()
        val method = args.str("method").uppercase()
        val statusCode = args.intOpt("statusCode")
        val statusClass = args.str("statusClass").lowercase().takeIf { it.isNotBlank() }
        val mime = args.strList("mime").map { it.lowercase() }.toSet()
        val keyword = args.strOpt("keyword")
        val limit = args.intOr("limit", 50).coerceIn(1, 500)
        val offset = args.intOr("offset", 0).coerceAtLeast(0)

        fun metaFilter(e: ProxyHistoryEntry): Boolean {
            if (host.isNotBlank() && !e.host.contains(host, ignoreCase = true)) return false
            if (path.isNotBlank() && !e.path.contains(path, ignoreCase = true)) return false
            if (method.isNotBlank() && !e.method.equals(method, ignoreCase = true)) return false
            if (statusCode != null && e.statusCode != statusCode) return false
            if (statusClass != null && !statusClassMatches(e.statusCode, statusClass)) return false
            if (mime.isNotEmpty() && e.mimeType.lowercase() !in mime) return false
            return true
        }

        val matches = if (keyword.isNullOrBlank()) {
            store.loadHistoryMetadata().filter { metaFilter(it) }
        } else {
            // 内容关键词:流式扫全表 raw,命中后再过 metadata 过滤。
            val matched = ArrayList<ProxyHistoryEntry>()
            store.scanHistoryDetails { id, mimeType, code, requestRaw, responseRaw ->
                if ((requestRaw.contains(keyword, ignoreCase = true) || responseRaw.contains(keyword, ignoreCase = true))) {
                    store.loadHistoryById(id)?.let { entry ->
                        if (metaFilter(entry)) matched.add(entry)
                    }
                }
                true
            }
            matched
        }

        val total = matches.size
        val paged = matches.sortedByDescending { it.id }.drop(offset).take(limit).map { historyMeta(it) }
        return McpToolResult.ok(mapOf("total" to total, "limit" to limit, "offset" to offset, "entries" to paged))
    }
}

internal class GetProxyHistoryEntryTool : BaseTool() {
    override val name = "get_proxy_history_entry"
    override val description = "Fetch full raw request and response for one or more proxy history entries by id (as returned by search_proxy_history)."
    override val inputSchema: ObjectNode = mcpSchema {
        arrayProp("ids", "integer", "History entry ids to fetch.")
        required("ids")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val ids = args.strList("ids").mapNotNull { it.toLongOrNull() }
            .ifEmpty { args.get("ids")?.mapNotNull { it?.asLong() } ?: emptyList() }
        if (ids.isEmpty()) return McpToolResult.error("No ids provided.")
        val entries = ids.mapNotNull { id ->
            store.loadHistoryById(id)?.let {
                mapOf(
                    "id" to it.id,
                    "method" to it.method,
                    "host" to it.host,
                    "path" to it.path,
                    "statusCode" to it.statusCode,
                    "requestRaw" to it.requestRaw,
                    "responseRaw" to it.responseRaw,
                    "originalRequestRaw" to it.originalRequestRaw,
                    "originalResponseRaw" to it.originalResponseRaw
                )
            }
        }
        return McpToolResult.ok(mapOf("entries" to entries))
    }
}

internal class GetSitemapTool : BaseTool() {
    override val name = "get_sitemap"
    override val description = "Reconstruct the target site map (grouped by scheme/host/port/path) from captured traffic. Returns aggregated methods, status codes, mime types, hit count, last seen."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("host", "Filter by host substring.")
        intProp("limit", "Max nodes (default 300, max 2000).", default = 300)
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val hostFilter = args.str("host").lowercase()
        val limit = args.intOr("limit", 300).coerceIn(1, 2000)

        data class NodeKey(val scheme: String, val host: String, val port: Int, val path: String)
        data class Node(var methods: MutableSet<String>, var statuses: MutableSet<Int>, var mimes: MutableSet<String>, var count: Int, var lastSeen: Long)

        val nodes = LinkedHashMap<NodeKey, Node>()
        store.loadHistoryMetadata().forEach { e ->
            if (hostFilter.isNotBlank() && !e.host.contains(hostFilter, ignoreCase = true)) return@forEach
            val (scheme, host, port) = schemeHostPort(e)
            val key = NodeKey(scheme, host, port, e.path)
            val node = nodes.getOrPut(key) { Node(mutableSetOf(), mutableSetOf(), mutableSetOf(), 0, 0) }
            node.methods.add(e.method)
            node.statuses.add(e.statusCode)
            node.mimes.add(e.mimeType)
            node.count++
            if (e.timeMillis > node.lastSeen) node.lastSeen = e.timeMillis
        }
        val result = nodes.entries.sortedByDescending { it.value.count }.take(limit).map { (k, v) ->
            mapOf(
                "scheme" to k.scheme, "host" to k.host, "port" to k.port, "path" to k.path,
                "methods" to v.methods.sorted(), "statusCodes" to v.statuses.sorted(),
                "mimeTypes" to v.mimes.sorted(), "count" to v.count, "lastSeenMillis" to v.lastSeen
            )
        }
        return McpToolResult.ok(mapOf("total" to nodes.size, "nodes" to result))
    }
}

internal class ListIssuesTool : BaseTool() {
    override val name = "list_issues"
    override val description = "List reported security issues (from xapp plugins, intruder scripts, or manual reports). Returns metadata only."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("severity", "Filter by severity: High/Medium/Low/Information.")
        stringProp("source", "Filter by source substring (e.g. xapp:plugin-id).")
        stringProp("host", "Filter by host substring.")
        intProp("limit", "Max results (default 100, max 1000).", default = 100)
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val severity = args.str("severity").lowercase().takeIf { it.isNotBlank() }
        val source = args.str("source").lowercase().takeIf { it.isNotBlank() }
        val host = args.str("host").lowercase().takeIf { it.isNotBlank() }
        val limit = args.intOr("limit", 100).coerceIn(1, 1000)

        val issues = store.loadReportedIssueMetadata().filter { i ->
            (severity == null || i.severity.equals(severity, ignoreCase = true)) &&
                (source == null || i.source.contains(source, ignoreCase = true)) &&
                (host == null || i.host.contains(host, ignoreCase = true))
        }.take(limit).map {
            mapOf(
                "issueId" to it.issueId, "source" to it.source, "name" to it.name,
                "severity" to it.severity, "confidence" to it.confidence,
                "host" to it.host, "path" to it.path, "method" to it.method,
                "url" to it.url, "tags" to it.tagsCsv, "createdAtMillis" to it.createdAtMillis
            )
        }
        return McpToolResult.ok(mapOf("total" to issues.size, "issues" to issues))
    }
}

internal class GetIssueTool : BaseTool() {
    override val name = "get_issue"
    override val description = "Fetch full issue detail including raw request/response by issueId."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("issueId", "Issue id (UUID).")
        required("issueId")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val issueId = args.str("issueId").takeIf { it.isNotBlank() } ?: return McpToolResult.error("issueId is required.")
        val meta = store.loadReportedIssueMetadata().firstOrNull { it.issueId == issueId }
            ?: return McpToolResult.error("Issue not found: $issueId")
        val raw = store.loadReportedIssueRaw(issueId)
        return McpToolResult.ok(mapOf(
            "issueId" to meta.issueId, "source" to meta.source, "name" to meta.name,
            "severity" to meta.severity, "confidence" to meta.confidence,
            "detail" to meta.detail, "remediation" to meta.remediation,
            "url" to meta.url, "host" to meta.host, "path" to meta.path, "method" to meta.method,
            "tags" to meta.tagsCsv, "createdAtMillis" to meta.createdAtMillis,
            "requestRaw" to (raw?.first ?: ""), "responseRaw" to (raw?.second ?: "")
        ))
    }
}

internal class SearchWsHistoryTool : BaseTool() {
    override val name = "search_ws_history"
    override val description = "Query captured WebSocket message history with filters. Returns metadata + preview only."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("host", "Filter by host substring.")
        stringProp("path", "Filter by path substring.")
        stringProp("direction", "C->S or S->C.")
        stringProp("keyword", "Case-insensitive content keyword (scans WS payloads).")
        intProp("limit", "Max results (default 50, max 500).", default = 50)
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val host = args.str("host").lowercase()
        val path = args.str("path").lowercase()
        val direction = args.str("direction")
        val keyword = args.strOpt("keyword")
        val limit = args.intOr("limit", 50).coerceIn(1, 500)

        fun metaFilter(e: ProxyWsHistoryEntry): Boolean {
            if (host.isNotBlank() && !e.host.contains(host, ignoreCase = true)) return false
            if (path.isNotBlank() && !e.path.contains(path, ignoreCase = true)) return false
            if (direction.isNotBlank() && !e.direction.equals(direction, ignoreCase = true)) return false
            return true
        }

        val matches = if (keyword.isNullOrBlank()) {
            store.loadWsHistoryMetadata().filter { metaFilter(it) }
        } else {
            // 内容关键词:流式扫 payload,收集命中 id 后一次性加载 metadata 再过滤(避免每命中一次全量加载)。
            val matchedIds = HashSet<Long>()
            store.scanWsDetails { id, _, _, _, _, _, _, payload ->
                if (payload.contains(keyword, ignoreCase = true)) matchedIds.add(id)
                true
            }
            store.loadWsHistoryMetadata().filter { it.id in matchedIds && metaFilter(it) }
        }
        val paged = matches.sortedByDescending { it.id }.take(limit).map { wsMeta(it) }
        return McpToolResult.ok(mapOf("total" to matches.size, "entries" to paged))
    }
}

internal class GetWsEntryTool : BaseTool() {
    override val name = "get_ws_entry"
    override val description = "Fetch full WebSocket message payload by id, plus the handshake session if available."
    override val inputSchema: ObjectNode = mcpSchema {
        intProp("id", "WS history entry id.") // note: id is Long in model; intProp acceptable for typical ids
        required("id")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val id = args.intOr("id", -1).toLong().takeIf { it >= 0 } ?: return McpToolResult.error("id is required.")
        val meta = store.loadWsHistoryMetadata().firstOrNull { it.id == id }
            ?: return McpToolResult.error("WS entry not found: $id")
        val payload = store.loadWsPayloadById(id)
        var session: Map<String, Any?>? = null
        meta.sessionId?.let { sid ->
            store.loadWsSession(sid)?.let { s ->
                session = mapOf(
                    "id" to s.id, "host" to s.host, "port" to s.port, "tls" to s.tls, "path" to s.path,
                    "handshakeRequest" to s.handshakeRequest, "handshakeResponse" to s.handshakeResponse
                )
            }
        }
        return McpToolResult.ok(mapOf(
            "entry" to wsMeta(meta), "payload" to (payload ?: ""), "session" to session
        ))
    }
}

internal class ExtractJsTool : BaseTool() {
    override val name = "extract_js"
    override val description = "Extract JavaScript from a captured response: external script src URLs, inline script contents, and suspected API endpoints/URLs (LinkFinder-style). Pass historyId to analyze a captured entry, or responseRaw to analyze arbitrary response text."
    override val inputSchema: ObjectNode = mcpSchema {
        intProp("historyId", "Proxy history entry id to analyze.")
        stringProp("responseRaw", "Arbitrary raw HTTP response text to analyze.")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val historyId = args.intOpt("historyId")?.toLong()
        val rawResponse = args.strOpt("responseRaw")
        val responseRaw = when {
            historyId != null -> store.loadHistoryById(historyId)?.responseRaw
                ?: return McpToolResult.error("History entry not found: $historyId")
            rawResponse != null -> rawResponse
            else -> return McpToolResult.error("Provide either historyId or responseRaw.")
        }
        val extraction = JsExtractor.extract(responseRaw)
        return McpToolResult.ok(mapOf(
            "scriptSrcs" to extraction.scriptSrcs,
            "inlineScripts" to extraction.inlineScripts,
            "endpoints" to extraction.endpoints
        ))
    }
}

internal class GetTrafficStatsTool : BaseTool() {
    override val name = "get_traffic_stats"
    override val description = "Summary statistics of captured traffic: total entries, counts by status class / mime / top hosts, and issue count."
    override val inputSchema: ObjectNode = mcpSchema {
        intProp("topHosts", "How many top hosts to return (default 20).", default = 20)
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val store = XproxyAppContext.requireDataStore()
        val topN = args.intOr("topHosts", 20).coerceIn(1, 200)
        val history = store.loadHistoryMetadata()
        val byStatusClass = HashMap<String, Int>()
        val byMime = HashMap<String, Int>()
        val byHost = HashMap<String, Int>()
        history.forEach { e ->
            val cls = "${e.statusCode / 100}xx"
            byStatusClass.merge(cls, 1, Int::plus)
            byMime.merge(e.mimeType, 1, Int::plus)
            byHost.merge(e.host, 1, Int::plus)
        }
        val issueCount = store.loadReportedIssueMetadata().size
        return McpToolResult.ok(mapOf(
            "totalHistory" to history.size,
            "totalIssues" to issueCount,
            "byStatusClass" to byStatusClass.toSortedMap(),
            "byMime" to byMime.toSortedMap(),
            "topHosts" to byHost.entries.sortedByDescending { it.value }.take(topN).associate { it.key to it.value }
        ))
    }
}

/* ------------------------------ helpers ------------------------------ */

private fun statusClassMatches(code: Int, cls: String): Boolean {
    val digit = cls.firstOrNull { it.isDigit() } ?: return false
    return code / 100 == digit.toString().toInt()
}

/** 从 ProxyHistoryEntry 推断 scheme/host/port(用于 site map 分组)。host 可能带端口。 */
private fun schemeHostPort(e: ProxyHistoryEntry): Triple<String, String, Int> {
    val scheme = if (e.tls) "https" else "http"
    val rawHost = e.host
    val (host, portFromHeader) = if (rawHost.startsWith("[")) {
        val end = rawHost.indexOf(']')
        if (end >= 0) rawHost.substring(1, end) to rawHost.substring(end + 1).removePrefix(":").toIntOrNull()
        else rawHost to null
    } else {
        val colon = rawHost.lastIndexOf(':')
        if (colon > 0) rawHost.substring(0, colon) to rawHost.substring(colon + 1).toIntOrNull()
        else rawHost to null
    }
    val port = portFromHeader ?: if (e.tls) 443 else 80
    return Triple(scheme, host, port)
}

/** Area 1 工具集。 */
fun trafficTools(): List<McpTool> = listOf(
    SearchProxyHistoryTool(),
    GetProxyHistoryEntryTool(),
    GetSitemapTool(),
    ListIssuesTool(),
    GetIssueTool(),
    SearchWsHistoryTool(),
    GetWsEntryTool(),
    ExtractJsTool(),
    GetTrafficStatsTool()
)
