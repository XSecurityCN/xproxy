package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRule
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceRule
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableCellRenderer

class ProxyOptionsPanel(
    initialRules: List<ProxyMatchReplaceRule>,
    initialInterceptRules: List<ProxyInterceptRule>,
    initialBindHost: String,
    initialBindPort: Int,
    private val onStartProxyRequested: (String, Int) -> Unit,
    private val onStopProxyRequested: () -> Unit,
    private val onRulesChanged: (List<ProxyMatchReplaceRule>) -> Unit,
    private val onInterceptRulesChanged: (List<ProxyInterceptRule>) -> Unit
) : JPanel(BorderLayout()) {

    private val ruleModel = ProxyMatchReplaceRuleTableModel()
    private val ruleTable = JTable(ruleModel)
    private val interceptRuleModel = ProxyInterceptRuleTableModel()
    private val interceptRuleTable = JTable(interceptRuleModel)

    private val addButton = JButton(I18n.t("common.add"))
    private val editButton = JButton(I18n.t("common.edit"))
    private val removeButton = JButton(I18n.t("common.remove"))
    private val upButton = JButton(I18n.t("common.up"))
    private val downButton = JButton(I18n.t("common.down"))
    private val addInterceptRuleButton = JButton(I18n.t("common.add"))
    private val editInterceptRuleButton = JButton(I18n.t("common.edit"))
    private val removeInterceptRuleButton = JButton(I18n.t("common.remove"))
    private val upInterceptRuleButton = JButton(I18n.t("common.up"))
    private val downInterceptRuleButton = JButton(I18n.t("common.down"))
    private val statusLabel = JLabel(I18n.t("proxy.status.stopped"))
    private val hostField = JTextField(initialBindHost, 12)
    private val portField = JTextField(initialBindPort.toString(), 6)
    private val startStopButton = JButton(I18n.t("proxy.action.start"))
    private var running = false
    private var statusState: ListenerStatusState = ListenerStatusState.Stopped

    init {
        ruleModel.setRules(initialRules)
        interceptRuleModel.setRules(initialInterceptRules)

        ruleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        ruleTable.fillsViewportHeight = true
        ruleTable.autoCreateRowSorter = false
        ruleTable.columnModel.getColumn(0).preferredWidth = 70
        ruleTable.columnModel.getColumn(0).maxWidth = 90
        ruleTable.columnModel.getColumn(1).preferredWidth = 130
        ruleTable.columnModel.getColumn(2).preferredWidth = 110
        ruleTable.columnModel.getColumn(3).preferredWidth = 260
        ruleTable.columnModel.getColumn(4).preferredWidth = 260
        ruleTable.columnModel.getColumn(5).preferredWidth = 92
        ruleTable.columnModel.getColumn(6).preferredWidth = 90
        interceptRuleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        interceptRuleTable.fillsViewportHeight = true
        interceptRuleTable.autoCreateRowSorter = false
        interceptRuleTable.columnModel.getColumn(0).preferredWidth = 70
        interceptRuleTable.columnModel.getColumn(0).maxWidth = 90
        interceptRuleTable.columnModel.getColumn(1).preferredWidth = 130
        interceptRuleTable.columnModel.getColumn(2).preferredWidth = 120
        interceptRuleTable.columnModel.getColumn(3).preferredWidth = 260
        interceptRuleTable.columnModel.getColumn(4).preferredWidth = 160
        interceptRuleTable.columnModel.getColumn(5).preferredWidth = 90
        configureRuleTableAlignment()

        add(buildMain(), BorderLayout.CENTER)
        bindStaticTexts()

        bindActions()
        publishRulesChanged()
        publishInterceptRulesChanged()
    }

    private fun configureRuleTableAlignment() {
        val leftRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        listOf(ruleTable, interceptRuleTable).forEach { table ->
            (table.tableHeader.defaultRenderer as? DefaultTableCellRenderer)?.horizontalAlignment = SwingConstants.LEFT
            for (columnIndex in 1 until table.columnModel.columnCount) {
                table.columnModel.getColumn(columnIndex).cellRenderer = leftRenderer
            }
        }
    }

    fun currentRules(): List<ProxyMatchReplaceRule> = ruleModel.rulesSnapshot()

    fun currentBindHost(): String = hostField.text.trim().ifEmpty { "127.0.0.1" }

    fun setBindPortText(port: Int) {
        portField.text = port.toString()
    }

    fun setListeningState(isRunning: Boolean, message: String) {
        running = isRunning
        statusState = ListenerStatusState.fromRuntimeMessage(isRunning, message)
        statusLabel.text = statusState.text()
        startStopButton.text = if (isRunning) I18n.t("proxy.action.stop") else I18n.t("proxy.action.start")
        hostField.isEnabled = !isRunning
        portField.isEnabled = !isRunning
        startStopButton.isEnabled = true
    }

    fun setListeningPending(message: String) {
        statusState = ListenerStatusState.fromRuntimeMessage(running, message)
        statusLabel.text = statusState.text()
        startStopButton.isEnabled = false
    }

    fun currentStatusText(): String = statusLabel.text

    private fun bindStaticTexts() {
        I18nBinder.bindText(addButton, "common.add")
        I18nBinder.bindText(editButton, "common.edit")
        I18nBinder.bindText(removeButton, "common.remove")
        I18nBinder.bindText(upButton, "common.up")
        I18nBinder.bindText(downButton, "common.down")
        I18nBinder.bindText(addInterceptRuleButton, "common.add")
        I18nBinder.bindText(editInterceptRuleButton, "common.edit")
        I18nBinder.bindText(removeInterceptRuleButton, "common.remove")
        I18nBinder.bindText(upInterceptRuleButton, "common.up")
        I18nBinder.bindText(downInterceptRuleButton, "common.down")
        I18nBinder.bind {
            startStopButton.text = if (running) I18n.t("proxy.action.stop") else I18n.t("proxy.action.start")
            statusLabel.text = statusState.text()
        }
        I18nBinder.bindTableHeaders(ruleTable)
        I18nBinder.bindTableHeaders(interceptRuleTable)
    }

    private fun buildMain(): JPanel {
        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(0, 10, 10, 10)
        }

        val listenSection = JPanel(BorderLayout(0, 6))
        val listenHeader = JPanel(BorderLayout())
        val listenTitle = JLabel(I18n.t("proxy.listener.title")).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        }
        I18nBinder.bindText(listenTitle, "proxy.listener.title")
        val listenDesc = JLabel(I18n.t("proxy.listener.description"))
        I18nBinder.bindText(listenDesc, "proxy.listener.description")
        val listenStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(listenTitle)
            add(listenDesc)
        }
        listenHeader.add(listenStack, BorderLayout.WEST)

        val listenPanel = buildListenPanel()
        listenSection.add(listenHeader, BorderLayout.NORTH)
        listenSection.add(listenPanel, BorderLayout.CENTER)

        val matchReplacePanel = JPanel(BorderLayout(0, 6))
        val matchReplaceHeader = JPanel(BorderLayout())
        val matchReplaceTitle = JLabel(I18n.t("proxy.match_replace.title")).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        }
        I18nBinder.bindText(matchReplaceTitle, "proxy.match_replace.title")
        val matchReplaceDesc = JLabel(I18n.t("proxy.match_replace.description"))
        I18nBinder.bindText(matchReplaceDesc, "proxy.match_replace.description")
        val matchReplaceStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(matchReplaceTitle)
            add(matchReplaceDesc)
        }
        matchReplaceHeader.add(matchReplaceStack, BorderLayout.WEST)

        val matchReplaceBody = JPanel(BorderLayout())
        val topPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("")
        }

        val left = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(addButton)
            add(Box.createVerticalStrut(8))
            add(editButton)
            add(Box.createVerticalStrut(8))
            add(removeButton)
            add(Box.createVerticalStrut(16))
            add(upButton)
            add(Box.createVerticalStrut(8))
            add(downButton)
        }

        val fixedWidth = 96
        for (button in arrayOf(addButton, editButton, removeButton, upButton, downButton)) {
            val size = button.preferredSize
            button.minimumSize = java.awt.Dimension(fixedWidth, size.height)
            button.maximumSize = java.awt.Dimension(fixedWidth, size.height)
            button.alignmentX = LEFT_ALIGNMENT
        }

        val tableScroll = JScrollPane(ruleTable).apply {
            preferredSize = java.awt.Dimension(800, 156)
        }

        topPanel.add(left, BorderLayout.WEST)
        topPanel.add(tableScroll, BorderLayout.CENTER)
        matchReplaceBody.add(topPanel, BorderLayout.CENTER)
        matchReplacePanel.add(matchReplaceHeader, BorderLayout.NORTH)
        matchReplacePanel.add(matchReplaceBody, BorderLayout.CENTER)

        val interceptPanel = buildInterceptRulePanel()
        val rulesPanel = JPanel(BorderLayout(0, 8))
        rulesPanel.add(matchReplacePanel, BorderLayout.NORTH)
        rulesPanel.add(interceptPanel, BorderLayout.CENTER)

        panel.add(listenSection, BorderLayout.NORTH)
        panel.add(rulesPanel, BorderLayout.CENTER)
        return panel
    }

    private fun buildListenPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            border = BorderFactory.createTitledBorder("")
        }
        val bindLabel = JLabel(I18n.t("proxy.listener.bind"))
        I18nBinder.bindText(bindLabel, "proxy.listener.bind")
        panel.add(bindLabel)
        panel.add(hostField)
        val portLabel = JLabel(I18n.t("proxy.listener.port"))
        I18nBinder.bindText(portLabel, "proxy.listener.port")
        panel.add(portLabel)
        panel.add(portField)
        panel.add(startStopButton)
        panel.add(statusLabel)
        return panel
    }

    private fun buildInterceptRulePanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 6))
        val header = JPanel(BorderLayout())
        val title = JLabel(I18n.t("proxy.intercept_rule.title")).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
        }
        I18nBinder.bindText(title, "proxy.intercept_rule.title")
        val desc = JLabel(I18n.t("proxy.intercept_rule.description"))
        I18nBinder.bindText(desc, "proxy.intercept_rule.description")
        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(title)
            add(desc)
        }
        header.add(stack, BorderLayout.WEST)

        val body = JPanel(BorderLayout())
        val rowPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("")
        }

        val left = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(addInterceptRuleButton)
            add(Box.createVerticalStrut(8))
            add(editInterceptRuleButton)
            add(Box.createVerticalStrut(8))
            add(removeInterceptRuleButton)
            add(Box.createVerticalStrut(16))
            add(upInterceptRuleButton)
            add(Box.createVerticalStrut(8))
            add(downInterceptRuleButton)
        }

        val fixedWidth = 96
        for (button in arrayOf(addInterceptRuleButton, editInterceptRuleButton, removeInterceptRuleButton, upInterceptRuleButton, downInterceptRuleButton)) {
            val size = button.preferredSize
            button.minimumSize = java.awt.Dimension(fixedWidth, size.height)
            button.maximumSize = java.awt.Dimension(fixedWidth, size.height)
            button.alignmentX = LEFT_ALIGNMENT
        }

        val tableScroll = JScrollPane(interceptRuleTable).apply {
            preferredSize = java.awt.Dimension(800, 156)
        }

        rowPanel.add(left, BorderLayout.WEST)
        rowPanel.add(tableScroll, BorderLayout.CENTER)

        val reservedPanel = JPanel(BorderLayout()).apply {
            preferredSize = java.awt.Dimension(200, 96)
        }

        body.add(rowPanel, BorderLayout.NORTH)
        body.add(reservedPanel, BorderLayout.CENTER)

        panel.add(header, BorderLayout.NORTH)
        panel.add(body, BorderLayout.CENTER)
        return panel
    }

    private fun bindActions() {
        startStopButton.addActionListener {
            if (running) {
                setListeningPending(I18n.t("proxy.status.stopping"))
                onStopProxyRequested.invoke()
                return@addActionListener
            }
            val host = hostField.text.trim().ifEmpty { "127.0.0.1" }
            val port = portField.text.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                statusLabel.text = I18n.t("proxy.status.invalid_port")
                return@addActionListener
            }
            setListeningPending(I18n.t("proxy.status.starting"))
            onStartProxyRequested.invoke(host, port)
        }

        ruleModel.addTableModelListener { event ->
            if (event.type == TableModelEvent.UPDATE && event.column == 0) {
                publishRulesChanged()
            }
        }

        ruleTable.selectionModel.addListSelectionListener {
            updateButtons()
        }
        interceptRuleModel.addTableModelListener { event ->
            if (event.type == TableModelEvent.UPDATE && event.column == 0) {
                publishInterceptRulesChanged()
            }
        }
        interceptRuleTable.selectionModel.addListSelectionListener {
            updateInterceptRuleButtons()
        }

        ruleTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    editSelectedRule()
                }
            }
        })

        addButton.addActionListener {
            val dialog = RuleEditorDialog(SwingUtilities.getWindowAncestor(this), null, ruleModel.rulesSnapshot())
            val created = dialog.open() ?: return@addActionListener
            ruleModel.addRule(created)
            selectModelRow(ruleModel.rowCount - 1)
            publishRulesChanged()
            updateButtons()
        }

        editButton.addActionListener {
            editSelectedRule()
        }

        removeButton.addActionListener {
            val row = selectedModelRow()
            if (row < 0) {
                return@addActionListener
            }
            ruleModel.removeRule(row)
            val next = if (row >= ruleModel.rowCount) ruleModel.rowCount - 1 else row
            selectModelRow(next)
            publishRulesChanged()
            updateButtons()
        }

        upButton.addActionListener {
            val row = selectedModelRow()
            if (row <= 0) {
                return@addActionListener
            }
            ruleModel.moveUp(row)
            selectModelRow(row - 1)
            publishRulesChanged()
            updateButtons()
        }

        downButton.addActionListener {
            val row = selectedModelRow()
            if (row < 0 || row >= ruleModel.rowCount - 1) {
                return@addActionListener
            }
            ruleModel.moveDown(row)
            selectModelRow(row + 1)
            publishRulesChanged()
            updateButtons()
        }

        addInterceptRuleButton.addActionListener {
            val dialog = InterceptRuleEditorDialog(SwingUtilities.getWindowAncestor(this), null)
            val created = dialog.open() ?: return@addActionListener
            interceptRuleModel.addRule(created)
            selectInterceptRuleModelRow(interceptRuleModel.rowCount - 1)
            publishInterceptRulesChanged()
            updateInterceptRuleButtons()
        }

        editInterceptRuleButton.addActionListener {
            editSelectedInterceptRule()
        }

        removeInterceptRuleButton.addActionListener {
            val row = selectedInterceptRuleModelRow()
            if (row < 0) {
                return@addActionListener
            }
            interceptRuleModel.removeRule(row)
            val next = if (row >= interceptRuleModel.rowCount) interceptRuleModel.rowCount - 1 else row
            selectInterceptRuleModelRow(next)
            publishInterceptRulesChanged()
            updateInterceptRuleButtons()
        }

        upInterceptRuleButton.addActionListener {
            val row = selectedInterceptRuleModelRow()
            if (row <= 0) {
                return@addActionListener
            }
            interceptRuleModel.moveUp(row)
            selectInterceptRuleModelRow(row - 1)
            publishInterceptRulesChanged()
            updateInterceptRuleButtons()
        }

        downInterceptRuleButton.addActionListener {
            val row = selectedInterceptRuleModelRow()
            if (row < 0 || row >= interceptRuleModel.rowCount - 1) {
                return@addActionListener
            }
            interceptRuleModel.moveDown(row)
            selectInterceptRuleModelRow(row + 1)
            publishInterceptRulesChanged()
            updateInterceptRuleButtons()
        }

        interceptRuleTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    editSelectedInterceptRule()
                }
            }
        })

        updateButtons()
        updateInterceptRuleButtons()
    }

    private fun editSelectedRule() {
        val row = selectedModelRow()
        if (row < 0) {
            return
        }
        val current = ruleModel.getRuleAt(row) ?: return
        val dialog = RuleEditorDialog(SwingUtilities.getWindowAncestor(this), current, ruleModel.rulesSnapshot())
        val edited = dialog.open() ?: return
        ruleModel.updateRule(row, edited)
        selectModelRow(row)
        publishRulesChanged()
    }

    private fun updateButtons() {
        val row = selectedModelRow()
        val hasSelection = row >= 0
        editButton.isEnabled = hasSelection
        removeButton.isEnabled = hasSelection
        upButton.isEnabled = row > 0
        downButton.isEnabled = hasSelection && row < ruleModel.rowCount - 1
    }

    private fun selectedModelRow(): Int {
        val viewRow = ruleTable.selectedRow
        if (viewRow < 0) {
            return -1
        }
        return viewRow
    }

    private fun selectModelRow(row: Int) {
        if (row < 0 || row >= ruleModel.rowCount) {
            ruleTable.clearSelection()
            return
        }
        ruleTable.selectionModel.setSelectionInterval(row, row)
    }

    private fun publishRulesChanged() {
        onRulesChanged.invoke(ruleModel.rulesSnapshot())
    }

    private fun publishInterceptRulesChanged() {
        onInterceptRulesChanged.invoke(interceptRuleModel.rulesSnapshot())
    }

    private fun selectedInterceptRuleModelRow(): Int {
        val viewRow = interceptRuleTable.selectedRow
        if (viewRow < 0) {
            return -1
        }
        return viewRow
    }

    private fun selectInterceptRuleModelRow(row: Int) {
        if (row < 0 || row >= interceptRuleModel.rowCount) {
            interceptRuleTable.clearSelection()
            return
        }
        interceptRuleTable.selectionModel.setSelectionInterval(row, row)
    }

    private fun updateInterceptRuleButtons() {
        val row = selectedInterceptRuleModelRow()
        val hasSelection = row >= 0
        editInterceptRuleButton.isEnabled = hasSelection
        removeInterceptRuleButton.isEnabled = hasSelection
        upInterceptRuleButton.isEnabled = row > 0
        downInterceptRuleButton.isEnabled = hasSelection && row < interceptRuleModel.rowCount - 1
    }

    private fun editSelectedInterceptRule() {
        val row = selectedInterceptRuleModelRow()
        if (row < 0) {
            return
        }
        val current = interceptRuleModel.getRuleAt(row) ?: return
        val dialog = InterceptRuleEditorDialog(SwingUtilities.getWindowAncestor(this), current)
        val edited = dialog.open() ?: return
        interceptRuleModel.updateRule(row, edited)
        selectInterceptRuleModelRow(row)
        publishInterceptRulesChanged()
    }
}
