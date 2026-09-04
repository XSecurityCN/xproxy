package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JToggleButton
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel

internal data class IssueRecord(
    val key: String,
    val category: String,
    val severity: String,
    val description: String,
    val historyId: Long,
    val requestRaw: String,
    val responseRaw: String,
    val method: String,
    val path: String,
    val host: String,
    val evidence: List<String> = emptyList()
)

internal data class IssueTreeNode(
    val label: String,
    val issue: IssueRecord? = null
) {
    override fun toString(): String = label
}

internal class SeverityDotIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = 10

    override fun getIconHeight(): Int = 10

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(x, y, iconWidth, iconHeight)
        g2.color = Color(255, 255, 255, 180)
        g2.drawOval(x, y, iconWidth, iconHeight)
        g2.dispose()
    }
}

internal class VerticalDockToggleButton(label: String) : JToggleButton(label) {
    init {
        isContentAreaFilled = false
        isOpaque = true
        isFocusPainted = false
        horizontalAlignment = SwingConstants.CENTER
        preferredSize = Dimension(36, 120)
        minimumSize = Dimension(36, 84)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val selectedBg = UIManager.getColor("TabbedPane.selectedBackground") ?: Color(232, 236, 243)
        val normalBg = UIManager.getColor("Panel.background") ?: Color(245, 245, 247)
        val borderColor = UIManager.getColor("Component.borderColor") ?: Color(198, 198, 204)
        g2.color = if (isSelected) selectedBg else normalBg
        g2.fillRect(0, 0, width, height)
        g2.color = borderColor
        g2.drawRect(0, 0, width - 1, height - 1)

        g2.color = UIManager.getColor("Label.foreground") ?: Color(38, 38, 42)
        val fm: FontMetrics = g2.getFontMetrics(font)
        val centerX = width / 2.0
        val centerY = height / 2.0
        g2.rotate(Math.PI / 2, centerX, centerY)
        val textWidth = fm.stringWidth(text)
        val textX = (centerX - textWidth / 2.0).toInt()
        val textY = (centerY + (fm.ascent - fm.descent) / 2.0).toInt()
        g2.drawString(text, textX, textY)
        g2.dispose()
    }
}

internal class TargetContentsTableModel : AbstractTableModel() {
    private val rows = ArrayList<ProxyHistoryEntry>()
    private val columnKeys = listOf(
        "proxy.column.id",
        "proxy.column.method",
        "proxy.column.host",
        "proxy.column.path",
        "proxy.column.status",
        "proxy.column.length",
        "proxy.column.mime_type",
        "proxy.column.title",
        "proxy.column.tls",
        "proxy.column.protocol",
        "proxy.column.modified"
    )

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columnKeys.size

    override fun getColumnName(column: Int): String = I18n.t(columnKeys[column])

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        0 -> java.lang.Long::class.java
        4, 5 -> java.lang.Integer::class.java
        else -> String::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = rows[rowIndex]
        return when (columnIndex) {
            0 -> entry.id
            1 -> entry.method
            2 -> entry.host
            3 -> entry.path
            4 -> entry.statusCode
            5 -> entry.length
            6 -> entry.mimeType
            7 -> entry.title
            8 -> if (entry.tls) "\u2713" else "\u2717"
            9 -> entry.protocol
            10 -> if (entry.modified) "\u2713" else "\u2717"
            else -> ""
        }
    }

    fun setRows(newRows: List<ProxyHistoryEntry>) {
        rows.clear()
        rows.addAll(newRows)
        fireTableDataChanged()
    }

    fun currentRows(): List<ProxyHistoryEntry> = rows.toList()

    fun getAt(row: Int): ProxyHistoryEntry? {
        if (row < 0 || row >= rows.size) return null
        return rows[row]
    }
}
