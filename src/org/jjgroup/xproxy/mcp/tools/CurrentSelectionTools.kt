package org.jjgroup.xproxy.mcp.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.ui.currentTabState
import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.server.mcpSchema
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/* ============================ 当前查看请求工具 ============================ */

/**
 * 读取用户当前正在各模块查看的请求(proxy 选中条目 / target 选中节点或 issue / fuzzer 当前 tab),
 * 以及当前激活的 dock 卡片。选区在 EDT 上读取(带超时,避免 EDT 繁忙时阻塞 agent),raw 缺失时
 * off-EDT 回退到 ProjectDataStore 按 id 取全量。
 */
internal class GetCurrentRequestTool : BaseTool() {
    override val name = "get_current_request"
    override val description = "Return what the user is currently viewing: the active dock module plus the currently-selected request in Proxy (history row), Target (site-map node / detail entry / selected issue) and Fuzzer (active tab). Full raw request/response included when available. Use this to see what the user is looking at right now."
    override val inputSchema: ObjectNode = mcpSchema {}

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val snapshot = readOnEdtWithTimeout()
            ?: return McpToolResult.error("Could not read UI selection (UI busy or not ready). Retry shortly.")

        val store = runCatching { XproxyAppContext.requireDataStore() }.getOrNull()
        val out = LinkedHashMap<String, Any?>()
        out["activeModule"] = snapshot.activeModule

        // ---- Proxy ----
        out["proxy"] = buildProxy(snapshot, store)

        // ---- Target ----
        out["target"] = buildTarget(snapshot, store)

        // ---- Fuzzer ----
        out["fuzzer"] = buildFuzzer(snapshot)

