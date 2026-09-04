package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.request.applySyntax
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.kits.core.HttpViewerToolContext
import org.jjgroup.xproxy.proxy.core.ProxyController
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.model.ProxyInterceptItem
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRule
import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.settings.core.UiThemePalette
import org.jjgroup.xproxy.ui.http.HttpRequestResponseViewer
import org.jjgroup.xproxy.ui.marking.HighlightCellRenderer
import org.jjgroup.xproxy.ui.marking.TrafficHighlightRegistry
import org.jjgroup.xproxy.ui.table.MimeFilterBar
import org.jjgroup.xproxy.ui.table.MimeFilterState
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.Dimension
import org.jjgroup.xproxy.core.boundedLruMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.table.DefaultTableCellRenderer
import kotlin.concurrent.thread
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.RTextScrollPane

class ProxyPanel(
    internal val onSendToFuzzer: ((String, HttpService?) -> Unit)? = null,
    internal val onSendToCodec: ((String, String?) -> Unit)? = null,
    internal val onSendToWsRepeater: ((org.jjgroup.xproxy.proxy.ws.WsRepeaterTarget, Int, String, org.jjgroup.xproxy.proxy.ws.WsLiveConnection?) -> Unit)? = null,
    internal val projectDataStore: ProjectDataStore? = null,
    internal val initialHistory: List<ProxyHistoryEntry>? = null,
    internal val initialWsHistory: List<ProxyWsHistoryEntry>? = null,
    internal val onHistoryEntryAdded: ((ProxyHistoryEntry) -> Unit)? = null,
    internal val onHistoryEntryCaptured: ((ProxyHistoryEntry) -> Unit)? = null,
    private val onBeforeRequestRewrite: ((String, String, Boolean) -> String)? = null,
    private val onAfterResponseRewrite: ((String, String, String, Boolean) -> String)? = null
) : JPanel(BorderLayout()) {

    companion object {
        internal const val MAX_HISTORY_CACHE_CHARS = 256 * 1024
        // 历史详情/WS 载荷缓存为有界 LRU:超出条目数淘汰最久未访问者,避免随流量无限增长导致 OOM
        // (此前为无界 ConcurrentHashMap,13 万条流量 ≈ 数 GB 永久驻留)。2000 条 × 256KB/条上限 ≈ 512MB 最坏。
        internal const val HISTORY_DETAIL_CACHE_MAX_ENTRIES = 2000
        internal const val WS_PAYLOAD_CACHE_MAX_ENTRIES = 1000
        private const val LEGACY_DEMO_MATCH_REPLACE_RULE_ID = "demo-http-match-replace-rule"
        private const val LEGACY_DEMO_INTERCEPT_RULE_ID = "demo-intercept-rule"
    }

    internal val controller = ProxyController()
    internal val interceptModel = ProxyInterceptTableModel()
    internal val historyModel = ProxyHistoryTableModel()
    internal val wsHistoryModel = ProxyWsHistoryTableModel()
    internal val interceptTable = JTable(interceptModel)
    internal val historyTable = JTable(historyModel)
    internal val wsHistoryTable = JTable(wsHistoryModel)
    internal lateinit var proxyOptionsPanel: ProxyOptionsPanel
    internal val interceptDetailViewer = HttpRequestResponseViewer(
        requestEditable = true,
        responseEditable = true,
        onSendToFuzzer = { requestRaw ->
            val row = selectedModelRow(interceptTable)
            val selected = interceptModel.getAt(row)
            onSendToFuzzer?.invoke(requestRaw, selected?.let { toHttpService(it) })
        },
        onSendToCodec = onSendToCodec,
        onInterceptThisResponse = { markSelectedInterceptThisResponse() },
        toolContext = HttpViewerToolContext.PROXY,
        onApplyRequestMutation = { raw -> applyInterceptRequestMutation(raw) },
        onApplyResponseMutation = { raw -> applyInterceptResponseMutation(raw) }
    )
    internal val historyDetailViewer = HttpRequestResponseViewer(
        onSendToFuzzer = { requestRaw ->
            val row = selectedModelRow(historyTable)
            val selected = historyModel.getAt(row)
            val service = selected?.let { toHttpService(it) }
            onSendToFuzzer?.invoke(requestRaw, service)
        },
        onSendToCodec = onSendToCodec,
        toolContext = HttpViewerToolContext.PROXY
    )

    internal val historyDetailLayout = CardLayout()
    internal val historyDetailCard = JPanel(historyDetailLayout)
    internal val historyEmptyCardId = "history-empty"
    internal val historyContentCardId = "history-content"
    internal var historySplitPane: JSplitPane? = null
    internal var historyDefaultDividerSize = 0
    internal var historyDetailDividerRatio = 0.35
    internal val wsDetailLayout = CardLayout()
    internal val wsDetailCard = JPanel(wsDetailLayout)
    internal val wsEmptyCardId = "ws-empty"
    internal val wsContentCardId = "ws-content"
    internal var wsSplitPane: JSplitPane? = null
    internal var wsDefaultDividerSize = 0
    internal var wsDetailDividerRatio = 0.5

    private fun applyInterceptRequestMutation(raw: String): Boolean {
        interceptDetailViewer.showRequest(raw)
        return true
    }

    private fun applyInterceptResponseMutation(raw: String): Boolean {
        interceptDetailViewer.showResponse(raw)
        return true
    }

    internal val wsPrettyArea = RSyntaxTextArea(8, 60)
    internal val wsRawArea = RSyntaxTextArea(8, 60)
    internal val wsDetailTabs = JTabbedPane()
    internal val mimeFilterState = MimeFilterState()
    internal val historyDetailCache: MutableMap<Long, ProxyHistoryEntry> =
        boundedLruMap(HISTORY_DETAIL_CACHE_MAX_ENTRIES)
    internal val wsPayloadCache: MutableMap<Long, String> =
        boundedLruMap(WS_PAYLOAD_CACHE_MAX_ENTRIES)
    internal val historyIdAllocator = AtomicLong(0)
    private var highlightListenerUnsub: (() -> Unit)? = null

    internal val interceptToggle = InterceptToggleButton(I18n.t("proxy.intercept.off"))
    internal val forwardButton = JButton(I18n.t("proxy.action.forward"))
    internal val forwardMenuButton = SplitMenuGlyphButton()
    internal val dropButton = JButton(I18n.t("proxy.action.drop"))
    internal val dropMenuButton = SplitMenuGlyphButton()
    internal val forwardMenu = JPopupMenu()
    internal val dropMenu = JPopupMenu()
    internal val statusRefreshTimer = Timer(700) {
        refreshRunningStatusText()
    }
    internal val persistExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xproxy-proxy-persist").apply { isDaemon = true }
    }
    internal var currentInterceptItemId: Long? = null
    internal var suppressInterceptEditPersist = false
    internal var historyDetailActivatedByUser = false
    internal var lastHistoryDetailId: Long? = null
    internal var lastHistoryDetailRequestRaw: String = ""
    internal var lastHistoryDetailResponseRaw: String = ""
    // SSE 流式实时刷新的 latest-wins 合并:高频 chunk 只更新 pendingSseEntry(最新),通过单次 invokeLater 落地,
    // 避免每条事件都向 EDT 投递(不用 Swing Timer 去抖,符合既有约束)。
    internal val pendingSseEntry = java.util.concurrent.atomic.AtomicReference<ProxyHistoryEntry?>(null)
    internal val sseRefreshScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val historyDetailExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xproxy-history-detail-load").apply { isDaemon = true }
    }
    // 关键词过滤的全表后台扫描专用线程:与 historyDetailExecutor 隔离,避免长扫描阻塞行选中详情加载。
    internal val keywordFilterExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xproxy-keyword-filter").apply { isDaemon = true }
    }
    // 关键词过滤的代际 token:每次 applyFilters 自增,后台扫描据此取消过期任务并丢弃过期结果。
    internal val filterGeneration = AtomicLong(0)
    @Volatile
    internal var historyDetailGeneration: Long = 0
    internal var lastWsDetailId: Long? = null
    internal var lastWsDetailPayload: String = ""
    internal val wsRenderExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xproxy-ws-render").apply { isDaemon = true }
    }
    @Volatile
    internal var wsRenderGeneration: Long = 0
    @Volatile
    internal var wsRenderInProgress: Boolean = false
    internal var wsRenderPendingId: Long? = null
    internal var wsRenderPendingPayload: String = ""
    internal var wsDeferredPrettyRaw: String? = null
    internal var wsProgrammaticTabChange = false
    internal var wsUserChangedTabGeneration: Long = -1L

    init {
        interceptTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        historyTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        wsHistoryTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        interceptTable.rowSelectionAllowed = true
        historyTable.rowSelectionAllowed = true
        wsHistoryTable.rowSelectionAllowed = true
        wsHistoryTable.selectionBackground = historyTable.selectionBackground
        wsHistoryTable.selectionForeground = historyTable.selectionForeground
        historyTable.autoCreateRowSorter = true
        wsHistoryTable.autoCreateRowSorter = true
        historyTable.rowSorter?.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.DESCENDING))
        wsHistoryTable.rowSorter?.sortKeys = listOf(RowSorter.SortKey(0, SortOrder.DESCENDING))
        configureTrafficTableAlignment()
        configureTableColumnWidths()
        forwardButton.font = dropButton.font
        val dropHeight = JButton(I18n.t("proxy.action.drop")).preferredSize.height
        val currentForwardSize = forwardButton.preferredSize
        val normalizedForwardSize = Dimension((currentForwardSize.width * 1.08).toInt().coerceAtLeast(120), dropHeight)
        forwardButton.preferredSize = normalizedForwardSize
        forwardButton.minimumSize = normalizedForwardSize
        forwardButton.maximumSize = normalizedForwardSize
        val normalizedDropSize = Dimension((dropButton.preferredSize.width * 1.12).toInt().coerceAtLeast(95), normalizedForwardSize.height)
        dropButton.preferredSize = normalizedDropSize
        dropButton.minimumSize = normalizedDropSize
        dropButton.maximumSize = normalizedDropSize
        val menuButtonSide = normalizedForwardSize.height
        val menuButtonSize = Dimension(menuButtonSide, menuButtonSide)
        listOf(forwardMenuButton, dropMenuButton).forEach { menuButton ->
            menuButton.preferredSize = menuButtonSize
            menuButton.minimumSize = menuButtonSize
            menuButton.maximumSize = menuButtonSize
            menuButton.margin = Insets(0, 0, 0, 0)
            menuButton.isFocusable = false
        }
        applyInterceptToggleVisual(false)
        val normalizedInterceptSize = Dimension(
            (interceptToggle.preferredSize.width + 18).coerceAtLeast(140),
            normalizedForwardSize.height
        )
        interceptToggle.preferredSize = normalizedInterceptSize
        interceptToggle.minimumSize = normalizedInterceptSize
        interceptToggle.maximumSize = normalizedInterceptSize
        installInterceptBulkMenus()
        installSendToFuzzerMenu(
            table = interceptTable,
            requestProvider = { row -> interceptModel.getAt(row)?.requestRaw },
            serviceProvider = { row -> interceptModel.getAt(row)?.let { toHttpService(it) } },
            interceptResponseAction = { row ->
                val item = interceptModel.getAt(row)
                if (item != null && item.phase == ProxyInterceptItem.Phase.REQUEST) {
                    controller.markInterceptThisResponse(item.id)
                }
            }
        )
        installHistoryPopupMenu()
        installWsPopupMenu()
        registerHighlightListener()

        val loadedRules = ensureDemoMatchReplaceRule(projectDataStore?.loadProxyMatchReplaceRules().orEmpty())
        val loadedInterceptRules = ensureDemoInterceptRule(projectDataStore?.loadProxyInterceptRules().orEmpty())
        controller.setMatchReplaceRules(loadedRules)
        controller.setInterceptRules(loadedInterceptRules)
        controller.onBeforeRequestRewrite = onBeforeRequestRewrite
        controller.onAfterResponseRewrite = onAfterResponseRewrite
        proxyOptionsPanel = ProxyOptionsPanel(
            initialRules = loadedRules,
            initialInterceptRules = loadedInterceptRules,
            initialBindHost = "127.0.0.1",
            initialBindPort = 8080,
            onStartProxyRequested = { host, port ->
                thread(name = "proxy-start", isDaemon = true) {
                    controller.start(host, port, true)
                }
            },
            onStopProxyRequested = {
                thread(name = "proxy-stop", isDaemon = true) {
                    controller.stop()
                }
            },
            onRulesChanged = { rules ->
                controller.setMatchReplaceRules(rules)
                projectDataStore?.replaceProxyMatchReplaceRules(rules)
            },
            onInterceptRulesChanged = { rules: List<ProxyInterceptRule> ->
                controller.setInterceptRules(rules)
                projectDataStore?.replaceProxyInterceptRules(rules)
            }
        )

        add(buildCenterTabs(), BorderLayout.CENTER)
        bindStaticTexts()
        bindActions()
        bindControllerCallbacks()
        hydrateHistoryFromProjectStore()
        hydrateWsHistoryFromProjectStore()
        statusRefreshTimer.start()
        autoStartProxy()
    }

    private fun configureTrafficTableAlignment() {
        val leftCellRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        val historyHighlightRenderer = HighlightCellRenderer(TrafficHighlightRegistry.Kind.HTTP) { table, modelRow ->
            historyModel.getAt(modelRow)?.id
        }
        val wsHighlightRenderer = HighlightCellRenderer(TrafficHighlightRegistry.Kind.WS) { table, modelRow ->
            wsHistoryModel.getAt(modelRow)?.id
        }
        listOf(interceptTable, historyTable, wsHistoryTable).forEach { table ->
            (table.tableHeader.defaultRenderer as? DefaultTableCellRenderer)?.horizontalAlignment = SwingConstants.LEFT
        }
        // intercept 无 entry id,沿用普通左对齐渲染器;history/ws 行底色按高亮着色。
        for (columnIndex in 0 until interceptTable.columnModel.columnCount) {
            interceptTable.columnModel.getColumn(columnIndex).cellRenderer = leftCellRenderer
        }
        for (columnIndex in 0 until historyTable.columnModel.columnCount) {
            historyTable.columnModel.getColumn(columnIndex).cellRenderer = historyHighlightRenderer
        }
        for (columnIndex in 0 until wsHistoryTable.columnModel.columnCount) {
            wsHistoryTable.columnModel.getColumn(columnIndex).cellRenderer = wsHighlightRenderer
        }
    }

    private fun configureTableColumnWidths() {
        interceptTable.columnModel.getColumn(0).minWidth = 56
        interceptTable.columnModel.getColumn(0).preferredWidth = 72
        interceptTable.columnModel.getColumn(0).maxWidth = 96
        interceptTable.columnModel.getColumn(1).preferredWidth = 110
        interceptTable.columnModel.getColumn(1).maxWidth = 140
        interceptTable.columnModel.getColumn(2).preferredWidth = 78
        interceptTable.columnModel.getColumn(2).maxWidth = 96
        interceptTable.columnModel.getColumn(3).preferredWidth = 220
        interceptTable.columnModel.getColumn(4).preferredWidth = 420

        historyTable.columnModel.getColumn(0).minWidth = 56
        historyTable.columnModel.getColumn(0).preferredWidth = 72
        historyTable.columnModel.getColumn(0).maxWidth = 96
        historyTable.columnModel.getColumn(1).preferredWidth = 74
        historyTable.columnModel.getColumn(1).maxWidth = 98
        historyTable.columnModel.getColumn(2).preferredWidth = 190
        historyTable.columnModel.getColumn(3).preferredWidth = 320
        historyTable.columnModel.getColumn(4).preferredWidth = 72
        historyTable.columnModel.getColumn(4).maxWidth = 88
        historyTable.columnModel.getColumn(5).preferredWidth = 84
        historyTable.columnModel.getColumn(5).maxWidth = 104
        historyTable.columnModel.getColumn(6).preferredWidth = 120
        historyTable.columnModel.getColumn(7).preferredWidth = 240
        historyTable.columnModel.getColumn(8).preferredWidth = 52
        historyTable.columnModel.getColumn(8).maxWidth = 60
        historyTable.columnModel.getColumn(9).preferredWidth = 94
        historyTable.columnModel.getColumn(9).maxWidth = 116
        historyTable.columnModel.getColumn(10).preferredWidth = 74
        historyTable.columnModel.getColumn(10).maxWidth = 90
        historyTable.columnModel.getColumn(11).preferredWidth = 132
        historyTable.columnModel.getColumn(11).maxWidth = 172
        historyTable.columnModel.getColumn(12).preferredWidth = 78
        historyTable.columnModel.getColumn(12).maxWidth = 96

        wsHistoryTable.columnModel.getColumn(0).minWidth = 56
        wsHistoryTable.columnModel.getColumn(0).preferredWidth = 72
        wsHistoryTable.columnModel.getColumn(0).maxWidth = 96
        wsHistoryTable.columnModel.getColumn(1).preferredWidth = 190
        wsHistoryTable.columnModel.getColumn(2).preferredWidth = 320
        wsHistoryTable.columnModel.getColumn(3).preferredWidth = 92
        wsHistoryTable.columnModel.getColumn(3).maxWidth = 112
        wsHistoryTable.columnModel.getColumn(4).preferredWidth = 90
        wsHistoryTable.columnModel.getColumn(4).maxWidth = 110
        wsHistoryTable.columnModel.getColumn(5).preferredWidth = 110
        wsHistoryTable.columnModel.getColumn(6).preferredWidth = 84
        wsHistoryTable.columnModel.getColumn(6).maxWidth = 104
        wsHistoryTable.columnModel.getColumn(7).preferredWidth = 360
    }

    private fun registerHighlightListener() {
        highlightListenerUnsub?.invoke()
        highlightListenerUnsub = TrafficHighlightRegistry.addListener {
            SwingUtilities.invokeLater {
                historyTable.repaint()
                wsHistoryTable.repaint()
            }
        }
    }

    fun shutdown() {
        highlightListenerUnsub?.invoke()
        highlightListenerUnsub = null
        statusRefreshTimer.stop()
        interceptDetailViewer.shutdownRenderers()
        historyDetailViewer.shutdownRenderers()
        historyDetailExecutor.shutdownNow()
        keywordFilterExecutor.shutdownNow()
        wsRenderExecutor.shutdownNow()
        persistExecutor.shutdownNow()
        controller.stop()
    }

    internal fun refreshRunningStatusText() {
        if (!controller.isRunning()) {
            return
        }
        val current = proxyOptionsPanel.currentStatusText()
            if (current == I18n.t("proxy.status.starting") || current == I18n.t("proxy.status.stopping") || current.startsWith(I18n.t("proxy.status.start_failed_prefix"))) {
            return
        }
        proxyOptionsPanel.setListeningState(true, controller.currentStatusText())
    }

    internal fun buildCenterTabs(): JTabbedPane {
        val tabs = JTabbedPane()
        tabs.addTab(I18n.t("proxy.tab.intercept"), buildInterceptTab())
        tabs.addTab(I18n.t("proxy.tab.http_history"), buildHistoryTab())
        tabs.addTab(I18n.t("proxy.tab.ws_history"), buildWsHistoryTab())
        tabs.addTab(I18n.t("proxy.tab.options"), proxyOptionsPanel)
        I18nBinder.bindTab(tabs, 0, "proxy.tab.intercept")
        I18nBinder.bindTab(tabs, 1, "proxy.tab.http_history")
        I18nBinder.bindTab(tabs, 2, "proxy.tab.ws_history")
        I18nBinder.bindTab(tabs, 3, "proxy.tab.options")
        return tabs
    }

    private fun bindStaticTexts() {
        I18nBinder.bindText(forwardButton, "proxy.action.forward")
        I18nBinder.bindText(dropButton, "proxy.action.drop")
        I18nBinder.bindTableHeaders(interceptTable)
        I18nBinder.bindTableHeaders(historyTable)
        I18nBinder.bindTableHeaders(wsHistoryTable)
        I18nBinder.bind {
            interceptToggle.text = if (interceptToggle.isSelected) I18n.t("proxy.intercept.on") else I18n.t("proxy.intercept.off")
        }
    }

    internal fun buildWsHistoryTab(): JPanel {
        val panel = JPanel(BorderLayout())
        listOf(wsPrettyArea, wsRawArea).forEach {
            it.isEditable = false
            it.lineWrap = true
            it.wrapStyleWord = true
            it.highlightCurrentLine = true
            val isDark = (UIManager.get("laf.dark") as? Boolean) == true
            it.currentLineHighlightColor = if (isDark) Color(64, 68, 75) else Color(230, 230, 230)
            applySyntax(it, BodyKind.OTHER)
        }
        val prettyPane = RTextScrollPane(wsPrettyArea)
        val rawPane = RTextScrollPane(wsRawArea)
        prettyPane.lineNumbersEnabled = true
        rawPane.lineNumbersEnabled = true
        // 详情区右键:发送到重放器(作用于当前选中 WS 历史条目)+ 复制/全选。
        attachWsDetailPopup(wsPrettyArea, prettyPane)
        attachWsDetailPopup(wsRawArea, rawPane)
        wsDetailTabs.removeAll()
        wsDetailTabs.addTab(I18n.t("http.pretty"), prettyPane)
        wsDetailTabs.addTab(I18n.t("http.raw"), rawPane)
        I18nBinder.bindTab(wsDetailTabs, 0, "http.pretty")
        I18nBinder.bindTab(wsDetailTabs, 1, "http.raw")
        wsDetailTabs.addChangeListener {
            if (!wsProgrammaticTabChange) {
                wsUserChangedTabGeneration = wsRenderGeneration
            }
            if (wsDetailTabs.selectedIndex == 0) {
                materializeDeferredWsPrettyIfNeeded()
            }
        }

        val wsEmpty = JPanel(BorderLayout())
        val wsEmptyLabel = JLabel(I18n.t("proxy.empty.select_ws_history"))
        I18nBinder.bindText(wsEmptyLabel, "proxy.empty.select_ws_history")
        wsEmpty.add(wsEmptyLabel, BorderLayout.CENTER)
        wsDetailCard.removeAll()
        wsDetailCard.add(wsEmpty, wsEmptyCardId)
        wsDetailCard.add(wsDetailTabs, wsContentCardId)
        wsDetailLayout.show(wsDetailCard, wsEmptyCardId)

        val wsTop = JPanel(BorderLayout())
        wsTop.add(JScrollPane(wsHistoryTable), BorderLayout.CENTER)
        val wsSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, wsTop, wsDetailCard)
        wsSplitPane = wsSplit
        wsDefaultDividerSize = wsSplit.dividerSize
        wsSplit.resizeWeight = 0.5
        hideWsDetailDrawer()
        panel.add(MimeFilterBar(mimeFilterState) { applyFilters() }, BorderLayout.NORTH)
        panel.add(wsSplit, BorderLayout.CENTER)
        return panel
    }

    internal fun hideWsDetailDrawer() {
        val split = wsSplitPane ?: return
        if (split.dividerSize > 0 && split.height > 0) {
            wsDetailDividerRatio = (split.dividerLocation.toDouble() / split.height.toDouble()).coerceIn(0.1, 0.9)
        }
        split.dividerSize = 0
        SwingUtilities.invokeLater {
            split.setDividerLocation(1.0)
            split.revalidate()
            split.repaint()
        }
    }

    internal fun showWsDetailDrawer() {
        val split = wsSplitPane ?: return
        if (split.dividerSize > 0) {
            split.revalidate()
            split.repaint()
            return
        }
        split.dividerSize = wsDefaultDividerSize
        SwingUtilities.invokeLater {
            split.setDividerLocation(wsDetailDividerRatio.coerceIn(0.1, 0.9))
            split.revalidate()
            split.repaint()
        }
    }

    internal fun buildInterceptTab(): JSplitPane {
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        forwardButton.isEnabled = false
        forwardMenuButton.isEnabled = false
        dropButton.isEnabled = false
        dropMenuButton.isEnabled = false
        controls.add(buildSplitActionButton(forwardButton, forwardMenuButton, SplitActionStyle.PRIMARY))
        controls.add(buildSplitActionButton(dropButton, dropMenuButton, SplitActionStyle.SECONDARY))
        controls.add(interceptToggle)

        val interceptTop = JPanel(BorderLayout())
        interceptTop.add(controls, BorderLayout.NORTH)
        interceptTop.add(JScrollPane(interceptTable), BorderLayout.CENTER)

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, interceptTop, interceptDetailViewer)
        split.resizeWeight = 0.35
        return split
    }

    internal fun buildHistoryTab(): JSplitPane {
        val historyTop = JPanel(BorderLayout())
        historyTop.add(MimeFilterBar(mimeFilterState) { applyFilters() }, BorderLayout.NORTH)
        historyTop.add(JScrollPane(historyTable), BorderLayout.CENTER)

        val empty = JPanel(BorderLayout())
        val emptyLabel = JLabel(I18n.t("proxy.empty.select_http_history"))
        I18nBinder.bindText(emptyLabel, "proxy.empty.select_http_history")
        empty.add(emptyLabel, BorderLayout.CENTER)
        historyDetailCard.add(empty, historyEmptyCardId)
        historyDetailCard.add(historyDetailViewer, historyContentCardId)
        historyDetailLayout.show(historyDetailCard, historyEmptyCardId)

        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, historyTop, historyDetailCard)
        historySplitPane = split
        historyDefaultDividerSize = split.dividerSize
        split.resizeWeight = 0.35
        hideHistoryDetailDrawer()
        return split
    }

    internal fun hideHistoryDetailDrawer() {
        val split = historySplitPane ?: return
        if (split.dividerSize > 0 && split.height > 0) {
            historyDetailDividerRatio = (split.dividerLocation.toDouble() / split.height.toDouble()).coerceIn(0.1, 0.9)
        }
        split.dividerSize = 0
        SwingUtilities.invokeLater {
            split.setDividerLocation(1.0)
            split.revalidate()
            split.repaint()
        }
    }

    internal fun showHistoryDetailDrawer() {
        val split = historySplitPane ?: return
        if (split.dividerSize > 0) {
            split.revalidate()
            split.repaint()
            return
        }
        split.dividerSize = historyDefaultDividerSize
        SwingUtilities.invokeLater {
            split.setDividerLocation(historyDetailDividerRatio.coerceIn(0.1, 0.9))
            split.revalidate()
            split.repaint()
        }
    }

    internal fun refreshInterceptActionButtons(selectedItem: ProxyInterceptItem?) {
        val hasSelection = selectedItem != null
        val hasAny = interceptModel.rowCount > 0
        forwardButton.isEnabled = hasSelection
        dropButton.isEnabled = hasSelection
        forwardMenuButton.isEnabled = hasAny
        dropMenuButton.isEnabled = hasAny
    }

    internal fun applyInterceptToggleVisual(enabled: Boolean) {
        interceptToggle.text = if (enabled) I18n.t("proxy.intercept.on") else I18n.t("proxy.intercept.off")
        interceptToggle.icon = if (enabled) {
            SignalDotIcon(Color(230, 67, 67))
        } else {
            SignalDotIcon(Color(68, 182, 96))
        }
        interceptToggle.horizontalTextPosition = SwingConstants.RIGHT
        interceptToggle.iconTextGap = 8
        interceptToggle.foreground = UiThemePalette.dockSelectedText
    }

    internal fun selectedModelRow(table: JTable): Int {
        val viewRow = table.selectedRow
        if (viewRow < 0) {
            return -1
        }
        return if (table.rowSorter != null) table.convertRowIndexToModel(viewRow) else viewRow
    }

    internal fun selectedModelRows(table: JTable): List<Int> {
        val viewRows = table.selectedRows
        if (viewRows.isEmpty()) {
            return emptyList()
        }
        return viewRows.map { viewRow ->
            if (table.rowSorter != null) table.convertRowIndexToModel(viewRow) else viewRow
        }.distinct()
    }

    internal fun findInterceptRowById(id: Long): Int = interceptModel.indexOfId(id)

    internal fun findHistoryRowById(id: Long): Int = historyModel.indexOfId(id)

    internal fun findWsHistoryRowById(id: Long): Int = wsHistoryModel.indexOfId(id)
}
