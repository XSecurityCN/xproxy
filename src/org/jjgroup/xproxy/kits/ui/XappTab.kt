package org.jjgroup.xproxy.kits.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.kits.core.loadPlugins
import org.jjgroup.xproxy.kits.model.XappPlugin
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode

internal fun KitsPanel.buildXappRootTab(): JPanel {
    val tabs = JTabbedPane().apply {
        addTab(I18n.t("kits.xapp"), buildXappTab())
        addTab(I18n.t("kits.xapp_store"), buildStoreTab())
        addTab(I18n.t("kits.api_docs"), buildApiTab("xapp APIs", apiDocEntries()))
        I18nBinder.bindTab(this, 0, "kits.xapp")
        I18nBinder.bindTab(this, 1, "kits.xapp_store")
        I18nBinder.bindTab(this, 2, "kits.api_docs")
    }
    return JPanel(BorderLayout()).apply { add(tabs, BorderLayout.CENTER) }
}

private fun KitsPanel.buildXappTab(): JPanel {
    val panel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }

    val top = JPanel(BorderLayout())
    val xappPath = xappManager.xappDirectory()
    top.add(JLabel("xapp root: $xappPath"), BorderLayout.WEST)
    val actions = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0))
    val openButton = JButton(I18n.t("common.open_folder")).apply {
        addActionListener {
            val dir = xappManager.xappDirectory().toFile()
            if (!dir.exists()) {
                dir.mkdirs()
            }
            runCatching {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(dir)
                }
            }
        }
    }
    I18nBinder.bindText(openButton, "common.open_folder")
    val reloadButton = JButton(I18n.t("common.reload")).apply {
        addActionListener {
            xappManager.loadPlugins()
        }
    }
    I18nBinder.bindText(reloadButton, "common.reload")
    actions.add(openButton)
    actions.add(reloadButton)
    top.add(actions, BorderLayout.EAST)

    xappTable.apply {
        fillsViewportHeight = true
        rowHeight = 26
        autoCreateRowSorter = true
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowSelectionAllowed = true
        columnSelectionAllowed = false
        cellSelectionEnabled = false
        selectionBackground = shadowSelection
        selectionForeground = shadowSelectionForeground
        setDefaultRenderer(java.lang.Boolean::class.java, object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component = javax.swing.JCheckBox().apply {
                this.isSelected = value as? Boolean ?: false
                horizontalAlignment = javax.swing.SwingConstants.CENTER
                isOpaque = true
                if (table.isRowSelected(row)) {
                    background = shadowSelection
                    foreground = shadowSelectionForeground
                } else {
                    background = table.background
                    foreground = table.foreground
                }
            }
        })
        listOf(70, 220, 90, 140, 380, 220).forEachIndexed { index, width ->
            columnModel.getColumn(index).preferredWidth = width
        }
        columnModel.getColumn(0).maxWidth = 90
        columnModel.getColumn(2).maxWidth = 120
        selectionModel.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            onPluginSelectionChanged()
        }
    }

    val tablePanel = JPanel(BorderLayout()).apply {
        add(JScrollPane(xappTable), BorderLayout.CENTER)
        add(
            JLabel("Select an xapp to open drawer editor and console. Each sub-directory in ~/.xproxy/xapp is one plugin.").apply {
                border = BorderFactory.createEmptyBorder(6, 2, 0, 2)
            },
            BorderLayout.SOUTH
        )
    }

    configureEditorArea()
    configureConsoleArea()
    val drawerPanel = buildDrawerPanel()

    xappSplit.apply {
        topComponent = tablePanel
        bottomComponent = drawerPanel
        resizeWeight = 1.0
        addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (!drawerVisible) return@addPropertyChangeListener
            if (xappSplit.height <= 0) return@addPropertyChangeListener
            if (xappSplit.dividerSize <= 0) return@addPropertyChangeListener
            drawerRatio = (xappSplit.dividerLocation.toDouble() / xappSplit.height.toDouble()).coerceIn(0.30, 0.85)
        }
    }
    splitDefaultDividerSize = xappSplit.dividerSize
    hideDrawer()

    panel.add(top, BorderLayout.NORTH)
    panel.add(xappSplit, BorderLayout.CENTER)
    return panel
}

private fun KitsPanel.buildDrawerPanel(): JPanel {
    val drawer = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
    }

    val editSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
        resizeWeight = 0.25
        leftComponent = JScrollPane(editorTree)
        rightComponent = RTextScrollPane(editorArea).apply { lineNumbersEnabled = true }
    }

    val editPanel = JPanel(BorderLayout()).apply {
        add(
            JPanel(BorderLayout()).apply {
                add(JLabel("xapp edit"), BorderLayout.WEST)
                add(
                    JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0)).apply { add(saveButton) },
                    BorderLayout.EAST
                )
            },
            BorderLayout.NORTH
        )
        add(editSplit, BorderLayout.CENTER)
    }

    drawerTabs.apply {
        addTab("xapp edit", editPanel)
        addTab("console", JScrollPane(consoleArea))
    }
    drawer.add(drawerTabs, BorderLayout.CENTER)
    return drawer
}

