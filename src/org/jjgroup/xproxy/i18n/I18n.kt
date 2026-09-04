package org.jjgroup.xproxy.i18n

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.jjgroup.xproxy.core.Settings
import org.jjgroup.xproxy.project.core.ProjectPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class I18nLocaleOption(val key: String, val displayName: String) {
    SYSTEM("system", "System"),
    EN("en", "English"),
    ZH_CN("zh-CN", "简体中文");

    companion object {
        fun fromKey(key: String?): I18nLocaleOption = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

data class I18nReloadResult(val success: Boolean, val error: String? = null)

object I18n {
    private const val KEY_LANGUAGE = "ui.language"
    private const val DEFAULT_LOCALE = "en"
    private val supportedLocaleFiles = listOf("en.json", "zh-CN.json")
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var bundledRoot: Path? = null
    private var userRoot: Path = ProjectPaths.globalRoot.resolve("i18n")
    private var selected = I18nLocaleOption.SYSTEM
    private var selectedBundle: Map<String, String> = emptyMap()
    private var englishBundle: Map<String, String> = emptyMap()
    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private var settingsGet: (String, String) -> String = { key, default -> Settings.getString(key, default) }
    private var settingsSet: (String, String) -> Unit = { key, value -> Settings.setString(key, value) }
    private var settingsRegister: (String, String) -> Unit = { key, default -> Settings.registerSetting(key, default) }
    private var testPersistedLanguage: String? = null

    fun registerSettings() {
        settingsRegister(KEY_LANGUAGE, I18nLocaleOption.SYSTEM.key)
        selected = I18nLocaleOption.fromKey(settingsGet(KEY_LANGUAGE, I18nLocaleOption.SYSTEM.key))
        reload()
    }

    fun configureForTests(bundledRoot: Path, userRoot: Path, persistedLanguage: String? = I18nLocaleOption.SYSTEM.key) {
        this.bundledRoot = bundledRoot
        this.userRoot = userRoot
        testPersistedLanguage = persistedLanguage
        settingsGet = { _, default -> testPersistedLanguage ?: default }
        settingsSet = { _, value -> testPersistedLanguage = value }
        settingsRegister = { _, default -> if (testPersistedLanguage == null) testPersistedLanguage = default }
        selected = I18nLocaleOption.fromKey(testPersistedLanguage ?: I18nLocaleOption.SYSTEM.key)
        selectedBundle = emptyMap()
        englishBundle = emptyMap()
        listeners.clear()
    }

    fun persistedLanguageForTests(): String? = testPersistedLanguage

    fun resetRuntimeForTests() {
        bundledRoot = null
        userRoot = ProjectPaths.globalRoot.resolve("i18n")
        settingsGet = { key, default -> Settings.getString(key, default) }
        settingsSet = { key, value -> Settings.setString(key, value) }
        settingsRegister = { key, default -> Settings.registerSetting(key, default) }
        testPersistedLanguage = null
        selectedBundle = emptyMap()
        englishBundle = emptyMap()
        listeners.clear()
    }

    fun userBundleRoot(): Path = userRoot

    fun localeOption(): I18nLocaleOption = selected

    fun currentLocaleKey(): String = when (selected) {
        I18nLocaleOption.SYSTEM -> resolveSystemLocale()
        I18nLocaleOption.EN -> "en"
        I18nLocaleOption.ZH_CN -> "zh-CN"
    }

    fun setLocaleOption(option: I18nLocaleOption): I18nReloadResult {
        selected = option
        runCatching { settingsSet(KEY_LANGUAGE, option.key) }
        val result = reload()
        notifyListeners()
        return result
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun reload(): I18nReloadResult {
        return try {
            englishBundle = loadBundle("en") ?: loadBundledBundle("en") ?: emptyMap()
            val selectedKey = currentLocaleKey()
            selectedBundle = if (selectedKey == "en") {
                englishBundle
            } else {
                loadBundle(selectedKey) ?: loadBundledBundle(selectedKey) ?: emptyMap()
            }
            notifyListeners()
            I18nReloadResult(true)
        } catch (ex: Exception) {
            selectedBundle = emptyMap()
            englishBundle = loadBundledBundle("en") ?: emptyMap()
            notifyListeners()
            I18nReloadResult(false, ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun syncUserBundles() {
        Files.createDirectories(userRoot)
        supportedLocaleFiles.forEach { fileName ->
            val localeKey = fileName.removeSuffix(".json")
            val bundled = loadBundledJsonMap(localeKey) ?: emptyMap()
            val target = userRoot.resolve(fileName)
            if (!Files.exists(target)) {
                Files.writeString(target, mapper.writeValueAsString(bundled) + "\n")
            } else {
                val existing = runCatching { parseJsonMap(Files.readString(target)) }.getOrNull()
                if (existing != null) {
                    val merged = mergeMissing(existing.toMutableMap(), bundled)
                    Files.writeString(target, mapper.writeValueAsString(merged) + "\n")
                }
            }
        }
    }

    fun t(key: String, vararg args: Pair<String, Any?>): String {
        val raw = selectedBundle[key] ?: englishBundle[key] ?: key
        return args.fold(raw) { acc, pair -> acc.replace("{${pair.first}}", pair.second?.toString().orEmpty()) }
    }

    private fun notifyListeners() {
        listeners.forEach { listener -> runCatching { listener.invoke() } }
    }

    private fun resolveSystemLocale(): String {
        val locale = Locale.getDefault()
        return if (locale.language.equals("zh", ignoreCase = true)) "zh-CN" else DEFAULT_LOCALE
    }

    private fun loadBundle(localeKey: String): Map<String, String>? {
        val path = userRoot.resolve("$localeKey.json")
        return if (Files.exists(path)) flatten(parseJsonMap(Files.readString(path))) else null
    }

    private fun loadBundledBundle(localeKey: String): Map<String, String>? {
        val element = loadBundledJsonMap(localeKey) ?: return null
        return flatten(element)
    }

    private fun loadBundledJsonMap(localeKey: String): Map<String, Any?>? {
        bundledRoot?.let { root ->
            val path = root.resolve("$localeKey.json")
            if (Files.exists(path)) return parseJsonMap(Files.readString(path))
        }
        val stream = javaClass.classLoader.getResourceAsStream("i18n/$localeKey.json") ?: return null
        return stream.reader(Charsets.UTF_8).use { parseJsonMap(it.readText()) }
    }

    private fun parseJsonMap(text: String): Map<String, Any?> {
        return mapper.readValue(text, object : TypeReference<Map<String, Any?>>() {})
    }

    private fun flatten(element: Map<String, Any?>, prefix: String = ""): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        element.forEach { (key, value) ->
            val next = if (prefix.isBlank()) key else "$prefix.$key"
            if (value is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                result.putAll(flatten(value as Map<String, Any?>, next))
            } else if (value != null) {
                result[next] = value.toString()
            }
        }
        return result
    }

    private fun mergeMissing(existing: MutableMap<String, Any?>, defaults: Map<String, Any?>): MutableMap<String, Any?> {
        defaults.forEach { (key, defaultValue) ->
            val current = existing[key]
            if (current == null) {
                existing[key] = defaultValue
            } else if (current is Map<*, *> && defaultValue is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                existing[key] = mergeMissing(current.toMutableMap() as MutableMap<String, Any?>, defaultValue as Map<String, Any?>)
            }
        }
        return existing
    }
}
