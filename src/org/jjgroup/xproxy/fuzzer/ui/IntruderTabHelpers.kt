package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.request.parseHeaders
import org.jjgroup.xproxy.fuzzer.request.splitMessage
import org.jjgroup.xproxy.project.core.FuzzerTabRecord
import java.net.URI

internal fun parseResponseStatusCode(responseText: String): Int? {
    val statusLine = responseText.lineSequence().firstOrNull()?.trim().orEmpty()
    if (!statusLine.startsWith("HTTP/", true)) {
        return null
    }
    val parts = statusLine.split(' ')
    return if (parts.size >= 2) parts[1].toIntOrNull() else null
}

internal fun parseResponseHeaders(responseText: String): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    val lines = responseText.split("\r\n", "\n")
    for (line in lines.drop(1)) {
        if (line.isBlank()) {
            break
        }
        val idx = line.indexOf(':')
        if (idx <= 0) {
            continue
        }
        val key = line.substring(0, idx).trim().lowercase()
        val value = line.substring(idx + 1).trim()
        headers[key] = value
    }
    return headers
}

internal fun updateRequestContentLength(rawRequestText: String): String {
    val parsed = splitMessage(rawRequestText)
    val separator = if (parsed.separator == "\n" || !rawRequestText.contains("\r\n")) "\n" else "\r\n"
    val headerLines = parsed.headers.split(Regex("\\r?\\n")).toMutableList()
    if (headerLines.isEmpty()) {
        return rawRequestText
    }

    val transferEncoding = headerLines
        .firstOrNull { it.substringBefore(':').trim().equals("Transfer-Encoding", ignoreCase = true) }
        ?.substringAfter(':', "")
        ?.lowercase()
        .orEmpty()
    if (transferEncoding.contains("chunked")) {
        removeHeader(headerLines, "Content-Length")
        return composeRequest(headerLines, parsed.body, separator)
    }

    val bodyLength = parsed.body.toByteArray(Charsets.ISO_8859_1).size
    removeHeader(headerLines, "Content-Length")
    setHeader(headerLines, "Content-Length", bodyLength.toString())
    return composeRequest(headerLines, parsed.body, separator)
}

internal fun resolveRedirectUri(requestRawText: String, responseText: String, target: HttpService): URI? {
    val location = parseResponseHeaders(responseText)["location"]?.trim().orEmpty()
    if (location.isEmpty()) {
        return null
    }
    return try {
        val base = URI(toFullUrl(target, requestRawText))
        base.resolve(location)
    } catch (_: Exception) {
        try {
            URI(location)
        } catch (_: Exception) {
            null
        }
    }
}

internal fun nextTabCounterFromRecords(records: List<FuzzerTabRecord>): Int {
    if (records.isEmpty()) {
        return 1
    }
    val plausibleUpperBound = records.size + 1
    val maxNumericTitle = records
        .mapNotNull { it.title.trim().toIntOrNull() }
        .filter { it in 1..plausibleUpperBound }
        .maxOrNull() ?: 0
    return when {
        maxNumericTitle > 0 -> maxNumericTitle + 1
        else -> records.size + 1
    }
}

internal fun inferTargetFromRequest(rawRequest: String, fallback: HttpService): HttpService {
    try {
        val parsed = splitMessage(rawRequest)
        val requestLine = parsed.headers.lineSequence().firstOrNull()?.trim().orEmpty()
        if (requestLine.isEmpty()) {
            return HttpService(fallback.host, fallback.port, fallback.protocol)
        }

        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            return HttpService(fallback.host, fallback.port, fallback.protocol)
        }

        val method = parts[0].uppercase()
        val targetToken = parts[1]
        val headers = parseHeaders(parsed.headers)

        var host: String? = null
        var port: Int? = null
        var protocol: String? = null

        if (targetToken.startsWith("http://") || targetToken.startsWith("https://")) {
            val uri = URI(targetToken)
            if (!uri.host.isNullOrBlank()) {
                host = uri.host
            }
            protocol = uri.scheme?.lowercase()
            port = if (uri.port != -1) uri.port else if (protocol == "https") 443 else 80
        }

        if (method == "CONNECT") {
            val authority = targetToken.trim()
            if (authority.startsWith("[") && authority.contains("]")) {
                val end = authority.indexOf(']')
                host = authority.substring(1, end)
                val rest = authority.substring(end + 1)
                if (rest.startsWith(":")) {
                    port = rest.substring(1).toIntOrNull()
                }
            } else {
                val idx = authority.lastIndexOf(':')
                if (idx > 0) {
                    host = authority.substring(0, idx)
                    port = authority.substring(idx + 1).toIntOrNull()
                } else if (authority.isNotEmpty()) {
                    host = authority
                }
            }
            protocol = "https"
        }

        val hostHeader = headers["host"]
        if (host == null && !hostHeader.isNullOrBlank()) {
            val headerValue = hostHeader.trim()
            if (headerValue.startsWith("[") && headerValue.contains("]")) {
                val end = headerValue.indexOf(']')
                host = headerValue.substring(1, end)
                val rest = headerValue.substring(end + 1)
                if (rest.startsWith(":")) {
                    port = rest.substring(1).toIntOrNull() ?: port
                }
            } else {
                val idx = headerValue.lastIndexOf(':')
                if (idx > 0 && headerValue.indexOf(':') == idx) {
                    host = headerValue.substring(0, idx)
                    port = headerValue.substring(idx + 1).toIntOrNull() ?: port
                } else {
                    host = headerValue
                }
            }
        }

        if (host.isNullOrBlank()) {
            host = fallback.host
        }
        if (protocol.isNullOrBlank()) {
            protocol = if (port == 443) "https" else fallback.protocol
        }
        if (port == null) {
            port = if (protocol == "https") 443 else 80
        }

        return HttpService(host, port, protocol)
    } catch (_: Exception) {
        return HttpService(fallback.host, fallback.port, fallback.protocol)
    }
}
