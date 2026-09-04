package org.jjgroup.xproxy.ui.marking

import org.jjgroup.xproxy.i18n.I18n
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JMenu
import javax.swing.JMenuItem

/**
 * 流量行右键 "Highlight" 子菜单:调色板每色一项(带色块图标)+ 分隔后的 Clear。
 *
 * 作用于 [idsProvider] 返回的所有选中 entry id(支持多选);点击经 [TrafficHighlightRegistry]
 * 立即着色并通知监听器 repaint。`Clear` 等价于设为 NONE。
 */
fun buildHighlightSubmenu(
    kind: TrafficHighlightRegistry.Kind,
    idsProvider: () -> Set<Long>
): JMenu {
    val menu = JMenu(I18n.t("menu.highlight"))
    TrafficHighlight.colorChoices.forEach { color ->
        val item = JMenuItem(I18n.t(color.i18nKey), ColorSwatchIcon(color.swatch()))
        item.addActionListener {
            applyHighlight(kind, idsProvider(), color)
        }
        menu.add(item)
    }
    menu.addSeparator()
    val clear = JMenuItem(I18n.t("menu.clear_highlight"))
    clear.addActionListener {
        applyHighlight(kind, idsProvider(), TrafficHighlight.NONE)
    }
    menu.add(clear)
    return menu
}

private fun applyHighlight(kind: TrafficHighlightRegistry.Kind, ids: Set<Long>, color: TrafficHighlight) {
    if (ids.isEmpty()) return
    ids.forEach { TrafficHighlightRegistry.set(kind, it, color) }
}

/** 菜单项左侧的小色块图标。 */
private class ColorSwatchIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = 14
    override fun getIconHeight(): Int = 14
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillRoundRect(x, y, iconWidth, iconHeight, 4, 4)
        g2.color = Color(120, 120, 120, 180)
        g2.drawRoundRect(x, y, iconWidth - 1, iconHeight - 1, 4, 4)
        g2.dispose()
    }
}
