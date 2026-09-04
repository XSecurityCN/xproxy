package org.jjgroup.xproxy.fuzzer.ui

import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSeparator
import javax.swing.ListCellRenderer
import javax.swing.border.Border
import javax.swing.border.EmptyBorder

class ComboBoxRenderer(padding: Int) : JLabel(), ListCellRenderer<Any> {
    private val insetBorder: Border = EmptyBorder(padding, padding, padding, padding)
    private val defaultRenderer = DefaultListCellRenderer()

    override fun getListCellRendererComponent(
        list: JList<out Any>,
        value: Any,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        if (value is JSeparator) {
            return value
        }
        text = value.toString()
        val renderer = defaultRenderer.getListCellRendererComponent(
            list,
            value,
            index,
            isSelected,
            cellHasFocus,
        ) as JLabel
        renderer.border = insetBorder
        return renderer
    }
}
