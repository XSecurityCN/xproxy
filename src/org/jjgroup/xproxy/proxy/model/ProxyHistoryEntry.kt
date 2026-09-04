package org.jjgroup.xproxy.proxy.model

data class ProxyHistoryEntry(
    val id: Long,
    val timeMillis: Long,
    val method: String,
    val host: String,
    val path: String,
    val statusCode: Int,
    val length: Int,
    val mimeType: String,
    val title: String,
    val tls: Boolean,
    val modified: Boolean,
    val tool: String = "proxy",
    val requestRaw: String,
    val responseRaw: String,
    val originalRequestRaw: String = "",
    val originalResponseRaw: String = "",
    val protocol: String = "http/1.1",
    val streamId: Int? = null,
    val wasDowngraded: Boolean = false
)
