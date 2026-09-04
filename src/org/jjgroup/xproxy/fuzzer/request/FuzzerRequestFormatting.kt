package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.settings.core.ResponsePrettySettings
import org.jjgroup.xproxy.ui.highlight.HttpHighlighter
import org.jjgroup.xproxy.engine.http.uncompressIfNecessary
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.fuzzer.model.BodyKind

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea

private fun appendIndent(builder: StringBuilder, indent: Int) {
    repeat(indent) {
        builder.append("  ")
    }
}

fun formatJson(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) {
        return body
    }
    val sb = StringBuilder()
    var indent = 0
    var inString = false
    var escaped = false
    var index = 0
    while (index < trimmed.length) {
        val c = trimmed[index]
        if (inString) {
            sb.append(c)
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = false
            }
            index += 1
            continue
        }
        when (c) {
            '"' -> {
                inString = true
                sb.append(c)
            }

            '{', '[' -> {
                val closeChar = if (c == '{') '}' else ']'
                var next = index + 1
                while (next < trimmed.length && trimmed[next].isWhitespace()) {
                    next += 1
                }
                if (next < trimmed.length && trimmed[next] == closeChar) {
                    sb.append(c)
                    sb.append(closeChar)
                    index = next + 1
                    continue
                }
                sb.append(c)
                sb.append('\n')
                indent += 1
                appendIndent(sb, indent)
            }

            '}', ']' -> {
                sb.append('\n')
                indent = maxOf(indent - 1, 0)
                appendIndent(sb, indent)
                sb.append(c)
            }

            ',' -> {
                sb.append(c)
                sb.append('\n')
                appendIndent(sb, indent)
            }

            ':' -> {
                sb.append(": ")
            }

            else -> {
                if (!c.isWhitespace()) {
                    sb.append(c)
                }
            }
        }
        index += 1
    }
    return sb.toString()
}

fun compactJson(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) {
        return body
    }
    val sb = StringBuilder(trimmed.length)
    var inString = false
    var escaped = false
    for (c in trimmed) {
        if (inString) {
            sb.append(c)
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                inString = false
            }
            continue
        }

        when (c) {
            '"' -> {
                inString = true
                sb.append(c)
            }

            ' ', '\t', '\r', '\n' -> {}

            else -> sb.append(c)
        }
    }
    return if (inString) body else sb.toString()
}

fun formatHtml(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) {
        return body
    }
    val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    val sb = StringBuilder()
    var indent = 0
    var index = 0
    while (index < trimmed.length) {
        if (trimmed[index] == '<') {
            val end = trimmed.indexOf('>', index)
            if (end == -1) {
                if (sb.isNotEmpty()) {
                    sb.append('\n')
                    appendIndent(sb, indent)
                }
                sb.append(trimmed.substring(index))
                break
            }
            val tag = trimmed.substring(index, end + 1)
            val lower = tag.lowercase()
            val isComment = lower.startsWith("<!--")
            val isClosing = lower.startsWith("</")
            val isBang = lower.startsWith("<!")
            val tagNameStart = if (isClosing) 2 else 1
            val tagName = lower.substring(tagNameStart).trimStart().takeWhile { it.isLetterOrDigit() }
            val isVoid = lower.endsWith("/>") || voidTags.contains(tagName)

            if (isClosing) {
                indent = maxOf(indent - 1, 0)
            }
            if (sb.isNotEmpty()) {
                sb.append('\n')
            }
            appendIndent(sb, indent)
            sb.append(tag.trim())
            if (!isClosing && !isVoid && !isComment && !isBang) {
                indent += 1
            }
            index = end + 1
        } else {
            val next = trimmed.indexOf('<', index)
            val text = if (next == -1) trimmed.substring(index) else trimmed.substring(index, next)
            val cleaned = text.trim()
            if (cleaned.isNotEmpty()) {
                if (sb.isNotEmpty()) {
                    sb.append('\n')
                }
                appendIndent(sb, indent)
                sb.append(cleaned)
            }
            index = if (next == -1) trimmed.length else next
        }
    }
    return sb.toString()
}

