package org.jjgroup.xproxy.kits.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.kits.core.XappManager
import org.jjgroup.xproxy.kits.core.IntruderScriptManager
import org.jjgroup.xproxy.kits.core.loadPlugins
import org.jjgroup.xproxy.kits.core.scan
import org.jjgroup.xproxy.kits.core.rewriteBeforeRequest
import org.jjgroup.xproxy.kits.core.rewriteAfterRequest
import org.jjgroup.xproxy.kits.core.updateEnabled
import org.jjgroup.xproxy.kits.model.IntruderAttackScript
import org.jjgroup.xproxy.kits.model.XappPlugin
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.nio.file.Path
import java.util.ArrayDeque
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.table.TableCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class KitsPanel(
    private val projectDataStore: ProjectDataStore?,
    private val onXappHistoryEntry: ((ProxyHistoryEntry) -> Unit)? = null
) : JPanel(BorderLayout()) {
    internal val xappManager = XappManager(projectDataStore, onXappHistoryEntry)
    internal val intruderScriptManager = IntruderScriptManager(projectDataStore)
    internal val xappTableModel = XappTableModel(
        onEnabledChanged = { plugin, enabled -> xappManager.updateEnabled(plugin.manifest.id, enabled) }
    )
    internal val xappTable = object : JTable(xappTableModel) {
        override fun prepareRenderer(renderer: TableCellRenderer, row: Int, column: Int): Component {
            val component = super.prepareRenderer(renderer, row, column)
            if (isRowSelected(row)) {
                component.background = shadowSelection
                component.foreground = shadowSelectionForeground
            } else {
                component.background = background
                component.foreground = foreground
            }
            return component
        }
    }
    internal val editorTreeRoot = DefaultMutableTreeNode("files")
    internal val editorTreeModel = DefaultTreeModel(editorTreeRoot)
    internal val editorTree = JTree(editorTreeModel)
    internal val editorArea = RSyntaxTextArea(20, 80)
    internal val consoleArea = JTextArea()
    internal val saveButton = JButton(I18n.t("common.save"))
    internal val drawerTabs = JTabbedPane()
    internal val xappSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT)
    internal val logLinesByPluginId = LinkedHashMap<String, ArrayDeque<String>>()
    internal val pluginsById = LinkedHashMap<String, XappPlugin>()
    internal var disposePluginListener: (() -> Unit)? = null
    internal var disposeLogListener: (() -> Unit)? = null
    internal var selectedPluginId: String? = null
    internal var activeEditingPath: Path? = null
    internal var splitDefaultDividerSize = 0
    internal var drawerVisible = false
    internal var drawerRatio = 0.58
    internal var xappTableReloading = false

    internal val intruderTableModel = IntruderScriptTableModel(
        onEnabledChanged = { script, enabled ->
            intruderScriptManager.updateEnabled(script.key, enabled)
            reloadIntruderScripts(script.key)
        },
        onCategoryChanged = { script, category ->
            intruderScriptManager.updateCategory(script.key, category)
            reloadIntruderScripts(script.key)
        }
    )
    internal val intruderTable = JTable(intruderTableModel)
    internal val intruderEditorArea = RSyntaxTextArea(20, 80)
    internal val intruderSaveButton = JButton(I18n.t("common.save"))
    internal val intruderNewButton = JButton(I18n.t("kits.new_script"))
    internal val intruderDeleteButton = JButton(I18n.t("common.delete"))
    internal val intruderOpenDirButton = JButton(I18n.t("common.open_folder"))
    internal var selectedIntruderScript: IntruderAttackScript? = null
    internal val shadowSelection = Color(220, 224, 232)
    internal val shadowSelectionForeground: Color = UIManager.getColor("Table.foreground") ?: Color(32, 36, 42)

    internal data class FileTreeNode(val path: Path, val file: Boolean) {
        override fun toString(): String = path.fileName?.toString() ?: path.toString()
    }

    internal data class ApiDocEntry(
        val category: String,
        val name: String,
        val code: String
    )

    internal data class ApiTreeNode(
        val label: String,
        val entry: ApiDocEntry? = null
    ) {
        override fun toString(): String = label
    }

    init {
        val tabs = JTabbedPane()
        tabs.addTab(I18n.t("kits.xapp"), buildXappRootTab())
        tabs.addTab(I18n.t("kits.xintruder"), buildIntruderRootTab())
        tabs.addTab(I18n.t("kits.xserver"), JPanel())
        I18nBinder.bindTab(tabs, 0, "kits.xapp")
        I18nBinder.bindTab(tabs, 1, "kits.xintruder")
        I18nBinder.bindTab(tabs, 2, "kits.xserver")
        I18nBinder.bindText(saveButton, "common.save")
        I18nBinder.bindText(intruderSaveButton, "common.save")
        I18nBinder.bindText(intruderNewButton, "kits.new_script")
        I18nBinder.bindText(intruderDeleteButton, "common.delete")
        I18nBinder.bindText(intruderOpenDirButton, "common.open_folder")
        tabs.setEnabledAt(2, false)
        add(tabs, BorderLayout.CENTER)

        disposePluginListener = xappManager.addListener { plugins ->
            SwingUtilities.invokeLater {
                val previousSelected = selectedPluginId
                xappTableReloading = true
                pluginsById.clear()
                plugins.forEach { plugin -> pluginsById[plugin.manifest.id] = plugin }
                try {
                    xappTableModel.setPlugins(plugins)
                    if (!previousSelected.isNullOrBlank()) {
                        val row = xappTableModel.indexOfPlugin(previousSelected)
                        if (row >= 0) {
                            val viewRow = xappTable.convertRowIndexToView(row)
                            if (viewRow >= 0) {
                                xappTable.selectionModel.setSelectionInterval(viewRow, viewRow)
                            }
                        }
                    }
                } finally {
                    xappTableReloading = false
                }
                if (selectedPluginId != null && !pluginsById.containsKey(selectedPluginId)) {
                    clearDrawerState()
                    hideDrawer()
                }
            }
        }
        disposeLogListener = xappManager.addLogListener { pluginId, line ->
            SwingUtilities.invokeLater {
                appendLog(pluginId, line)
            }
        }
        xappManager.loadPlugins()
        reloadIntruderScripts(null)
    }

    fun onProxyHistoryEntry(entry: ProxyHistoryEntry) {
        xappManager.scan(entry)
    }

    fun onBeforeRequestRewrite(requestRaw: String, host: String, tls: Boolean): String {
        return xappManager.rewriteBeforeRequest(requestRaw, host, tls)
    }

    fun onAfterResponseRewrite(requestRaw: String, responseRaw: String, host: String, tls: Boolean): String {
        return xappManager.rewriteAfterRequest(requestRaw, responseRaw, host, tls)
    }

    fun shutdown() {
        disposePluginListener?.invoke()
        disposePluginListener = null
        disposeLogListener?.invoke()
        disposeLogListener = null
        xappManager.shutdown()
    }
}
