package org.jjgroup.xproxy.proxy.runtime

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

// extractTitle 每个响应都会调用,预编译正则为常量,避免每次重新 Pattern.compile。
private val NATIVE_TITLE_TAG_REGEX =
    Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val NATIVE_WHITESPACE_REGEX = Regex("\\s+")

internal fun parseStatusCode(rawResponse: String): Int {
    val line = rawResponse.lineSequence().firstOrNull()?.trim().orEmpty()
    return line.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
}

internal fun responseBody(rawResponse: String): String {
    val marker = rawResponse.indexOf("\r\n\r\n")
    if (marker >= 0) {
        return rawResponse.substring(marker + 4)
    }
    val lfMarker = rawResponse.indexOf("\n\n")
    if (lfMarker >= 0) {
        return rawResponse.substring(lfMarker + 2)
    }
    return ""
}

internal fun parseMimeType(rawResponse: String, body: String): String {
    val contentTypeLine = rawResponse.lineSequence()
        .firstOrNull { it.startsWith("content-type:", ignoreCase = true) }
        ?.substringAfter(':', "")
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        .orEmpty()
    if (contentTypeLine.isNotBlank()) {
        return when {
            contentTypeLine.contains("json") -> "json"
            contentTypeLine.contains("xml") -> "xml"
            contentTypeLine.contains("html") -> "html"
            contentTypeLine.startsWith("text/") -> "text"
            else -> "other"
        }
    }
    val trimmed = body.trimStart()
    return when {
        trimmed.startsWith("{") || trimmed.startsWith("[") -> "json"
        trimmed.startsWith("<") -> "html"
        trimmed.isBlank() -> "other"
        else -> "text"
    }
}

internal fun extractTitle(body: String): String {
    return NATIVE_TITLE_TAG_REGEX
        .find(body)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(NATIVE_WHITESPACE_REGEX, " ")
        ?.trim()
        ?: ""
}

internal fun headerValue(headers: List<Pair<String, String>>, name: String): String? {
    for ((headerName, value) in headers) {
        if (headerName.equals(name, ignoreCase = true)) return value
    }
    return null
}

internal fun closeQuietly(server: ServerSocket?) {
    if (server == null) return
    try {
        server.close()
    } catch (_: Exception) {
    }
}

internal fun closeQuietly(socket: Socket?) {
    if (socket == null) return
    try {
        socket.close()
    } catch (_: Exception) {
    }
}

internal fun closeQuietly(output: OutputStream?) {
    if (output == null) return
    try {
        output.close()
    } catch (_: Exception) {
    }
}
