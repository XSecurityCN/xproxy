package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.proxy.core.ProxyMatchReplaceEngine
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceAction
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceMode
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceRule
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceScope
import org.jjgroup.xproxy.ui.highlight.HttpHighlighter
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.table.AbstractTableModel
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.RTextScrollPane

internal class ProxyMatchReplaceRuleTableModel : AbstractTableModel() {
    private val columns = arrayOf(
        "proxy.column.enabled",
        "proxy.column.item",
        "proxy.column.name",
        "proxy.column.match",
        "proxy.column.replace",
        "proxy.column.action",
        "proxy.column.type"
    )
    private val rows = ArrayList<ProxyMatchReplaceRule>()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns.getOrNull(column)?.let { I18n.t(it) }.orEmpty()

    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.enabled
            1 -> row.scope.label
            2 -> row.name
            3 -> row.matchText
            4 -> row.replaceText
            5 -> row.action.label
            6 -> row.mode.label
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (rowIndex !in rows.indices || columnIndex != 0) {
            return
        }
        rows[rowIndex].enabled = (aValue as? Boolean) == true
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    fun setRules(newRules: List<ProxyMatchReplaceRule>) {
        rows.clear()
        rows.addAll(newRules.map { it.copy() })
        fireTableDataChanged()
    }

    fun addRule(rule: ProxyMatchReplaceRule) {
        rows.add(rule.copy())
        fireTableRowsInserted(rows.size - 1, rows.size - 1)
    }

    fun updateRule(row: Int, rule: ProxyMatchReplaceRule) {
        if (row !in rows.indices) {
            return
        }
        rows[row] = rule.copy()
        fireTableRowsUpdated(row, row)
    }

    fun removeRule(row: Int) {
        if (row !in rows.indices) {
            return
        }
        rows.removeAt(row)
        fireTableRowsDeleted(row, row)
    }

    fun moveUp(row: Int) {
        if (row <= 0 || row >= rows.size) {
            return
        }
        val tmp = rows[row - 1]
        rows[row - 1] = rows[row]
        rows[row] = tmp
        fireTableRowsUpdated(row - 1, row)
    }

    fun moveDown(row: Int) {
        if (row < 0 || row >= rows.size - 1) {
            return
        }
        val tmp = rows[row + 1]
        rows[row + 1] = rows[row]
        rows[row] = tmp
        fireTableRowsUpdated(row, row + 1)
    }

    fun getRuleAt(row: Int): ProxyMatchReplaceRule? = if (row in rows.indices) rows[row].copy() else null

    fun rulesSnapshot(): List<ProxyMatchReplaceRule> = rows.map { it.copy() }
}

