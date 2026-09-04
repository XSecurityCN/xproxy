package org.jjgroup.xproxy.proxy.core.mutation

import org.jjgroup.xproxy.proxy.model.UnifiedHttpMessage

data class DerivedTarget(
    val scheme: String,
    val host: String,
    val port: Int,
    val authority: String,
    val path: String
) {
    companion object {
        fun fromRequest(request: UnifiedHttpMessage, fallbackScheme: String = "https"): DerivedTarget {
            val scheme = request.pseudoHeaders[":scheme"]?.ifBlank { null }
                ?: request.metadata.scheme.ifBlank { fallbackScheme }
            val authority = request.pseudoHeaders[":authority"]?.ifBlank { null }
                ?: request.headers.firstOrNull { it.first.equals("host", ignoreCase = true) }?.second?.ifBlank { null }
                ?: request.metadata.host
            val defaultPort = if (scheme.equals("https", ignoreCase = true)) 443 else 80
            val host = extractHost(authority).ifBlank { request.metadata.host }
            val port = extractPort(authority) ?: request.metadata.port.takeIf { it > 0 } ?: defaultPort
            val normalizedAuthority = if (port == defaultPort) host else "$host:$port"
            val path = request.pseudoHeaders[":path"]?.ifBlank { null }
                ?: request.metadata.path.ifBlank { "/" }
            return DerivedTarget(scheme, host, port, normalizedAuthority, path)
        }

        private fun extractHost(authority: String): String {
            val token = authority.trim()
            if (token.startsWith("[") && token.contains("]")) {
                return token.substringAfter("[").substringBefore("]")
            }
            return if (token.count { it == ':' } == 1) token.substringBefore(':') else token
        }

        private fun extractPort(authority: String): Int? {
            val token = authority.trim()
            if (token.startsWith("[") && token.contains("]")) {
                return token.substringAfter("]", "").removePrefix(":").toIntOrNull()
            }
            return if (token.count { it == ':' } == 1) token.substringAfter(':').toIntOrNull() else null
        }
    }
}
