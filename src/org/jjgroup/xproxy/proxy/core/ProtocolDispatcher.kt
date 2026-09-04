package org.jjgroup.xproxy.proxy.core

enum class ProxyProtocolRoute {
    H1,
    H2
}

object ProtocolDispatcher {
    const val HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
    private val h2Preface = HTTP2_PREFACE.toByteArray(Charsets.ISO_8859_1)

    fun decideRoute(negotiatedAlpn: String?, firstRequestChunk: ByteArray?): ProxyProtocolRoute {
        val normalizedAlpn = negotiatedAlpn?.trim()?.lowercase()
        if (normalizedAlpn == "h2") {
            return ProxyProtocolRoute.H2
        }
        if (normalizedAlpn == "http/1.1") {
            return ProxyProtocolRoute.H1
        }

        if (firstRequestChunk != null && looksLikeHttp2Preface(firstRequestChunk)) {
            return ProxyProtocolRoute.H2
        }
        return ProxyProtocolRoute.H1
    }

    private fun looksLikeHttp2Preface(bytes: ByteArray): Boolean {
        if (bytes.size < h2Preface.size) {
            return false
        }
        for (i in h2Preface.indices) {
            if (bytes[i] != h2Preface[i]) {
                return false
            }
        }
        return true
    }
}
