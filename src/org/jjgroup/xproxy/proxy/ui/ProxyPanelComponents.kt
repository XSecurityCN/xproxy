package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JToggleButton

internal data class WsDisplayState(
    val rawText: String,
    val prettyText: String,
    val syntaxKind: BodyKind,
    val selectedTabIndex: Int,
    val bodySize: Int,
    val disableHighlight: Boolean,
    val deferredPrettyRaw: String?
)

internal enum class SplitActionStyle {
    PRIMARY,
    SECONDARY
}

internal class SplitMenuGlyphButton : JButton() {
    init {
        isContentAreaFilled = false
        isOpaque = false
        isBorderPainted = false
        isFocusPainted = false
        margin = Insets(0, 0, 0, 0)
        text = ""
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = foreground
        g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        val cx = width / 2f
        val cy = height / 2f
        val size = 5.0f
        g2.drawLine((cx - size).toInt(), (cy - size / 2f).toInt(), cx.toInt(), (cy + size / 2f).toInt())
        g2.drawLine(cx.toInt(), (cy + size / 2f).toInt(), (cx + size).toInt(), (cy - size / 2f).toInt())
        g2.dispose()
    }
}

internal class SignalDotIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = 10

    override fun getIconHeight(): Int = 10

    override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        g2.fillOval(x, y, iconWidth, iconHeight)
        g2.color = Color(255, 255, 255, 180)
        g2.drawOval(x, y, iconWidth, iconHeight)
        g2.dispose()
    }
}

internal class InterceptToggleButton(label: String) : JToggleButton(label) {
    init {
        isContentAreaFilled = false
        isOpaque = false
        isBorderPainted = false
        isFocusPainted = false
        margin = Insets(0, 12, 0, 12)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val pressed = model.isPressed || model.isArmed
        val enabled = isEnabled
        val fillColor = when {
            !enabled -> UiThemePalette.secondaryFillDisabled
            pressed -> UiThemePalette.secondaryFillPressed
            else -> UiThemePalette.secondaryFill
        }
        val borderColor = UiThemePalette.secondaryBorder
        g2.color = fillColor
        g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
        g2.color = borderColor
        g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
        g2.dispose()
        super.paintComponent(g)
    }
}
