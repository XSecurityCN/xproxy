package org.jjgroup.xproxy.project.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.project.core.ProjectRecord
import org.jjgroup.xproxy.project.core.ProjectRegistry
import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

object ProjectLauncher {
    internal fun createTableModelForTests(): AbstractTableModel = ProjectTableModel()

    fun selectProject(registry: ProjectRegistry): ProjectRecord? {
    val dialog = JDialog(null as JFrame?, I18n.t("project.management.title"), true)
        dialog.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        dialog.layout = BorderLayout(8, 8)
        runCatching {
            val iconUrl = UiThemePalette::class.java.classLoader.getResource("xproxy-icon.png")
            iconUrl?.let { dialog.setIconImage(javax.imageio.ImageIO.read(it)) }
        }

        val tableModel = ProjectTableModel()
        val table = JTable(tableModel).apply {
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            rowSelectionAllowed = true
            columnSelectionAllowed = false
            autoCreateRowSorter = true
            fillsViewportHeight = true
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = (rowHeight + 6)
            columnModel.getColumn(0).preferredWidth = 240
            columnModel.getColumn(1).preferredWidth = 130
            columnModel.getColumn(2).preferredWidth = 320
            columnModel.getColumn(3).maxWidth = 56
            columnModel.getColumn(3).minWidth = 56
            columnModel.getColumn(3).cellRenderer = PathOpenButtonRenderer()
            columnModel.getColumn(2).cellRenderer = DefaultTableCellRenderer().apply {
                horizontalAlignment = SwingConstants.LEFT
                toolTipText = I18n.t("project.path")
            }
        }
        configureTableAlignment(table)

        fun refreshProjects() {
            tableModel.setRows(registry.listProjects())
            if (tableModel.rowCount > 0) {
                table.setRowSelectionInterval(0, 0)
            }
        }

        var selected: ProjectRecord? = null

        fun loadSelectedProject() {
            val viewRow = table.selectedRow
            if (viewRow < 0) {
                return
            }
            val modelRow = table.convertRowIndexToModel(viewRow)
            val record = tableModel.recordAt(modelRow) ?: return
            registry.markOpened(record.id)
            selected = record
            dialog.dispose()
        }

        fun openProjectPath(rowViewIndex: Int) {
            if (rowViewIndex < 0) {
                return
            }
            val modelRow = table.convertRowIndexToModel(rowViewIndex)
            val record = tableModel.recordAt(modelRow) ?: return
            try {
                ProcessBuilder("open", record.projectDir).start()
            } catch (_: Exception) {
            }
        }

        val projectRootLabel = JLabel(I18n.t("project.root", "path" to registry.projectsRoot()))
        val changeRootButton = JButton(I18n.t("common.change"))
        I18nBinder.bindText(changeRootButton, "common.change")
        val top = JPanel(BorderLayout())
        top.border = BorderFactory.createEmptyBorder(8, 8, 0, 8)
        top.add(projectRootLabel, BorderLayout.CENTER)
        top.add(changeRootButton, BorderLayout.EAST)

        val center = JScrollPane(table)
        center.preferredSize = Dimension(760, 380)

        val newButton = JButton(I18n.t("common.new"))
        newButton.background = UiThemePalette.accent
        newButton.foreground = UiThemePalette.accentText
        newButton.isFocusPainted = false
        val loadButton = JButton(I18n.t("common.load"))
        val deleteButton = JButton(I18n.t("common.delete"))
        val cancelButton = JButton(I18n.t("common.exit"))
        I18nBinder.bindText(newButton, "common.new")
        I18nBinder.bindText(loadButton, "common.load")
        I18nBinder.bindText(deleteButton, "common.delete")
        I18nBinder.bindText(cancelButton, "common.exit")

        fun chooseProjectsRoot(initialRoot: Path): Path? {
            val chooser = JFileChooser(initialRoot.toFile())
            chooser.dialogTitle = I18n.t("project.select_root")
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.isAcceptAllFileFilterUsed = false
            chooser.selectedFile = initialRoot.toFile()
            val result = chooser.showOpenDialog(dialog)
            if (result != JFileChooser.APPROVE_OPTION) {
                return null
            }
            return chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()
        }

        changeRootButton.addActionListener {
            val selectedRoot = chooseProjectsRoot(registry.projectsRoot()) ?: return@addActionListener
            val confirm = JOptionPane.showConfirmDialog(
                dialog,
                I18n.t("project.confirm_root_message", "path" to selectedRoot),
                I18n.t("project.confirm_root_title"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )
            if (confirm == JOptionPane.YES_OPTION) {
                val persistedRoot = registry.updateProjectsRoot(selectedRoot)
                projectRootLabel.text = I18n.t("project.root", "path" to persistedRoot)
            }
        }

        newButton.addActionListener {
            val baseName = JOptionPane.showInputDialog(dialog, I18n.t("project.base_name"), I18n.t("project.new_project"), JOptionPane.PLAIN_MESSAGE)
            if (baseName != null) {
                val trimmed = baseName.trim()
                if (trimmed.isNotEmpty()) {
                    val created = registry.createProject(trimmed)
                    registry.markOpened(created.id)
                    selected = created
                    dialog.dispose()
                }
            }
        }

        loadButton.addActionListener { loadSelectedProject() }

        deleteButton.addActionListener {
            val selectedRows = table.selectedRows
            if (selectedRows.isEmpty()) {
                return@addActionListener
            }
            val records = selectedRows
                .map { viewRow -> table.convertRowIndexToModel(viewRow) }
                .mapNotNull { modelRow -> tableModel.recordAt(modelRow) }
                .distinctBy { it.id }
            if (records.isEmpty()) {
                return@addActionListener
            }
            val summary = if (records.size == 1) {
                I18n.t("project.delete_one_confirm", "name" to records.first().displayName)
            } else {
                I18n.t("project.delete_many_confirm", "count" to records.size)
            }
            val confirm = JOptionPane.showConfirmDialog(
                dialog,
                summary,
                I18n.t("project.delete_title"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            if (confirm == JOptionPane.YES_OPTION) {
                records.forEach { registry.deleteProject(it) }
                refreshProjects()
            }
        }

        cancelButton.addActionListener { dialog.dispose() }

        table.addMouseListener(createProjectTableMouseListener(table, ::openProjectPath, ::loadSelectedProject))

        val actions = createActionsPanel(newButton, deleteButton, loadButton, cancelButton)

        dialog.add(top, BorderLayout.NORTH)
        dialog.add(center, BorderLayout.CENTER)
        dialog.add(actions, BorderLayout.SOUTH)

        refreshProjects()
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
        return selected
    }

    private fun configureTableAlignment(table: JTable) {
        val leftRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.LEFT
        }
        (table.tableHeader.defaultRenderer as? DefaultTableCellRenderer)?.horizontalAlignment = SwingConstants.LEFT
        for (columnIndex in 0 until table.columnModel.columnCount) {
            if (columnIndex == 3) {
                continue
            }
            table.columnModel.getColumn(columnIndex).cellRenderer = leftRenderer
        }
    }

    internal fun createActionsPanel(
        newButton: JButton,
        deleteButton: JButton,
        loadButton: JButton,
        cancelButton: JButton
    ): JPanel {
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8))
        actions.border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
        actions.add(newButton)
        actions.add(loadButton)
        actions.add(deleteButton)
        actions.add(cancelButton)
        return actions
    }

