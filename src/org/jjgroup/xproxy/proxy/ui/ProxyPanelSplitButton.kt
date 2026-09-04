package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JPanel

internal fun buildSplitActionButton(mainButton: JButton, menuButton: JButton, style: SplitActionStyle): JPanel {
    mainButton.isContentAreaFilled = false
    mainButton.isOpaque = false
    mainButton.isBorderPainted = false
    mainButton.isFocusPainted = false
    mainButton.margin = Insets(0, 14, 0, 14)

    menuButton.isContentAreaFilled = false
    menuButton.isOpaque = false
    menuButton.isBorderPainted = false
    menuButton.isFocusPainted = false

    val panel = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val pressed = mainButton.model.isPressed || menuButton.model.isPressed
            val enabled = mainButton.isEnabled || menuButton.isEnabled
            val fillColor = when (style) {
                SplitActionStyle.PRIMARY -> when {
                    !enabled -> UiThemePalette.accentDisabled
                    pressed -> UiThemePalette.accentPressed
                    else -> UiThemePalette.accent
                }
                SplitActionStyle.SECONDARY -> when {
                    !enabled -> UiThemePalette.secondaryFillDisabled
                    pressed -> UiThemePalette.secondaryFillPressed
                    else -> UiThemePalette.secondaryFill
                }
            }

            val borderColor = when (style) {
                SplitActionStyle.PRIMARY -> UiThemePalette.accent
                SplitActionStyle.SECONDARY -> UiThemePalette.secondaryBorder
            }
            val separatorColor = when (style) {
                SplitActionStyle.PRIMARY -> UiThemePalette.secondarySeparator
                SplitActionStyle.SECONDARY -> UiThemePalette.secondarySeparator
            }

            g2.color = fillColor
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.color = borderColor
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)

            val dividerX = width - menuButton.width
            g2.color = separatorColor
            g2.drawLine(dividerX, 4, dividerX, height - 5)
            g2.dispose()

            mainButton.foreground = when (style) {
                SplitActionStyle.PRIMARY -> if (enabled) UiThemePalette.accentText else UiThemePalette.accentTextDisabled
                SplitActionStyle.SECONDARY -> if (enabled) UiThemePalette.secondaryText else UiThemePalette.secondaryTextDisabled
            }
            menuButton.foreground = when (style) {
                SplitActionStyle.PRIMARY -> if (enabled) UiThemePalette.accentText else UiThemePalette.accentTextDisabled
                SplitActionStyle.SECONDARY -> if (enabled) UiThemePalette.secondaryText else UiThemePalette.secondaryTextDisabled
            }

            super.paintComponent(g)
        }
    }
    panel.isOpaque = false
    panel.add(mainButton, BorderLayout.CENTER)
    panel.add(menuButton, BorderLayout.EAST)
    return panel
}
