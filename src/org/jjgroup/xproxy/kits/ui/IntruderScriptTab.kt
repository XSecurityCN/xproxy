package org.jjgroup.xproxy.kits.ui

import org.jjgroup.xproxy.kits.model.IntruderAttackScript
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.Desktop
import java.nio.file.Files
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

internal fun KitsPanel.buildIntruderRootTab(): JPanel {
    val tabs = JTabbedPane()
    tabs.addTab("attack script", buildIntruderAttackScriptTab())
    tabs.addTab("api docs", buildApiTab("intruder APIs", intruderApiDocEntries()))
    return JPanel(BorderLayout()).apply { add(tabs, BorderLayout.CENTER) }
}

private fun KitsPanel.buildIntruderAttackScriptTab(): JPanel {
    val panel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    val top = JPanel(BorderLayout())
    top.add(JLabel("intruder root: ${intruderScriptManager.scriptDirectory()}"), BorderLayout.WEST)
    val actions = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0))
    intruderOpenDirButton.addActionListener {
        val dir = intruderScriptManager.scriptDirectory().toFile()
        if (!dir.exists()) {
            dir.mkdirs()
        }
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir)
            }
        }
    }
    val reloadButton = JButton("Reload").apply {
        addActionListener {
            reloadIntruderScripts(selectedIntruderScript?.key)
        }
    }
    intruderNewButton.addActionListener {
        createIntruderScript()
    }
    intruderDeleteButton.addActionListener {
        deleteSelectedIntruderScript()
    }
    actions.add(intruderOpenDirButton)
    actions.add(intruderNewButton)
    actions.add(intruderDeleteButton)
    actions.add(reloadButton)
    top.add(actions, BorderLayout.EAST)

    intruderTable.fillsViewportHeight = true
    intruderTable.rowHeight = 26
    intruderTable.autoCreateRowSorter = true
    intruderTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    intruderTable.selectionBackground = shadowSelection
    intruderTable.selectionForeground = shadowSelectionForeground
    listOf(70, 220, 120, 320, 120).forEachIndexed { index, width ->
        intruderTable.columnModel.getColumn(index).preferredWidth = width
    }
    intruderTable.columnModel.getColumn(0).maxWidth = 90
    intruderTable.selectionModel.addListSelectionListener {
        if (it.valueIsAdjusting) {
            return@addListSelectionListener
        }
        onIntruderScriptSelectionChanged()
    }

    intruderEditorArea.apply {
        isEditable = true
        syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_PYTHON
        setCodeFoldingEnabled(true)
        antiAliasingEnabled = true
        isBracketMatchingEnabled = true
        tabSize = 4
    }

    intruderSaveButton.addActionListener {
        val script = selectedIntruderScript ?: return@addActionListener
        intruderScriptManager.saveScript(script.scriptPath, intruderEditorArea.text)
        reloadIntruderScripts(script.key)
    }

    val tablePanel = JPanel(BorderLayout())
    tablePanel.add(JScrollPane(intruderTable), BorderLayout.CENTER)
    val footer = JLabel("Manage intruder attack scripts under ~/.xproxy/intruder with category and enable states.").apply {
        border = BorderFactory.createEmptyBorder(6, 2, 0, 2)
    }
    tablePanel.add(footer, BorderLayout.SOUTH)

    val editorPanel = JPanel(BorderLayout())
    val editorTop = JPanel(BorderLayout())
    editorTop.add(JLabel("attack script editor"), BorderLayout.WEST)
    val editorActions = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0))
    editorActions.add(intruderSaveButton)
    editorTop.add(editorActions, BorderLayout.EAST)
    editorPanel.add(editorTop, BorderLayout.NORTH)
    val editorScroll = RTextScrollPane(intruderEditorArea)
    editorScroll.lineNumbersEnabled = true
    editorPanel.add(editorScroll, BorderLayout.CENTER)

    val split = JSplitPane(JSplitPane.VERTICAL_SPLIT)
    split.topComponent = tablePanel
    split.bottomComponent = editorPanel
    split.resizeWeight = 0.52
    SwingUtilities.invokeLater {
        split.setDividerLocation(0.52)
    }

    panel.add(top, BorderLayout.NORTH)
    panel.add(split, BorderLayout.CENTER)
    return panel
}

