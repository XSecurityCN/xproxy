package org.jjgroup.xproxy.proxy.core.h2

import org.jjgroup.xproxy.proxy.model.MessageDirection
import org.jjgroup.xproxy.proxy.model.UnifiedHttpMessage

data class Http2ValidationResult(
    val valid: Boolean,
    val message: String = ""
)

object Http2MessageCodec {
    private val requestPseudoHeaders = listOf(":method", ":path", ":scheme", ":authority")
    private val hopByHopHeaders = setOf(
        "connection",
        "keep-alive",
        "proxy-connection",
        "transfer-encoding",
        "upgrade"
    )

    fun validate(message: UnifiedHttpMessage): Http2ValidationResult {
        if (message.direction == MessageDirection.REQUEST) {
            val missing = requestPseudoHeaders.filter { message.pseudoHeaders[it].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                return Http2ValidationResult(false, "Missing HTTP/2 pseudo headers: ${missing.joinToString(", ")}")
            }
        } else {
            val status = message.pseudoHeaders[":status"]?.toIntOrNull()
            if (status == null || status !in 100..599) {
                return Http2ValidationResult(false, "Invalid HTTP/2 :status")
            }
        }
        return Http2ValidationResult(true)
    }

    fun toHeaderPairs(message: UnifiedHttpMessage): List<Pair<String, String>> {
        val output = ArrayList<Pair<String, String>>()
        if (message.direction == MessageDirection.REQUEST) {
            for (name in requestPseudoHeaders) {
                message.pseudoHeaders[name]?.takeIf { it.isNotBlank() }?.let { output += name to it }
            }
        } else {
            message.pseudoHeaders[":status"]?.takeIf { it.isNotBlank() }?.let { output += ":status" to it }
        }
        for ((name, value) in message.headers) {
            val lower = name.lowercase()
            if (lower.startsWith(":")) continue
            if (lower in hopByHopHeaders) continue
            if (message.direction == MessageDirection.REQUEST && lower == "host") continue
            output += lower to value
        }
        return output
    }
}
