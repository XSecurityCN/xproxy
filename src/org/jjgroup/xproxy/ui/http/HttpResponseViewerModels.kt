package org.jjgroup.xproxy.ui.http

import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.settings.core.ResponsePrettySettings

internal data class DeferredPrettyResponse(
    val headers: String,
    val decodedBody: String,
    val separator: String,
    val kind: BodyKind
)

internal data class ResponseDisplayState(
    val rawText: String,
    val prettyText: String,
    val renderText: String,
    val renderMode: ResponseRenderMode,
    val renderHtmlText: String?,
    val renderImageBytes: ByteArray?,
    val renderMessage: String,
    val rawKind: BodyKind,
    val prettyKind: BodyKind,
    val selectedTabIndex: Int,
    val bodySize: Int,
    val disableHighlight: Boolean,
    val headersOnlyHighlight: Boolean,
    val deferredPretty: DeferredPrettyResponse?,
    val deferredPrettyRaw: String?
)

internal enum class ResponseRenderMode {
    HTML,
    IMAGE,
    UNSUPPORTED
}

internal data class ViewerPreview(
    val text: String,
    val truncated: Boolean,
    val omittedChars: Int
)

internal data class BodyDisplayStats(
    val length: Int,
    val lineCount: Int,
    val maxLineLength: Int
) {
    val shouldUseSmoothTextMode: Boolean
        get() = length > ResponsePrettySettings.getSmoothTextViewMaxChars()

    val shouldUseTextPerformanceMode: Boolean
        get() = shouldUseSmoothTextMode ||
            maxLineLength > ResponsePrettySettings.getSmoothTextViewMaxLineChars() ||
            lineCount > ResponsePrettySettings.getSmoothTextViewMaxLines()

    val shouldHighlightBody: Boolean
        get() = length <= ResponsePrettySettings.getSmoothTextViewMaxBodyHighlightChars()
}

internal fun bodyDisplayStats(text: String): BodyDisplayStats {
    if (text.isEmpty()) {
        return BodyDisplayStats(0, 0, 0)
    }
    var lines = 1
    var current = 0
    var maxLine = 0
    for (ch in text) {
        if (ch == '\n' || ch == '\r') {
            if (current > maxLine) maxLine = current
            current = 0
            if (ch == '\n') lines++
        } else {
            current++
        }
    }
    if (current > maxLine) maxLine = current
    return BodyDisplayStats(text.length, lines, maxLine)
}

internal fun foldLongLinesForViewer(text: String, maxLineChars: Int = ResponsePrettySettings.getSmoothTextViewMaxLineChars()): String {
    val width = maxLineChars.coerceAtLeast(0)
    if (width <= 0 || text.length <= width) {
        return text
    }
    val stats = bodyDisplayStats(text)
    if (stats.maxLineLength <= width) {
        return text
    }
    val out = StringBuilder(text.length + text.length / width + 32)
    var current = 0
    for (ch in text) {
        out.append(ch)
        if (ch == '\n' || ch == '\r') {
            current = 0
        } else {
            current++
            if (current >= width) {
                out.append('\n')
                current = 0
            }
        }
    }
    return out.toString()
}

internal fun truncateForViewer(text: String, maxChars: Int, omittedLabel: String = "content"): Pair<String, Boolean> {
    val preview = buildViewerPreview(text, maxChars, omittedLabel)
    return preview.text to preview.truncated
}

internal fun buildViewerPreview(text: String, maxChars: Int, omittedLabel: String = "content", knownFullLength: Int = text.length): ViewerPreview {
    val limit = maxChars.coerceAtLeast(1024)
    val fullLength = knownFullLength.coerceAtLeast(text.length)
    if (fullLength <= limit && text.length <= limit) {
        return ViewerPreview(text, false, 0)
    }
    val preview = if (text.length > limit) text.take(limit) else text
    val omitted = (fullLength - preview.length).coerceAtLeast(0)
    return ViewerPreview(
        preview + "\n\n[... truncated $omittedLabel: $omitted chars omitted. Use copy/export/context menu actions for full stored content ...]",
        true,
        omitted
    )
}

internal fun joinMessageForViewer(headers: String, separator: String, body: String): String =
    if (body.isNotEmpty()) headers + separator + separator + body else headers

internal fun binaryBodyPlaceholder(label: String, bodySize: Int, mimeType: String): String =
    "[$label body omitted from text preview: ${bodySize.coerceAtLeast(0)} bytes${if (mimeType.isNotBlank()) ", $mimeType" else ""}. Use Render/Copy/Export for the full content.]"

internal fun largeBodyPlaceholder(label: String, bodySize: Int, mimeType: String, previewChars: Int): String =
    "[$label body omitted from Raw text preview for smooth browsing: ${bodySize.coerceAtLeast(0)} bytes${if (mimeType.isNotBlank()) ", $mimeType" else ""}. Pretty can render on demand; full content remains available via Copy/Export/context actions.]"
