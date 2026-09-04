package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.fuzzer.model.ParsedMessage

// 热路径(splitMessage/parseHeaders/ensureConnectionClose)每次调用都会触发,预编译避免重复 Pattern.compile。
// internal:同包 FuzzerRequestSending.syncContentLengthHeader 复用同一切行正则,避免重复字面量编译。
internal val HEADER_LINE_SPLIT = Regex("\r?\n")
private val CONNECTION_HEADER_REGEX = Regex("(?i)Connection:[^\r\n]*")

fun splitMessage(raw: String): ParsedMessage {
    val crlfIndex = raw.indexOf("\r\n\r\n")
    if (crlfIndex >= 0) {
        return ParsedMessage(raw.substring(0, crlfIndex), raw.substring(crlfIndex + 4), "\r\n")
    }
    val lfIndex = raw.indexOf("\n\n")
    if (lfIndex >= 0) {
        return ParsedMessage(raw.substring(0, lfIndex), raw.substring(lfIndex + 2), "\n")
    }
    val inferredSeparator = if (raw.contains("\r\n")) "\r\n" else "\n"
    return ParsedMessage(raw, "", inferredSeparator)
}

fun parseHeaders(headersText: String): Map<String, String> {
    val lines = headersText.split(HEADER_LINE_SPLIT)
    return lines.mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx > 0) {
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            if (name.isNotEmpty()) name to value else null
        } else {
            null
        }
    }.toMap()
}

fun detectBodyKind(headers: Map<String, String>, body: String): BodyKind {
    if (body.isBlank()) {
        return BodyKind.NONE
    }
    val contentType = headers["content-type"]?.lowercase()
    if (contentType != null) {
        when {
            contentType.contains("multipart/form-data") -> return BodyKind.FORM
            contentType.contains("application/json") || contentType.contains("text/json") || contentType.contains("+json") -> return BodyKind.JSON
            contentType.contains("text/html") || contentType.contains("application/xhtml+xml") -> return BodyKind.HTML
        }
    }
    val trimmed = body.trimStart()
    return when {
        trimmed.startsWith("{") || trimmed.startsWith("[") -> BodyKind.JSON
        trimmed.startsWith("<") -> BodyKind.HTML
        else -> BodyKind.OTHER
    }
}

fun normalizeRequestText(input: String): String = buildString {
    append(input.replace("\r\n", "\n").replace("\n", "\r\n"))
    if (!endsWith("\r\n\r\n")) {
        if (!endsWith("\r\n")) {
            append("\r\n")
        }
        append("\r\n")
    }
}

fun ensureConnectionClose(request: String): String {
    val headers = Utils.getHeaders(request)
    val lowerHeaders = headers.lowercase()
    return if (lowerHeaders.contains("connection:")) {
        request.replace(CONNECTION_HEADER_REGEX, "Connection: close")
    } else {
        request.replace("\r\n\r\n", "\r\nConnection: close\r\n\r\n")
    }
}
