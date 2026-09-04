package org.jjgroup.xproxy.kits.ui

import org.jjgroup.xproxy.kits.model.XappPlugin
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import javax.swing.table.AbstractTableModel

internal fun KitsPanel.configureConsoleArea() {
    consoleArea.apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
}

internal fun KitsPanel.appendLog(pluginId: String, line: String) {
    if (pluginId.isBlank()) return
    val queue = logLinesByPluginId.getOrPut(pluginId) { ArrayDeque() }
    queue.addLast(line)
    while (queue.size > 500) {
        queue.removeFirst()
    }
    if (pluginId == selectedPluginId) {
        renderConsole(pluginId)
    }
}

internal fun KitsPanel.renderConsole(pluginId: String) {
    val lines = logLinesByPluginId[pluginId].orEmpty()
    consoleArea.text = lines.joinToString("\n")
    consoleArea.caretPosition = consoleArea.document.length
}

internal fun syntaxStyleFor(path: Path): String {
    val filename = path.fileName?.toString()?.lowercase().orEmpty()
    return when {
        filename.endsWith(".py") -> SyntaxConstants.SYNTAX_STYLE_PYTHON
        filename.endsWith(".json") -> SyntaxConstants.SYNTAX_STYLE_JSON
        filename.endsWith(".xml") -> SyntaxConstants.SYNTAX_STYLE_XML
        filename.endsWith(".yaml") || filename.endsWith(".yml") -> SyntaxConstants.SYNTAX_STYLE_YAML
        filename.endsWith(".js") -> SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT
        filename.endsWith(".ts") -> SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT
        filename.endsWith(".java") -> SyntaxConstants.SYNTAX_STYLE_JAVA
        filename.endsWith(".kt") || filename.endsWith(".kts") -> SyntaxConstants.SYNTAX_STYLE_JAVA
        filename.endsWith(".sh") || filename.endsWith(".bash") -> SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL
        filename.endsWith(".md") -> SyntaxConstants.SYNTAX_STYLE_MARKDOWN
        filename.endsWith(".ini") || filename.endsWith(".cfg") || filename.endsWith(".conf") -> SyntaxConstants.SYNTAX_STYLE_INI
        filename.endsWith(".properties") -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE
        else -> SyntaxConstants.SYNTAX_STYLE_NONE
    }
}

internal class XappTableModel(
    private val onEnabledChanged: (XappPlugin, Boolean) -> Unit
) : AbstractTableModel() {
    private val columns = listOf("Enabled", "Plugin", "Version", "Author", "Description", "Status")
    private val rows = ArrayList<XappPlugin>()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> row.enabled
            1 -> row.manifest.name
            2 -> row.manifest.version
            3 -> row.manifest.author
            4 -> row.manifest.description
            5 -> row.loadError ?: if (!Files.exists(row.scriptPath)) "Missing script" else "Ready"
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != 0) return
        val row = rows.getOrNull(rowIndex) ?: return
        val enabled = (aValue as? Boolean) ?: return
        onEnabledChanged(row, enabled)
    }

    fun setPlugins(plugins: List<XappPlugin>) {
        rows.clear()
        rows.addAll(plugins)
        fireTableDataChanged()
    }

    fun getAt(row: Int): XappPlugin? = rows.getOrNull(row)

    fun indexOfPlugin(pluginId: String): Int =
        rows.indexOfFirst { it.manifest.id == pluginId }
}