    internal fun createProjectTableMouseListener(
        table: JTable,
        openProjectPath: (Int) -> Unit,
        loadSelectedProject: () -> Unit
    ): MouseAdapter {
        return object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0 || col < 0) {
                    return
                }
                if (col == 3 && SwingUtilities.isLeftMouseButton(e)) {
                    openProjectPath(row)
                    return
                }
                if (SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown && !e.isControlDown && !e.isMetaDown) {
                    table.setRowSelectionInterval(row, row)
                    if (e.clickCount >= 2) {
                        loadSelectedProject()
                    }
                }
            }
        }
    }

    private class ProjectTableModel : AbstractTableModel() {
        private val columnKeys = arrayOf("project.column.name", "project.column.date", "project.column.path", "")
        private val rows = mutableListOf<ProjectRecord>()

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columnKeys.size

        override fun getColumnName(column: Int): String = columnKeys[column].takeIf { it.isNotBlank() }?.let { I18n.t(it) }.orEmpty()

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val record = rows[rowIndex]
            return when (columnIndex) {
                0 -> record.baseName
                1 -> record.createdDate
                2 -> record.projectDir
                3 -> "..."
                else -> ""
            }
        }

        fun setRows(records: List<ProjectRecord>) {
            rows.clear()
            rows.addAll(records)
            fireTableDataChanged()
        }

        fun recordAt(index: Int): ProjectRecord? {
            if (index !in rows.indices) {
                return null
            }
            return rows[index]
        }
    }

    private class PathOpenButtonRenderer : JButton(), javax.swing.table.TableCellRenderer {
        init {
            text = "..."
            isFocusable = false
            margin = java.awt.Insets(1, 6, 1, 6)
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            foreground = if (isSelected) table.selectionForeground else table.foreground
            background = if (isSelected) table.selectionBackground else table.background
            return this
        }
    }
}
