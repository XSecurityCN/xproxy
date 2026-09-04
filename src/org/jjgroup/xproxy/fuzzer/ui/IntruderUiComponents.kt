package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.JButton

internal class OrangePrimaryButton(label: String) : JButton(label) {
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

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = when {
            !isEnabled -> fillDisabled
            model.isArmed || model.isPressed -> fillPressed
            else -> fill
        }
        g2.fillRoundRect(1, 2, width - 2, height - 4, 12, 12)
        g2.dispose()
        foreground = if (isEnabled) UiThemePalette.accentText else UiThemePalette.accentTextDisabled
        super.paintComponent(g)
    }
}
