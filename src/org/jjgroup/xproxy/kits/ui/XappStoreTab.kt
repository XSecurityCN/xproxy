package org.jjgroup.xproxy.kits.ui

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.kits.core.XappStoreClient
import org.jjgroup.xproxy.kits.core.loadPlugins
import org.jjgroup.xproxy.kits.model.StoreXapp
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowFilter
import javax.swing.SwingWorker
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

internal enum class InstallStatus(val label: String) {
    INSTALL("Install"),
    UPDATE("Update"),
    INSTALLED("Installed")
}

internal class StoreTableModel : AbstractTableModel() {
    private val columns = listOf("", "Name", "Version", "Author", "Description", "Status")
    private val rows = ArrayList<StoreXapp>()
    private val checked = HashSet<String>()
    private val statusMap = HashMap<String, InstallStatus>()

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]

    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val row = rows[rowIndex]
        return when (columnIndex) {
            0 -> checked.contains(row.id)
            1 -> row.name
            2 -> row.version
            3 -> row.author
            4 -> row.description
            5 -> statusMap[row.id]?.label ?: ""
            else -> ""
        }
    }

    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (columnIndex != 0) return
        val row = rows.getOrNull(rowIndex) ?: return
        val selected = (aValue as? Boolean) ?: return
        if (selected) checked.add(row.id) else checked.remove(row.id)
        fireTableCellUpdated(rowIndex, columnIndex)
    }

    fun setXapps(xapps: List<StoreXapp>, client: XappStoreClient) {
        rows.clear()
        checked.clear()
        statusMap.clear()
        rows.addAll(xapps)
        for (xapp in xapps) {
            statusMap[xapp.id] = computeStatus(xapp, client)
        }
        fireTableDataChanged()
    }

    fun getCheckedXapps(): List<StoreXapp> =
        rows.filter { checked.contains(it.id) && statusMap[it.id] != InstallStatus.INSTALLED }

    fun getAt(row: Int): StoreXapp? = rows.getOrNull(row)

    fun refreshStatus(client: XappStoreClient) {
        for (xapp in rows) {
            statusMap[xapp.id] = computeStatus(xapp, client)
        }
        checked.clear()
        fireTableDataChanged()
    }

    private fun computeStatus(xapp: StoreXapp, client: XappStoreClient): InstallStatus {
        if (!client.isInstalled(xapp.id)) return InstallStatus.INSTALL
        val localVersion = client.getInstalledVersion(xapp.id)
        return if (localVersion != xapp.version) InstallStatus.UPDATE else InstallStatus.INSTALLED
    }
}

internal fun KitsPanel.buildStoreTab(): JPanel {
    val client = XappStoreClient()
    val tableModel = StoreTableModel()
    val table = JTable(tableModel).apply {
        fillsViewportHeight = true
        rowHeight = 26
        autoCreateRowSorter = false
        setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
        selectionBackground = shadowSelection
        selectionForeground = shadowSelectionForeground
        setDefaultRenderer(java.lang.Boolean::class.java, object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component = javax.swing.JCheckBox().apply {
                this.isSelected = value as? Boolean ?: false
                horizontalAlignment = javax.swing.SwingConstants.CENTER
                isOpaque = true
                background = if (table.isRowSelected(row)) shadowSelection else table.background
                foreground = if (table.isRowSelected(row)) shadowSelectionForeground else table.foreground
            }
        })
        listOf(40, 200, 80, 120, 380, 100).forEachIndexed { index, width ->
            columnModel.getColumn(index).preferredWidth = width
        }
        columnModel.getColumn(0).maxWidth = 60
        columnModel.getColumn(2).maxWidth = 100
        columnModel.getColumn(5).maxWidth = 120
    }

    val sorter = TableRowSorter(tableModel)
    table.rowSorter = sorter

    val searchField = JTextField(16)
    searchField.addCaretListener {
        val text = searchField.text.orEmpty().trim()
        sorter.rowFilter = if (text.isEmpty()) null else RowFilter.regexFilter("(?i)$text", 1, 4)
    }

    val refreshButton = JButton(I18n.t("common.refresh"))
    val installButton = JButton(I18n.t("kits.install_selected"))
    val statusLabel = JLabel("")
    I18nBinder.bindText(refreshButton, "common.refresh")
    I18nBinder.bindText(installButton, "kits.install_selected")

    val toolbar = JPanel(BorderLayout()).apply {
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(refreshButton)
            add(statusLabel)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            add(JLabel(I18n.t("common.search")))
            add(searchField)
        }
        add(left, BorderLayout.WEST)
        add(right, BorderLayout.EAST)
    }

    val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
        add(installButton)
    }

    // Load cached index on init
    val cached = client.loadCachedIndex()
    if (cached.isNotEmpty()) {
        tableModel.setXapps(cached, client)
    } else {
        statusLabel.text = "Click Refresh to fetch xapp list"
    }

    refreshButton.addActionListener {
        refreshButton.isEnabled = false
        statusLabel.text = "Fetching..."
        object : SwingWorker<List<StoreXapp>, Void>() {
            override fun doInBackground(): List<StoreXapp> = client.fetchIndex()
            override fun done() {
                refreshButton.isEnabled = true
                try {
                    val xapps = get()
                    tableModel.setXapps(xapps, client)
                    statusLabel.text = "${xapps.size} xapps available"
                } catch (e: Exception) {
                    statusLabel.text = "Failed to fetch xapp index"
                    Utils.err("Xapp store fetch failed: ${e.message}")
                }
            }
        }.execute()
    }

    installButton.addActionListener {
        val toInstall = tableModel.getCheckedXapps()
        if (toInstall.isEmpty()) return@addActionListener
        installButton.isEnabled = false
        statusLabel.text = "Installing ${toInstall.size} xapp(s)..."
        object : SwingWorker<List<String>, String>() {
            override fun doInBackground(): List<String> {
                val failures = mutableListOf<String>()
                for (xapp in toInstall) {
                    publish("Installing ${xapp.name}...")
                    try {
                        client.downloadXapp(xapp)
                    } catch (e: Exception) {
                        failures.add(xapp.name)
                        Utils.err("Install failed for ${xapp.name}: ${e.message}")
                    }
                }
                return failures
            }

            override fun process(chunks: MutableList<String>) {
                statusLabel.text = chunks.lastOrNull() ?: ""
            }

            override fun done() {
                installButton.isEnabled = true
                try {
                    val failures = get()
                    xappManager.loadPlugins()
                    tableModel.refreshStatus(client)
                    statusLabel.text = if (failures.isEmpty()) {
                        "Install complete"
                    } else {
                        "Install failed: ${failures.joinToString(", ")}"
                    }
                } catch (e: Exception) {
                    statusLabel.text = "Install error"
                    Utils.err("Xapp store install error: ${e.message}")
                }
            }
        }.execute()
    }

    return JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(toolbar, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
        add(bottomBar, BorderLayout.SOUTH)
    }
}
