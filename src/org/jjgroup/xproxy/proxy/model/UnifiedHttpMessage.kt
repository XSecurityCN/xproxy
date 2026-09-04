package org.jjgroup.xproxy.proxy.model

enum class HttpProtocol {
    H1_1,
    H2
}

enum class MessageDirection {
    REQUEST,
    RESPONSE
}

data class MessageMetadata(
    val wasDowngraded: Boolean = false,
    val clientAlpn: String? = null,
    val upstreamAlpn: String? = null,
    val tls: Boolean = false,
    val host: String = "",
    val port: Int = if (tls) 443 else 80,
    val method: String = "",
    val path: String = "",
    val scheme: String = if (tls) "https" else "http",
    val streamId: Int? = null,
    val adapter: String = ""
)

data class BodyRef(
    val bytes: ByteArray
)

data class UnifiedHttpMessage(
    val protocol: HttpProtocol,
    val direction: MessageDirection,
    val streamId: Int?,
    val pseudoHeaders: Map<String, String>,
    val headers: List<Pair<String, String>>,
    val trailers: List<Pair<String, String>>,
    val bodyRef: BodyRef?,
    val metadata: MessageMetadata
)
