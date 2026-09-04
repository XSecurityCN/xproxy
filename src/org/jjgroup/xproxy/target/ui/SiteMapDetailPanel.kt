package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.kits.core.HttpViewerToolContext
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.ui.http.HttpRequestResponseViewer
import org.jjgroup.xproxy.ui.marking.HighlightCellRenderer
import org.jjgroup.xproxy.ui.marking.TrafficHighlightRegistry
import org.jjgroup.xproxy.ui.table.MimeFilterBar
import org.jjgroup.xproxy.ui.table.MimeFilterState
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Color
import java.awt.Component
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

internal class SiteMapDetailPanel(
    internal val onSendToFuzzer: (String, HttpService?) -> Unit,
    internal val onSendToCodec: ((String, String?) -> Unit)?,
    private val onDeleteHistoryIds: ((Set<Long>) -> Unit)?,
    private val onDeleteReportedIssueId: ((String) -> Unit)?,
    internal val resolveDetail: (ProxyHistoryEntry) -> ProxyHistoryEntry?,
    internal val resolveDetailById: (Long) -> ProxyHistoryEntry?,
    internal val resolveIssueRaw: (String) -> Pair<String, String>? = { null },
    internal val toHttpService: (ProxyHistoryEntry) -> HttpService,
    internal val normalizePath: (String) -> String,
    internal val isPathUnder: (String, String?) -> Boolean
) : JPanel(BorderLayout()) {

    var currentDetailHistory: ProxyHistoryEntry? = null
    private var lastDisplayedRequestRaw: String? = null
    private var lastDisplayedResponseRaw: String? = null
    val detailViewer = HttpRequestResponseViewer(
        onSendToFuzzer = { requestRaw ->
            onSendToFuzzer.invoke(requestRaw, currentDetailHistory?.let { toHttpService(it) })
        },
        onSendToCodec = onSendToCodec,
        toolContext = HttpViewerToolContext.TARGET
    )

    val reportedIssuesById = LinkedHashMap<String, org.jjgroup.xproxy.issue.model.ReportedIssue>()
    private val contentsModel = TargetContentsTableModel()
    val contentsTable = JTable(contentsModel).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        autoCreateRowSorter = true
        rowSorter?.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.DESCENDING))
    }
    private val issuesRoot = DefaultMutableTreeNode(I18n.t("target.issues"))
    private val issuesTreeModel = DefaultTreeModel(issuesRoot)
    val issuesTree = JTree(issuesTreeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ): Component {
                val rendered = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                val node = value as? DefaultMutableTreeNode
                val data = node?.userObject as? IssueTreeNode
                if (data != null) {
                    text = data.label
                    icon = data.issue?.let { issueSeverityIcon(it.severity) }
                }
                return rendered
            }
        }
    }
    private val issuesDockButton = VerticalDockToggleButton(I18n.t("target.issues"))
    private val mimeFilterState = MimeFilterState()
    var suppressContentsSelectionListener = false
    var suppressIssuesSelectionListener = false
    private var upperRightSplit: JSplitPane? = null
    private var upperRightDefaultDividerSize = 0
    private var issuesDividerRatio = 0.7
    private var issuesVisible = false
    internal val issueDefaultSeverityIcon: Icon = SeverityDotIcon(Color(108, 117, 125))
    internal val issueSeverityIcons: Map<String, Icon> = mapOf(
        "high" to SeverityDotIcon(Color(220, 53, 69)),
        "medium" to SeverityDotIcon(Color(253, 126, 20)),
        "low" to SeverityDotIcon(Color(255, 193, 7)),
        "information" to SeverityDotIcon(Color(13, 110, 253)),
        "info" to SeverityDotIcon(Color(13, 110, 253))
    )

    var onRefreshContents: (() -> Unit)? = null

    companion object {
        internal val SENSITIVE_PATH_PATTERN = Regex(
            "(^|/)(\\.git|\\.svn|\\.env|phpinfo\\.php|swagger|actuator|admin|backup|bak)(/|$|\\.)",
            setOf(RegexOption.IGNORE_CASE)
        )
        internal val VERSION_TOKEN_PATTERN = Regex("\\d+\\.\\d+")

        private val COLUMN_WIDTHS = listOf(
            //  index -> (minWidth, preferredWidth, maxWidth)
            Triple(56, 72, 96),       // 0: #
            Triple(null, 74, 98),     // 1: Method
            Triple(null, 190, null),  // 2: Host
            Triple(null, 320, null),  // 3: Path
            Triple(null, 72, 88),     // 4: Status
            Triple(null, 84, 104),    // 5: Length
            Triple(null, 120, null),  // 6: MIME type
            Triple(null, 240, null),  // 7: Title
            Triple(null, 52, 60),     // 8: TLS
            Triple(null, 94, 116),
            Triple(null, 74, 90)
        )
    }

    init {
        configureContentsTableAlignment()
        configureContentsTableColumnWidths()
        I18nBinder.bindTableHeaders(contentsTable)
        installContentsPopupMenu()
        installIssuesPopupMenu()
        TrafficHighlightRegistry.addListener {
            SwingUtilities.invokeLater { contentsTable.repaint() }
        }

        contentsTable.selectionModel.addListSelectionListener {
            if (suppressContentsSelectionListener) {
                return@addListSelectionListener
            }
            suppressIssuesSelectionListener = true
            try {
                issuesTree.clearSelection()
            } finally {
                suppressIssuesSelectionListener = false
            }
            updateDetailFromContentsSelection()
        }

        issuesTree.addTreeSelectionListener {
            if (suppressIssuesSelectionListener) {
                return@addTreeSelectionListener
            }
            suppressContentsSelectionListener = true
            try {
                contentsTable.clearSelection()
            } finally {
                suppressContentsSelectionListener = false
            }
            updateDetailFromIssuesSelection()
        }

        val contentsPanel = JPanel(BorderLayout()).apply {
            val contentsTop = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                val contentsLabel = JLabel(I18n.t("target.contents"))
                I18nBinder.bindText(contentsLabel, "target.contents")
                add(contentsLabel)
                add(MimeFilterBar(mimeFilterState) { onRefreshContents?.invoke() })
            }
            add(contentsTop, BorderLayout.NORTH)
            add(JScrollPane(contentsTable), BorderLayout.CENTER)
        }

        val issuesPanel = JPanel(BorderLayout()).apply {
            val issuesTop = JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(0, 0, 4, 0)
                val issuesLabel = JLabel(I18n.t("target.issues"))
                I18nBinder.bindText(issuesLabel, "target.issues")
                add(issuesLabel, BorderLayout.WEST)
                val closeIssuesButton = JButton("\u00d7").apply {
                    margin = java.awt.Insets(0, 8, 0, 8)
                    addActionListener { hideIssuesDrawer() }
                }
                add(closeIssuesButton, BorderLayout.EAST)
            }
            add(issuesTop, BorderLayout.NORTH)
            add(JScrollPane(issuesTree), BorderLayout.CENTER)
        }

        val upperRight = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, contentsPanel, issuesPanel).also {
            upperRightSplit = it
            upperRightDefaultDividerSize = it.dividerSize
            it.resizeWeight = 0.7
        }

        val dockPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 2, 0, 0)
            issuesDockButton.addActionListener {
                if (issuesDockButton.isSelected) {
                    showIssuesDrawer()
                } else {
                    hideIssuesDrawer()
                }
            }
            add(issuesDockButton, BorderLayout.CENTER)
        }

        val upperRightContainer = JPanel(BorderLayout()).apply {
            add(upperRight, BorderLayout.CENTER)
            add(dockPanel, BorderLayout.EAST)
        }

        I18nBinder.bind {
            issuesDockButton.text = I18n.t("target.issues")
            issuesRoot.userObject = I18n.t("target.issues")
            issuesTreeModel.nodeChanged(issuesRoot)
        }

        val right = JSplitPane(JSplitPane.VERTICAL_SPLIT, upperRightContainer, detailViewer).apply {
            resizeWeight = 0.48
        }

        add(right, BorderLayout.CENTER)
        hideIssuesDrawer()
    }

    fun refreshContents(
        allRecords: List<HistoryRecord>,
        recordsByHost: Map<String, List<HistoryRecord>>,
        recordsByKey: Map<String, List<HistoryRecord>>,
        selectedNode: SiteMapNode?
    ) {
        val selectedContentId = selectedModelRow(contentsTable).let { row -> contentsModel.getAt(row)?.id }
        val selectedIssueKey = selectedIssueKey()

        val filtered = when {
            selectedNode == null -> allRecords
            !selectedNode.key.isNullOrBlank() -> recordsByKey[selectedNode.key] ?: emptyList()
            !selectedNode.hostKey.isNullOrBlank() && !selectedNode.pathPrefix.isNullOrBlank() -> {
                val hostRecords = recordsByHost[selectedNode.hostKey] ?: emptyList()
                hostRecords.filter { isPathUnder(it.normalizedPath, selectedNode.pathPrefix) }
            }
            !selectedNode.hostKey.isNullOrBlank() -> recordsByHost[selectedNode.hostKey] ?: emptyList()
            else -> allRecords
        }

        val rows = buildDeduplicatedRows(filtered)
        val keywordActive = mimeFilterState.keyword().isNotBlank()
        val filteredRows = rows.filter { entry ->
            val forFilter = if (keywordActive) resolveDetail(entry) ?: entry else entry
            mimeFilterState.matchesHttp(forFilter.mimeType, forFilter.statusCode, forFilter.requestRaw, forFilter.responseRaw)
        }
        suppressContentsSelectionListener = true
        try {
            contentsModel.setRows(filteredRows)
        } finally {
            suppressContentsSelectionListener = false
        }
        refreshIssuesTree(filteredRows, selectedNode)

        if (filteredRows.isEmpty()) {
            clearDetailIfNeeded()
            return
        }

        if (!selectedIssueKey.isNullOrBlank() && selectedIssueRecord() != null) {
            updateDetailFromIssuesSelection()
            return
        }

        val modelRowToSelect = selectedContentId
            ?.let { id -> findContentsRowById(id) }
            ?.takeIf { it >= 0 }
            ?: 0
        val viewRowToSelect = if (contentsTable.rowSorter != null) {
            contentsTable.convertRowIndexToView(modelRowToSelect)
        } else {
            modelRowToSelect
        }

        suppressContentsSelectionListener = true
        try {
            if (viewRowToSelect >= 0) {
                contentsTable.selectionModel.setSelectionInterval(viewRowToSelect, viewRowToSelect)
            }
        } finally {
            suppressContentsSelectionListener = false
        }
        updateDetailFromContentsSelection()
    }

    fun refreshIssuesTree(rows: List<ProxyHistoryEntry>, selectedNode: SiteMapNode?) {
        val selectedIssueKey = selectedIssueKey()
        val issues = detectIssues(rows) + mapReportedIssues(selectedNode)
        issuesRoot.removeAllChildren()

        if (issues.isEmpty()) {
            issuesRoot.add(DefaultMutableTreeNode(IssueTreeNode(I18n.t("target.no_issues"))))
            issuesTreeModel.reload(issuesRoot)
            return
        }

        val byCategory = LinkedHashMap<String, MutableList<IssueRecord>>()
        issues.forEach { issue ->
            byCategory.getOrPut(issue.category) { ArrayList() }.add(issue)
        }

        val selectedPath = ArrayList<Any>()
        selectedPath.add(issuesRoot)
        for ((category, categoryIssues) in byCategory) {
            val categoryNode = DefaultMutableTreeNode(IssueTreeNode("$category (${categoryIssues.size})"))
            issuesRoot.add(categoryNode)
            for (issue in categoryIssues) {
                val issueLabel = "${issue.method.uppercase(Locale.getDefault())} ${issue.path}"
                val issueNode = DefaultMutableTreeNode(IssueTreeNode(issueLabel, issue))
                categoryNode.add(issueNode)
                if (!selectedIssueKey.isNullOrBlank() && selectedIssueKey == issue.key) {
                    selectedPath.clear()
                    selectedPath.add(issuesRoot)
                    selectedPath.add(categoryNode)
                    selectedPath.add(issueNode)
                }
            }
        }

        issuesTreeModel.reload(issuesRoot)
        for (index in 0 until issuesRoot.childCount) {
            val child = issuesRoot.getChildAt(index) as? DefaultMutableTreeNode ?: continue
            issuesTree.expandPath(TreePath(arrayOf(issuesRoot, child)))
        }
        if (selectedPath.size >= 3) {
            suppressIssuesSelectionListener = true
            try {
                issuesTree.selectionPath = TreePath(selectedPath.toTypedArray())
            } finally {
                suppressIssuesSelectionListener = false
            }
            updateDetailFromIssuesSelection()
        }
    }

    fun contentsModelCurrentRows(): List<ProxyHistoryEntry> = contentsModel.currentRows()

    fun deleteByHistoryIds(ids: Set<Long>, seenHistoryIds: MutableSet<Long>, detailCache: MutableMap<Long, ProxyHistoryEntry>, allRecords: MutableList<HistoryRecord>, rebuildState: () -> Unit) {
        if (ids.isEmpty()) return
        onDeleteHistoryIds?.invoke(ids)
        seenHistoryIds.removeAll(ids)
        ids.forEach { detailCache.remove(it) }
        allRecords.removeIf { ids.contains(it.history.id) }
        rebuildState()
    }

    fun deleteIssueRecord(issue: IssueRecord, deleteByIds: (Set<Long>) -> Unit) {
        if (issue.historyId > 0) {
            deleteByIds(setOf(issue.historyId))
            return
        }
        if (issue.key.startsWith("script:")) {
            val issueId = issue.key.removePrefix("script:")
            if (issueId.isNotBlank()) {
                reportedIssuesById.remove(issueId)
                onDeleteReportedIssueId?.invoke(issueId)
            }
            refreshIssuesTree(contentsModel.currentRows(), null)
            if (selectedIssueRecord() != null) {
                updateDetailFromIssuesSelection()
            } else {
                currentDetailHistory = null
                detailViewer.clear()
            }
        }
    }

    fun selectedIssueKey(): String? {
        val path = issuesTree.selectionPath ?: return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val data = node.userObject as? IssueTreeNode ?: return null
        return data.issue?.key
    }

    fun selectedIssueRecord(): IssueRecord? {
        val path = issuesTree.selectionPath ?: return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val data = node.userObject as? IssueTreeNode ?: return null
        return data.issue
    }

    fun selectedModelRow(table: JTable): Int {
        val viewRow = table.selectedRow
        if (viewRow < 0) return -1
        return if (table.rowSorter != null) table.convertRowIndexToModel(viewRow) else viewRow
    }

    fun selectedModelRows(table: JTable): List<Int> {
        val viewRows = table.selectedRows
        if (viewRows.isEmpty()) return emptyList()
        return viewRows.map { row ->
            if (table.rowSorter != null) table.convertRowIndexToModel(row) else row
        }.distinct()
    }

    fun contentsModelGetAt(row: Int): ProxyHistoryEntry? = contentsModel.getAt(row)

    private fun updateDetailFromContentsSelection() {
        val row = selectedModelRow(contentsTable)
        val entry = contentsModel.getAt(row)
        if (entry == null) {
            clearDetailIfNeeded()
        } else {
            val detail = resolveDetail(entry) ?: entry
            showDetailIfChanged(detail, detail.requestRaw, detail.responseRaw)
        }
    }

    private fun updateDetailFromIssuesSelection() {
        val path = issuesTree.selectionPath ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? IssueTreeNode ?: return
        val issue = data.issue ?: return
        val detail = if (issue.historyId > 0) resolveDetailById(issue.historyId) else null
        if (!issuesVisible) {
            showIssuesDrawer()
        }
        val (reqRaw, respRaw) = resolveIssueRecordRaw(issue)
        showDetailIfChanged(detail, reqRaw, respRaw, issue.evidence)
    }

    /**
     * 解析一条 issue 展示/发送所需的 (requestRaw, responseRaw)。
     *
     * 启动期批量载入的 issue 仅含元数据(IssueRecord.requestRaw/responseRaw 为空),其 raw 按需由
     * [resolveIssueRaw] 按 issueId(PK 单行查库 + TargetPanel.issueRawCache 缓存)懒加载;
     * 实时由脚本上报([ingestReportedIssue])的 issue 已带 raw,直接复用。
     * 与 proxy_history 的 resolveDetail 同构。
     */
    internal fun resolveIssueRecordRaw(issue: IssueRecord): Pair<String, String> {
        if (issue.requestRaw.isNotEmpty() || issue.responseRaw.isNotEmpty()) {
            return issue.requestRaw to issue.responseRaw
        }
        val issueId = issue.key.removePrefix("script:")
        if (issueId.isBlank()) return "" to ""
        return resolveIssueRaw(issueId) ?: ("" to "")
    }

    private fun showDetailIfChanged(detail: ProxyHistoryEntry?, requestRaw: String, responseRaw: String, evidence: List<String> = emptyList()) {
        currentDetailHistory = detail
        if (requestRaw == lastDisplayedRequestRaw && responseRaw == lastDisplayedResponseRaw && evidence.isEmpty()) {
            return
        }
        lastDisplayedRequestRaw = requestRaw
        lastDisplayedResponseRaw = responseRaw
        detailViewer.showRequest(requestRaw)
        detailViewer.showResponse(responseRaw, evidence = evidence)
    }

    private fun clearDetailIfNeeded() {
        currentDetailHistory = null
        if (lastDisplayedRequestRaw == null && lastDisplayedResponseRaw == null) {
            return
        }
        lastDisplayedRequestRaw = null
        lastDisplayedResponseRaw = null
        detailViewer.clear()
    }

    private fun hideIssuesDrawer() {
        val split = upperRightSplit ?: return
        if (split.dividerSize > 0 && split.width > 0) {
            issuesDividerRatio = (split.dividerLocation.toDouble() / split.width.toDouble()).coerceIn(0.35, 0.92)
        }
        split.dividerSize = 0
        issuesVisible = false
        issuesDockButton.isSelected = false
        SwingUtilities.invokeLater {
            split.setDividerLocation(1.0)
            split.revalidate()
            split.repaint()
        }
    }

    private fun showIssuesDrawer() {
        val split = upperRightSplit ?: return
        split.dividerSize = upperRightDefaultDividerSize
        issuesVisible = true
        issuesDockButton.isSelected = true
        SwingUtilities.invokeLater {
            split.setDividerLocation(issuesDividerRatio.coerceIn(0.35, 0.92))
            split.revalidate()
            split.repaint()
        }
    }

    private fun configureContentsTableAlignment() {
        val highlightRenderer = HighlightCellRenderer(TrafficHighlightRegistry.Kind.HTTP) { table, modelRow ->
            contentsModel.getAt(modelRow)?.id
        }
        (contentsTable.tableHeader.defaultRenderer as? DefaultTableCellRenderer)?.horizontalAlignment = JLabel.LEFT
        for (columnIndex in 0 until contentsTable.columnModel.columnCount) {
            contentsTable.columnModel.getColumn(columnIndex).cellRenderer = highlightRenderer
        }
    }

    private fun configureContentsTableColumnWidths() {
        COLUMN_WIDTHS.forEachIndexed { index, (minW, prefW, maxW) ->
            val column = contentsTable.columnModel.getColumn(index)
            minW?.let { column.minWidth = it }
            prefW?.let { column.preferredWidth = it }
            maxW?.let { column.maxWidth = it }
        }
    }

    private fun buildDeduplicatedRows(records: List<HistoryRecord>): List<ProxyHistoryEntry> {
        val latestBySignature = LinkedHashMap<String, ProxyHistoryEntry>()
        for (record in records.asReversed()) {
            val signature = "${record.key}|${record.history.method.uppercase()}"
            if (!latestBySignature.containsKey(signature)) {
                latestBySignature[signature] = record.history
            }
        }
        return latestBySignature.values.toList()
    }

    private fun findContentsRowById(id: Long): Int {
        val rows = contentsModel.currentRows()
        for (index in rows.indices) {
            if (rows[index].id == id) {
                return index
            }
        }
        return -1
    }

    var onDeleteByIds: ((Set<Long>) -> Unit)? = null
    var onDeleteIssue: ((IssueRecord) -> Unit)? = null
}
