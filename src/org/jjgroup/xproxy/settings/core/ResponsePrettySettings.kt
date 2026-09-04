package org.jjgroup.xproxy.settings.core

import org.jjgroup.xproxy.core.Settings

object ResponsePrettySettings {
    private const val KEY_AUTO_PRETTY_MAX_BYTES = "response.pretty.auto.max-bytes"
    private const val KEY_AUTO_HIGHLIGHT_MAX_BYTES = "response.highlight.auto.max-bytes"
    private const val KEY_AUTO_PRETTY_MIME_WHITELIST = "response.pretty.auto.mime-whitelist"
    private const val KEY_LARGE_RESPONSE_PREVIEW_MAX_CHARS = "response.preview.large.max-chars"
    private const val KEY_HTML_RENDER_MAX_CHARS = "response.render.html.max-chars"
    private const val KEY_RAW_BODY_PREVIEW_MAX_BYTES = "response.raw.preview.max-bytes"
    private const val KEY_SMOOTH_TEXT_VIEW_MAX_BYTES = "response.smooth.text.max-bytes"
    private const val KEY_SMOOTH_TEXT_VIEW_MAX_LINE_CHARS = "response.smooth.text.max-line-chars"
    private const val KEY_SMOOTH_TEXT_VIEW_MAX_LINES = "response.smooth.text.max-lines"
    private const val KEY_SMOOTH_TEXT_VIEW_MAX_BODY_HIGHLIGHT_BYTES = "response.smooth.text.body-highlight.max-bytes"
    private const val KB = 1024
    const val DEFAULT_AUTO_PRETTY_MAX_BYTES = 2048 * KB
    const val DEFAULT_AUTO_HIGHLIGHT_MAX_BYTES = 2048 * KB
    const val DEFAULT_LARGE_RESPONSE_PREVIEW_MAX_CHARS = 4096 * KB
    private const val LEGACY_DEFAULT_HTML_RENDER_MAX_CHARS = 4096
    const val DEFAULT_HTML_RENDER_MAX_CHARS = 4096 * KB
    const val DEFAULT_RAW_BODY_PREVIEW_MAX_BYTES = 4096 * KB
    const val DEFAULT_SMOOTH_TEXT_VIEW_MAX_CHARS = 2048 * KB
    const val DEFAULT_SMOOTH_TEXT_VIEW_MAX_LINE_CHARS = 2048
    const val DEFAULT_SMOOTH_TEXT_VIEW_MAX_LINES = 800
    const val DEFAULT_SMOOTH_TEXT_VIEW_MAX_BODY_HIGHLIGHT_CHARS = 2048 * KB
    const val DEFAULT_AUTO_PRETTY_MIME_WHITELIST =
        "application/json,text/json,application/*+json,text/html,application/xhtml+xml,application/xml,text/xml"

