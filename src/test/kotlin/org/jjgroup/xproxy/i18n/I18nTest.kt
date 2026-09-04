package org.jjgroup.xproxy.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class I18nTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `loads nested json with fallback and placeholders`() {
        val bundled = temp.resolve("bundled")
        val user = temp.resolve("user")
        Files.createDirectories(bundled)
        Files.writeString(bundled.resolve("en.json"), """{"common":{"save":"Save {count}","delete":"Delete"}}""")
        Files.writeString(bundled.resolve("zh-CN.json"), """{"common":{"save":"保存 {count}"}}""")

        I18n.configureForTests(bundled, user)
        I18n.syncUserBundles()
        I18n.setLocaleOption(I18nLocaleOption.ZH_CN)

        assertEquals("保存 3", I18n.t("common.save", "count" to 3))
        assertEquals("Delete", I18n.t("common.delete"))
        assertEquals("missing.key", I18n.t("missing.key"))
    }

    @Test
    fun `sync creates files and does not overwrite existing user values`() {
        val bundled = temp.resolve("bundled")
        val user = temp.resolve("user")
        Files.createDirectories(bundled)
        Files.createDirectories(user)
        Files.writeString(bundled.resolve("en.json"), """{"common":{"save":"Save","delete":"Delete"}}""")
        Files.writeString(bundled.resolve("zh-CN.json"), """{"common":{"save":"保存","delete":"删除"}}""")
        Files.writeString(user.resolve("zh-CN.json"), """{"common":{"save":"我的保存"}}""")

        I18n.configureForTests(bundled, user)
        I18n.syncUserBundles()
        I18n.setLocaleOption(I18nLocaleOption.ZH_CN)

        assertTrue(Files.exists(user.resolve("en.json")))
        assertEquals("我的保存", I18n.t("common.save"))
        assertEquals("删除", I18n.t("common.delete"))
    }

    @Test
    fun `invalid user json falls back to bundled english and reports error`() {
        val bundled = temp.resolve("bundled")
        val user = temp.resolve("user")
        Files.createDirectories(bundled)
        Files.createDirectories(user)
        Files.writeString(bundled.resolve("en.json"), """{"common":{"save":"Save"}}""")
        Files.writeString(bundled.resolve("zh-CN.json"), """{"common":{"save":"保存"}}""")
        Files.writeString(user.resolve("zh-CN.json"), "{" )

        I18n.configureForTests(bundled, user)
        I18n.setLocaleOption(I18nLocaleOption.ZH_CN)
        val result = I18n.reload()

        assertFalse(result.success)
        assertEquals("Save", I18n.t("common.save"))
    }

    @Test
    fun `fresh runtime defaults to system and reloads persisted global language choice`() {
        val bundled = temp.resolve("bundled")
        val user = temp.resolve("user")
        Files.createDirectories(bundled)
        Files.writeString(bundled.resolve("en.json"), """{"common":{"save":"Save"}}""")
        Files.writeString(bundled.resolve("zh-CN.json"), """{"common":{"save":"保存"}}""")

        I18n.configureForTests(bundled, user, persistedLanguage = null)
        I18n.registerSettings()
        assertEquals(I18nLocaleOption.SYSTEM, I18n.localeOption())

        I18n.setLocaleOption(I18nLocaleOption.ZH_CN)
        assertEquals("zh-CN", I18n.persistedLanguageForTests())

        I18n.configureForTests(bundled, user, persistedLanguage = "zh-CN")
        I18n.registerSettings()
        assertEquals(I18nLocaleOption.ZH_CN, I18n.localeOption())
        assertEquals("保存", I18n.t("common.save"))
    }
}
