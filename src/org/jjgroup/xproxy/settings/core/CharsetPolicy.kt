package org.jjgroup.xproxy.settings.core

import org.jjgroup.xproxy.core.Settings
import java.nio.charset.Charset

// AUTO 模式下每个响应的 charset 探测都会调用以下正则(findCharsetFromHeaders / findCharsetFromHtmlMeta),
// 经 extractTitle 在每条响应上触发。预编译为常量,避免每次 Pattern.compile。
private val CHARSET_HEADER_REGEX =
    Regex("charset\\s*=\\s*['\"]?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
private val META_CHARSET_REGEX =
    Regex("<meta[^>]+charset\\s*=\\s*['\"]?([A-Za-z0-9_\\-]+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val META_HTTP_EQUIV_REGEX =
    Regex("<meta[^>]+content\\s*=\\s*['\"][^'\"]*charset=([A-Za-z0-9_\\-]+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

object CharsetPolicy {
    private const val KEY_DISPLAY = "encoding.display"
    private const val KEY_FORWARD = "encoding.forward"

    const val DISPLAY_AUTO = "Auto"
    const val FORWARD_FOLLOW_DISPLAY = "Follow Display"

    val displayOptions = listOf(DISPLAY_AUTO, "UTF-8", "GB18030", "GBK", "Big5", "ISO-8859-1")
    val forwardOptions = listOf(FORWARD_FOLLOW_DISPLAY, "UTF-8", "GB18030", "GBK", "Big5", "ISO-8859-1")

    fun registerSettings() {
        Settings.registerSetting(KEY_DISPLAY, DISPLAY_AUTO)
        Settings.registerSetting(KEY_FORWARD, FORWARD_FOLLOW_DISPLAY)
    }

    fun getDisplayOption(): String {
        val value = Settings.getString(KEY_DISPLAY, DISPLAY_AUTO)
        return if (displayOptions.contains(value)) value else DISPLAY_AUTO
    }

    fun setDisplayOption(option: String) {
        if (displayOptions.contains(option)) {
            Settings.setString(KEY_DISPLAY, option)
        }
    }

    fun getForwardOption(): String {
        val value = Settings.getString(KEY_FORWARD, FORWARD_FOLLOW_DISPLAY)
        return if (forwardOptions.contains(value)) value else FORWARD_FOLLOW_DISPLAY
    }

    fun setForwardOption(option: String) {
        if (forwardOptions.contains(option)) {
            Settings.setString(KEY_FORWARD, option)
        }
    }

    fun decodeBodyForDisplay(headersText: String, bodyIso88591: String): String {
        val bytes = bodyIso88591.toByteArray(Charsets.ISO_8859_1)
        val charset = resolveDisplayCharset(headersText, bytes)
        return String(bytes, charset)
    }

    fun decodeBodyPreviewForDisplay(headersText: String, bodyIso88591: String, maxBytes: Int): String {
        val limit = maxBytes.coerceAtLeast(0)
        val previewIso = if (bodyIso88591.length > limit) bodyIso88591.substring(0, limit) else bodyIso88591
        val bytes = previewIso.toByteArray(Charsets.ISO_8859_1)
        val charset = resolveDisplayCharset(headersText, bytes)
        return String(bytes, charset)
    }

    fun encodeBodyForForward(headersText: String, displayBody: String): String {
        val charset = resolveForwardCharset(headersText)
        val bytes = displayBody.toByteArray(charset)
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun resolveDisplayCharset(headersText: String, bodyBytes: ByteArray): Charset {
        val option = getDisplayOption()
        if (option != DISPLAY_AUTO) {
            return charsetOrUtf8(option)
        }

        findCharsetFromHeaders(headersText)?.let { return charsetOrUtf8(it) }

        detectBomCharset(bodyBytes)?.let { return it }

        findCharsetFromHtmlMeta(bodyBytes)?.let { return charsetOrUtf8(it) }

        val contentType = findContentType(headersText)
        if (listOf("json", "html", "xml").any { contentType?.contains(it) == true } || contentType?.startsWith("text/") == true) {
            if (isLikelyUtf8(bodyBytes)) {
                return Charsets.UTF_8
            }
            return charsetOrUtf8("GB18030")
        }

        return if (isLikelyUtf8(bodyBytes)) Charsets.UTF_8 else charsetOrUtf8("GB18030")
    }

    private fun resolveForwardCharset(headersText: String): Charset {
        val option = getForwardOption()
        if (option != FORWARD_FOLLOW_DISPLAY) {
            return charsetOrUtf8(option)
        }

        val display = getDisplayOption()
        if (display != DISPLAY_AUTO) {
            return charsetOrUtf8(display)
        }

        findCharsetFromHeaders(headersText)?.let { return charsetOrUtf8(it) }
        return Charsets.UTF_8
    }

    private fun findContentType(headersText: String): String? {
        val line = headersText.lineSequence().firstOrNull { it.lowercase().startsWith("content-type:") } ?: return null
        return line.substringAfter(':', "").trim().lowercase()
    }

    private fun findCharsetFromHeaders(headersText: String): String? {
        val contentType = findContentType(headersText) ?: return null
        val m = CHARSET_HEADER_REGEX.find(contentType)
        return m?.groupValues?.getOrNull(1)
    }

    private fun findCharsetFromHtmlMeta(bodyBytes: ByteArray): String? {
        val probe = String(bodyBytes, Charsets.ISO_8859_1)
        val meta = META_CHARSET_REGEX
            .find(probe)
            ?.groupValues
            ?.getOrNull(1)
        if (!meta.isNullOrBlank()) {
            return meta
        }
        val httpEquiv = META_HTTP_EQUIV_REGEX
            .find(probe)
            ?.groupValues
            ?.getOrNull(1)
        return httpEquiv
    }

    private fun detectBomCharset(bytes: ByteArray): Charset? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return charsetOrUtf8("UTF-16BE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return charsetOrUtf8("UTF-16LE")
        }
        return null
    }

    private fun isLikelyUtf8(bytes: ByteArray): Boolean {
        var index = 0
        while (index < bytes.size) {
            val b = bytes[index].toInt() and 0xFF
            when {
                b and 0x80 == 0 -> index += 1
                b and 0xE0 == 0xC0 -> {
                    if (index + 1 >= bytes.size) return false
                    if (!isContinuation(bytes[index + 1])) return false
                    index += 2
                }

                b and 0xF0 == 0xE0 -> {
                    if (index + 2 >= bytes.size) return false
                    if (!isContinuation(bytes[index + 1]) || !isContinuation(bytes[index + 2])) return false
                    index += 3
                }

                b and 0xF8 == 0xF0 -> {
                    if (index + 3 >= bytes.size) return false
                    if (!isContinuation(bytes[index + 1]) || !isContinuation(bytes[index + 2]) || !isContinuation(bytes[index + 3])) return false
                    index += 4
                }

                else -> return false
            }
        }
        return true
    }

    private fun isContinuation(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v and 0xC0 == 0x80
    }

    private fun charsetOrUtf8(name: String): Charset {
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }
}