    fun registerSettings() {
        Settings.registerSetting(KEY_AUTO_PRETTY_MAX_BYTES, DEFAULT_AUTO_PRETTY_MAX_BYTES)
        Settings.registerSetting(KEY_AUTO_HIGHLIGHT_MAX_BYTES, DEFAULT_AUTO_HIGHLIGHT_MAX_BYTES)
        Settings.registerSetting(KEY_AUTO_PRETTY_MIME_WHITELIST, DEFAULT_AUTO_PRETTY_MIME_WHITELIST)
        Settings.registerSetting(KEY_LARGE_RESPONSE_PREVIEW_MAX_CHARS, DEFAULT_LARGE_RESPONSE_PREVIEW_MAX_CHARS)
        Settings.registerSetting(KEY_HTML_RENDER_MAX_CHARS, DEFAULT_HTML_RENDER_MAX_CHARS)
        Settings.registerSetting(KEY_RAW_BODY_PREVIEW_MAX_BYTES, DEFAULT_RAW_BODY_PREVIEW_MAX_BYTES)
        Settings.registerSetting(KEY_SMOOTH_TEXT_VIEW_MAX_BYTES, DEFAULT_SMOOTH_TEXT_VIEW_MAX_CHARS)
        Settings.registerSetting(KEY_SMOOTH_TEXT_VIEW_MAX_LINE_CHARS, DEFAULT_SMOOTH_TEXT_VIEW_MAX_LINE_CHARS)
        Settings.registerSetting(KEY_SMOOTH_TEXT_VIEW_MAX_LINES, DEFAULT_SMOOTH_TEXT_VIEW_MAX_LINES)
        Settings.registerSetting(KEY_SMOOTH_TEXT_VIEW_MAX_BODY_HIGHLIGHT_BYTES, DEFAULT_SMOOTH_TEXT_VIEW_MAX_BODY_HIGHLIGHT_CHARS)
        val value = getAutoPrettyMaxBytes()
        if (value < 0) {
            setAutoPrettyMaxBytes(DEFAULT_AUTO_PRETTY_MAX_BYTES)
        }
        if (getLargeResponsePreviewMaxChars() < 1024) {
            setLargeResponsePreviewMaxChars(DEFAULT_LARGE_RESPONSE_PREVIEW_MAX_CHARS)
        }
        if (getAutoPrettyMimeWhitelist().isBlank()) {
            setAutoPrettyMimeWhitelist(DEFAULT_AUTO_PRETTY_MIME_WHITELIST)
        }
        val htmlRenderMaxChars = getHtmlRenderMaxChars()
        if (htmlRenderMaxChars < 0) {
            setHtmlRenderMaxChars(DEFAULT_HTML_RENDER_MAX_CHARS)
        } else if (htmlRenderMaxChars == LEGACY_DEFAULT_HTML_RENDER_MAX_CHARS) {
            setHtmlRenderMaxChars(DEFAULT_HTML_RENDER_MAX_CHARS)
        }
        if (getRawBodyPreviewMaxBytes() < 0) setRawBodyPreviewMaxBytes(DEFAULT_RAW_BODY_PREVIEW_MAX_BYTES)
        if (getSmoothTextViewMaxChars() < 0) setSmoothTextViewMaxChars(DEFAULT_SMOOTH_TEXT_VIEW_MAX_CHARS)
        if (getSmoothTextViewMaxLineChars() < 0) setSmoothTextViewMaxLineChars(DEFAULT_SMOOTH_TEXT_VIEW_MAX_LINE_CHARS)
        if (getSmoothTextViewMaxLines() < 0) setSmoothTextViewMaxLines(DEFAULT_SMOOTH_TEXT_VIEW_MAX_LINES)
        if (getSmoothTextViewMaxBodyHighlightChars() < 0) setSmoothTextViewMaxBodyHighlightChars(DEFAULT_SMOOTH_TEXT_VIEW_MAX_BODY_HIGHLIGHT_CHARS)
    }

    fun getAutoPrettyMaxBytes(): Int = Settings.getInt(KEY_AUTO_PRETTY_MAX_BYTES, DEFAULT_AUTO_PRETTY_MAX_BYTES)

    fun setAutoPrettyMaxBytes(bytes: Int) = Settings.setInt(KEY_AUTO_PRETTY_MAX_BYTES, bytes)

    fun getAutoPrettyMaxKb(): Int = bytesToKb(getAutoPrettyMaxBytes())

    fun setAutoPrettyMaxKb(kb: Int) = setAutoPrettyMaxBytes(kbToBytes(kb))

    fun getAutoHighlightMaxBytes(): Int = Settings.getInt(KEY_AUTO_HIGHLIGHT_MAX_BYTES, DEFAULT_AUTO_HIGHLIGHT_MAX_BYTES)

    fun setAutoHighlightMaxBytes(bytes: Int) = Settings.setInt(KEY_AUTO_HIGHLIGHT_MAX_BYTES, bytes)

    fun getAutoHighlightMaxKb(): Int = bytesToKb(getAutoHighlightMaxBytes())

    fun setAutoHighlightMaxKb(kb: Int) = setAutoHighlightMaxBytes(kbToBytes(kb))

    fun getLargeResponsePreviewMaxChars(): Int = Settings.getInt(KEY_LARGE_RESPONSE_PREVIEW_MAX_CHARS, DEFAULT_LARGE_RESPONSE_PREVIEW_MAX_CHARS)

    fun setLargeResponsePreviewMaxChars(chars: Int) = Settings.setInt(KEY_LARGE_RESPONSE_PREVIEW_MAX_CHARS, chars)

    fun getLargeResponsePreviewMaxKb(): Int = bytesToKb(getLargeResponsePreviewMaxChars())

    fun setLargeResponsePreviewMaxKb(kb: Int) = setLargeResponsePreviewMaxChars(kbToBytes(kb))

    fun getHtmlRenderMaxChars(): Int = Settings.getInt(KEY_HTML_RENDER_MAX_CHARS, DEFAULT_HTML_RENDER_MAX_CHARS)

    fun setHtmlRenderMaxChars(chars: Int) = Settings.setInt(KEY_HTML_RENDER_MAX_CHARS, chars)

    fun getHtmlRenderMaxKb(): Int = bytesToKb(getHtmlRenderMaxChars())

