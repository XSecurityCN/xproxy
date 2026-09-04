package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRule
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRuleAction
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRuleMode
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.FlowLayout
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
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.table.AbstractTableModel

internal class ProxyInterceptRuleTableModel : AbstractTableModel() {
    private val columns = arrayOf(
        "proxy.column.enabled",
        "proxy.column.action",
        "proxy.column.name",
        "proxy.column.match",
        "proxy.column.scope",
        "proxy.column.type"
    )
    private val rows = ArrayList<ProxyInterceptRule>()

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
            1 -> row.action.label
            2 -> row.name
            3 -> row.matchText
            4 -> row.scopeSummary()
            5 -> row.mode.label
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

    fun setRules(newRules: List<ProxyInterceptRule>) {
        rows.clear()
        rows.addAll(newRules.map { it.copy() })
        fireTableDataChanged()
    }

    fun addRule(rule: ProxyInterceptRule) {
        rows.add(rule.copy())
        fireTableRowsInserted(rows.size - 1, rows.size - 1)
    }

    fun updateRule(row: Int, rule: ProxyInterceptRule) {
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

    fun getRuleAt(row: Int): ProxyInterceptRule? = if (row in rows.indices) rows[row].copy() else null

    fun rulesSnapshot(): List<ProxyInterceptRule> = rows.map { it.copy() }
}

internal class InterceptRuleEditorDialog(
    owner: Window?,
    private val original: ProxyInterceptRule?
) : JDialog(owner, if (original == null) I18n.t("proxy.intercept_rule.add_title") else I18n.t("proxy.intercept_rule.edit_title"), Dialog.ModalityType.APPLICATION_MODAL) {
    private val enabledCheck = JCheckBox(I18n.t("proxy.column.enabled"))
    private val modeCombo = JComboBox(ProxyInterceptRuleMode.entries.toTypedArray())
    private val actionCombo = JComboBox(ProxyInterceptRuleAction.entries.toTypedArray())
    private val nameField = JTextField(24)
    private val matchField = JTextField(30)
    private val requestHeaderCheck = JCheckBox(I18n.t("proxy.scope.request_header"))
    private val requestBodyCheck = JCheckBox(I18n.t("proxy.scope.request_body"))
    private val responseHeaderCheck = JCheckBox(I18n.t("proxy.scope.response_header"))
    private val responseBodyCheck = JCheckBox(I18n.t("proxy.scope.response_body"))
    private val okButton = JButton(I18n.t("common.ok"))
    private val cancelButton = JButton(I18n.t("common.cancel"))
    private var resultRule: ProxyInterceptRule? = null

    init {
        layout = BorderLayout(8, 8)
        rootPane.defaultButton = okButton

        val content = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(buildForm(), BorderLayout.CENTER)
            add(buildBottomButtons(), BorderLayout.SOUTH)
        }
        add(content, BorderLayout.CENTER)

        val seed = original ?: ProxyInterceptRule(enabled = true, name = I18n.t("proxy.rule.default_name"))
        enabledCheck.isSelected = seed.enabled
        modeCombo.selectedItem = seed.mode
        actionCombo.selectedItem = seed.action
        nameField.text = seed.name
        matchField.text = seed.matchText
        requestHeaderCheck.isSelected = seed.matchRequestHeader
        requestBodyCheck.isSelected = seed.matchRequestBody
        responseHeaderCheck.isSelected = seed.matchResponseHeader
        responseBodyCheck.isSelected = seed.matchResponseBody

        bindActions()
        setSize(700, 320)
        setLocationRelativeTo(owner)
    }

    fun open(): ProxyInterceptRule? {
        isVisible = true
        return resultRule
    }

    private fun buildForm(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 4, 4, 4)
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }

        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(JLabel(I18n.t("proxy.column.name")), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(nameField, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        panel.add(JLabel(I18n.t("proxy.column.match")), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(matchField, gbc)

        val modePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        modePanel.add(enabledCheck)
        modePanel.add(JLabel(I18n.t("proxy.column.mode")))
        modePanel.add(modeCombo)
        modePanel.add(JLabel(I18n.t("proxy.column.action")))
        modePanel.add(actionCombo)
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 2
        panel.add(modePanel, gbc)

        val scopePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            border = BorderFactory.createTitledBorder(I18n.t("proxy.column.scope"))
        }
        scopePanel.add(requestHeaderCheck)
        scopePanel.add(requestBodyCheck)
        scopePanel.add(responseHeaderCheck)
        scopePanel.add(responseBodyCheck)
        gbc.gridy = 3
        panel.add(scopePanel, gbc)
        return panel
    }

    private fun buildBottomButtons(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        panel.add(cancelButton)
        panel.add(okButton)
        return panel
    }

    private fun bindActions() {
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

    private fun buildRuleFromFields(validate: Boolean): ProxyInterceptRule? {
        val name = nameField.text.trim()
        val matchText = matchField.text
        val mode = modeCombo.selectedItem as? ProxyInterceptRuleMode ?: ProxyInterceptRuleMode.TEXT
        val action = actionCombo.selectedItem as? ProxyInterceptRuleAction ?: ProxyInterceptRuleAction.FORWARD
        val matchRequestHeader = requestHeaderCheck.isSelected
        val matchRequestBody = requestBodyCheck.isSelected
        val matchResponseHeader = responseHeaderCheck.isSelected
        val matchResponseBody = responseBodyCheck.isSelected

        if (validate) {
            if (name.isBlank()) {
                JOptionPane.showMessageDialog(this, I18n.t("proxy.rule.name_required"), I18n.t("proxy.rule.invalid_title"), JOptionPane.WARNING_MESSAGE)
                return null
            }
            if (matchText.isEmpty()) {
                JOptionPane.showMessageDialog(this, I18n.t("proxy.rule.match_required"), I18n.t("proxy.rule.invalid_title"), JOptionPane.WARNING_MESSAGE)
                return null
            }
            if (!matchRequestHeader && !matchRequestBody && !matchResponseHeader && !matchResponseBody) {
                JOptionPane.showMessageDialog(this, I18n.t("proxy.rule.scope_required"), I18n.t("proxy.rule.invalid_title"), JOptionPane.WARNING_MESSAGE)
                return null
            }
            if (mode == ProxyInterceptRuleMode.REGEX) {
                try {
                    Regex(matchText)
                } catch (ex: Exception) {
                    JOptionPane.showMessageDialog(this, I18n.t("proxy.rule.invalid_regex", "error" to ex.message), I18n.t("proxy.rule.invalid_title"), JOptionPane.WARNING_MESSAGE)
                    return null
                }
            }
        }

        return ProxyInterceptRule(
            ruleId = original?.ruleId ?: java.util.UUID.randomUUID().toString(),
            enabled = enabledCheck.isSelected,
            name = if (name.isBlank()) I18n.t("proxy.rule.default_name") else name,
            mode = mode,
            matchText = matchText,
            action = action,
            matchRequestHeader = matchRequestHeader,
            matchRequestBody = matchRequestBody,
            matchResponseHeader = matchResponseHeader,
            matchResponseBody = matchResponseBody
        )
    }
}
