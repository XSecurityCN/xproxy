package org.jjgroup.xproxy.settings.core

import org.jjgroup.xproxy.core.Settings
import com.formdev.flatlaf.FlatDarculaLaf
import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatIntelliJLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf

enum class UiThemeOption(val key: String, val displayName: String) {
    FLAT_LIGHT("flat-light", "Flat Light"),
    FLAT_DARK("flat-dark", "Flat Dark"),
    FLAT_INTELLIJ("flat-intellij", "Flat IntelliJ"),
    FLAT_DARCULA("flat-darcula", "Flat Darcula");

    override fun toString(): String = displayName

    fun setup(): Boolean {
        return when (this) {
            FLAT_LIGHT -> FlatLightLaf.setup()
            FLAT_DARK -> FlatDarkLaf.setup()
            FLAT_INTELLIJ -> FlatIntelliJLaf.setup()
            FLAT_DARCULA -> FlatDarculaLaf.setup()
        }
    }

    companion object {
        fun fromKey(key: String): UiThemeOption {
            return entries.firstOrNull { it.key == key.trim().lowercase() } ?: FLAT_INTELLIJ
        }
    }
}

object UiThemeSettings {
    private const val KEY_UI_THEME = "ui.theme"
    private val defaultTheme = UiThemeOption.FLAT_INTELLIJ

    fun registerSettings() {
        Settings.registerSetting(KEY_UI_THEME, defaultTheme.key)
        val normalized = getThemeOption().key
        Settings.setString(KEY_UI_THEME, normalized)
    }

    fun getThemeOption(): UiThemeOption = UiThemeOption.fromKey(Settings.getString(KEY_UI_THEME, defaultTheme.key))

    fun setThemeOption(theme: UiThemeOption) = Settings.setString(KEY_UI_THEME, theme.key)

    fun applyCurrentTheme(): Boolean = applyTheme(getThemeOption())

    fun applyTheme(theme: UiThemeOption): Boolean {
        val installed = runCatching { theme.setup() }.getOrDefault(false)
        if (!installed) {
            return false
        }
        FlatLaf.updateUI()
        return true
    }
}
