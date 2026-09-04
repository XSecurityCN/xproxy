package org.jjgroup.xproxy.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.border.TitledBorder

class I18nBinderTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `binder refreshes button label tab and titled border`() {
        val bundled = temp.resolve("bundled")
        val user = temp.resolve("user")
        Files.createDirectories(bundled)
        Files.writeString(bundled.resolve("en.json"), """{"common":{"save":"Save"},"tabs":{"proxy":"Proxy"},"settings":{"language":{"title":"Language"}}}""")
        Files.writeString(bundled.resolve("zh-CN.json"), """{"common":{"save":"保存"},"tabs":{"proxy":"代理"},"settings":{"language":{"title":"语言"}}}""")
        I18n.configureForTests(bundled, user)
        I18n.syncUserBundles()
        I18n.setLocaleOption(I18nLocaleOption.EN)

        val button = JButton()
        val label = JLabel()
        val tabs = JTabbedPane().apply { addTab("", JPanel()) }
        val panel = JPanel().apply { border = TitledBorder("") }
        var manual = ""

        I18nBinder.bindText(button, "common.save")
        I18nBinder.bindText(label, "common.save")
        I18nBinder.bindTab(tabs, 0, "tabs.proxy")
        I18nBinder.bindTitleBorder(panel, "settings.language.title")
        I18nBinder.bind { manual = I18n.t("common.save") }

        assertEquals("Save", button.text)
        assertEquals("Save", label.text)
        assertEquals("Proxy", tabs.getTitleAt(0))
        assertEquals("Language", (panel.border as TitledBorder).title)
        assertEquals("Save", manual)

        I18n.setLocaleOption(I18nLocaleOption.ZH_CN)

        assertEquals("保存", button.text)
        assertEquals("保存", label.text)
        assertEquals("代理", tabs.getTitleAt(0))
        assertEquals("语言", (panel.border as TitledBorder).title)
        assertEquals("保存", manual)
    }
}
