package org.jjgroup.xproxy.project.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class ProjectLauncherTest {

    @Test
    fun `actions panel excludes refresh button`() {
        val panel = ProjectLauncher.createActionsPanel(
            newButton = JButton("New"),
            deleteButton = JButton("Delete"),
            loadButton = JButton("Load"),
            cancelButton = JButton("Exit")
        )

        val labels = panel.components.mapNotNull { (it as? JButton)?.text }
        assertEquals(listOf("New", "Load", "Delete", "Exit"), labels)
        assertFalse(labels.contains("Refresh"))
    }

    @Test
    fun `single click selects project row without loading it`() {
        val table = projectTable()
        var loaded = 0

        val listener = ProjectLauncher.createProjectTableMouseListener(
            table = table,
            openProjectPath = {},
            loadSelectedProject = { loaded += 1 }
        )

        listener.mouseClicked(tableClick(table, clickCount = 1, column = 0))

        assertEquals(1, table.selectedRow)
        assertEquals(0, loaded)
    }

    @Test
    fun `double click selects and loads project row`() {
        val table = projectTable()
        var loaded = 0

        val listener = ProjectLauncher.createProjectTableMouseListener(
            table = table,
            openProjectPath = {},
            loadSelectedProject = { loaded += 1 }
        )

        listener.mouseClicked(tableClick(table, clickCount = 2, column = 0))

        assertEquals(1, table.selectedRow)
        assertEquals(1, loaded)
    }

    private fun projectTable(): JTable {
        val model = DefaultTableModel(arrayOf("Project Name", "Date", "Project Path", ""), 2)
        return JTable(model).apply {
            setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            setSize(400, 48)
            rowHeight = 24
            columnModel.getColumn(0).preferredWidth = 120
            columnModel.getColumn(1).preferredWidth = 80
            columnModel.getColumn(2).preferredWidth = 160
            columnModel.getColumn(3).preferredWidth = 40
            doLayout()
        }
    }

    private fun tableClick(table: JTable, clickCount: Int, column: Int): MouseEvent {
        val row = 1
        val rect = table.getCellRect(row, column, true)
        return MouseEvent(
            table,
            MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(),
            0,
            rect.x + rect.width / 2,
            rect.y + rect.height / 2,
            clickCount,
            false,
            MouseEvent.BUTTON1
        )
    }
}
