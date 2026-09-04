package org.jjgroup.xproxy.proxy.model

data class ProxyWsHistoryEntry(
    val id: Long,
    val timeMillis: Long,
    val host: String,
    val path: String,
    val direction: String,
    val messageType: String,
    val mimeType: String,
    val length: Int,
    val preview: String,
    val payload: String,
    // 所属 WebSocket 会话 id(指向 [WsSession.id]);握手帧与数据帧同一会话共享。
    // 历史(升级前)记录为 null,重放器在此为 null 时禁用"重放"。
    val sessionId: Long? = null
)
