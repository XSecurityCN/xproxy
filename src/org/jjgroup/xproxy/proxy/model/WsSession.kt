package org.jjgroup.xproxy.proxy.model

/**
 * 一个 WebSocket 会话的握手上下文,供重放器(Repeater)重建连接使用。
 *
 * 在代理捕获到 HTTP Upgrade(101 Switching Protocols)时创建:记录原始握手请求与响应、
 * 目标 host/port/tls,使任意一条 [ProxyWsHistoryEntry] 都能通过 [sessionId] 回溯到所属会话,
 * 进而用(可能被用户编辑过的)握手请求重新建立 WebSocket 连接并重放数据帧。
 *
 * @param id 会话自增 id,与 [ProxyWsHistoryEntry.sessionId] 对应。
 * @param timeMillis 握手完成时间。
 * @param host 目标主机(不含端口)。
 * @param port 目标端口(由 Host 头或协议默认值推导)。
 * @param tls 是否为 wss(HTTPS)连接。
 * @param path 握手请求 URI(path)。
 * @param handshakeRequest 原始握手请求文本(含请求行与全部头),重放时 Sec-WebSocket-Key 会被替换为新生成的随机值。
 * @param handshakeResponse 原始握手响应文本(101 响应头),仅供展示。
 */
data class WsSession(
    val id: Long,
    val timeMillis: Long,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val path: String,
    val handshakeRequest: String,
    val handshakeResponse: String = ""
)
