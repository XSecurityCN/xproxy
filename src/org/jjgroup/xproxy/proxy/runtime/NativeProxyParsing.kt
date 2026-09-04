package org.jjgroup.xproxy.proxy.runtime

import java.net.URI
import java.util.Locale

internal data class NativeParsedHttpRequest(
    val requestLine: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray
) {
    fun toRaw(line: NativeProxyParsing.RequestLine): String {
        val sb = StringBuilder()
        sb.append(line.method).append(' ').append(line.target).append(' ').append(line.version).append("\r\n")
        for ((name, value) in headers) {
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        if (body.isNotEmpty()) {
            sb.append(String(body, Charsets.ISO_8859_1))
        }
        return sb.toString()
    }
}

internal object NativeProxyParsing {
    internal data class RequestLine(val method: String, val target: String, val version: String)

    internal data class UpstreamTarget(
        val host: String,
        val port: Int,
        val scheme: String,
        val originForm: String
    )

    fun parseRequestLine(line: String): RequestLine? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(' ').filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]
        if (!version.startsWith("HTTP/")) return null
        if (!Regex("^[A-Z!#$%&'*+.^_`|~-]+$").matches(method)) return null
        if (target.length > 8192) return null
        return RequestLine(method = method, target = target, version = version)
    }

    fun resolveUpstreamTarget(requestLine: RequestLine, headers: List<Pair<String, String>>): UpstreamTarget? {
        val target = requestLine.target
        if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            val uri = try {
                URI(target)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val port = when {
                uri.port > 0 -> uri.port
                scheme == "http" -> 80
                scheme == "https" -> 443
                else -> return null
            }
            val rawPath = uri.rawPath.takeUnless { it.isNullOrBlank() } ?: "/"
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return UpstreamTarget(host = host, port = port, scheme = scheme, originForm = "$rawPath$query")
        }
        if (target == "*") {
            val hostHeader = findHeader(headers, "Host") ?: return null
            val authority = parseAuthorityWithDefaultPort(hostHeader, 80) ?: return null
            return UpstreamTarget(host = authority.first, port = authority.second, scheme = "http", originForm = "*")
        }
        if (!target.startsWith('/')) return null
        val hostHeader = findHeader(headers, "Host") ?: return null
        val authority = parseAuthorityWithDefaultPort(hostHeader, 80) ?: return null
        return UpstreamTarget(host = authority.first, port = authority.second, scheme = "http", originForm = target)
    }

    fun parseConnectAuthority(authority: String): Pair<String, Int>? {
        return parseAuthorityWithDefaultPort(authority.trim(), 443)
    }

    fun hostHeaderValue(host: String, port: Int, scheme: String): String {
        val defaultPort = if (scheme.equals("https", ignoreCase = true)) 443 else 80
        return if (port == defaultPort) host else "$host:$port"
    }

    fun parseRawRequest(raw: String): Pair<RequestLine, NativeParsedHttpRequest>? {
        val normalized = raw.replace("\r\n", "\n")
        val parts = normalized.split("\n\n", limit = 2)
        val head = parts.firstOrNull() ?: return null
        val body = if (parts.size > 1) parts[1] else ""
        val lines = head.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return null
        }
        val line = parseRequestLine(lines[0]) ?: return null
        val headers = ArrayList<Pair<String, String>>()
        for (headerLine in lines.drop(1)) {
            val idx = headerLine.indexOf(':')
            if (idx <= 0) {
                continue
            }
            val name = headerLine.substring(0, idx).trim()
            val value = headerLine.substring(idx + 1).trim()
            if (name.isNotBlank()) {
                headers += name to value
            }
        }
        return line to NativeParsedHttpRequest(
            requestLine = lines[0],
            headers = headers,
            body = body.toByteArray(Charsets.ISO_8859_1)
        )
    }

    private fun findHeader(headers: List<Pair<String, String>>, name: String): String? {
        for ((key, value) in headers) {
            if (key.equals(name, ignoreCase = true)) return value
        }
        return null
    }

    private fun parseAuthorityWithDefaultPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        val value = authority.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("[")) {
            val closeIdx = value.indexOf(']')
            if (closeIdx <= 1) return null
            val host = value.substring(1, closeIdx)
            if (closeIdx == value.length - 1) return host to defaultPort
            if (value.getOrNull(closeIdx + 1) != ':') return null
            val port = value.substring(closeIdx + 2).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return host to port
        }

        val colonCount = value.count { it == ':' }
        if (colonCount == 0) return value to defaultPort
        if (colonCount > 1) return null
        val idx = value.lastIndexOf(':')
        if (idx <= 0 || idx == value.length - 1) return null
        val host = value.substring(0, idx)
        val port = value.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }
}
