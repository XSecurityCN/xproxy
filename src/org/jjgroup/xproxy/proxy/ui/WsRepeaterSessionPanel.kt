package org.jjgroup.xproxy.proxy.ui

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.RTextScrollPane
import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.fuzzer.request.applySyntax
import org.jjgroup.xproxy.fuzzer.ui.OrangeForwardStyleButton
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.proxy.ws.WsFrameCodec
import org.jjgroup.xproxy.proxy.ws.WsRepeaterClient
import org.jjgroup.xproxy.proxy.ws.WsRepeaterConnection
import org.jjgroup.xproxy.proxy.ws.WsRepeaterInboundFrame
import org.jjgroup.xproxy.proxy.ws.WsRepeaterTarget
import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.HexFormat
import java.util.concurrent.Executors
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * 单个 WebSocket 重放会话面板:参考 Burp Suite 的 WebSocket Repeater。
 *
 * 作为 fuzzer(HTTP 重放)tab 的 WS 覆盖视图:当 HTTP tab 收到 101 响应、或其它模块(代理 WS 历史)
 * 发来 WS 信息时,以本面板覆盖该 tab 的 HTTP 视图,激活 WS 重放布局。
 *
 * - 顶部:目标摘要 + 连接状态 + 操作栏(帧类型 / 发送 / 重连 / 断开 / 清空)。
 * - 上:Payload 编辑器与 Handshake 选项卡(可编辑握手请求,展示握手响应)。
 * - 下:入站帧表(服务端->客户端)+ 选中帧的 payload 详情。
 *
 * 连接在后台单线程执行器上串行执行(连接/发送/读取),UI 更新回 EDT。
 */
