package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponse

/**
 * SSE(Server-Sent Events, `text/event-stream`)流式响应的实时捕获支持。
 *
 * 与 WebSocket tap 同一套线程模型:Netty 事件循环上按 chunk 追加 body,经 [ProxyController.onHistoryUpdated]
 * 通知 UI 在 EDT 上实时刷新。SSE body 设上限([SSE_CAPTURE_MAX_BYTES])避免无限流 OOM。
 */

/** SSE body 累积上限:超过后停止追加并标注截断,防止长连接流式响应无限增长占用内存。 */
internal const val SSE_CAPTURE_MAX_BYTES: Int = 1024 * 1024

/** 判定响应是否为 SSE(`Content-Type: text/event-stream`)。 */
internal fun isSseResponse(response: HttpResponse): Boolean {
    val contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE) ?: return false
    return contentType.substringBefore(';').trim().equals("text/event-stream", ignoreCase = true)
}

/**
 * 单条 SSE 流的累积状态。每个 SSE 响应对应一个实例,按请求 identity 在 [ProxyController.sseStreamStates] 中索引。
 * 首部到达时创建并记录首条历史条目(body 空);随后每个 body chunk 经 [appendChunk] 追加;连接关闭(LastHttpContent)时 finalize。
 */
internal class SseStreamState(
    val entryId: Long,
    val timeMillis: Long,
    val responseHeadersRaw: String,
    val requestRaw: String,
    val originalRequestRaw: String,
    val method: String,
    val host: String,
    val path: String,
    val statusCode: Int,
    val tls: Boolean,
    val protocol: String,
    val streamId: Int?
) {
    // ISO_8859_1 累积,与 formatResponseRaw 的 body 编码一致(保字节,显示时再按 charset 解码)。
    private val bodyBuf: StringBuilder = StringBuilder()
    private var byteCount: Long = 0

    @Volatile
    var finalized: Boolean = false
        private set

    @Volatile
    var truncated: Boolean = false
        private set

    /** 拼接首部 + 当前已累积 body,作为历史条目的 responseRaw。 */
    fun responseRaw(): String = responseHeadersRaw + bodyBuf.toString()

    fun bodyLength(): Int = byteCount.toInt().coerceAtLeast(0)

    /** 追加一个 body chunk 的原始字节。超过上限后停止追加并标注截断(仅一次)。 */
    fun appendChunk(bytes: ByteArray) {
        if (finalized || truncated || bytes.isEmpty()) {
            return
        }
        val remaining = SSE_CAPTURE_MAX_BYTES.toLong() - byteCount
        if (remaining <= 0L) {
            markTruncated()
            return
        }
        val take = if (bytes.size.toLong() <= remaining) bytes.size else remaining.toInt()
        if (take > 0) {
            bodyBuf.append(String(bytes, 0, take, Charsets.ISO_8859_1))
            byteCount += take.toLong()
        }
        if (take < bytes.size) {
            markTruncated()
        }
    }

    /** 标记流结束;之后追加的 chunk 会被忽略。 */
    fun markFinalized() {
        finalized = true
    }

    private fun markTruncated() {
        if (truncated) {
            return
        }
        truncated = true
        bodyBuf.append("\n\n[... SSE stream truncated at ").append(SSE_CAPTURE_MAX_BYTES)
            .append(" bytes; further events not captured ...]")
    }
}

/** 首部到达时记录首条历史条目(body 空,mimeType="sse"),让历史表立即出现该行。 */
internal fun ProxyController.recordSseHttpHistory(state: SseStreamState) {
    onHistoryAdded?.invoke(
        ProxyHistoryEntry(
            id = state.entryId,
            timeMillis = state.timeMillis,
            method = state.method,
            host = state.host,
            path = state.path,
            statusCode = state.statusCode,
            length = 0,
            mimeType = "sse",
            title = "",
            tls = state.tls,
            modified = false,
            tool = "proxy",
            requestRaw = state.requestRaw,
            responseRaw = state.responseHeadersRaw,
            originalRequestRaw = if (state.requestRaw != state.originalRequestRaw) state.originalRequestRaw else "",
            originalResponseRaw = "",
            protocol = state.protocol,
            streamId = state.streamId
        )
    )
}

/** 发送一次实时更新(finalized=true 时为最终态,UI 层据此落库)。 */
internal fun ProxyController.emitSseUpdate(state: SseStreamState, finalized: Boolean) {
    onHistoryUpdated?.invoke(
        ProxyHistoryEntry(
            id = state.entryId,
            timeMillis = state.timeMillis,
            method = state.method,
            host = state.host,
            path = state.path,
            statusCode = state.statusCode,
            length = state.bodyLength(),
            mimeType = "sse",
            title = "",
            tls = state.tls,
            modified = false,
            tool = "proxy",
            requestRaw = state.requestRaw,
            responseRaw = state.responseRaw(),
            originalRequestRaw = if (state.requestRaw != state.originalRequestRaw) state.originalRequestRaw else "",
            originalResponseRaw = "",
            protocol = state.protocol,
            streamId = state.streamId
        ),
        finalized
    )
}
