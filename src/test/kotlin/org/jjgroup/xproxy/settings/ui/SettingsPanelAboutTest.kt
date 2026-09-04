package org.jjgroup.xproxy.settings.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nLocaleOption
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane

class SettingsPanelAboutTest {
    @TempDir
    lateinit var temp: Path

    private fun JComponent.labelTexts(): List<String> = descendants().mapNotNull { (it as? JLabel)?.text }

    private fun JComponent.descendants(): List<java.awt.Component> {
        val result = ArrayList<java.awt.Component>()
        fun visit(component: java.awt.Component) {
            result.add(component)
            when (component) {
                is JScrollPane -> component.viewport?.view?.let { visit(it) }
                is java.awt.Container -> component.components.forEach { visit(it) }
            }
        }
        visit(this)
        return result
    }
}