internal class RuleEditorDialog(
    owner: Window?,
    private val original: ProxyMatchReplaceRule?,
    private val existingRules: List<ProxyMatchReplaceRule>
) : JDialog(owner, if (original == null) I18n.t("proxy.match_replace.add_title") else I18n.t("proxy.match_replace.edit_title"), Dialog.ModalityType.APPLICATION_MODAL) {

    companion object {
        private const val DEFAULT_DEBUG_REQUEST = """POST /update HTTP/2
Host: example.com
Content-Type: application/json
User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36
Content-Length: 34

{
  \"key1\": \"value1\",
  \"key2\": \"value2\"
}"""
    }

    private val enabledCheck = JCheckBox(I18n.t("proxy.column.enabled"))
    private val scopeCombo = JComboBox(ProxyMatchReplaceScope.entries.toTypedArray())
    private val modeCombo = JComboBox(ProxyMatchReplaceMode.entries.toTypedArray())
    private val actionCombo = JComboBox(ProxyMatchReplaceAction.entries.toTypedArray())
    private val nameField = JTextField(24)
    private val matchField = JTextField(30)
    private val replaceField = JTextField(30)

    private val debugInput = RSyntaxTextArea(16, 60)
    private val debugOutput = RSyntaxTextArea(16, 60)
    private val testButton = JButton(I18n.t("common.test"))
    private val resetButton = JButton(I18n.t("common.reset"))
    private val statusLabel = JLabel(" ")

    private val okButton = JButton(I18n.t("common.ok"))
    private val cancelButton = JButton(I18n.t("common.cancel"))

    private var resultRule: ProxyMatchReplaceRule? = null

    init {
        layout = BorderLayout(8, 8)
        rootPane.defaultButton = okButton

        val content = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(buildRuleForm(), BorderLayout.NORTH)
            add(buildDebugPane(), BorderLayout.CENTER)
            add(buildBottomButtons(), BorderLayout.SOUTH)
        }
        add(content, BorderLayout.CENTER)

        listOf(debugInput, debugOutput).forEach {
            HttpHighlighter.attach(it)
            it.highlightCurrentLine = true
            it.currentLineHighlightColor = java.awt.Color(230, 230, 230)
            it.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        }
        debugOutput.isEditable = false
        debugInput.text = DEFAULT_DEBUG_REQUEST

        val seed = original ?: ProxyMatchReplaceRule(enabled = true, name = I18n.t("proxy.rule.default_name"))
        enabledCheck.isSelected = seed.enabled
        scopeCombo.selectedItem = seed.scope
        modeCombo.selectedItem = seed.mode
        actionCombo.selectedItem = seed.action
        nameField.text = seed.name
        matchField.text = seed.matchText
        replaceField.text = seed.replaceText

        bindDialogActions()

        setSize(980, 700)
        setLocationRelativeTo(owner)
    }

    fun open(): ProxyMatchReplaceRule? {
        isVisible = true
        return resultRule
    }

    private fun buildRuleForm(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }

        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(JLabel(I18n.t("proxy.column.type")), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(scopeCombo, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.0
        panel.add(JLabel(I18n.t("proxy.column.name")), gbc)
        gbc.gridx = 3
        gbc.weightx = 1.0
        panel.add(nameField, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        panel.add(JLabel(I18n.t("proxy.column.match")), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(matchField, gbc)

        gbc.gridx = 2
        gbc.weightx = 0.0
        panel.add(JLabel(I18n.t("proxy.column.replace")), gbc)
        gbc.gridx = 3
        gbc.weightx = 1.0
        panel.add(replaceField, gbc)

        val modePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        modePanel.add(enabledCheck)
        modePanel.add(JLabel(I18n.t("proxy.column.mode")))
        modePanel.add(modeCombo)
        modePanel.add(JLabel(I18n.t("proxy.column.action")))
        modePanel.add(actionCombo)
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 4
        gbc.weightx = 1.0
        panel.add(modePanel, gbc)

        return panel
    }

    private fun buildDebugPane(): JPanel {
        val panel = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createTitledBorder(I18n.t("proxy.match_replace.debug_test"))
        }

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        top.add(testButton)
        top.add(resetButton)
        top.add(statusLabel)

        val inputWrap = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(I18n.t("proxy.match_replace.original"))
        }
        val inputScroll = RTextScrollPane(debugInput).apply {
            lineNumbersEnabled = true
        }
        inputWrap.add(inputScroll, BorderLayout.CENTER)

        val outputWrap = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(I18n.t("proxy.match_replace.auto_modified"))
        }
        val outputScroll = RTextScrollPane(debugOutput).apply {
            lineNumbersEnabled = true
        }
        outputWrap.add(outputScroll, BorderLayout.CENTER)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, inputWrap, outputWrap).apply {
            resizeWeight = 0.5
        }

        panel.add(top, BorderLayout.NORTH)
        panel.add(split, BorderLayout.CENTER)
        return panel
    }

    private fun buildBottomButtons(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        panel.add(cancelButton)
        panel.add(okButton)
        return panel
    }

    private fun bindDialogActions() {
        testButton.addActionListener {
            val draft = buildRuleFromFields(validate = false) ?: return@addActionListener
            val draftForTest = draft.copy(enabled = true)
            val merged = existingRules.map { it.copy() }.toMutableList()
            if (original != null) {
                val index = merged.indexOfFirst { it.ruleId == original.ruleId }
                if (index >= 0) {
                    merged[index] = draftForTest
                }
            } else {
                merged.add(draftForTest)
            }
            val result = ProxyMatchReplaceEngine.debugApply(debugInput.text, draftForTest.scope, merged)
            debugOutput.text = result.output
            debugOutput.caretPosition = 0
            statusLabel.text = if (draftForTest.scope == ProxyMatchReplaceScope.REQUEST_FIRST_LINE && result.replacementCount == 0) {
                I18n.t("proxy.match_replace.debug_first_line_zero")
            } else {
                I18n.t("proxy.match_replace.debug_result", "matched" to result.matchedRuleCount, "replacements" to result.replacementCount)
            }
        }

        resetButton.addActionListener {
            debugOutput.text = ""
            statusLabel.text = " "
        }

        okButton.addActionListener {
            val built = buildRuleFromFields(validate = true) ?: return@addActionListener
            resultRule = built
            dispose()
        }

        cancelButton.addActionListener {
            resultRule = null
            dispose()
        }
    }

    private fun buildRuleFromFields(validate: Boolean): ProxyMatchReplaceRule? {
        val name = nameField.text.trim()
        val matchText = matchField.text
        val scope = scopeCombo.selectedItem as? ProxyMatchReplaceScope ?: ProxyMatchReplaceScope.REQUEST_BODY
        val mode = modeCombo.selectedItem as? ProxyMatchReplaceMode ?: ProxyMatchReplaceMode.TEXT
        val action = actionCombo.selectedItem as? ProxyMatchReplaceAction ?: ProxyMatchReplaceAction.REPLACE

        if (validate) {
            if (name.isBlank()) {
                JOptionPane.showMessageDialog(this, I18n.t("proxy.rule.name_required"), I18n.t("proxy.rule.invalid_title"), JOptionPane.WARNING_MESSAGE)
                return null
            }
            if (matchText.isEmpty() && action != ProxyMatchReplaceAction.ADD) {
                JOptionPane.showMessageDialog(this, I18n.t("proxy.rule.match_required"), I18n.t("proxy.rule.invalid_title"), JOptionPane.WARNING_MESSAGE)
                return null
            }
            if (action == ProxyMatchReplaceAction.ADD && mode == ProxyMatchReplaceMode.REGEX && matchText.isEmpty()) {
                JOptionPane.showMessageDialog(this, I18n.t("proxy.rule.match_required_add_regex"), I18n.t("proxy.rule.invalid_title"), JOptionPane.WARNING_MESSAGE)
                return null
            }
            if (mode == ProxyMatchReplaceMode.REGEX) {
                try {
                    Regex(matchText)
                } catch (ex: Exception) {
                    JOptionPane.showMessageDialog(this, I18n.t("proxy.rule.invalid_regex", "error" to ex.message), I18n.t("proxy.rule.invalid_title"), JOptionPane.WARNING_MESSAGE)
                    return null
                }
            }
        }

        return ProxyMatchReplaceRule(
            ruleId = original?.ruleId ?: java.util.UUID.randomUUID().toString(),
            enabled = enabledCheck.isSelected,
            name = if (name.isBlank()) I18n.t("proxy.rule.default_name") else name,
            scope = scope,
            mode = mode,
            action = action,
            matchText = matchText,
            replaceText = replaceField.text
        )
    }
}
