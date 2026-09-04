package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

internal class OrangeForwardStyleButton(label: String) : JButton(label) {
    private val fill get() = UiThemePalette.accent
    private val fillPressed get() = UiThemePalette.accentPressed
    private val fillDisabled get() = UiThemePalette.accentDisabled

    init {
        isContentAreaFilled = false
        isOpaque = false
        isBorderPainted = false
        isFocusPainted = false
        foreground = UiThemePalette.accentText
        margin = Insets(0, 12, 0, 12)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = when {
            !isEnabled -> fillDisabled
            model.isArmed || model.isPressed -> fillPressed
            else -> fill
        }
        // 填充贴近按钮边界,可视高度与普通 JButton 一致(不再因 2px 上下内缩显得偏矮)。
        g2.fillRoundRect(0, 0, width, height, 12, 12)
        g2.dispose()
        foreground = if (isEnabled) UiThemePalette.accentText else UiThemePalette.accentTextDisabled
        super.paintComponent(g)
    }
}

internal class SplitNavButton(label: String) : JButton(label) {
    private val dropdownHotZoneRatio = 0.4
    private val dropdownHotZoneMinWidth = 18
    private val separatorColor = Color(200, 200, 200)
    var onPrimaryClick: (() -> Unit)? = null
    var menuItemsProvider: (() -> List<Pair<String, () -> Unit>>) = { emptyList() }

    private fun dropdownHotZoneWidth(): Int =
        maxOf(dropdownHotZoneMinWidth, (width * dropdownHotZoneRatio).toInt())

    init {
        margin = Insets(0, 10, 0, 26)
        addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (!isEnabled || !SwingUtilities.isLeftMouseButton(e)) {
                    return
                }
                if (e.x >= width - dropdownHotZoneWidth()) {
                    showDropdown()
                } else {
                    onPrimaryClick?.invoke()
                }
            }
        })
    }

    private fun showDropdown() {
        val options = menuItemsProvider.invoke()
        if (options.isEmpty()) {
            return
        }
        val popup = JPopupMenu()
        for ((label, action) in options) {
            val item = JMenuItem(label)
            item.addActionListener { action.invoke() }
            popup.add(item)
        }
        popup.show(this, width - dropdownHotZoneWidth(), height)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.color = separatorColor
        val splitX = width - dropdownHotZoneWidth()
        g2.drawLine(splitX, 3, splitX, height - 4)
        val cx = splitX + dropdownHotZoneWidth() / 2
        val cy = height / 2 + 1
        val tri = intArrayOf(cx - 3, cx + 3, cx)
        val tiy = intArrayOf(cy - 2, cy - 2, cy + 2)
        g2.fillPolygon(tri, tiy, 3)
        g2.dispose()
    }
}
