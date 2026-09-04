package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.proxy.core.h2.Http2InboundEvent

internal data class ProxyProtocolMetadata(
    val protocol: String,
    val streamId: Int?,
    val wasDowngraded: Boolean
)

internal object ProxyHttp2Bridge {
    private val streamIdHeaderNames = setOf("x-stream-id")

    fun resolveMetadata(requestRaw: String, responseRaw: String, tls: Boolean, streamIdHint: Int?): ProxyProtocolMetadata {
        val requestProtocol = inferProtocolFromRequest(requestRaw)
        val responseProtocol = inferProtocolFromResponse(responseRaw)
        val protocol = when {
            responseProtocol != null -> responseProtocol
            requestProtocol != null -> requestProtocol
            tls -> "https"
            else -> "http/1.1"
        }
        val streamId = streamIdHint ?: extractStreamId(requestRaw) ?: extractStreamId(responseRaw)
        val wasDowngraded = requestProtocol == "http/2" && responseProtocol != null && responseProtocol != "http/2"
        return ProxyProtocolMetadata(
            protocol = protocol,
            streamId = streamId,
            wasDowngraded = wasDowngraded
        )
    }

    fun requestRawForRecordedProtocol(requestRaw: String, protocol: String): String {
        if (!protocol.equals("http/1.1", ignoreCase = true)) {
            return requestRaw
        }
        val lineEnd = requestRaw.indexOf("\r\n").takeIf { it >= 0 } ?: requestRaw.indexOf('\n')
        val firstLine = if (lineEnd >= 0) requestRaw.substring(0, lineEnd).trimEnd('\r') else requestRaw.trimEnd('\r', '\n')
        val normalizedLine = when {
            firstLine.endsWith("HTTP/2", ignoreCase = true) -> firstLine.dropLast(6).trimEnd() + " HTTP/1.1"
            firstLine.endsWith("HTTP/2.0", ignoreCase = true) -> firstLine.dropLast(8).trimEnd() + " HTTP/1.1"
            else -> return requestRaw
        }
        if (lineEnd < 0) {
            return normalizedLine
        }
        return normalizedLine + requestRaw.substring(lineEnd)
    }

    fun requestEvents(streamId: Int, requestRaw: String): List<Http2InboundEvent> {
        val normalized = requestRaw.replace("\r\n", "\n")
        val parts = normalized.split("\n\n", limit = 2)
        val headerBlock = parts.firstOrNull().orEmpty()
        val body = if (parts.size > 1) parts[1] else ""
        val lines = headerBlock.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) {
            return emptyList()
        }

        val requestLine = lines.first()
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2) {
            return emptyList()
        }
        val method = requestParts[0].trim().uppercase()
        val path = requestParts[1].trim().ifBlank { "/" }
        val headers = ArrayList<Pair<String, String>>()
        headers.add(":method" to method)
        headers.add(":path" to path)

        val regularHeaders = parseHeaders(lines.drop(1))
        val authority = regularHeaders.firstOrNull { it.first.equals("host", ignoreCase = true) }?.second.orEmpty()
        if (authority.isNotBlank()) {
            headers.add(":authority" to authority)
        }
        for ((name, value) in regularHeaders) {
            if (name.equals("host", ignoreCase = true)) {
                continue
            }
            headers.add(name.lowercase() to value)
        }

        val events = ArrayList<Http2InboundEvent>()
        val bodyBytes = body.toByteArray(Charsets.ISO_8859_1)
        events.add(Http2InboundEvent.Headers(streamId, headers, bodyBytes.isEmpty()))
        if (bodyBytes.isNotEmpty()) {
            events.add(Http2InboundEvent.Data(streamId, bodyBytes, true))
        }
        return events
    }

    fun responseEvents(streamId: Int, responseRaw: String): List<Http2InboundEvent> {
        val normalized = responseRaw.replace("\r\n", "\n")
        val parts = normalized.split("\n\n", limit = 2)
        val headerBlock = parts.firstOrNull().orEmpty()
        val body = if (parts.size > 1) parts[1] else ""
        val lines = headerBlock.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) {
            return emptyList()
        }
        val statusCode = lines.first().split(" ").getOrNull(1)?.trim().orEmpty()
        if (statusCode.isBlank()) {
            return emptyList()
        }
        val headers = ArrayList<Pair<String, String>>()
        headers.add(":status" to statusCode)
        for ((name, value) in parseHeaders(lines.drop(1))) {
            headers.add(name.lowercase() to value)
        }
        val bodyBytes = body.toByteArray(Charsets.ISO_8859_1)
        val events = ArrayList<Http2InboundEvent>()
        events.add(Http2InboundEvent.Headers(streamId, headers, bodyBytes.isEmpty()))
        if (bodyBytes.isNotEmpty()) {
            events.add(Http2InboundEvent.Data(streamId, bodyBytes, true))
        }
        return events
    }

    private fun parseHeaders(lines: List<String>): List<Pair<String, String>> {
        val headers = ArrayList<Pair<String, String>>()
        for (line in lines) {
            val idx = line.indexOf(':')
            if (idx <= 0) {
                continue
            }
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isNotBlank()) {
                headers.add(name to value)
            }
        }
        return headers
    }

    private fun inferProtocolFromRequest(requestRaw: String): String? {
        val line = requestRaw.lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.startsWith("PRI * HTTP/2.0")) {
            return "http/2"
        }
        return when {
            line.endsWith("HTTP/2") -> "http/2"
            line.endsWith("HTTP/2.0") -> "http/2"
            line.endsWith("HTTP/1.1") -> "http/1.1"
            else -> null
        }
    }

    private fun inferProtocolFromResponse(responseRaw: String): String? {
        val line = responseRaw.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            line.startsWith("HTTP/2") -> "http/2"
            line.startsWith("HTTP/1.1") -> "http/1.1"
            else -> null
        }
    }

    private fun extractStreamId(raw: String): Int? {
        for (line in raw.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) {
                continue
            }
            val name = line.substring(0, idx).trim().lowercase()
            if (name !in streamIdHeaderNames) {
                continue
            }
            return line.substring(idx + 1).trim().toIntOrNull()
        }
        return null
    }
}
