package org.jjgroup.xproxy.ui.http

import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.settings.core.ResponsePrettySettings

internal fun inferResponseMimeType(headers: Map<String, String>, kind: BodyKind): String {
    val contentType = headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
    if (!contentType.isNullOrBlank()) {
        return contentType
    }
    return when (kind) {
        BodyKind.JSON -> "application/json"
        BodyKind.HTML -> "text/html"
        else -> "application/octet-stream"
    }
}

internal fun resolveRenderMode(mimeType: String, contentTypeRaw: String, kind: BodyKind): ResponseRenderMode {
    return when {
        mimeType.startsWith("image/") || contentTypeRaw.startsWith("image/") -> ResponseRenderMode.IMAGE
        kind == BodyKind.HTML || mimeType == "text/html" || mimeType == "application/xhtml+xml" -> ResponseRenderMode.HTML
        else -> ResponseRenderMode.UNSUPPORTED
    }
}

internal fun inferBodyKindFromContentType(contentType: String): BodyKind {
    val normalized = contentType.lowercase()
    return when {
        normalized.contains("multipart/form-data") -> BodyKind.FORM
        normalized.contains("application/json") || normalized.contains("text/json") || normalized.contains("+json") -> BodyKind.JSON
        normalized.contains("text/html") || normalized.contains("application/xhtml+xml") -> BodyKind.HTML
        else -> BodyKind.OTHER
    }
}

internal fun isBinaryMime(mimeType: String): Boolean {
    val normalized = mimeType.lowercase()
    if (normalized.startsWith("text/") || normalized.contains("json") || normalized.contains("xml") || normalized.contains("html")) {
        return false
    }
    return normalized.startsWith("image/") ||
        normalized.startsWith("audio/") ||
        normalized.startsWith("video/") ||
        normalized == "application/octet-stream"
}

internal fun isBinaryLikeBody(bodyText: String, mimeType: String, kind: BodyKind): Boolean {
    if (kind != BodyKind.OTHER) {
        return false
    }
    val normalized = mimeType.lowercase()
    if (normalized.startsWith("text/") || normalized.contains("json") || normalized.contains("xml") || normalized.contains("html")) {
        return false
    }
    if (isBinaryMime(normalized)) {
        return true
    }
    val sample = bodyText.take(4096)
    if (sample.isEmpty()) {
        return false
    }
    val control = sample.count { ch ->
        (ch.code in 0..8) || ch.code == 11 || ch.code == 12 || (ch.code in 14..31) || ch.code == 0xFFFD
    }
    return control > 0 || control * 100 / sample.length > 2
}

internal fun renderUnavailableMessage(mode: ResponseRenderMode): String {
    return when (mode) {
        ResponseRenderMode.UNSUPPORTED -> "Render supports only HTML and Image resources."
        ResponseRenderMode.HTML,
        ResponseRenderMode.IMAGE -> ""
    }
}

internal fun shouldDisableHtmlRender(bodyText: String): Boolean {
    val threshold = ResponsePrettySettings.getHtmlRenderMaxChars().coerceAtLeast(0)
    return bodyText.length > threshold
}

internal fun sanitizeHtmlForSafeRender(html: String): String {
    var safe = html
    safe = safe.replace(Regex("(?is)<(script|iframe|frame|object|embed|link|source|video|audio)\\b[^>]*>.*?</\\1>"), "")
    safe = safe.replace(Regex("(?is)<(script|iframe|frame|object|embed|link|source|video|audio)\\b[^>]*/?>"), "")
    val blockedAttributes = listOf("src", "srcset", "href", "xlink:href", "poster", "data", "style")
    for (attr in blockedAttributes) {
        val attrPattern = Regex("(?is)\\s${Regex.escape(attr)}\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
        val safeAttrName = attr.replace(':', '-')
        safe = safe.replace(attrPattern, " data-xproxy-blocked-$safeAttrName=\"#\"")
    }
    return safe
}