        return McpToolResult.ok(out)
    }

    private fun buildProxy(s: SelectionSnapshot, store: org.jjgroup.xproxy.project.core.ProjectDataStore?): Map<String, Any?>? {
        val id = s.proxyEntryId ?: s.proxyMeta?.id ?: return null
        var reqRaw = s.proxyReqRaw
        var respRaw = s.proxyRespRaw
        var meta: ProxyHistoryEntry? = s.proxyMeta
        // raw 缺失(如选中但详情尚未解析完成)时按 id 回库取全量。
        if ((reqRaw.isBlank() || respRaw.isBlank()) && store != null) {
            val full = runCatching { store.loadHistoryById(id) }.getOrNull()
            if (full != null) {
                if (reqRaw.isBlank()) reqRaw = full.requestRaw
                if (respRaw.isBlank()) respRaw = full.responseRaw
                if (meta == null) meta = full
            }
        }
        return mapOf(
            "entryId" to id,
            "method" to (meta?.method ?: ""),
            "host" to (meta?.host ?: ""),
            "path" to (meta?.path ?: ""),
            "statusCode" to (meta?.statusCode ?: 0),
            "requestRaw" to reqRaw,
            "responseRaw" to respRaw
        )
    }

    private fun buildTarget(s: SelectionSnapshot, store: org.jjgroup.xproxy.project.core.ProjectDataStore?): Map<String, Any?>? {
        val node = s.targetNodeLabel
        val current = s.targetCurrent
        val issueKey = s.targetIssueKey
        if (node == null && current == null && issueKey == null) return null

        val result = LinkedHashMap<String, Any?>()
        if (node != null) {
            result["selectedNode"] = mapOf(
                "label" to node,
                "key" to (s.targetNodeKey ?: "")
            )
        }
        if (current != null) {
            var reqRaw = current.requestRaw
            var respRaw = current.responseRaw
            if ((reqRaw.isBlank() || respRaw.isBlank()) && store != null && current.id > 0) {
                val full = runCatching { store.loadHistoryById(current.id) }.getOrNull()
                if (full != null) {
                    if (reqRaw.isBlank()) reqRaw = full.requestRaw
                    if (respRaw.isBlank()) respRaw = full.responseRaw
                }
            }
            result["currentEntry"] = mapOf(
                "entryId" to current.id,
                "method" to current.method,
                "host" to current.host,
                "path" to current.path,
                "statusCode" to current.statusCode,
                "requestRaw" to reqRaw,
                "responseRaw" to respRaw
            )
        }
        if (issueKey != null && store != null) {
            val meta = runCatching { store.loadReportedIssueMetadata().firstOrNull { it.issueId == issueKey } }.getOrNull()
            val raw = runCatching { store.loadReportedIssueRaw(issueKey) }.getOrNull()
            result["selectedIssue"] = mapOf(
                "issueId" to issueKey,
                "name" to (meta?.name ?: ""),
                "severity" to (meta?.severity ?: ""),
                "host" to (meta?.host ?: ""),
                "path" to (meta?.path ?: ""),
                "requestRaw" to (raw?.first ?: ""),
                "responseRaw" to (raw?.second ?: "")
            )
        }
        return result
    }

    private fun buildFuzzer(s: SelectionSnapshot): Map<String, Any?>? {
        if (s.fuzzerReqRaw == null && s.fuzzerRespRaw == null && s.fuzzerTabId == null) return null
        return mapOf(
            "tabId" to (s.fuzzerTabId ?: ""),
            "title" to (s.fuzzerTitle ?: ""),
            "requestRaw" to (s.fuzzerReqRaw ?: ""),
            "responseRaw" to (s.fuzzerRespRaw ?: ""),
            "target" to (s.fuzzerTarget?.let {
                mapOf("host" to it.host, "port" to it.port, "protocol" to it.protocol)
            })
        )
    }

    /** 在 EDT 上读取选区(带超时);EDT 繁忙或异常返回 null。 */
    private fun readOnEdtWithTimeout(timeoutMs: Long = 3000): SelectionSnapshot? {
        if (SwingUtilities.isEventDispatchThread()) {
            return runCatching { gatherSnapshot() }.getOrElse { AppLogger.warn("MCP get_current_request gather failed", it); null }
        }
        val latch = CountDownLatch(1)
        val ref = AtomicReference<SelectionSnapshot?>()
        SwingUtilities.invokeLater {
            try {
                ref.set(runCatching { gatherSnapshot() }.getOrNull())
            } catch (e: Throwable) {
                AppLogger.warn("MCP get_current_request EDT gather failed", e)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return ref.get()
    }

    /** 各模块独立 try/catch,单个模块失败不影响其余。须在 EDT 调用。 */
    private fun gatherSnapshot(): SelectionSnapshot {
        val active = runCatching { XproxyAppContext.activeDockCard() }.getOrNull()

        val proxy = runCatching {
            val p = XproxyAppContext.proxyPanel() ?: return@runCatching null
            val id = p.lastHistoryDetailId
            val row = p.selectedModelRow(p.historyTable)
            val meta = if (row >= 0) p.historyModel.getAt(row) else null
            Triple(id, meta, p.lastHistoryDetailRequestRaw to p.lastHistoryDetailResponseRaw)
        }.getOrNull()

        val target = runCatching {
            val tp = XproxyAppContext.targetPanel() ?: return@runCatching null
            val node = tp.treePanel().selectedSiteMapNode()
            val current = tp.detailPanel().currentDetailHistory
            val issueKey = tp.detailPanel().selectedIssueKey()
            TargetBits(node?.label, node?.key, current, issueKey)
        }.getOrNull()

        val fuzzer = runCatching {
            val ui = XproxyAppContext.intruderUiContext() ?: return@runCatching null
            val state = ui.currentTabState() ?: return@runCatching null
            val selected = ui.requestTabBar.selectedComponent
            val title = runCatching { ui.requestTabBar.getTitleAt(ui.requestTabBar.selectedIndex) }.getOrNull()
            val tabId = ui.tabPersistentIds[selected]
            FuzzerBits(title, tabId, state.requestEditor.text, state.responseRaw.text, state.target)
        }.getOrNull()

        return SelectionSnapshot(
            activeModule = active,
            proxyEntryId = proxy?.first,
            proxyMeta = proxy?.second,
            proxyReqRaw = proxy?.third?.first ?: "",
            proxyRespRaw = proxy?.third?.second ?: "",
            targetNodeLabel = target?.nodeLabel,
            targetNodeKey = target?.nodeKey,
            targetCurrent = target?.current,
            targetIssueKey = target?.issueKey,
            fuzzerTitle = fuzzer?.title,
            fuzzerTabId = fuzzer?.tabId,
            fuzzerReqRaw = fuzzer?.reqRaw,
            fuzzerRespRaw = fuzzer?.respRaw,
            fuzzerTarget = fuzzer?.target
        )
    }

    private data class TargetBits(val nodeLabel: String?, val nodeKey: String?, val current: ProxyHistoryEntry?, val issueKey: String?)
    private data class FuzzerBits(val title: String?, val tabId: String?, val reqRaw: String?, val respRaw: String?, val target: HttpService?)
    private data class SelectionSnapshot(
        val activeModule: String?,
        val proxyEntryId: Long?,
        val proxyMeta: ProxyHistoryEntry?,
        val proxyReqRaw: String,
        val proxyRespRaw: String,
        val targetNodeLabel: String?,
        val targetNodeKey: String?,
        val targetCurrent: ProxyHistoryEntry?,
        val targetIssueKey: String?,
        val fuzzerTitle: String?,
        val fuzzerTabId: String?,
        val fuzzerReqRaw: String?,
        val fuzzerRespRaw: String?,
        val fuzzerTarget: HttpService?
    )
}

/** 当前查看请求工具集。 */
fun currentSelectionTools(): List<McpTool> = listOf(GetCurrentRequestTool())
