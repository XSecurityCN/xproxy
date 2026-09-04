package org.jjgroup.xproxy.i18n

import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.border.TitledBorder

object I18nBinder {
    fun bindText(button: AbstractButton, key: String, vararg args: Pair<String, Any?>) {
        bind { button.text = I18n.t(key, *args) }
    }

    fun bindText(label: JLabel, key: String, vararg args: Pair<String, Any?>) {
        bind { label.text = I18n.t(key, *args) }
    }

    fun bindTab(tabs: JTabbedPane, index: Int, key: String, vararg args: Pair<String, Any?>) {
        bind {
            if (index >= 0 && index < tabs.tabCount) {
                tabs.setTitleAt(index, I18n.t(key, *args))
            }
        }
    }

    fun bindTitleBorder(component: JComponent, key: String, vararg args: Pair<String, Any?>) {
        bind {
            val border = component.border
            if (border is TitledBorder) {
                border.title = I18n.t(key, *args)
                component.repaint()
            }
        }
    }

    fun bindTableHeaders(table: JTable) {
        bind {
            val model = table.model
            for (viewIndex in 0 until table.columnModel.columnCount) {
                val column = table.columnModel.getColumn(viewIndex)
                val modelIndex = table.convertColumnIndexToModel(viewIndex)
                column.headerValue = model.getColumnName(modelIndex)
            }
            table.tableHeader?.revalidate()
            table.tableHeader?.repaint()
        }
    }

    fun bind(callback: () -> Unit) {
        val refresh = {
            if (SwingUtilities.isEventDispatchThread()) {
                callback()
            } else {
                SwingUtilities.invokeAndWait { callback() }
            }
        }
        I18n.addListener(refresh)
        refresh()
    }
}
