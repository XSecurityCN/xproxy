package org.jjgroup.xproxy.ui.table

import org.jjgroup.xproxy.AttackHandler
import org.jjgroup.xproxy.Request
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.fuzzer.request.applySyntax
import org.jjgroup.xproxy.fuzzer.request.detectBodyKind
import org.jjgroup.xproxy.fuzzer.request.parseHeaders
import org.jjgroup.xproxy.fuzzer.request.splitMessage
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.kits.core.HttpViewerToolContext
import org.jjgroup.xproxy.settings.core.ResponsePrettySettings
import org.jjgroup.xproxy.ui.http.HttpRequestResponseViewer

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.RowFilter
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.BevelBorder
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter
import javax.swing.event.TableModelEvent

class UpdateStatusbar(val message: JLabel, val handler: AttackHandler) : ActionListener {
    lateinit var timer: Timer

    override fun actionPerformed(e: ActionEvent?) {
        if (handler.hasFinished() || SwingUtilities.getWindowAncestor(message) == null) {
            timer.stop()
        }

        message.text = handler.statusString()
    }

}

interface OutputHandler {
    fun add(req: Request)
    fun getAllRquests(): List<Request>
}

class ConsolePrinter : OutputHandler {
    private val requestID = AtomicInteger(0)

    init {
        Utils.out("ID | Word | Status | Wordcount | Length | Time")
    }

    override fun add(req: Request) {
        Utils.out("${requestID.incrementAndGet()} | ${req.words.joinToString(separator = "/")} | ${req.code} | ${req.wordcount} | ${req.length} | ${req.time}")
    }

    override fun getAllRquests(): List<Request> {
        return emptyList()
    }
}

