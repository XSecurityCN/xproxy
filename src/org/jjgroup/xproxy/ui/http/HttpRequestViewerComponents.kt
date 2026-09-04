package org.jjgroup.xproxy.ui.http

import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints

enum class RequestBodyEncodingTarget {
    JSON,
    FORM_DATA,
    MULTIPART,
    XML
}

internal enum class PayloadViewMode {
    MODIFIED,
    ORIGINAL
}

internal class WrapStateIcon(private val enabled: Boolean) : javax.swing.Icon {
    override fun getIconWidth(): Int = 14
    override fun getIconHeight(): Int = 14

    override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color(90, 90, 96)
        g2.drawLine(x + 1, y + 4, x + 9, y + 4)
        g2.drawLine(x + 1, y + 8, x + 9, y + 8)
        g2.drawLine(x + 9, y + 4, x + 9, y + 11)
        g2.drawLine(x + 9, y + 11, x + 6, y + 8)
        if (!enabled) {
            g2.color = Color(180, 70, 70)
            g2.drawLine(x + 1, y + 12, x + 12, y + 1)
        }
        g2.dispose()
    }
}
