package org.jjgroup.xproxy.proxy.runtime.native.h2

data class NativeHttp2StreamRequest(
    val streamId: Int,
    val method: String,
    val path: String,
    val authority: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray
) {
    fun toRawRequest(): String {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(" HTTP/2\r\n")
        sb.append("Host: ").append(authority).append("\r\n")
        for ((name, value) in headers) {
            if (name.startsWith(":")) continue
            if (name.equals("host", ignoreCase = true)) continue
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        if (body.isNotEmpty()) {
            sb.append(String(body, Charsets.ISO_8859_1))
        }
        return sb.toString()
    }

    fun applyEditedRaw(raw: String): NativeHttp2StreamRequest? {
        val parsed = parseRawHttpRequest(raw)
        val authorityHeader = parsed.headers.firstOrNull { it.first.equals("host", ignoreCase = true) }?.second
            ?: authority
        return copy(
            method = parsed.method,
            path = parsed.path,
            authority = authorityHeader,
            headers = parsed.headers,
            body = parsed.body
        )
    }

    fun toPseudoHeaders(): List<Pair<String, String>> {
        val pseudo = ArrayList<Pair<String, String>>()
        pseudo += ":method" to method
        pseudo += ":path" to path
        pseudo += ":authority" to authority
        pseudo += ":scheme" to "https"
        for ((name, value) in headers) {
            if (name.startsWith(":")) continue
            if (name.equals("host", ignoreCase = true)) continue
            pseudo += name.lowercase() to value
        }
        return pseudo
    }
}

object NativeHttp2StreamInterceptor {
    fun fromPseudoHeadersAndBody(
        streamId: Int,
        pseudoHeaders: List<Pair<String, String>>,
        body: ByteArray
    ): NativeHttp2StreamRequest? {
        val method = pseudoHeaders.firstOrNull { it.first == ":method" }?.second ?: return null
        val path = pseudoHeaders.firstOrNull { it.first == ":path" }?.second ?: return null
        val authority = pseudoHeaders.firstOrNull { it.first == ":authority" }?.second.orEmpty()
        val regular = pseudoHeaders.filterNot { it.first.startsWith(":") }
        return NativeHttp2StreamRequest(
            streamId = streamId,
            method = method,
            path = path,
            authority = authority,
            headers = regular,
            body = body
        )
    }
}