internal fun KitsPanel.reloadIntruderScripts(preferredKey: String?) {
    val scripts = intruderScriptManager.loadScripts()
    intruderTableModel.setScripts(scripts)
    if (scripts.isEmpty()) {
        selectedIntruderScript = null
        intruderEditorArea.text = ""
        return
    }
    val selectedRow = when {
        !preferredKey.isNullOrBlank() -> intruderTableModel.indexOfScript(preferredKey)
        else -> 0
    }
    val row = if (selectedRow >= 0) selectedRow else 0
    val viewRow = intruderTable.convertRowIndexToView(row)
    if (viewRow >= 0) {
        intruderTable.selectionModel.setSelectionInterval(viewRow, viewRow)
    }
    onIntruderScriptSelectionChanged()
}

private fun KitsPanel.onIntruderScriptSelectionChanged() {
    val viewRow = intruderTable.selectedRow
    if (viewRow < 0) {
        selectedIntruderScript = null
        intruderEditorArea.text = ""
        return
    }
    val modelRow = intruderTable.convertRowIndexToModel(viewRow)
    val script = intruderTableModel.getAt(modelRow) ?: return
    selectedIntruderScript = script
    val content = runCatching { Files.readString(script.scriptPath, Charsets.UTF_8) }.getOrDefault("")
    intruderEditorArea.text = content
    intruderEditorArea.caretPosition = 0
}

private fun KitsPanel.createIntruderScript() {
    val categoryField = JTextField("General")
    val nameField = JTextField("new_attack_script")
    val form = JPanel(java.awt.GridLayout(0, 1, 4, 4))
    form.add(JLabel("Category"))
    form.add(categoryField)
    form.add(JLabel("Script Name (without .py)"))
    form.add(nameField)

    val result = JOptionPane.showConfirmDialog(
        this,
        form,
        "Create Intruder Attack Script",
        JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE
    )
    if (result != JOptionPane.OK_OPTION) {
        return
    }

    val createdPath = runCatching {
        intruderScriptManager.createScript(categoryField.text, nameField.text)
    }.getOrNull() ?: return
    val key = intruderScriptManager.scriptDirectory().relativize(createdPath)
        .toString()
        .replace(java.io.File.separatorChar, '/')
        .lowercase()
    reloadIntruderScripts(key)
}

private fun KitsPanel.deleteSelectedIntruderScript() {
    val script = selectedIntruderScript ?: return
    val result = JOptionPane.showConfirmDialog(
        this,
        "Delete script ${script.relativePath}?",
        "Delete Intruder Script",
        JOptionPane.YES_NO_OPTION,
        JOptionPane.WARNING_MESSAGE
    )
    if (result != JOptionPane.YES_OPTION) {
        return
    }
    intruderScriptManager.deleteScript(script.scriptPath)
    reloadIntruderScripts(null)
}

internal class IntruderScriptTableModel(
    private val onEnabledChanged: (IntruderAttackScript, Boolean) -> Unit,
    private val onCategoryChanged: (IntruderAttackScript, String) -> Unit
) : AbstractTableModel() {
    private val columns = listOf("Enabled", "Script", "Category", "Path", "Status")
    private val rows = ArrayList<IntruderAttackScript>()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        return columnIndex == 0 || columnIndex == 2
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.enabled
            1 -> row.name
            2 -> row.category
            3 -> row.relativePath
            4 -> row.status
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        val row = rows.getOrNull(rowIndex) ?: return
        when (columnIndex) {
            0 -> {
                val enabled = (aValue as? Boolean) ?: return
                onEnabledChanged(row, enabled)
            }

            2 -> {
                val category = (aValue as? String)?.trim().orEmpty()
                onCategoryChanged(row, category)
            }
        }
    }

    fun setScripts(scripts: List<IntruderAttackScript>) {
        rows.clear()
        rows.addAll(scripts)
        fireTableDataChanged()
    }

    fun getAt(row: Int): IntruderAttackScript? {
        return rows.getOrNull(row)
    }

    fun indexOfScript(scriptKey: String): Int {
        return rows.indexOfFirst { it.key == scriptKey }
    }
}
