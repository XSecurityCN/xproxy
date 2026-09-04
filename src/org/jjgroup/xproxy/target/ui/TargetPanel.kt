package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.core.boundedLruMap
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.issue.model.ReportedIssue
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.target.core.SiteMapService
import java.awt.BorderLayout
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.JPanel

class TargetPanel(
    private val onSendToFuzzer: (String, HttpService?) -> Unit,
    private val onSendToCodec: ((String, String?) -> Unit)? = null,
    private val onDeleteHistoryIds: ((Set<Long>) -> Unit)? = null,
    private val onDeleteReportedIssueId: ((String) -> Unit)? = null,
    private val detailLoader: ((Long) -> ProxyHistoryEntry?)? = null,
    private val issueRawLoader: ((String) -> Pair<String, String>?)? = null
) : JPanel(BorderLayout()) {

    private val service = SiteMapService()
    // 有界 LRU:SiteMap 选中节点按需加载的历史详情缓存。原为无界 LinkedHashMap(第六批给 ProxyPanel.historyDetailCache
    // 加了 LRU,漏了此处),随浏览节点线性增长。与 ProxyPanel 一致 cap=2000,最坏 2000×256KB≈512MB。
    private val detailCache = boundedLruMap<Long, ProxyHistoryEntry>(2000)
    // issue 请求/响应 raw 的按需懒加载缓存(配合 metadata-only 批量载入)。选中 issue 时 PK 单行查库后缓存,
    // cap=500,典型 issue response_raw ~40KB,最坏 ~20MB。
    private val issueRawCache = boundedLruMap<String, Pair<String, String>>(500)

    private val treePanel = SiteMapTreePanel(
        service = service,
        onSelectionChanged = { refreshContentsBySelection() },
        onSendToFuzzer = onSendToFuzzer,
        onSendToCodec = onSendToCodec,
        onDeleteHistoryIds = onDeleteHistoryIds
    )

    private val detailPanel = SiteMapDetailPanel(
        onSendToFuzzer = onSendToFuzzer,
        onSendToCodec = onSendToCodec,
        onDeleteHistoryIds = onDeleteHistoryIds,
        onDeleteReportedIssueId = onDeleteReportedIssueId,
        resolveDetail = { entry -> resolveDetail(entry) },
        resolveDetailById = { id -> resolveDetail(id) },
        resolveIssueRaw = { issueId -> resolveIssueRaw(issueId) },
        toHttpService = { history -> treePanel.toHttpService(history) },
        normalizePath = { path -> treePanel.normalizePath(path) },
        isPathUnder = { path, prefix -> treePanel.isPathUnder(path, prefix) }
    )

    /** 供 MCP get_current_request 读取当前选中节点/条目(internal,模块内可见)。 */
    internal fun treePanel(): SiteMapTreePanel = treePanel
    /** 供 MCP get_current_request 读取当前详情/选中 issue(internal,模块内可见)。 */
    internal fun detailPanel(): SiteMapDetailPanel = detailPanel

    init {
        treePanel.resolveDetailCallback = { id -> resolveDetail(id) }
        treePanel.onDeleteCallback = { ids -> deleteByHistoryIds(ids) }
        detailPanel.onRefreshContents = { refreshContentsBySelection() }
        detailPanel.onDeleteByIds = { ids -> deleteByHistoryIds(ids) }
        detailPanel.onDeleteIssue = { issue -> detailPanel.deleteIssueRecord(issue) { ids -> deleteByHistoryIds(ids) } }

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, detailPanel).apply {
            resizeWeight = 0.33
        }
        add(split, BorderLayout.CENTER)

        refreshContentsBySelection()
    }

    fun ingestFromProxy(history: ProxyHistoryEntry) {
        SwingUtilities.invokeLater {
            if (history.statusCode !in 200..399) {
                return@invokeLater
            }
            if (!treePanel.seenHistoryIds.add(history.id)) {
                return@invokeLater
            }
            val record = treePanel.indexHistory(history)
            treePanel.allRecords.add(record)
            treePanel.recordsByHost.getOrPut(record.hostKey) { ArrayList() }.add(record)
            treePanel.recordsByKey.getOrPut(record.key) { ArrayList() }.add(record)
            treePanel.upsertSiteMapEntry(history)

            val selected = treePanel.selectedSiteMapNode()
            if (treePanel.shouldRefreshForSelection(record, selected)) {
                refreshContentsBySelection()
            }
        }
    }

    fun ingestFromProxyBatch(histories: List<ProxyHistoryEntry>) {
        if (histories.isEmpty()) {
            return
        }
        SwingUtilities.invokeLater {
            var changed = false
            for (history in histories) {
                if (history.statusCode !in 200..399) {
                    continue
                }
                if (!treePanel.seenHistoryIds.add(history.id)) {
                    continue
                }
                val record = treePanel.indexHistory(history)
                treePanel.allRecords.add(record)
                treePanel.recordsByHost.getOrPut(record.hostKey) { ArrayList() }.add(record)
                treePanel.recordsByKey.getOrPut(record.key) { ArrayList() }.add(record)
                treePanel.upsertSiteMapEntry(history)
                changed = true
            }
            if (changed) {
                refreshContentsBySelection()
            }
        }
    }

    fun ingestReportedIssue(issue: ReportedIssue) {
        SwingUtilities.invokeLater {
            detailPanel.reportedIssuesById[issue.issueId] = issue
            detailPanel.refreshIssuesTree(detailPanel.contentsModelCurrentRows(), treePanel.selectedSiteMapNode())
        }
    }

    fun ingestReportedIssuesBatch(issues: List<ReportedIssue>) {
        if (issues.isEmpty()) {
            return
        }
        SwingUtilities.invokeLater {
            issues.forEach { issue -> detailPanel.reportedIssuesById[issue.issueId] = issue }
            detailPanel.refreshIssuesTree(detailPanel.contentsModelCurrentRows(), treePanel.selectedSiteMapNode())
        }
    }

    private fun refreshContentsBySelection() {
        val selectedNode = treePanel.selectedSiteMapNode()
        detailPanel.refreshContents(
            treePanel.allRecords,
            treePanel.recordsByHost,
            treePanel.recordsByKey,
            selectedNode
        )
    }

    private fun deleteByHistoryIds(ids: Set<Long>) {
        detailPanel.deleteByHistoryIds(
            ids,
            treePanel.seenHistoryIds,
            detailCache,
            treePanel.allRecords
        ) {
            treePanel.rebuildStateFromRecords()
            refreshContentsBySelection()
        }
    }

    private fun resolveDetail(entry: ProxyHistoryEntry): ProxyHistoryEntry? {
        if (entry.requestRaw.isNotEmpty() || entry.responseRaw.isNotEmpty()) {
            return entry
        }
        return resolveDetail(entry.id)
    }

    private fun resolveDetail(historyId: Long): ProxyHistoryEntry? {
        detailCache[historyId]?.let { return it }
        val loaded = detailLoader?.invoke(historyId) ?: return null
        detailCache[historyId] = loaded
        return loaded
    }

    /**
     * 按 issueId 懒加载并缓存单条 issue 的 (requestRaw, responseRaw)。
     * 启动期 [ingestReportedIssuesBatch] 只载元数据(raw=""),选中 issue 查看请求/响应时由此按需回填。
     * 与 [resolveDetail] 同构:缓存命中直返,未命中调 [issueRawLoader](PK 单行查库)后入缓存。
     */
    private fun resolveIssueRaw(issueId: String): Pair<String, String>? {
        issueRawCache[issueId]?.let { return it }
        val loaded = issueRawLoader?.invoke(issueId) ?: return null
        issueRawCache[issueId] = loaded
        return loaded
    }
}