class RequestTable(
    val handler: AttackHandler,
    initialRequests: List<Request> = emptyList(),
    private val onRequestAdded: ((Request) -> Unit)? = null,
    private val onSendToFuzzer: ((String) -> Unit)? = null,
    private val onSendToCodec: ((String, String?) -> Unit)? = null
): JPanel(), OutputHandler {
    val model = RequestTableModel()
    val issueTable = object : JTable(model) {
        override fun getScrollableTracksViewportWidth(): Boolean {
            // 视口宽于列总宽时填满(消除右侧空白),窄于时允许横向滚动。
            val viewport = parent as? java.awt.Component ?: return false
            return viewport.width >= preferredSize.width
        }
    }
    private val detailsViewer = HttpRequestResponseViewer(
        requestEditable = false,
        responseEditable = false,
        responseRenderVisible = true,
        onSendToFuzzer = onSendToFuzzer,
        onSendToCodec = onSendToCodec,
        toolContext = HttpViewerToolContext.TABLE
    )
    private val historyFilterField = PlaceholderTextField(26, I18n.t("request_table.history_filter"))
    private lateinit var sorter: TableRowSorter<RequestTableModel>
    private val requestListView: JScrollPane
    private var currentRequest: Request? = null
    private var firstEntry = true
    private val lock = Object()
    private var descending = true
    private var initialized = false
    private var sortModifiedAfterInit = false
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xproxy-fuzzer-result-persist").apply { isDaemon = true }
    }
    private val anomalyRankRenderer = object : DefaultTableCellRenderer() {
        private val numberFormat = NumberFormat.getNumberInstance()

        init {
            horizontalAlignment = SwingConstants.LEFT
        }

        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val formattedValue = if (value is Number) {
                numberFormat.format(value)
            } else {
                value
            }
            return super.getTableCellRendererComponent(table, formattedValue, isSelected, hasFocus, row, column)
        }
    }

    private fun applyAnomalyRankColumnRenderer() {
        val anomalyColumn = model.anomalyRankColumnIndex()
        if (anomalyColumn < issueTable.columnModel.columnCount) {
            issueTable.columnModel.getColumn(anomalyColumn).cellRenderer = anomalyRankRenderer
        }
    }

    // time 列底层值是微秒(与 req.time 脚本 API / 持久化一致),渲染时换算为秒显示,排序仍按原始微秒数值。
    private val timeColumnRenderer = object : DefaultTableCellRenderer() {
        init {
            horizontalAlignment = SwingConstants.LEFT
        }

        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val formattedValue = if (value is Number) {
                String.format("%.3f", value.toLong() / 1_000_000.0) + "s"
            } else {
                value
            }
            return super.getTableCellRendererComponent(table, formattedValue, isSelected, hasFocus, row, column)
        }
    }

    private fun applyTimeColumnRenderer() {
        val timeColumn = model.timeColumnIndex()
        if (timeColumn in 0 until issueTable.columnModel.columnCount) {
            issueTable.columnModel.getColumn(timeColumn).cellRenderer = timeColumnRenderer
        }
    }

    private fun configureIssueTableAlignment() {
        val leftRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        (issueTable.tableHeader.defaultRenderer as? DefaultTableCellRenderer)?.horizontalAlignment = SwingConstants.LEFT
        // 用表级默认渲染器保证全表左对齐:fireTableStructureChanged 只重建列、不清除表级默认渲染器,
        // 因此列重建后数字列不会回退为默认右对齐。Long 列只有 time,复用 timeColumnRenderer(左对齐 + 秒格式)。
        issueTable.setDefaultRenderer(Any::class.java, leftRenderer)
        issueTable.setDefaultRenderer(java.lang.Number::class.java, leftRenderer)
        issueTable.setDefaultRenderer(java.lang.Integer::class.java, leftRenderer)
        issueTable.setDefaultRenderer(java.lang.Long::class.java, timeColumnRenderer)
        issueTable.setDefaultRenderer(String::class.java, leftRenderer)
    }

    fun clear() {
        model.clear()
    }

    fun hasSortBeenModified(): Boolean = sortModifiedAfterInit

    fun setCurrentRequest(req: Request?) {
        synchronized(lock) {
            currentRequest = req
            val rawRequest = req?.getRequest().orEmpty()
            val rawResponse = req?.response.orEmpty()

            detailsViewer.showRequest(rawRequest)
            detailsViewer.showResponse(rawResponse)

            val requestKind = if (rawRequest.isBlank()) {
                BodyKind.NONE
            } else {
                val parsed = splitMessage(rawRequest)
                val headers = parseHeaders(parsed.headers)
                detectBodyKind(headers, parsed.body)
            }
            applySyntax(detailsViewer.requestRawArea, requestKind)
            applySyntax(detailsViewer.requestPrettyArea, requestKind)
        }
    }

    private fun applyHistoryFilter() {
        val keyword = historyFilterField.text.trim()
        sorter.rowFilter = if (keyword.isEmpty()) {
            null
        } else {
            object : RowFilter<RequestTableModel, Int>() {
                override fun include(entry: Entry<out RequestTableModel, out Int>): Boolean {
                    val columnCount = entry.valueCount
                    for (column in 0 until columnCount) {
                        val cellText = entry.getStringValue(column)
                        if (cellText.contains(keyword, ignoreCase = true)) {
                            return true
                        }
                    }
                    return false
                }
            }
        }
    }

    fun setSortOrder(column: Int, descending: Boolean) {
        if (initialized) {
            sortModifiedAfterInit = true
        }
        this.descending = descending
        val order = if (descending) javax.swing.SortOrder.DESCENDING else javax.swing.SortOrder.ASCENDING
        issueTable.rowSorter.sortKeys = listOf(javax.swing.RowSorter.SortKey(column, order))
    }

    internal fun autoSortByAnomalyRank() {
        descending = true
        val order = javax.swing.SortOrder.DESCENDING
        issueTable.rowSorter.sortKeys = listOf(javax.swing.RowSorter.SortKey(model.anomalyRankColumnIndex(), order))
    }

    init {
        sorter = object : TableRowSorter<RequestTableModel>(model) {
            override fun toggleSortOrder(column: Int) {
                sortModifiedAfterInit = true
                val sortKeys = this.sortKeys
                if (sortKeys.isEmpty() || sortKeys[0].column != column) {
                    this.sortKeys = listOf(javax.swing.RowSorter.SortKey(column, javax.swing.SortOrder.DESCENDING))
                } else if (sortKeys[0].sortOrder == javax.swing.SortOrder.DESCENDING) {
                    this.sortKeys = listOf(javax.swing.RowSorter.SortKey(column, javax.swing.SortOrder.ASCENDING))
                } else {
                    this.sortKeys = emptyList()
                }
            }
        }
        issueTable.rowSorter = sorter
        setSortOrder(0, true)
        configureIssueTableAlignment()
        I18nBinder.bindTableHeaders(issueTable)

        applyAnomalyRankColumnRenderer()
        applyTimeColumnRenderer()

        model.addTableModelListener { event ->
            if (event.firstRow == TableModelEvent.HEADER_ROW) {
                // fireTableStructureChanged 会重建列并丢弃列上的 cellRenderer,
                // 需要重新应用左对齐 + anomaly/time 自定义渲染器,否则数字列回退为默认右对齐。
                configureIssueTableAlignment()
                applyAnomalyRankColumnRenderer()
                applyTimeColumnRenderer()
            }
        }

        issueTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS

        issueTable.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val selectedRow = issueTable.selectedRow
            if (selectedRow < 0) return@addListSelectionListener
            val req = model.getRequest(issueTable.convertRowIndexToModel(selectedRow))
            setCurrentRequest(req)
        }

        requestListView = JScrollPane(issueTable)

        val frameSize = Utils.getIntruderFrameSize()
        historyFilterField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(java.awt.Color(198, 198, 204), 1),
            BorderFactory.createEmptyBorder(3, 8, 3, 6)
        )
        historyFilterField.addActionListener { applyHistoryFilter() }
        val historySearchButton = JButton(SearchGlyphIcon()).apply {
            toolTipText = I18n.t("request_table.search_history")
            isFocusPainted = false
            isContentAreaFilled = false
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            margin = java.awt.Insets(0, 0, 0, 0)
            addActionListener { applyHistoryFilter() }
        }

        val historyPanel = JPanel(BorderLayout())
        val historyTop = JPanel(BorderLayout())
        val historyLabel = JLabel(I18n.t("request_table.http_history"))
        I18nBinder.bindText(historyLabel, "request_table.http_history")
        I18nBinder.bind {
            historyFilterField.placeholder = I18n.t("request_table.history_filter")
            historyFilterField.repaint()
            historySearchButton.toolTipText = I18n.t("request_table.search_history")
        }
        historyLabel.border = BorderFactory.createEmptyBorder(0, 0, 4, 8)
        historyTop.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        historyTop.add(historyLabel, BorderLayout.WEST)
        val historySearchBox = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(historyFilterField, BorderLayout.CENTER)
            add(historySearchButton, BorderLayout.EAST)
        }
        historyTop.add(historySearchBox, BorderLayout.EAST)
        historyPanel.add(historyTop, BorderLayout.NORTH)
        historyPanel.add(requestListView, BorderLayout.CENTER)

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, historyPanel, detailsViewer)
        requestListView.preferredSize = Dimension(frameSize.width, frameSize.height/2)
        splitPane.setDividerLocation(0.2)
        splitPane.preferredSize = Dimension(frameSize.width, frameSize.height)

        this.layout = BorderLayout()
        this.add(splitPane, BorderLayout.CENTER)
        splitPane.resizeWeight = 0.5

        val statusPanel = JPanel()
        statusPanel.border = BevelBorder(BevelBorder.LOWERED)
        this.add(statusPanel, BorderLayout.SOUTH)
        statusPanel.preferredSize = Dimension(this.width, 30)
        statusPanel.layout = BoxLayout(statusPanel, BoxLayout.X_AXIS)
        val statusLabel = JLabel("")
        statusLabel.horizontalAlignment = SwingConstants.LEFT
        statusPanel.add(statusLabel)

        val updateStatusbar = UpdateStatusbar(statusLabel, handler)
        val panelUpdater = Timer(1000, updateStatusbar)
        updateStatusbar.timer = panelUpdater
        panelUpdater.start()

        val menu = JPopupMenu()
        val sendToFuzzerButton = JMenuItem(I18n.t("menu.send_to_fuzzer"))
        val sendToCodecMenu = JMenu(I18n.t("menu.use_codec"))
        I18nBinder.bindText(sendToFuzzerButton, "menu.send_to_fuzzer")
        I18nBinder.bindText(sendToCodecMenu, "menu.use_codec")
        sendToFuzzerButton.isEnabled = onSendToFuzzer != null
        sendToFuzzerButton.addActionListener {
            val requestRaw = currentRequest?.getRequest().orEmpty()
            if (requestRaw.isNotBlank()) {
                onSendToFuzzer?.invoke(requestRaw)
            }
        }
        menu.add(sendToFuzzerButton)
        menu.add(sendToCodecMenu)

        fun rebuildSendToCodecMenu() {
            sendToCodecMenu.removeAll()
            val requestRaw = currentRequest?.getRequest().orEmpty()
            if (requestRaw.isBlank() || (onSendToCodec == null && !org.jjgroup.xproxy.codec.core.CodecHub.hasReceiver())) {
                sendToCodecMenu.isEnabled = false
                return
            }
            val defaultItem = JMenuItem(I18n.t("menu.send_to_default_codec"))
            defaultItem.addActionListener { onSendToCodec?.invoke(requestRaw, null) ?: org.jjgroup.xproxy.codec.core.CodecHub.send(requestRaw, null) }
            sendToCodecMenu.add(defaultItem)
            val tabTitles = org.jjgroup.xproxy.codec.core.CodecHub.tabTitles()
            if (tabTitles.size > 1) {
                sendToCodecMenu.addSeparator()
                tabTitles.drop(1).forEach { tabTitle ->
                    val item = JMenuItem(tabTitle)
                    item.addActionListener { onSendToCodec?.invoke(requestRaw, tabTitle) ?: org.jjgroup.xproxy.codec.core.CodecHub.send(requestRaw, tabTitle) }
                    sendToCodecMenu.add(item)
                }
            }
            sendToCodecMenu.isEnabled = true
        }

        menu.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {
                rebuildSendToCodecMenu()
            }

            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {
            }

            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {
            }
        })

        issueTable.componentPopupMenu = menu
        initialized = true

        if (initialRequests.isNotEmpty()) {
            synchronized(lock) {
                for (request in initialRequests) {
                    model.addRow(request)
                }
            }
            val first = initialRequests.first()
            if (shouldAutoPreview(first)) {
                setCurrentRequest(first)
            }
            firstEntry = false
        }
    }

    private fun getSelectedRequests(): ArrayList<Request> {
        synchronized(lock) {
            val requests = ArrayList<Request>()
            val table = issueTable.model as RequestTableModel
            for (index in issueTable.selectedRows) {
                val req = table.getRequest(issueTable.convertRowIndexToModel(index))
                if (req != null) {
                    requests.add(req)
                }
            }
            return requests
        }
    }

    override fun add(req: Request) {
        onRequestAdded?.let { saver ->
            persistExecutor.execute {
                saver(req)
            }
        }

        val updateUi = {
            synchronized(lock) {
                model.addRow(req)
            }
            if (firstEntry) {
                if (shouldAutoPreview(req)) {
                    setCurrentRequest(req)
                }
                firstEntry = false
            }
        }

        if (SwingUtilities.isEventDispatchThread()) {
            updateUi()
        } else {
            SwingUtilities.invokeLater(updateUi)
        }
    }

    fun shutdown() {
        persistExecutor.shutdownNow()
    }

    private fun shouldAutoPreview(req: Request): Boolean {
        val threshold = ResponsePrettySettings.getAutoHighlightMaxBytes().coerceAtLeast(1024)
        val responseSize = req.response?.length ?: 0
        return responseSize <= threshold
    }

    override fun getAllRquests(): List<Request> {
        return model.getAllRequests()
    }
}