private fun KitsPanel.configureEditorArea() {
    editorArea.apply {
        isEditable = true
        syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_NONE
        setCodeFoldingEnabled(true)
        antiAliasingEnabled = true
        isBracketMatchingEnabled = true
        tabSize = 2
    }
    editorTree.apply {
        isRootVisible = false
        addTreeSelectionListener {
            val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val data = node.userObject as? KitsPanel.FileTreeNode ?: return@addTreeSelectionListener
            if (data.file) {
                loadFileIntoEditor(data.path)
            } else {
                val plugin = selectedPluginId?.let { id -> pluginsById[id] }
                if (plugin != null) {
                    selectDefaultEntryFile(plugin)
                }
            }
        }
    }
    saveButton.addActionListener {
        val path = activeEditingPath ?: return@addActionListener
        runCatching {
            Files.writeString(path, editorArea.text, Charsets.UTF_8)
            appendLog(selectedPluginId.orEmpty(), "[editor] saved ${path.fileName}")
        }
    }
}

internal fun KitsPanel.onPluginSelectionChanged() {
    val viewRow = xappTable.selectedRow
    if (viewRow < 0) {
        if (xappTableReloading) {
            return
        }
        clearDrawerState()
        hideDrawer()
        return
    }
    val modelRow = xappTable.convertRowIndexToModel(viewRow)
    val plugin = xappTableModel.getAt(modelRow) ?: return
    selectedPluginId = plugin.manifest.id
    showDrawer()
    rebuildTree(plugin)
    selectDefaultEntryFile(plugin)
    renderConsole(plugin.manifest.id)
}

internal fun KitsPanel.clearDrawerState() {
    selectedPluginId = null
    activeEditingPath = null
    editorTreeRoot.removeAllChildren()
    editorTreeModel.reload()
    editorArea.text = ""
    consoleArea.text = ""
}

internal fun KitsPanel.hideDrawer() {
    xappSplit.dividerSize = 0
    drawerVisible = false
    SwingUtilities.invokeLater {
        xappSplit.setDividerLocation(1.0)
    }
}

private fun KitsPanel.showDrawer() {
    if (drawerVisible) return
    xappSplit.dividerSize = splitDefaultDividerSize
    drawerVisible = true
    SwingUtilities.invokeLater {
        xappSplit.setDividerLocation(drawerRatio.coerceIn(0.30, 0.85))
    }
}

private fun KitsPanel.rebuildTree(plugin: XappPlugin) {
    editorTreeRoot.removeAllChildren()
    populateTree(editorTreeRoot, plugin.directory)
    editorTreeModel.reload()
    for (index in 0 until editorTree.rowCount) {
        editorTree.expandRow(index)
    }
}

private fun KitsPanel.populateTree(parent: DefaultMutableTreeNode, directory: Path) {
    val children = Files.list(directory).use { stream ->
        stream.sorted { left, right ->
            val leftDir = Files.isDirectory(left)
            val rightDir = Files.isDirectory(right)
            when {
                leftDir && !rightDir -> -1
                !leftDir && rightDir -> 1
                else -> left.fileName.toString().compareTo(right.fileName.toString(), ignoreCase = true)
            }
        }.toList()
    }
    children.forEach { path ->
        val fileNode = DefaultMutableTreeNode(KitsPanel.FileTreeNode(path, Files.isRegularFile(path)))
        parent.add(fileNode)
        if (Files.isDirectory(path)) {
            populateTree(fileNode, path)
        }
    }
}

private fun KitsPanel.selectDefaultEntryFile(plugin: XappPlugin) {
    val entryPath = plugin.scriptPath
    if (Files.exists(entryPath) && Files.isRegularFile(entryPath)) {
        selectFileInTree(entryPath)
        loadFileIntoEditor(entryPath)
        return
    }
    activeEditingPath = null
    editorArea.text = ""
}

private fun KitsPanel.selectFileInTree(path: Path) {
    val root = editorTreeModel.root as? DefaultMutableTreeNode ?: return
    val node = findNode(root, path) ?: return
    val treePath = javax.swing.tree.TreePath(node.path)
    editorTree.selectionPath = treePath
    editorTree.scrollPathToVisible(treePath)
}

private fun findNode(node: DefaultMutableTreeNode, target: Path): DefaultMutableTreeNode? {
    val data = node.userObject as? KitsPanel.FileTreeNode
    if (data?.path == target) return node
    for (index in 0 until node.childCount) {
        val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
        val found = findNode(child, target)
        if (found != null) return found
    }
    return null
}

private fun KitsPanel.loadFileIntoEditor(path: Path) {
    if (!Files.isRegularFile(path)) return
    runCatching {
        val content = Files.readString(path, Charsets.UTF_8)
        activeEditingPath = path
        editorArea.syntaxEditingStyle = syntaxStyleFor(path)
        editorArea.text = content
        editorArea.caretPosition = 0
        editorArea.revalidate()
        editorArea.repaint()
    }.onFailure {
        activeEditingPath = null
        editorArea.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_NONE
        editorArea.text = ""
    }
}
