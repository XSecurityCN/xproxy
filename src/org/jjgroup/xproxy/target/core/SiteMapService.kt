package org.jjgroup.xproxy.target.core

import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.target.model.SiteMapEntry
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class SiteMapService {
    private val entries = ConcurrentHashMap<String, SiteMapEntry>()

    fun upsert(history: ProxyHistoryEntry): SiteMapEntry {
        val target = inferTarget(history)
        val normalizedPath = normalizePath(history.path)
        val key = "${target.protocol}://${target.host}:${target.port}|$normalizedPath"

        val existing = entries[key]
        if (existing == null) {
            val created = SiteMapEntry(
                key = key,
                protocol = target.protocol,
                host = target.host,
                port = target.port,
                path = normalizedPath,
                method = history.method,
                statusCode = history.statusCode,
                mimeType = history.mimeType,
                length = history.length,
                tls = history.tls,
                requestRaw = history.requestRaw,
                responseRaw = history.responseRaw,
                title = history.title,
                count = 1,
                lastSeenMillis = history.timeMillis
            )
            entries[key] = created
            return created
        }

        existing.method = history.method
        existing.statusCode = history.statusCode
        existing.mimeType = history.mimeType
        existing.length = history.length
        existing.tls = history.tls
        existing.requestRaw = history.requestRaw
        existing.responseRaw = history.responseRaw
        existing.title = history.title
        existing.count += 1
        existing.lastSeenMillis = history.timeMillis
        return existing
    }

    fun get(key: String): SiteMapEntry? = entries[key]

    fun remove(key: String) = entries.remove(key)

    fun clear() {
        entries.clear()
    }

    private data class TargetInfo(val protocol: String, val host: String, val port: Int)

    private fun protocolFromHistory(history: ProxyHistoryEntry): String? {
        val normalized = history.protocol.trim().lowercase()
        return when (normalized) {
            "http/2", "h2", "http2" -> if (history.tls) "https" else "http"
            "https" -> "https"
            "http", "http/1.1" -> "http"
            else -> null
        }
    }

    private fun inferTarget(history: ProxyHistoryEntry): TargetInfo {
        val requestLine = history.requestRaw.lineSequence().firstOrNull()?.trim().orEmpty()
        val parts = requestLine.split(" ")
        val targetToken = if (parts.size >= 2) parts[1] else ""

        var host: String? = null
        var port: Int? = null
        var protocol: String? = null

        if (targetToken.startsWith("http://") || targetToken.startsWith("https://")) {
            try {
                val uri = URI(targetToken)
                if (!uri.host.isNullOrBlank()) {
                    host = uri.host
                }
                protocol = uri.scheme?.lowercase()
                port = if (uri.port != -1) uri.port else if (protocol == "https") 443 else 80
            } catch (_: Exception) {
            }
        }

        val headers = history.requestRaw.lineSequence().drop(1)
        val hostHeader = headers.firstOrNull { it.lowercase().startsWith("host:") }?.substringAfter(':', "")?.trim()
        if (host.isNullOrBlank() && !hostHeader.isNullOrBlank()) {
            parseHostHeader(hostHeader).let { (h, p) ->
                host = h
                p?.let { port = it }
            }
        }

        val resolvedHost = host.takeUnless { it.isNullOrBlank() }
            ?: history.host.substringBefore(':')
        val resolvedProtocol = protocol.takeUnless { it.isNullOrBlank() }
            ?: protocolFromHistory(history)
            ?: if (history.tls) "https" else "http"
        val resolvedPort = port
            ?: history.host.substringAfter(':', "").toIntOrNull()
            ?: if (resolvedProtocol == "https") 443 else 80

        return TargetInfo(resolvedProtocol, resolvedHost, resolvedPort)
    }

    private fun parseHostHeader(hostHeader: String): Pair<String, Int?> = when {
        hostHeader.startsWith("[") && hostHeader.contains("]") -> {
            val end = hostHeader.indexOf(']')
            val h = hostHeader.substring(1, end)
            val rest = hostHeader.substring(end + 1)
            val p = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
            h to p
        }
        else -> {
            val idx = hostHeader.lastIndexOf(':')
            if (idx > 0 && hostHeader.indexOf(':') == idx) {
                hostHeader.substring(0, idx) to hostHeader.substring(idx + 1).toIntOrNull()
            } else {
                hostHeader to null
            }
        }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) {
            return "/"
        }
        return try {
            val token = path.trim()
            if (token.startsWith("http://") || token.startsWith("https://")) {
                val uri = URI(token)
                uri.path.ifBlank { "/" }
            } else {
                token.substringBefore('?').ifBlank { "/" }
            }
        } catch (_: Exception) {
            path.substringBefore('?').ifBlank { "/" }
        }
    }
}