    fun setHtmlRenderMaxKb(kb: Int) = setHtmlRenderMaxChars(kbToBytes(kb))

    fun getRawBodyPreviewMaxBytes(): Int = Settings.getInt(KEY_RAW_BODY_PREVIEW_MAX_BYTES, DEFAULT_RAW_BODY_PREVIEW_MAX_BYTES)

    fun setRawBodyPreviewMaxBytes(bytes: Int) = Settings.setInt(KEY_RAW_BODY_PREVIEW_MAX_BYTES, bytes)

    fun getRawBodyPreviewMaxKb(): Int = bytesToKb(getRawBodyPreviewMaxBytes())

    fun setRawBodyPreviewMaxKb(kb: Int) = setRawBodyPreviewMaxBytes(kbToBytes(kb))

    fun getSmoothTextViewMaxChars(): Int = Settings.getInt(KEY_SMOOTH_TEXT_VIEW_MAX_BYTES, DEFAULT_SMOOTH_TEXT_VIEW_MAX_CHARS)

    fun setSmoothTextViewMaxChars(chars: Int) = Settings.setInt(KEY_SMOOTH_TEXT_VIEW_MAX_BYTES, chars)

    fun getSmoothTextViewMaxKb(): Int = bytesToKb(getSmoothTextViewMaxChars())

    fun setSmoothTextViewMaxKb(kb: Int) = setSmoothTextViewMaxChars(kbToBytes(kb))

    fun getSmoothTextViewMaxLineChars(): Int = Settings.getInt(KEY_SMOOTH_TEXT_VIEW_MAX_LINE_CHARS, DEFAULT_SMOOTH_TEXT_VIEW_MAX_LINE_CHARS)

    fun setSmoothTextViewMaxLineChars(chars: Int) = Settings.setInt(KEY_SMOOTH_TEXT_VIEW_MAX_LINE_CHARS, chars)

    fun getSmoothTextViewMaxLines(): Int = Settings.getInt(KEY_SMOOTH_TEXT_VIEW_MAX_LINES, DEFAULT_SMOOTH_TEXT_VIEW_MAX_LINES)

    fun setSmoothTextViewMaxLines(lines: Int) = Settings.setInt(KEY_SMOOTH_TEXT_VIEW_MAX_LINES, lines)

    fun getSmoothTextViewMaxBodyHighlightChars(): Int = Settings.getInt(KEY_SMOOTH_TEXT_VIEW_MAX_BODY_HIGHLIGHT_BYTES, DEFAULT_SMOOTH_TEXT_VIEW_MAX_BODY_HIGHLIGHT_CHARS)

    fun setSmoothTextViewMaxBodyHighlightChars(chars: Int) = Settings.setInt(KEY_SMOOTH_TEXT_VIEW_MAX_BODY_HIGHLIGHT_BYTES, chars)

    fun getSmoothTextViewMaxBodyHighlightKb(): Int = bytesToKb(getSmoothTextViewMaxBodyHighlightChars())

    fun setSmoothTextViewMaxBodyHighlightKb(kb: Int) = setSmoothTextViewMaxBodyHighlightChars(kbToBytes(kb))

    fun getAutoPrettyMimeWhitelist(): String = Settings.getString(KEY_AUTO_PRETTY_MIME_WHITELIST, DEFAULT_AUTO_PRETTY_MIME_WHITELIST)

    fun setAutoPrettyMimeWhitelist(value: String) = Settings.setString(KEY_AUTO_PRETTY_MIME_WHITELIST, value)

    fun isMimeAllowed(mimeType: String): Boolean {
        val normalizedMime = mimeType.trim().lowercase().substringBefore(';').trim()
        if (normalizedMime.isBlank()) {
            return false
        }
        val rules = parseWhitelistPatterns(getAutoPrettyMimeWhitelist())
        if (rules.isEmpty()) {
            return false
        }
        return rules.any { matchesPattern(normalizedMime, it) }
    }

    private fun kbToBytes(kb: Int): Int = kb.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE / KB) * KB

    private fun bytesToKb(bytes: Int): Int = (bytes.coerceAtLeast(0) + KB - 1) / KB

    private fun parseWhitelistPatterns(raw: String): List<String> {
        return raw.split(',', '\n', '\r', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
    }

    private fun matchesPattern(mimeType: String, pattern: String): Boolean {
        if (!pattern.contains('*')) {
            return mimeType == pattern
        }
        val regex = pattern.split("*").joinToString(".*") { Regex.escape(it) }
        return Regex("^$regex$").matches(mimeType)
    }
}
