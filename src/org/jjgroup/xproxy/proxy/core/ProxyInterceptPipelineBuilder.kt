package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.model.ProxyInterceptItem
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRuleAction
import org.jjgroup.xproxy.settings.core.UpstreamProxyProtocol
import com.github.monkeywie.proxyee.intercept.HttpProxyIntercept
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline
import com.github.monkeywie.proxyee.intercept.common.CertDownIntercept
import com.github.monkeywie.proxyee.intercept.common.FullRequestIntercept
import com.github.monkeywie.proxyee.intercept.common.FullResponseIntercept
import com.github.monkeywie.proxyee.proxy.ProxyConfig
import com.github.monkeywie.proxyee.proxy.ProxyType
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.LastHttpContent

/**
 * 代理拦截管道([HttpProxyInterceptInitializer])的构建,以及 HTTP/2 事件分发、SSE finalizer 安装等
 * 与拦截管道紧密耦合的辅助逻辑。从 [ProxyLifecycle] 中抽出为 [ProxyController] 的 `internal` 扩展函数,
 * 行为与原私有成员方法完全一致。
 */

internal fun ProxyController.createInterceptInitializer(bindHost: String, bindPort: Int): HttpProxyInterceptInitializer {
    return object : HttpProxyInterceptInitializer() {
        override fun init(pipeline: HttpProxyInterceptPipeline) {
            pipeline.addFirst(object : HttpProxyIntercept() {
                override fun beforeRequest(
                    clientChannel: io.netty.channel.Channel,
                    httpRequest: HttpRequest,
                    pipeline: HttpProxyInterceptPipeline
                ) {
                    val upstream = resolveUpstreamProxy(bindHost, bindPort)
                    if (upstream == null) {
                        pipeline.setProxyConfig(null)
                    } else {
                        val proxyType = when (upstream.protocol) {
                            UpstreamProxyProtocol.HTTP -> ProxyType.HTTP
                            UpstreamProxyProtocol.SOCKS5 -> ProxyType.SOCKS5
                        }
                        pipeline.setProxyConfig(ProxyConfig(proxyType, upstream.host, upstream.port))
                        val authHeader = upstream.proxyAuthorizationHeaderValue()
                        if (!authHeader.isNullOrBlank() && upstream.protocol == UpstreamProxyProtocol.HTTP) {
                            httpRequest.headers().set("Proxy-Authorization", authHeader)
                        }
                    }
                    pipeline.beforeRequest(clientChannel, httpRequest)
                }
            })
            pipeline.addLast(CertDownIntercept())
            pipeline.addLast(object : HttpProxyIntercept() {
                override fun beforeRequest(
                    clientChannel: io.netty.channel.Channel,
                    httpRequest: HttpRequest,
                    pipeline: HttpProxyInterceptPipeline
                ) {
                    if (isWebSocketUpgradeRequest(httpRequest)) {
                        val host = httpRequest.headers().get("Host") ?: ""
                        val path = httpRequest.uri()
                        val requestRaw = if (httpRequest is FullHttpRequest) {
                            formatRequestRaw(httpRequest)
                        } else {
                            formatRequestHeadersOnly(httpRequest)
                        }
                        requestSnapshotByIdentity[System.identityHashCode(httpRequest)] = requestRaw
                        // 分配 WS 会话上下文(供重放器重建连接):handshake_response 暂空,afterResponse 时补全。
                        val tls = isTlsRequest(httpRequest, pipeline)
                        val (hostOnly, port) = parseWsHostPort(host, tls)
                        val sessionId = beginWsSession(hostOnly, port, tls, path, requestRaw, System.identityHashCode(httpRequest))
                        recordWsHandshakeRequest(sessionId, host, path, requestRaw)
                    }
                    pipeline.beforeRequest(clientChannel, httpRequest)
                }

                override fun afterResponse(
                    clientChannel: io.netty.channel.Channel,
                    proxyChannel: io.netty.channel.Channel,
                    httpResponse: HttpResponse,
                    pipeline: HttpProxyInterceptPipeline
                ) {
                    val request = pipeline.httpRequest
                    if (request != null && isWebSocketUpgradeResponse(request, httpResponse)) {
                        val requestIdentity = System.identityHashCode(request)
                        val host = request.headers().get("Host") ?: ""
                        val path = request.uri()
                        val requestRaw = requestSnapshotByIdentity[requestIdentity]
                            ?: if (request is FullHttpRequest) formatRequestRaw(request) else formatRequestHeadersOnly(request)
                        val responseRaw = if (httpResponse is FullHttpResponse) {
                            formatResponseRaw(httpResponse)
                        } else {
                            val statusLine = "${httpResponse.protocolVersion().text()} ${httpResponse.status().code()} ${httpResponse.status().reasonPhrase()}"
                            val headers = httpResponse.headers().entries().joinToString("\r\n") { "${it.key}: ${it.value}" }
                            "$statusLine\r\n$headers\r\n\r\n"
                        }
                        recordWsHandshakeHttpHistory(request, requestRaw, responseRaw, httpResponse, pipeline)
                        val sessionId = completeWsSession(requestIdentity, responseRaw)
                        if (sessionId != null) {
                            recordWsHandshakeResponse(sessionId, host, path, responseRaw)
                        } else {
                            // 异常路径(在途会话已丢失):仍记录握手响应,但无法关联会话,重放器将禁用。
                            recordWsHandshakeResponse(-1L, host, path, responseRaw)
                        }
                        runCatching {
                            val sid = sessionId ?: -1L
                            installWebSocketTaps(clientChannel, proxyChannel, host, path, sid)
                        }.onFailure {
                            Utils.err("Install websocket taps failed: ${it.message}")
                        }
                        requestSnapshotByIdentity.remove(requestIdentity)
                        requestOriginalSnapshotByIdentity.remove(requestIdentity)
                        requestModifiedByRequestIdentity.remove(requestIdentity)
                        responseInterceptByRequestIdentity.remove(requestIdentity)
                        requestH2StreamIdByIdentity.remove(requestIdentity)
                    }
                    pipeline.afterResponse(clientChannel, proxyChannel, httpResponse)
                }
            })
            pipeline.addLast(object : FullRequestIntercept() {
                override fun match(httpRequest: HttpRequest, pipeline: HttpProxyInterceptPipeline): Boolean {
                    return !isWebSocketUpgradeRequest(httpRequest)
                }

                override fun handleRequest(httpRequest: FullHttpRequest, pipeline: HttpProxyInterceptPipeline) {
                    val id = interceptId.incrementAndGet()
                    val hostBeforeRewrite = httpRequest.headers().get("Host") ?: ""
                    val tls = isTlsRequest(httpRequest, pipeline)
                    val requestIdentity = System.identityHashCode(httpRequest)
                    val originalRequestRaw = formatRequestRaw(httpRequest)
                    val autoReplaced = matchReplaceEngine.applyToRequest(httpRequest)
                    // 无规则命中时 ByteBuf 未变,复用已格式化结果,避免一次冗余 ByteBuf->ByteArray->String 拷贝。
                    var requestRaw = if (autoReplaced) formatRequestRaw(httpRequest) else originalRequestRaw
                    var xappReplaced = false
                    val rewrittenRequestRaw = runCatching {
                        onBeforeRequestRewrite?.invoke(requestRaw, hostBeforeRewrite, tls)
                    }.onFailure {
                        Utils.err("xapp on_before_request rewrite failed: ${it.message}")
                    }.getOrNull()
                    if (!rewrittenRequestRaw.isNullOrBlank() && rewrittenRequestRaw != requestRaw) {
                        runCatching {
                            applyEditedRequest(httpRequest, rewrittenRequestRaw)
                            requestRaw = formatRequestRaw(httpRequest)
                            xappReplaced = true
                        }.onFailure {
                            Utils.err("Apply xapp on_before_request rewrite failed: ${it.message}")
                        }
                    }
                    requestSnapshotByIdentity[requestIdentity] = requestRaw
                    // decideRoute 仅用前 24 字节(HTTP/2 preface)做路由判断,只传前缀字节,避免每请求全量 toByteArray 拷贝。
                    val prefixLen = ProtocolDispatcher.HTTP2_PREFACE.length
                    val firstChunk = requestRaw.substring(0, minOf(requestRaw.length, prefixLen))
                        .toByteArray(Charsets.ISO_8859_1)
                    val route = protocolRouteDecider(
                        null,
                        firstChunk
                    )
                    val requestMetadata = ProxyHttp2Bridge.resolveMetadata(
                        requestRaw = requestRaw,
                        responseRaw = "",
                        tls = tls,
                        streamIdHint = requestH2StreamIdByIdentity[requestIdentity]
                    )
                    if (route == ProxyProtocolRoute.H2 || requestMetadata.protocol == "http/2") {
                        val streamId = requestMetadata.streamId ?: reserveH2StreamId(requestIdentity)
                        requestH2StreamIdByIdentity[requestIdentity] = streamId
                        emitHttp2RequestEvents(streamId, requestRaw)
                    }
                    if (autoReplaced || xappReplaced) {
                        requestOriginalSnapshotByIdentity.putIfAbsent(requestIdentity, originalRequestRaw)
                        requestModifiedByRequestIdentity[requestIdentity] = true
                    }
                    val method = httpRequest.method().name()
                    val host = httpRequest.headers().get("Host") ?: ""
                    val path = httpRequest.uri()

                    if (interceptEnabled.get()) {
                        val item = ProxyInterceptItem(id, System.currentTimeMillis(), method, host, path, tls, requestRaw)
                        pendingIntercepts[id] = item
                        pendingInterceptRequests[id] = httpRequest
                        when (interceptRuleEngine.decideForRequest(requestRaw)) {
                            ProxyInterceptRuleAction.FORWARD -> {
                                pendingInterceptRequests.remove(id)
                                pendingIntercepts.remove(id)
                            }
                            ProxyInterceptRuleAction.DROP -> {
                                pendingInterceptRequests.remove(id)
                                pendingIntercepts.remove(id)
                                throw RuntimeException("Dropped by intercept rule")
                            }
                            null -> {
                                onInterceptQueued?.invoke(item)
                                val decision = item.awaitDecision()
                                if (decision == ProxyInterceptItem.Decision.DROP) {
                                    pendingInterceptRequests.remove(id)
                                    throw RuntimeException("Dropped by user")
                                }
                                pendingInterceptRequests.remove(id)
                            }
                        }
                    }
                }
            })
            pipeline.addLast(object : FullResponseIntercept() {
                override fun match(httpRequest: HttpRequest, httpResponse: HttpResponse, pipeline: HttpProxyInterceptPipeline): Boolean {
                    if (isSseResponse(httpResponse)) {
                        // HTTP/1.1 流式 SSE(非 Full)不聚合(HttpObjectAggregator 会缓冲到连接关闭/8MB),
                        // 改由下方 SSE 拦截器按 chunk 实时捕获;H2/已完整的 SSE(FullHttpResponse)走正常聚合,
                        // 由 handleResponse 一次性记录完整 body(非实时,属 H2 已知限制)。
                        return httpResponse is FullHttpResponse
                    }
                    return !isWebSocketUpgradeResponse(httpRequest, httpResponse)
                }

                override fun handleResponse(httpRequest: HttpRequest, httpResponse: FullHttpResponse, pipeline: HttpProxyInterceptPipeline) {
                    var modified = false
                    val requestIdentity = System.identityHashCode(httpRequest)
                    modified = requestModifiedByRequestIdentity.remove(requestIdentity) == true
                    // 缓存当前 httpResponse 的格式化结果,仅在内容被修改时失效。多数响应不会被 match/replace 或 xapp 改写,
                    // 可避免对同一响应反复 formatResponseRaw(每次约 3-4×bodySize 的 ByteBuf->ByteArray->String 堆分配)。
                    var responseRawCache: String? = null
                    fun formattedResponse(): String {
                        if (responseRawCache == null) responseRawCache = formatResponseRaw(httpResponse)
                        return responseRawCache!!
                    }
                    val originalResponseRaw = formattedResponse()
                    val responseAutoReplaced = matchReplaceEngine.applyToResponse(httpResponse)
                    if (responseAutoReplaced) responseRawCache = null
                    var responseXappReplaced = false
                    val requestRawForRewrite = requestSnapshotByIdentity[requestIdentity]
                        ?: formatRequestRaw(httpRequest)
                    val hostForRewrite = httpRequest.headers().get("Host") ?: ""
                    val tlsForRewrite = isTlsRequest(httpRequest, pipeline)
                    val responseRawAfterRules = formattedResponse()
                    val rewrittenResponseRaw = runCatching {
                        onAfterResponseRewrite?.invoke(requestRawForRewrite, responseRawAfterRules, hostForRewrite, tlsForRewrite)
                    }.onFailure {
                        Utils.err("xapp on_after_request rewrite failed: ${it.message}")
                    }.getOrNull()
                    if (!rewrittenResponseRaw.isNullOrBlank() && rewrittenResponseRaw != responseRawAfterRules) {
                        runCatching {
                            applyEditedResponse(httpResponse, rewrittenResponseRaw)
                            responseXappReplaced = true
                            responseRawCache = null
                        }.onFailure {
                            Utils.err("Apply xapp on_after_request rewrite failed: ${it.message}")
                        }
                    }
                    if (responseAutoReplaced || responseXappReplaced) {
                        modified = true
                    }
                    val interceptId = responseInterceptByRequestIdentity.remove(requestIdentity)
                    var interceptOriginalRequestRaw = ""
                    var interceptOriginalResponseRaw = ""
                    if (interceptId != null) {
                        val item = pendingIntercepts[interceptId]
                        if (item != null) {
                            if (item.requestModified && item.originalRequestRaw != item.requestRaw) {
                                modified = true
                                interceptOriginalRequestRaw = item.originalRequestRaw
                            }
                            val responseRawForIntercept = formattedResponse()
                            pendingInterceptResponses[interceptId] = httpResponse
                            item.enterResponsePhase(responseRawForIntercept)
                            when (interceptRuleEngine.decideForResponse(item.requestRaw, responseRawForIntercept)) {
                                ProxyInterceptRuleAction.FORWARD -> {
                                    modified = modified || item.requestModified || item.responseModified
                                    if (item.responseModified && item.originalResponseRaw != item.responseRaw) {
                                        interceptOriginalResponseRaw = item.originalResponseRaw
                                    }
                                    pendingInterceptResponses.remove(interceptId)
                                    pendingIntercepts.remove(interceptId)
                                    onInterceptResolved?.invoke(interceptId)
                                }
                                ProxyInterceptRuleAction.DROP -> {
                                    pendingInterceptResponses.remove(interceptId)
                                    pendingIntercepts.remove(interceptId)
                                    onInterceptResolved?.invoke(interceptId)
                                    throw RuntimeException("Intercepted response dropped by rule")
                                }
                                null -> {
                                    onInterceptChanged?.invoke(item)
                                    when (item.awaitDecision(60000)) {
                                        ProxyInterceptItem.Decision.FORWARD -> {
                                            modified = modified || item.requestModified || item.responseModified
                                            if (item.responseModified && item.originalResponseRaw != item.responseRaw) {
                                                interceptOriginalResponseRaw = item.originalResponseRaw
                                            }
                                        }

                                        ProxyInterceptItem.Decision.DROP,
                                        ProxyInterceptItem.Decision.PENDING -> {
                                            pendingInterceptResponses.remove(interceptId)
                                            pendingIntercepts.remove(interceptId)
                                            onInterceptResolved?.invoke(interceptId)
                                            throw RuntimeException("Intercepted response dropped")
                                        }
                                    }
                                }
                            }
                        }
                        responseRawCache = null
                    }
                    val responseRaw = formattedResponse()
                    val h2StreamId = requestH2StreamIdByIdentity[requestIdentity]
                    val protocolMetadata = ProxyHttp2Bridge.resolveMetadata(
                        requestRaw = requestRawForRewrite,
                        responseRaw = responseRaw,
                        tls = tlsForRewrite,
                        streamIdHint = h2StreamId
                    )
                    if (protocolMetadata.protocol == "http/2" && protocolMetadata.streamId != null) {
                        emitHttp2ResponseEvents(protocolMetadata.streamId, responseRaw)
                    }
                    val id = historyId.incrementAndGet()
                    val method = httpRequest.method().name()
                    val host = httpRequest.headers().get("Host") ?: ""
                    val path = httpRequest.uri()
                    val statusCode = httpResponse.status().code()
                    val length = try {
                        httpResponse.content().readableBytes()
                    } catch (_: Exception) {
                        0
                    }
                    val requestRaw = requestSnapshotByIdentity.remove(requestIdentity)
                        ?: formatRequestRaw(httpRequest)
                    val finalRequestRaw = ProxyHttp2Bridge.requestRawForRecordedProtocol(
                        requestRaw,
                        protocolMetadata.protocol
                    )
                    val originalRequestRaw = if (interceptOriginalRequestRaw.isNotBlank()) {
                        interceptOriginalRequestRaw
                    } else {
                        requestOriginalSnapshotByIdentity.remove(requestIdentity).orEmpty()
                    }
                    val responseRawForAnalysis = truncateBodyForAnalysis(responseRaw, ProxyController.HISTORY_ANALYSIS_BODY_LIMIT_BYTES)
                    val mimeType = detectMimeType(httpResponse, responseRawForAnalysis)
                    val title = extractTitle(responseRawForAnalysis)
                    val tls = isTlsRequest(httpRequest, pipeline)
                    onHistoryAdded?.invoke(
                        ProxyHistoryEntry(
                            id = id,
                            timeMillis = System.currentTimeMillis(),
                            method = method,
                            host = host,
                            path = path,
                            statusCode = statusCode,
                            length = length,
                            mimeType = mimeType,
                            title = title,
                            tls = tls,
                            modified = modified,
                            tool = "proxy",
                            requestRaw = finalRequestRaw,
                            responseRaw = responseRaw,
                            originalRequestRaw = if (finalRequestRaw != originalRequestRaw) originalRequestRaw else "",
                            originalResponseRaw = when {
                                interceptOriginalResponseRaw.isNotBlank() && responseRaw != interceptOriginalResponseRaw -> interceptOriginalResponseRaw
                                (responseAutoReplaced || responseXappReplaced) && responseRaw != originalResponseRaw -> originalResponseRaw
                                else -> ""
                            },
                            protocol = protocolMetadata.protocol,
                            streamId = protocolMetadata.streamId,
                            wasDowngraded = protocolMetadata.wasDowngraded
                        )
                    )
                    requestH2StreamIdByIdentity.remove(requestIdentity)
                }
            })
            // SSE 流式响应拦截器:FullResponseIntercept 对 SSE 不聚合(match 返回 false),
            // 首部与各 body chunk 在此按事件实时捕获并通知 UI 刷新。位于 FullResponseIntercept 之后、
            // default(写客户端)之前,捕获后调用 pipeline.afterResponse 继续转发到客户端。
            pipeline.addLast(object : HttpProxyIntercept() {
                override fun afterResponse(
                    clientChannel: io.netty.channel.Channel,
                    proxyChannel: io.netty.channel.Channel,
                    httpResponse: HttpResponse,
                    pipeline: HttpProxyInterceptPipeline
                ) {
                    val request = pipeline.httpRequest
                    if (request != null && isSseResponse(httpResponse) && httpResponse !is FullHttpResponse) {
                        val requestIdentity = System.identityHashCode(request)
                        if (!sseStreamStates.containsKey(requestIdentity)) {
                            val requestRaw = requestSnapshotByIdentity[requestIdentity]
                                ?: if (request is FullHttpRequest) formatRequestRaw(request) else formatRequestHeadersOnly(request)
                            val originalRequestRaw = requestOriginalSnapshotByIdentity[requestIdentity] ?: ""
                            val state = SseStreamState(
                                entryId = historyId.incrementAndGet(),
                                timeMillis = System.currentTimeMillis(),
                                responseHeadersRaw = formatResponseHeadersOnly(httpResponse),
                                requestRaw = requestRaw,
                                originalRequestRaw = originalRequestRaw,
                                method = request.method().name(),
                                host = request.headers().get("Host") ?: "",
                                path = request.uri(),
                                statusCode = httpResponse.status().code(),
                                tls = isTlsRequest(request, pipeline),
                                protocol = "http/1.1",
                                streamId = requestH2StreamIdByIdentity[requestIdentity]
                            )
                            sseStreamStates[requestIdentity] = state
                            runCatching { recordSseHttpHistory(state) }
                                .onFailure { Utils.err("SSE record history failed: ${it.message}") }
                            runCatching { installSseFinalizer(clientChannel, requestIdentity) }
                                .onFailure { Utils.err("Install SSE finalizer failed: ${it.message}") }
                        }
                    }
                    pipeline.afterResponse(clientChannel, proxyChannel, httpResponse)
                }

                override fun afterResponse(
                    clientChannel: io.netty.channel.Channel,
                    proxyChannel: io.netty.channel.Channel,
                    httpContent: HttpContent,
                    pipeline: HttpProxyInterceptPipeline
                ) {
                    val request = pipeline.httpRequest
                    if (request != null) {
                        val requestIdentity = System.identityHashCode(request)
                        val state = sseStreamStates[requestIdentity]
                        if (state != null && !state.finalized) {
                            var appended = false
                            runCatching {
                                val content = httpContent.content()
                                val readable = content.readableBytes()
                                if (readable > 0) {
                                    val bytes = ByteArray(readable)
                                    content.getBytes(content.readerIndex(), bytes)
                                    state.appendChunk(bytes)
                                    appended = true
                                }
                            }.onFailure { Utils.err("SSE chunk append failed: ${it.message}") }
                            if (httpContent is LastHttpContent) {
                                state.markFinalized()
                                sseStreamStates.remove(requestIdentity)
                                requestSnapshotByIdentity.remove(requestIdentity)
                                requestOriginalSnapshotByIdentity.remove(requestIdentity)
                                requestModifiedByRequestIdentity.remove(requestIdentity)
                                responseInterceptByRequestIdentity.remove(requestIdentity)
                                requestH2StreamIdByIdentity.remove(requestIdentity)
                                runCatching { emitSseUpdate(state, finalized = true) }
                                    .onFailure { Utils.err("SSE finalize emit failed: ${it.message}") }
                            } else if (appended) {
                                runCatching { emitSseUpdate(state, finalized = false) }
                                    .onFailure { Utils.err("SSE update emit failed: ${it.message}") }
                            }
                        }
                    }
                    pipeline.afterResponse(clientChannel, proxyChannel, httpContent)
                }
            })
        }
    }
}