fun formatBody(body: String, kind: BodyKind, separator: String): String {
    val formatted = when (kind) {
        BodyKind.JSON -> formatJson(body)
        BodyKind.HTML -> formatHtml(body)
        else -> body
    }
    return if (separator == "\r\n") {
        formatted.replace("\r\n", "\n").replace("\n", "\r\n")
    } else {
        formatted.replace("\r\n", "\n")
    }
}

fun applySyntax(textArea: RSyntaxTextArea, kind: BodyKind) {
    val threshold = ResponsePrettySettings.getAutoHighlightMaxBytes().coerceAtLeast(1024)
    val sizeHint = (textArea.getClientProperty("xproxy.highlight-size-hint") as? Int) ?: textArea.text.length
    if (sizeHint > threshold) {
        HttpHighlighter.attachHeadersOnly(textArea)
    } else {
        HttpHighlighter.attach(textArea)
    }
}

fun decodeChunkedBody(body: String): String {
    val out = StringBuilder(body.length)
    var cursor = 0
    while (true) {
        val lengthEnd = body.indexOf("\r\n", cursor)
        if (lengthEnd == -1) {
            return body
        }
        val lengthLine = body.substring(cursor, lengthEnd).trim()
        val lengthValue = lengthLine.substringBefore(';').trim()
        val chunkLength = try {
            lengthValue.toInt(16)
        } catch (ex: Exception) {
            return body
        }
        val chunkStart = lengthEnd + 2
        if (chunkLength == 0) {
            return out.toString()
        }
        val chunkEnd = chunkStart + chunkLength
        if (body.length < chunkEnd + 2) {
            return body
        }
        out.append(body, chunkStart, chunkEnd)
        cursor = chunkEnd + 2
    }
}

fun decodeChunkedBodyPreview(body: String, maxChars: Int): String {
    val limit = maxChars.coerceAtLeast(0)
    if (limit == 0) return ""
    val out = StringBuilder(limit.coerceAtMost(body.length))
    var cursor = 0
    while (out.length < limit) {
        val lengthEnd = body.indexOf("\r\n", cursor)
        if (lengthEnd == -1) {
            return body.take(limit)
        }
        val lengthLine = body.substring(cursor, lengthEnd).trim()
        val lengthValue = lengthLine.substringBefore(';').trim()
        val chunkLength = try {
            lengthValue.toInt(16)
        } catch (ex: Exception) {
            return body.take(limit)
        }
        val chunkStart = lengthEnd + 2
        if (chunkLength == 0) {
            return out.toString()
        }
        val chunkEnd = chunkStart + chunkLength
        if (body.length < chunkEnd + 2) {
            return body.take(limit)
        }
        val remaining = limit - out.length
        out.append(body, chunkStart, (chunkStart + remaining).coerceAtMost(chunkEnd))
        cursor = chunkEnd + 2
    }
    return out.toString()
}

fun decodeResponseBody(headersText: String, body: String): String {
    val lowerHeaders = headersText.lowercase()
    val dechunked = if (lowerHeaders.contains("transfer-encoding: chunked")) {
        decodeChunkedBody(body)
    } else {
        body
    }
    return uncompressIfNecessary(headersText, dechunked)
}

fun decodeResponseBodyPreview(headersText: String, body: String, maxChars: Int): String {
    val lowerHeaders = headersText.lowercase()
    val contentEncoding = lowerHeaders.lineSequence()
        .firstOrNull { it.startsWith("content-encoding:") }
        ?.substringAfter(':', "")
        ?.trim()
        ?.lowercase()
        .orEmpty()
    if (contentEncoding.isNotBlank() && contentEncoding != "identity") {
        return decodeResponseBody(headersText, body).take(maxChars.coerceAtLeast(0))
    }
    return if (lowerHeaders.contains("transfer-encoding: chunked")) {
        decodeChunkedBodyPreview(body, maxChars)
    } else {
        body.take(maxChars.coerceAtLeast(0))
    }
}

fun formatTarget(target: HttpService): String {
    if (target.host.isBlank() && target.protocol.isBlank() && target.port <= 0) {
        return "${I18n.t("common.target")}: -"
    }
    return "${I18n.t("common.target")}: ${target.protocol}://${target.host}:${target.port}"
}
