package org.jjgroup.xproxy.proxy.core.mutation

import org.jjgroup.xproxy.proxy.model.BodyRef
import org.jjgroup.xproxy.proxy.model.HttpProtocol
import org.jjgroup.xproxy.proxy.model.MessageDirection
import org.jjgroup.xproxy.proxy.model.MessageMetadata
import org.jjgroup.xproxy.proxy.model.UnifiedHttpMessage

object RawHttpMessageCodec {
    fun toRaw(message: UnifiedHttpMessage): String {
        return when (message.direction) {
            MessageDirection.REQUEST -> requestToRaw(message)
            MessageDirection.RESPONSE -> responseToRaw(message)
        }
    }

    fun parseRequest(raw: String, fallback: MessageMetadata = MessageMetadata()): UnifiedHttpMessage {
        val (head, body) = split(raw)
        val lines = head.replace("\r\n", "\n").split('\n').filter { it.isNotBlank() }
        val requestLine = lines.firstOrNull().orEmpty().trim()
        val requestParts = requestLine.split(' ').filter { it.isNotBlank() }
        val method = requestParts.getOrNull(0).orEmpty().ifBlank { fallback.method.ifBlank { "GET" } }
        val path = requestParts.getOrNull(1).orEmpty().ifBlank { fallback.path.ifBlank { "/" } }
        val version = requestParts.getOrNull(2).orEmpty()
        val protocol = if (version.equals("HTTP/2", true) || version.equals("HTTP/2.0", true)) HttpProtocol.H2 else HttpProtocol.H1_1
        val parsedHeaders = parseHeaders(lines.drop(1))
        val pseudoFromRaw = parsedHeaders.filter { it.first.startsWith(":") }.associate { it.first.lowercase() to it.second }
        val regular = parsedHeaders.filterNot { it.first.startsWith(":") }
        val hostHeader = regular.firstOrNull { it.first.equals("host", ignoreCase = true) }?.second.orEmpty()
        val authority = pseudoFromRaw[":authority"]?.ifBlank { null }
            ?: hostHeader.ifBlank { fallback.host }
        val scheme = pseudoFromRaw[":scheme"]?.ifBlank { null }
            ?: fallback.scheme.ifBlank { if (fallback.tls) "https" else "http" }
        val pseudo = linkedMapOf<String, String>()
        if (protocol == HttpProtocol.H2) {
            pseudo[":method"] = pseudoFromRaw[":method"] ?: method
            pseudo[":path"] = pseudoFromRaw[":path"] ?: path
            pseudo[":scheme"] = scheme
            pseudo[":authority"] = authority
        }
        return UnifiedHttpMessage(
            protocol = protocol,
            direction = MessageDirection.REQUEST,
            streamId = fallback.streamId,
            pseudoHeaders = pseudo,
            headers = regular,
            trailers = emptyList(),
            bodyRef = BodyRef(body.toByteArray(Charsets.ISO_8859_1)),
            metadata = fallback.copy(
                method = method,
                path = path,
                host = extractHost(authority).ifBlank { fallback.host },
                port = extractPort(authority) ?: fallback.port,
                scheme = scheme
            )
        )
    }

    private fun requestToRaw(message: UnifiedHttpMessage): String {
        val method = message.pseudoHeaders[":method"] ?: message.metadata.method.ifBlank { "GET" }
        val path = message.pseudoHeaders[":path"] ?: message.metadata.path.ifBlank { "/" }
        val version = if (message.protocol == HttpProtocol.H2) "HTTP/2" else "HTTP/1.1"
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
        val authority = message.pseudoHeaders[":authority"] ?: message.metadata.host
        if (message.protocol == HttpProtocol.H2 && authority.isNotBlank()) {
            sb.append("Host: ").append(authority).append("\r\n")
        }
        for ((name, value) in message.headers) {
            if (name.startsWith(":")) continue
            if (message.protocol == HttpProtocol.H2 && name.equals("host", ignoreCase = true)) continue
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        message.bodyRef?.bytes?.let { sb.append(String(it, Charsets.ISO_8859_1)) }
        return sb.toString()
    }

    private fun responseToRaw(message: UnifiedHttpMessage): String {
        val status = message.pseudoHeaders[":status"] ?: "200"
        val version = if (message.protocol == HttpProtocol.H2) "HTTP/2" else "HTTP/1.1"
        val sb = StringBuilder()
        sb.append(version).append(' ').append(status).append(" \r\n")
        for ((name, value) in message.headers) {
            if (name.startsWith(":")) continue
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        message.bodyRef?.bytes?.let { sb.append(String(it, Charsets.ISO_8859_1)) }
        return sb.toString()
    }

    private fun split(raw: String): Pair<String, String> {
        val crlf = raw.indexOf("\r\n\r\n")
        if (crlf >= 0) return raw.substring(0, crlf) to raw.substring(crlf + 4)
        val lf = raw.indexOf("\n\n")
        if (lf >= 0) return raw.substring(0, lf) to raw.substring(lf + 2)
        return raw to ""
    }

    private fun parseHeaders(lines: List<String>): List<Pair<String, String>> = lines.mapNotNull { line ->
        val idx = if (line.startsWith(":")) line.indexOf(':', 1) else line.indexOf(':')
        if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }

    private fun extractHost(authority: String): String {
        val token = authority.trim()
        if (token.startsWith("[") && token.contains("]")) return token.substringAfter("[").substringBefore("]")
        return if (token.count { it == ':' } == 1) token.substringBefore(':') else token
    }

    private fun extractPort(authority: String): Int? {
        val token = authority.trim()
        if (token.startsWith("[") && token.contains("]")) return token.substringAfter("]", "").removePrefix(":").toIntOrNull()
        return if (token.count { it == ':' } == 1) token.substringAfter(':').toIntOrNull() else null
    }
}
