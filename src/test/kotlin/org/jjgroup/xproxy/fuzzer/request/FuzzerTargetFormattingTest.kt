package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nLocaleOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FuzzerTargetFormattingTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `target label translates word but keeps ascii colon`() {
        val bundled = temp.resolve("bundled")
        val user = temp.resolve("user")
        Files.createDirectories(bundled)
        Files.writeString(bundled.resolve("en.json"), """{"common":{"target":"Target"}}""")
        Files.writeString(bundled.resolve("zh-CN.json"), """{"common":{"target":"目标"}}""")
        I18n.configureForTests(bundled, user, persistedLanguage = I18nLocaleOption.SYSTEM.key)
        I18n.registerSettings()
        I18n.syncUserBundles()
        val target = HttpService("example.com", 443, "https")

        I18n.setLocaleOption(I18nLocaleOption.EN)
        assertEquals("Target: https://example.com:443", formatTarget(target))

        I18n.setLocaleOption(I18nLocaleOption.ZH_CN)
        assertEquals("目标: https://example.com:443", formatTarget(target))
    }

    @Test
    fun `empty target renders placeholder dash instead of bare colons`() {
        val bundled = temp.resolve("bundled")
        val user = temp.resolve("user")
        Files.createDirectories(bundled)
        Files.writeString(bundled.resolve("en.json"), """{"common":{"target":"Target"}}""")
        Files.writeString(bundled.resolve("zh-CN.json"), """{"common":{"target":"目标"}}""")
        I18n.configureForTests(bundled, user, persistedLanguage = I18nLocaleOption.SYSTEM.key)
        I18n.registerSettings()
        I18n.syncUserBundles()
        val empty = HttpService("", 0, "")

        I18n.setLocaleOption(I18nLocaleOption.EN)
        assertEquals("Target: -", formatTarget(empty))

        I18n.setLocaleOption(I18nLocaleOption.ZH_CN)
        assertEquals("目标: -", formatTarget(empty))
    }
}
