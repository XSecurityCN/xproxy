package org.jjgroup.xproxy.codec.ui

import java.awt.Color
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

internal fun CodecPanel.startInlineRename(component: Component, header: JPanel, label: JLabel) {
    if (header.components.any { it is JTextField }) return
    val tab = tabByComponent[component] ?: return
    val original = label.text
    val editor = JTextField(tab.title).apply {
        columns = maxOf(6, original.length.coerceAtMost(28))
        border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        font = label.font
        foreground = label.foreground
        caretColor = label.foreground
        selectionColor = tabAccentColor
        selectedTextColor = Color(255, 255, 255)
        background = (header.getClientProperty("xproxy.tab.fill") as? Color) ?: tabSelectedBg
        isOpaque = true
    }

    var finished = false
    var clickAwayListener: java.awt.event.AWTEventListener? = null

    fun restore(newValue: String?) {
        if (finished) return
        finished = true
        clickAwayListener?.let { listener ->
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            clickAwayListener = null
        }
        if (newValue != null) {
            val trimmed = newValue.trim()
            if (trimmed.isNotEmpty()) {
                tab.title = uniqueTitle(trimmed, tab.recordId)
                refreshTabHeaderTitles()
                persistState()
            }
        }
        val editorIndex = header.components.indexOf(editor)
        if (editorIndex != -1) {
            header.remove(editor)
        }
        if (header.components.indexOf(label) == -1) {
            header.add(label, 0)
        }
        header.revalidate()
        header.repaint()
    }

    editor.addActionListener { restore(editor.text) }
    editor.registerKeyboardAction(
        { restore(null) },
        KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
        JComponent.WHEN_FOCUSED
    )
    editor.addFocusListener(object : java.awt.event.FocusAdapter() {
        override fun focusLost(e: java.awt.event.FocusEvent?) {
            restore(editor.text)
        }
    })
    clickAwayListener = java.awt.event.AWTEventListener { event ->
        val mouseEvent = event as? java.awt.event.MouseEvent ?: return@AWTEventListener
        if (mouseEvent.id != java.awt.event.MouseEvent.MOUSE_PRESSED) return@AWTEventListener
        val source = mouseEvent.source as? Component ?: run {
            SwingUtilities.invokeLater { restore(editor.text) }
            return@AWTEventListener
        }
        if (SwingUtilities.isDescendingFrom(source, editor)) return@AWTEventListener
        SwingUtilities.invokeLater { restore(editor.text) }
    }
    java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(clickAwayListener, java.awt.AWTEvent.MOUSE_EVENT_MASK)

    val labelIndex = header.components.indexOf(label)
    if (labelIndex != -1) {
        header.remove(labelIndex)
        header.add(editor, 0)
    } else {
        header.add(editor, 0)
    }
    header.revalidate()
    header.repaint()
    SwingUtilities.invokeLater {
        editor.requestFocusInWindow()
        editor.selectAll()
    }
}