class WsRepeaterSessionPanel(
    private val initialTarget: WsRepeaterTarget,
    private val initialOpcode: Int = WsFrameCodec.OPCODE_TEXT,
    private val initialPayload: String = "",
    liveConnection: org.jjgroup.xproxy.proxy.ws.WsLiveConnection? = null
) : JPanel(BorderLayout()) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xproxy-ws-repeater").apply { isDaemon = true }
    }

    /**
     * 连接模式:
     * - attached([attachedLive] != null):复用代理侧原连接,向其写入掩码帧、订阅入站响应;不持有 socket。
     *   原连接断开时 [attachedLive] 仍非空但 [WsLiveConnection.alive] 为 false,用户点"重连"回退到独立连接。
     * - standalone([standaloneConn]):独立新连接([WsRepeaterClient.connect]),与 HTTP 重放一致。
     */
    private var attachedLive: org.jjgroup.xproxy.proxy.ws.WsLiveConnection? = liveConnection
    @Volatile
    private var standaloneConn: WsRepeaterConnection? = null
    private var inboundUnsub: (() -> Unit)? = null
    private var deadUnsub: (() -> Unit)? = null

    /**
     * 内容变更回调(帧增删、载荷/握手编辑、连接状态变化),由 fuzzer tab 系统接到 scheduleFuzzerTabsPersist,
     * 使 WS 模式与已交换帧能被持久化(否则 activateWsMode 后的 WS 状态不会落库)。
     */
    var onContentChanged: (() -> Unit)? = null

    private fun notifyChanged() {
        onContentChanged?.invoke()
    }

    private val targetLabel = JLabel("")
    private val statusLabel = JLabel(I18n.t("wsrepeater.status.disconnected"))

    private val opcodeCombo = JComboBox(DefaultComboBoxModel(arrayOf(
        WsFrameCodec.OPCODE_TEXT,
        WsFrameCodec.OPCODE_BINARY,
        WsFrameCodec.OPCODE_PING,
        WsFrameCodec.OPCODE_CLOSE
    )))

    private val payloadArea = RSyntaxTextArea(8, 60)
    private val handshakeRequestArea = RSyntaxTextArea(10, 60)
    private val handshakeResponseArea = RSyntaxTextArea(6, 60)
    private val inboundDetailArea = RSyntaxTextArea(6, 60)

    private val inboundModel = InboundFrameTableModel()
    private val inboundTable = JTable(inboundModel)

    init {
        opcodeCombo.renderer = SimpleOpcodeRenderer()
        opcodeCombo.selectedItem = initialOpcode
        configureEditors()
        configureTableAlignment()
        payloadArea.text = initialPayload
        handshakeRequestArea.text = initialTarget.handshakeRequest
        applySyntax(handshakeRequestArea, BodyKind.OTHER)
        add(buildStatusBar(), BorderLayout.NORTH)
        add(buildCenterSplit(), BorderLayout.CENTER)
        updateTargetLabel()
        // attached 模式:订阅原连接的入站帧(服务端响应)与断开事件。
        attachToLive()
        // 立即按当前连接态刷新状态(attached 且 alive 时应显示"已连接",而非初始的"未连接")。
        updateConnectionStatus()
    }

    /** 订阅原连接(若处于 attached 模式),入站帧追加到帧表,断开时刷新状态。 */
    private fun attachToLive() {
        val live = attachedLive ?: return
        inboundUnsub = live.subscribeInbound { frame ->
            SwingUtilities.invokeLater {
                inboundModel.add(frame, outbound = false)
                notifyChanged()
            }
        }
        deadUnsub = live.subscribeDead {
            SwingUtilities.invokeLater { updateConnectionStatus() }
        }
    }

    /** 取消订阅原连接(切到 standalone 或关闭时调用);不关闭底层通道(属代理)。 */
    private fun detachFromLive() {
        inboundUnsub?.invoke()
        inboundUnsub = null
        deadUnsub?.invoke()
        deadUnsub = null
        attachedLive = null
    }

    fun displayTitle(): String {
        val scheme = if (initialTarget.tls) "wss" else "ws"
        // 仅展示 scheme://host:port,不带握手 path 与查询参数(与 HTTP 重放目标栏一致,避免过长)。
        return "$scheme://${initialTarget.host}:${initialTarget.port}"
    }

    /** 当前(可能被用户编辑过的)握手请求文本,供持久化与重放重建使用。 */
    fun currentHandshake(): String = handshakeRequestArea.text

    /** 当前选中的帧类型,供持久化。 */
    fun currentOpcode(): Int = (opcodeCombo.selectedItem as? Int) ?: WsFrameCodec.OPCODE_TEXT

    /** 当前载荷文本,供持久化。 */
    fun currentPayload(): String = payloadArea.text

    /** 快照当前已交换的全部帧(出站+入站),供持久化。 */
    fun snapshotFrames(): List<org.jjgroup.xproxy.project.core.FuzzerTabWsFrameRecord> = inboundModel.snapshot()

    /** 从持久化数据恢复帧表(重启后回填之前的交换记录)。 */
    fun restoreFrames(records: List<org.jjgroup.xproxy.project.core.FuzzerTabWsFrameRecord>) {
        inboundModel.clear()
        inboundDetailArea.text = ""
        for (rec in records) {
            val payloadBytes = decodePayloadForRestore(rec.opcode, rec.payload)
            val outbound = rec.direction == "C -> S"
            inboundModel.add(WsRepeaterInboundFrame(rec.opcode, payloadBytes, true), outbound = outbound)
        }
    }

    private fun decodePayloadForRestore(opcode: Int, payloadText: String): ByteArray =
        if (opcode == WsFrameCodec.OPCODE_TEXT || opcode == WsFrameCodec.OPCODE_CONTINUATION) {
            payloadText.toByteArray(Charsets.UTF_8)
        } else {
            runCatching { HexFormat.of().parseHex(payloadText) }.getOrElse { ByteArray(0) }
        }

    /** tab 关闭 / 窗口关闭时调用:取消订阅原连接、关闭独立连接并终止执行器。 */
    fun shutdown() {
        detachFromLive()
        standaloneConn?.let { runCatching { it.close() } }
        standaloneConn = null
        executor.shutdownNow()
    }

    private fun configureTableAlignment() {
        // 全部列(含 #、Length 等数值列)默认左对齐,与代理历史表一致。
        val leftRenderer = DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.LEFT }
        (inboundTable.tableHeader.defaultRenderer as? DefaultTableCellRenderer)?.horizontalAlignment = SwingConstants.LEFT
        for (c in 0 until inboundTable.columnModel.columnCount) {
            inboundTable.columnModel.getColumn(c).cellRenderer = leftRenderer
        }
    }

    private fun configureEditors() {
        listOf(payloadArea, handshakeRequestArea, handshakeResponseArea, inboundDetailArea).forEach {
            it.lineWrap = true
            it.wrapStyleWord = true
            it.highlightCurrentLine = true
            val isDark = (javax.swing.UIManager.get("laf.dark") as? Boolean) == true
            it.currentLineHighlightColor = if (isDark) Color(64, 68, 75) else Color(230, 230, 230)
        }
        payloadArea.isEditable = true
        handshakeRequestArea.isEditable = true
        handshakeResponseArea.isEditable = false
        inboundDetailArea.isEditable = false
        applySyntax(payloadArea, BodyKind.OTHER)
        applySyntax(handshakeResponseArea, BodyKind.OTHER)
        applySyntax(inboundDetailArea, BodyKind.OTHER)
        // 载荷/握手编辑、帧类型切换均触发持久化(scheduleFuzzerTabsPersist 已去抖)。
        val docListener = object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { notifyChanged() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { notifyChanged() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { notifyChanged() }
        }
        payloadArea.document.addDocumentListener(docListener)
        handshakeRequestArea.document.addDocumentListener(docListener)
        opcodeCombo.addActionListener { notifyChanged() }
    }

    private fun buildStatusBar(): JPanel {
        val bar = JPanel(BorderLayout(8, 4))
        bar.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)

        // 左栏:目标： <ws url> <连接状态>。
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val targetPrefix = JLabel(I18n.t("wsrepeater.target"))
        targetPrefix.foreground = UiThemePalette.mutedText
        targetLabel.foreground = UiThemePalette.mutedText
        statusLabel.foreground = UiThemePalette.mutedText
        left.add(targetPrefix)
        left.add(targetLabel)
        left.add(statusLabel)
        bar.add(left, BorderLayout.WEST)

        // 右栏:帧类型 + 操作按钮(发送为主按钮,与 HTTP 重放一致的橙色样式)。
        val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        val opcodeLabel = JLabel(I18n.t("wsrepeater.opcode"))
        val sendButton = OrangeForwardStyleButton(I18n.t("wsrepeater.send"))
        val reconnectButton = JButton(I18n.t("wsrepeater.reconnect"))
        val disconnectButton = JButton(I18n.t("wsrepeater.disconnect"))
        val clearButton = JButton(I18n.t("common.clear"))
        // 与 HTTP 重放一致:发送按钮尺寸对齐到同级普通按钮(垂直边距 0 的橙色按钮 preferred 高度会偏矮)。
        sendButton.apply {
            preferredSize = reconnectButton.preferredSize
            minimumSize = reconnectButton.minimumSize
            maximumSize = reconnectButton.maximumSize
        }
        sendButton.addActionListener { send() }
        reconnectButton.addActionListener { reconnect() }
        disconnectButton.addActionListener { disconnect() }
        clearButton.addActionListener {
            inboundModel.clear()
            inboundDetailArea.text = ""
            notifyChanged()
        }
        toolbar.add(opcodeLabel)
        toolbar.add(opcodeCombo)
        toolbar.add(reconnectButton)
        toolbar.add(disconnectButton)
        toolbar.add(clearButton)
        toolbar.add(sendButton)
        bar.add(toolbar, BorderLayout.EAST)
        return bar
    }

    private fun buildCenterSplit(): JSplitPane {
        val composeTabs = JTabbedPane()
        composeTabs.addTab(I18n.t("wsrepeater.tab.payload"), RTextScrollPane(payloadArea))

        val handshakeSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        handshakeSplit.resizeWeight = 0.5
        val reqPanel = JPanel(BorderLayout())
        reqPanel.add(JLabel(I18n.t("wsrepeater.handshake_request")), BorderLayout.NORTH)
        reqPanel.add(RTextScrollPane(handshakeRequestArea), BorderLayout.CENTER)
        val respPanel = JPanel(BorderLayout())
        respPanel.add(JLabel(I18n.t("wsrepeater.handshake_response")), BorderLayout.NORTH)
        respPanel.add(RTextScrollPane(handshakeResponseArea), BorderLayout.CENTER)
        handshakeSplit.leftComponent = reqPanel
        handshakeSplit.rightComponent = respPanel
        composeTabs.addTab(I18n.t("wsrepeater.tab.handshake"), handshakeSplit)

        val inboundSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        inboundSplit.resizeWeight = 0.5
        inboundSplit.leftComponent = JScrollPane(inboundTable)
        val detailPanel = JPanel(BorderLayout())
        detailPanel.add(JLabel(I18n.t("wsrepeater.inbound_detail")), BorderLayout.NORTH)
        detailPanel.add(RTextScrollPane(inboundDetailArea), BorderLayout.CENTER)
        inboundSplit.rightComponent = detailPanel

        inboundTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val row = inboundTable.selectedRow
                if (row >= 0) {
                    inboundModel.getAt(inboundTable.convertRowIndexToModel(row))?.let { frame ->
                        inboundDetailArea.text = frame.displayText
                        inboundDetailArea.caretPosition = 0
                    }
                }
            }
        }

        // 两栏布局:左栏整栏为 composeTabs(载荷/握手),右栏整栏为入站帧表 + 帧详情(上下堆叠)。
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, composeTabs, inboundSplit)
        // 左:右 = 1:2(左栏占 1/3);resizeWeight 仅管额外空间分配,初始比例需在首次显示后设定。
        split.resizeWeight = 1.0 / 3.0
        split.preferredSize = Dimension(900, 480)
        // setDividerLocation 在首次显示前(size=0)会被忽略,用一次性 HierarchyListener 等到真正显示时再设。
        split.addHierarchyListener(object : java.awt.event.HierarchyListener {
            override fun hierarchyChanged(e: java.awt.event.HierarchyEvent) {
                if (split.isShowing && split.width > 0) {
                    split.setDividerLocation(1.0 / 3.0)
                    split.removeHierarchyListener(this)
                }
            }
        })
        return split
    }

    // --- actions ---

    private fun send() {
        val opcode = opcodeCombo.selectedItem as? Int ?: WsFrameCodec.OPCODE_TEXT
        val payloadBytes = encodePayloadForSend(opcode)
        if (payloadBytes == null) {
            JOptionPane.showMessageDialog(this, I18n.t("wsrepeater.error.invalid_hex"), I18n.t("wsrepeater.send"), JOptionPane.ERROR_MESSAGE)
            return
        }
        executor.execute {
            val live = attachedLive
            if (live != null) {
                // attached:复用代理侧原连接,向其写入掩码帧;入站响应由订阅者异步追加。
                if (!live.alive) {
                    reportError(RuntimeException(I18n.t("wsrepeater.error.live_dead")))
                    return@execute
                }
                val ok = live.sendFrame(opcode, payloadBytes)
                SwingUtilities.invokeLater {
                    inboundModel.add(WsRepeaterInboundFrame(opcode, payloadBytes, true), outbound = true)
                    inboundDetailArea.text = ""
                    notifyChanged()
                    updateConnectionStatus()
                }
                if (!ok) {
                    reportError(RuntimeException(I18n.t("wsrepeater.error.live_dead")))
                }
                return@execute
            }
            // standalone:独立新连接(与 HTTP 重放一致)。
            try {
                val conn = ensureStandaloneConnected() ?: return@execute
                conn.sendFrame(opcode, payloadBytes)
                SwingUtilities.invokeLater {
                    inboundModel.add(WsRepeaterInboundFrame(opcode, payloadBytes, true), outbound = true)
                    inboundDetailArea.text = ""
                    notifyChanged()
                }
                conn.readInboundFrames(
                    onFrame = { frame ->
                        SwingUtilities.invokeLater {
                            inboundModel.add(frame, outbound = false)
                            notifyChanged()
                        }
                    },
                    shouldCancel = { false },
                    idleTimeoutMs = 600L,
                    maxDurationMs = 3000L
                )
                SwingUtilities.invokeLater { updateConnectionStatus() }
            } catch (ex: Exception) {
                reportError(ex)
                standaloneConn?.let { runCatching { it.close() } }
                standaloneConn = null
                SwingUtilities.invokeLater { updateConnectionStatus() }
            }
        }
    }

    private fun reconnect() {
        executor.execute {
            // 切到 standalone:取消原连接订阅 + 关闭既有独立连接,再建立新独立连接。
            detachFromLive()
            standaloneConn?.let { runCatching { it.close() } }
            standaloneConn = null
            SwingUtilities.invokeLater {
                updateConnectionStatus()
                handshakeResponseArea.text = ""
            }
            try {
                val target = currentTarget()
                val conn = WsRepeaterClient.connect(target) { false }
                standaloneConn = conn
                SwingUtilities.invokeLater {
                    handshakeResponseArea.text = conn.handshakeResponseRaw
                    handshakeResponseArea.caretPosition = 0
                    updateConnectionStatus()
                }
            } catch (ex: Exception) {
                reportError(ex)
            }
        }
    }

    private fun disconnect() {
        executor.execute {
            // attached:仅取消订阅(底层通道属代理,不关闭);standalone:关闭独立连接。
            detachFromLive()
            standaloneConn?.let { runCatching { it.close() } }
            standaloneConn = null
            SwingUtilities.invokeLater { updateConnectionStatus() }
        }
    }

    private fun ensureStandaloneConnected(): WsRepeaterConnection? {
        standaloneConn?.let { if (!it.closed) return it }
        val target = currentTarget()
        val conn = WsRepeaterClient.connect(target) { false }
        standaloneConn = conn
        SwingUtilities.invokeLater {
            handshakeResponseArea.text = conn.handshakeResponseRaw
            handshakeResponseArea.caretPosition = 0
            updateConnectionStatus()
        }
        return conn
    }

    private fun currentTarget(): WsRepeaterTarget {
        val editedHandshake = handshakeRequestArea.text
        return initialTarget.copy(handshakeRequest = editedHandshake)
    }

    private fun encodePayloadForSend(opcode: Int): ByteArray? {
        val text = payloadArea.text
        return when (opcode) {
            WsFrameCodec.OPCODE_TEXT, WsFrameCodec.OPCODE_PING -> text.toByteArray(Charsets.UTF_8)
            WsFrameCodec.OPCODE_CLOSE -> ByteArray(0)
            WsFrameCodec.OPCODE_BINARY -> {
                val hex = text.replace(Regex("[\\s,]"), "")
                if (hex.isEmpty()) ByteArray(0)
                else try {
                    HexFormat.of().parseHex(hex)
                } catch (_: Exception) {
                    null
                }
            }
            else -> text.toByteArray(Charsets.ISO_8859_1)
        }
    }

    private fun updateTargetLabel() {
        targetLabel.text = displayTitle()
    }

    private fun updateConnectionStatus() {
        val live = attachedLive
        val standalone = standaloneConn
        val connected = when {
            live != null -> live.alive
            standalone != null -> !standalone.closed
            else -> false
        }
        if (connected) {
            statusLabel.text = I18n.t("wsrepeater.status.connected")
            statusLabel.foreground = Color(46, 160, 67)
        } else {
            statusLabel.text = I18n.t("wsrepeater.status.disconnected")
            statusLabel.foreground = UiThemePalette.mutedText
        }
    }

    private fun reportError(ex: Exception) {
        SwingUtilities.invokeLater {
            JOptionPane.showMessageDialog(
                this,
                ex.message ?: ex.toString(),
                I18n.t("wsrepeater.error.title"),
                JOptionPane.ERROR_MESSAGE
            )
        }
    }

    // --- inbound table model ---

    private class InboundFrameTableModel : AbstractTableModel() {
        private val rows = ArrayList<InboundRow>()
        private val columns = listOf(
            "wsrepeater.column.index",
            "wsrepeater.column.direction",
            "wsrepeater.column.opcode",
            "wsrepeater.column.length",
            "wsrepeater.column.preview"
        )

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = I18n.t(columns[column])

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Integer::class.java
            3 -> java.lang.Integer::class.java
            else -> String::class.java
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.index
                1 -> row.direction
                2 -> WsFrameCodec.opcodeName(row.frame.opcode)
                3 -> row.frame.payload.size
                4 -> row.preview
                else -> ""
            }
        }

        fun add(frame: WsRepeaterInboundFrame, outbound: Boolean) {
            val index = rows.size + 1
            val direction = if (outbound) "C -> S" else "S -> C"
            val preview = frame.displayText.replace("\r", " ").replace("\n", " ").take(200)
            rows.add(InboundRow(index, direction, frame, preview))
            fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
        }

        fun getAt(row: Int): WsRepeaterInboundFrame? = rows.getOrNull(row)?.frame

        /** 快照当前所有帧(供持久化);ArrayList(rows) 复制底层数组,避免与 EDT 并发 add 时的 CME。 */
        fun snapshot(): List<org.jjgroup.xproxy.project.core.FuzzerTabWsFrameRecord> =
            ArrayList(rows).map { org.jjgroup.xproxy.project.core.FuzzerTabWsFrameRecord(it.direction, it.frame.opcode, it.frame.displayText) }

        fun clear() {
            rows.clear()
            fireTableDataChanged()
        }
    }

    private class InboundRow(
        val index: Int,
        val direction: String,
        val frame: WsRepeaterInboundFrame,
        val preview: String
    )

    private class SimpleOpcodeRenderer : javax.swing.ListCellRenderer<Any> {
        private val delegate = javax.swing.DefaultListCellRenderer()
        override fun getListCellRendererComponent(
            list: javax.swing.JList<out Any>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val label = delegate.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            (label as? javax.swing.JLabel)?.text = (value as? Int)?.let { WsFrameCodec.opcodeName(it) } ?: ""
            return label
        }
    }
}
