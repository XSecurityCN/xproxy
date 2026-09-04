package org.jjgroup.xproxy.ui.table

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

class MimeFilterBar(
    private val state: MimeFilterState,
    private val onChanged: () -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)) {
    private val filterButton = JButton(FilterGlyphIcon())
    private val summary = JLabel()

    data class DialogLabels(val title: String, val texts: List<String>)

    companion object {
        fun dialogLabelsForTests(): DialogLabels = dialogLabels()

        private fun dialogLabels(): DialogLabels = DialogLabels(
            title = I18n.t("mime_filter.title"),
            texts = listOf(
                I18n.t("mime_filter.by_mime_type"),
                I18n.t("mime_filter.by_status_code"),
                I18n.t("mime_filter.by_keyword"),
                I18n.t("mime_filter.keyword"),
                I18n.t("search.regex"),
                I18n.t("search.match_case"),
                I18n.t("proxy.column.scope"),
                I18n.t("proxy.scope.request_header"),
                I18n.t("proxy.scope.request_body"),
                I18n.t("proxy.scope.response_header"),
                I18n.t("proxy.scope.response_body"),
                I18n.t("mime_filter.condition"),
                I18n.t("mime_filter.show_all"),
                I18n.t("mime_filter.hide_all"),
                I18n.t("mime_filter.restore_default"),
                I18n.t("common.apply"),
                I18n.t("mime_filter.apply_and_close"),
                I18n.t("common.cancel")
            )
        )
    }

    init {
        filterButton.toolTipText = I18n.t("mime_filter.settings")
        filterButton.margin = Insets(1, 6, 1, 6)
        add(filterButton)
        add(summary)
        updateSummary()
        I18nBinder.bind {
            filterButton.toolTipText = I18n.t("mime_filter.settings")
            updateSummary()
        }

        filterButton.addActionListener {
            showFilterDialog()
        }

        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        summary.cursor = cursor
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    showFilterDialog()
                }
            }
        })
        summary.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    showFilterDialog()
                }
            }
        })

        state.addListener {
            updateSummary()
            onChanged.invoke()
        }
    }

    private fun showFilterDialog() {
        val owner = SwingUtilities.getWindowAncestor(this)
        val dialog = JDialog(owner, I18n.t("mime_filter.title"), Dialog.ModalityType.APPLICATION_MODAL)
        dialog.layout = BorderLayout(8, 8)

        val snapshot = state.selectedTypes()
        val snapshotStatus = state.selectedStatusBuckets()
        val snapshotKeyword = state.keyword()
        val snapshotRegex = state.keywordRegex()
        val snapshotCaseSensitive = state.keywordCaseSensitive()
        val snapshotScopeRequestHeader = state.keywordScopeRequestHeader()
        val snapshotScopeRequestBody = state.keywordScopeRequestBody()
        val snapshotScopeResponseHeader = state.keywordScopeResponseHeader()
        val snapshotScopeResponseBody = state.keywordScopeResponseBody()
        val snapshotLogic = state.logicMode()
        val working = LinkedHashSet(snapshot)
        val workingStatus = LinkedHashSet(snapshotStatus)
        var workingKeyword = snapshotKeyword
        var workingRegex = snapshotRegex
        var workingCaseSensitive = snapshotCaseSensitive
        var workingScopeRequestHeader = snapshotScopeRequestHeader
        var workingScopeRequestBody = snapshotScopeRequestBody
        var workingScopeResponseHeader = snapshotScopeResponseHeader
        var workingScopeResponseBody = snapshotScopeResponseBody
        var workingLogic = snapshotLogic
        val checkboxes = LinkedHashMap<String, JCheckBox>()
        val statusBoxes = LinkedHashMap<String, JCheckBox>()

        val center = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8))

        val mimePanel = JPanel(GridBagLayout())
        mimePanel.border = BorderFactory.createTitledBorder(I18n.t("mime_filter.by_mime_type"))
        val mimeGbc = GridBagConstraints()
        mimeGbc.insets = Insets(4, 8, 4, 8)
        mimeGbc.anchor = GridBagConstraints.WEST

        state.types().forEachIndexed { index, mimeType ->
            mimeGbc.gridx = index % 2
            mimeGbc.gridy = index / 2
            val box = JCheckBox(mimeType)
            box.isSelected = working.contains(mimeType)
            box.addActionListener {
                if (box.isSelected) {
                    working.add(mimeType)
                } else {
                    working.remove(mimeType)
                }
            }
            checkboxes[mimeType] = box
            mimePanel.add(box, mimeGbc)
        }
        center.add(mimePanel)

        val statusPanel = JPanel(GridBagLayout())
        statusPanel.border = BorderFactory.createTitledBorder(I18n.t("mime_filter.by_status_code"))
        val statusGbc = GridBagConstraints()
        statusGbc.insets = Insets(4, 8, 4, 8)
        statusGbc.anchor = GridBagConstraints.WEST

        state.statusBuckets().forEachIndexed { index, bucket ->
            statusGbc.gridx = 0
            statusGbc.gridy = index
            val box = JCheckBox(bucket)
            box.isSelected = workingStatus.contains(bucket)
            box.addActionListener {
                if (box.isSelected) {
                    workingStatus.add(bucket)
                } else {
                    workingStatus.remove(bucket)
                }
            }
            statusBoxes[bucket] = box
            statusPanel.add(box, statusGbc)
        }
        center.add(statusPanel)

        val keywordPanel = JPanel(GridBagLayout())
        keywordPanel.border = BorderFactory.createTitledBorder(I18n.t("mime_filter.by_keyword"))
        val keywordGbc = GridBagConstraints()
        keywordGbc.insets = Insets(4, 8, 4, 8)
        keywordGbc.anchor = GridBagConstraints.WEST
        keywordGbc.fill = GridBagConstraints.HORIZONTAL
        keywordGbc.weightx = 1.0

        keywordGbc.gridx = 0
        keywordGbc.gridy = 0
        keywordPanel.add(JLabel(I18n.t("mime_filter.keyword")), keywordGbc)

        keywordGbc.gridy = 1
        val keywordField = JTextField(workingKeyword, 36)
        keywordField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { workingKeyword = keywordField.text }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { workingKeyword = keywordField.text }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { workingKeyword = keywordField.text }
        })
        keywordPanel.add(keywordField, keywordGbc)

        keywordGbc.gridy = 2
        val regexBox = JCheckBox(I18n.t("search.regex"))
        regexBox.isSelected = workingRegex
        regexBox.addActionListener { workingRegex = regexBox.isSelected }
        val caseSensitiveBox = JCheckBox(I18n.t("search.match_case"))
        caseSensitiveBox.isSelected = workingCaseSensitive
        caseSensitiveBox.addActionListener { workingCaseSensitive = caseSensitiveBox.isSelected }
        // "正则" 与 "区分大小写" 放到同一水平行。
        val regexRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        regexRow.add(regexBox)
        regexRow.add(caseSensitiveBox)
        keywordPanel.add(regexRow, keywordGbc)

        keywordGbc.gridy = 4
        keywordPanel.add(JLabel(I18n.t("proxy.column.scope")), keywordGbc)

        val scopePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        val requestHeaderScopeBox = JCheckBox(I18n.t("proxy.scope.request_header"), workingScopeRequestHeader)
        val requestBodyScopeBox = JCheckBox(I18n.t("proxy.scope.request_body"), workingScopeRequestBody)
        val responseHeaderScopeBox = JCheckBox(I18n.t("proxy.scope.response_header"), workingScopeResponseHeader)
        val responseBodyScopeBox = JCheckBox(I18n.t("proxy.scope.response_body"), workingScopeResponseBody)
        requestHeaderScopeBox.addActionListener { workingScopeRequestHeader = requestHeaderScopeBox.isSelected }
        requestBodyScopeBox.addActionListener { workingScopeRequestBody = requestBodyScopeBox.isSelected }
        responseHeaderScopeBox.addActionListener { workingScopeResponseHeader = responseHeaderScopeBox.isSelected }
        responseBodyScopeBox.addActionListener { workingScopeResponseBody = responseBodyScopeBox.isSelected }
        scopePanel.add(requestHeaderScopeBox)
        scopePanel.add(requestBodyScopeBox)
        scopePanel.add(responseHeaderScopeBox)
        scopePanel.add(responseBodyScopeBox)
        keywordGbc.gridy = 5
        keywordPanel.add(scopePanel, keywordGbc)
        center.add(keywordPanel)

        val logicBox = JComboBox(arrayOf("AND", "OR"))
        logicBox.selectedItem = if (workingLogic == MimeFilterState.LogicMode.AND) "AND" else "OR"
        logicBox.addActionListener {
            workingLogic = if (logicBox.selectedItem == "OR") MimeFilterState.LogicMode.OR else MimeFilterState.LogicMode.AND
        }

        fun refreshChecksFromWorking() {
            for ((mimeType, box) in checkboxes) {
                box.isSelected = working.contains(mimeType)
            }
            for ((bucket, box) in statusBoxes) {
                box.isSelected = workingStatus.contains(bucket)
            }
        }

        /** 把对话框选项重置为统一基准:MIME 设为 [mimeTypes]、状态码全选、关键字清空、
         *  正则/区分大小写关闭、作用域全开、逻辑 AND。showAll 传全部类型,revert 传默认类型。 */
        fun applyState(mimeTypes: Collection<String>) {
            working.clear()
            working.addAll(mimeTypes)
            workingStatus.clear()
            workingStatus.addAll(state.statusBuckets())
            workingKeyword = ""
            keywordField.text = ""
            workingRegex = false
            regexBox.isSelected = false
            workingCaseSensitive = false
            caseSensitiveBox.isSelected = false
            workingScopeRequestHeader = true
            requestHeaderScopeBox.isSelected = true
            workingScopeRequestBody = true
            requestBodyScopeBox.isSelected = true
            workingScopeResponseHeader = true
            responseHeaderScopeBox.isSelected = true
            workingScopeResponseBody = true
            responseBodyScopeBox.isSelected = true
            workingLogic = MimeFilterState.LogicMode.AND
            logicBox.selectedItem = "AND"
            refreshChecksFromWorking()
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8))
        val logicLabel = JLabel(I18n.t("mime_filter.condition"))
        val showAll = JButton(I18n.t("mime_filter.show_all"))
        val hideAll = JButton(I18n.t("mime_filter.hide_all"))
        val revert = JButton(I18n.t("mime_filter.restore_default"))
        val apply = JButton(I18n.t("common.apply"))
        val applyAndClose = JButton(I18n.t("mime_filter.apply_and_close"))
        val cancel = JButton(I18n.t("common.cancel"))

        showAll.addActionListener {
            applyState(state.types())
        }

        hideAll.addActionListener {
            working.clear()
            workingStatus.clear()
            workingKeyword = ""
            keywordField.text = ""
            workingRegex = false
            regexBox.isSelected = false
            workingCaseSensitive = false
            caseSensitiveBox.isSelected = false
            workingScopeRequestHeader = true
            requestHeaderScopeBox.isSelected = true
            workingScopeRequestBody = true
            requestBodyScopeBox.isSelected = true
            workingScopeResponseHeader = true
            responseHeaderScopeBox.isSelected = true
            workingScopeResponseBody = true
            responseBodyScopeBox.isSelected = true
            refreshChecksFromWorking()
        }

        revert.addActionListener {
            applyState(state.defaultTypes())
        }

        apply.addActionListener {
            state.batch {
                state.replaceSelectedTypes(working)
                state.statusBuckets().forEach { state.setStatusBucketAllowed(it, workingStatus.contains(it)) }
                state.setKeyword(workingKeyword)
                state.setKeywordRegex(workingRegex)
                state.setKeywordCaseSensitive(workingCaseSensitive)
                state.setKeywordScopeRequestHeader(workingScopeRequestHeader)
                state.setKeywordScopeRequestBody(workingScopeRequestBody)
                state.setKeywordScopeResponseHeader(workingScopeResponseHeader)
                state.setKeywordScopeResponseBody(workingScopeResponseBody)
                state.setLogicMode(workingLogic)
            }
        }

        applyAndClose.addActionListener {
            state.batch {
                state.replaceSelectedTypes(working)
                state.statusBuckets().forEach { state.setStatusBucketAllowed(it, workingStatus.contains(it)) }
                state.setKeyword(workingKeyword)
                state.setKeywordRegex(workingRegex)
                state.setKeywordCaseSensitive(workingCaseSensitive)
                state.setKeywordScopeRequestHeader(workingScopeRequestHeader)
                state.setKeywordScopeRequestBody(workingScopeRequestBody)
                state.setKeywordScopeResponseHeader(workingScopeResponseHeader)
                state.setKeywordScopeResponseBody(workingScopeResponseBody)
                state.setLogicMode(workingLogic)
            }
            dialog.dispose()
        }

        cancel.addActionListener {
            dialog.dispose()
        }

        buttons.add(logicLabel)
        buttons.add(logicBox)
        buttons.add(showAll)
        buttons.add(hideAll)
        buttons.add(revert)
        buttons.add(cancel)
        buttons.add(apply)
        buttons.add(applyAndClose)

        dialog.add(center, BorderLayout.CENTER)
        dialog.add(buttons, BorderLayout.SOUTH)
        dialog.pack()
        dialog.setLocationRelativeTo(owner)
        dialog.isVisible = true
    }

    private fun updateSummary() {
        val selected = state.selectedTypes().toList().sorted()
        val logic = if (state.logicMode() == MimeFilterState.LogicMode.AND) "AND" else "OR"
        summary.text = if (selected.isEmpty()) {
            I18n.t("mime_filter.summary_hiding_all", "logic" to logic)
        } else {
            I18n.t("mime_filter.summary_showing", "types" to selected.joinToString(", "), "logic" to logic)
        }
    }

    private class FilterGlyphIcon : Icon {
        override fun getIconWidth(): Int = 14

        override fun getIconHeight(): Int = 14

        override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(80, 80, 80)
            val top = intArrayOf(x + 1, x + 13, x + 9, x + 5)
            val topY = intArrayOf(y + 2, y + 2, y + 7, y + 7)
            g2.fillPolygon(top, topY, 4)
            val stem = intArrayOf(x + 6, x + 8, x + 8, x + 6)
            val stemY = intArrayOf(y + 7, y + 7, y + 12, y + 12)
            g2.fillPolygon(stem, stemY, 4)
            g2.dispose()
        }
    }
}