internal fun ProxyController.reserveH2StreamId(requestIdentity: Int): Int {
    val streamId = h2StreamIdAllocator.getAndAdd(2)
    requestH2StreamIdByIdentity[requestIdentity] = streamId
    return streamId
}

internal fun ProxyController.emitHttp2RequestEvents(streamId: Int, requestRaw: String) {
    val adapter = h2PipelineAdapter
    for (event in ProxyHttp2Bridge.requestEvents(streamId, requestRaw)) {
        runCatching { adapter.consume(event) }
            .onFailure { Utils.err("HTTP/2 request hook dispatch failed: ${it.message}") }
    }
}

internal fun ProxyController.emitHttp2ResponseEvents(streamId: Int, responseRaw: String) {
    val adapter = h2PipelineAdapter
    for (event in ProxyHttp2Bridge.responseEvents(streamId, responseRaw)) {
        runCatching { adapter.consume(event) }
            .onFailure { Utils.err("HTTP/2 response hook dispatch failed: ${it.message}") }
    }
}

/**
 * 在 client channel 上安装一个 finalizer:连接关闭(channelInactive)时,若 SSE 流尚未 finalize,
 * 则立即 finalize 并发出最终态(供 UI 落库完整 body)。覆盖客户端主动关闭/异常断连的场景
 * (此时不会有 LastHttpContent,仅靠 chunk 路径的 finalize 不会触发)。
 */
internal fun ProxyController.installSseFinalizer(channel: Channel, requestIdentity: Int) {
    val installer = Runnable {
        channel.attr(ProxyController.SSE_IDENTITY_ATTR).set(requestIdentity)
        if (channel.pipeline().get("xproxy-sse-finalizer") == null) {
            channel.pipeline().addLast("xproxy-sse-finalizer", object : ChannelInboundHandlerAdapter() {
                override fun channelInactive(ctx: ChannelHandlerContext) {
                    val id = ctx.channel().attr(ProxyController.SSE_IDENTITY_ATTR).getAndSet(null)
                    if (id != null) {
                        val state = sseStreamStates.remove(id)
                        if (state != null && !state.finalized) {
                            state.markFinalized()
                            runCatching { emitSseUpdate(state, finalized = true) }
                                .onFailure { Utils.err("SSE finalize-on-close emit failed: ${it.message}") }
                        }
                    }
                    ctx.fireChannelInactive()
                }
            })
        }
    }
    if (channel.eventLoop().inEventLoop()) {
        installer.run()
    } else {
        channel.eventLoop().execute(installer)
    }
}
