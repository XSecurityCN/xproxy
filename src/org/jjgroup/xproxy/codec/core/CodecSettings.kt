package org.jjgroup.xproxy.codec.core

import org.jjgroup.xproxy.core.Settings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID

data class CodecTabRecord(
    val tabId: String,
    val title: String,
    val rules: List<String>,
    val input: String
)

data class CodecState(
    val tabs: List<CodecTabRecord>,
    val selectedTabId: String,
    val defaultTabId: String
)

object CodecSettings {
    private const val KEY_CODEC_STATE = "codec.tabs.state"
    private const val STORAGE_VERSION = 1
    private const val MAX_PERSISTED_INPUT_CHARS = 1024
    private const val MAX_PERSISTED_PAYLOAD_CHARS = 6000

    fun registerSettings() {
        Settings.registerSetting(KEY_CODEC_STATE, "")
    }

    fun loadState(): CodecState {
        val encoded = Settings.getString(KEY_CODEC_STATE, "")
        if (encoded.isBlank()) {
            return defaultState()
        }

        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            val data = DataInputStream(ByteArrayInputStream(bytes))
            val version = data.readInt()
            if (version != STORAGE_VERSION) {
                return@runCatching defaultState()
            }
            val tabCount = data.readInt().coerceAtLeast(0)
            val tabs = ArrayList<CodecTabRecord>(tabCount)
            repeat(tabCount) {
                val tabId = data.readSizedString()
                val title = data.readSizedString()
                val ruleCount = data.readInt().coerceAtLeast(0)
                val rules = ArrayList<String>(ruleCount)
                repeat(ruleCount) {
                    rules.add(data.readSizedString())
                }
                val input = data.readSizedString()
                tabs.add(
                    CodecTabRecord(
                        tabId = tabId,
                        title = title,
                        rules = rules,
                        input = input
                    )
                )
            }
            val selectedTabId = data.readSizedString()
            val defaultTabId = data.readSizedString()
            normalizeState(
                CodecState(
                    tabs = tabs,
                    selectedTabId = selectedTabId,
                    defaultTabId = defaultTabId
                )
            )
        }.getOrElse {
            defaultState()
        }
    }

    fun saveState(state: CodecState) {
        val normalized = normalizeState(state)
        val compactTabs = normalized.tabs.map { tab ->
            tab.copy(
                input = tab.input.take(MAX_PERSISTED_INPUT_CHARS)
            )
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeInt(STORAGE_VERSION)
            data.writeInt(compactTabs.size)
            for (tab in compactTabs) {
                data.writeSizedString(tab.tabId)
                data.writeSizedString(tab.title)
                data.writeInt(tab.rules.size)
                tab.rules.forEach { rule -> data.writeSizedString(rule) }
                data.writeSizedString(tab.input)
            }
            data.writeSizedString(normalized.selectedTabId)
            data.writeSizedString(normalized.defaultTabId)
        }
        val encoded = Base64.getEncoder().encodeToString(bytes.toByteArray())
        val noInputEncoded = Base64.getEncoder().encodeToString(serializeWithoutInput(normalized))
        val safeEncoded = when {
            encoded.length <= MAX_PERSISTED_PAYLOAD_CHARS -> encoded
            noInputEncoded.length <= MAX_PERSISTED_PAYLOAD_CHARS -> noInputEncoded
            else -> Base64.getEncoder().encodeToString(serializeWithoutInput(defaultState()))
        }
        runCatching {
            Settings.setString(KEY_CODEC_STATE, safeEncoded)
        }
    }

    fun defaultState(): CodecState {
        val defaultTabId = UUID.randomUUID().toString()
        val defaultTab = CodecTabRecord(
            tabId = defaultTabId,
            title = "default",
            rules = emptyList(),
            input = ""
        )
        return CodecState(
            tabs = listOf(defaultTab),
            selectedTabId = defaultTabId,
            defaultTabId = defaultTabId
        )
    }

    private fun normalizeState(state: CodecState): CodecState {
        val cleaned = state.tabs
            .map { tab ->
                tab.copy(
                    title = tab.title.trim().ifBlank { "untitled" },
                    rules = tab.rules.map { it.trim() }.filter { it.isNotBlank() }
                )
            }
            .distinctBy { it.tabId }
            .ifEmpty { defaultState().tabs }

        val selected = cleaned.firstOrNull { it.tabId == state.selectedTabId }?.tabId ?: cleaned.first().tabId
        val default = cleaned.firstOrNull { it.tabId == state.defaultTabId }?.tabId ?: cleaned.first().tabId
        return CodecState(cleaned, selected, default)
    }

    private fun serializeWithoutInput(state: CodecState): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeInt(STORAGE_VERSION)
            data.writeInt(state.tabs.size)
            for (tab in state.tabs) {
                data.writeSizedString(tab.tabId)
                data.writeSizedString(tab.title)
                data.writeInt(tab.rules.size)
                tab.rules.forEach { rule -> data.writeSizedString(rule) }
                data.writeSizedString("")
            }
            data.writeSizedString(state.selectedTabId)
            data.writeSizedString(state.defaultTabId)
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeSizedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readSizedString(): String {
        val size = readInt().coerceAtLeast(0)
        val buffer = ByteArray(size)
        readFully(buffer)
        return String(buffer, Charsets.UTF_8)
    }
}
