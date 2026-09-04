package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.model.ProxyInterceptItem
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRule
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceRule
import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry
import org.jjgroup.xproxy.proxy.model.WsSession
import org.jjgroup.xproxy.proxy.core.h2.Http2InterceptHooks
import org.jjgroup.xproxy.proxy.core.h2.Http2PipelineAdapter
import org.jjgroup.xproxy.proxy.runtime.NativeProxyRuntime
import org.jjgroup.xproxy.proxy.runtime.ProxyeeRuntime
import org.jjgroup.xproxy.proxy.runtime.ProxyRuntime
import org.jjgroup.xproxy.proxy.runtime.ProxyRuntimeType
import org.jjgroup.xproxy.settings.core.UpstreamProxySettings
import org.jjgroup.xproxy.settings.core.XproxyCaManager
import com.github.monkeywie.proxyee.server.HttpProxyCACertFactory
import com.github.monkeywie.proxyee.server.accept.HttpProxyMitmMatcher
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ProxyController {
    companion object {
        internal val C2S_TAP_INSTALLED: AttributeKey<Boolean> = AttributeKey.valueOf("xproxy.ws.tap.c2s")
        internal val S2C_TAP_INSTALLED: AttributeKey<Boolean> = AttributeKey.valueOf("xproxy.ws.tap.s2c")
        // SSE 流式 state 的请求 identity,挂在 client channel 上,供 channelInactive 时 finalize。
        internal val SSE_IDENTITY_ATTR: AttributeKey<Int> = AttributeKey.valueOf("xproxy.sse.identity")
        internal const val HISTORY_ANALYSIS_BODY_LIMIT_BYTES = 64 * 1024
        // 下游 H1 解码器(HttpServerCodec/HttpClientCodec)的 maxHeaderSize / maxInitialLineLength 上限。
        // proxyee 默认 8192/4096 会拒掉抖音等大 Cookie 站点(431/414)。与 H2 侧 HttpProxyServerHandler
        // 的 MAX_DOWNSTREAM_HEADER_LIST_SIZE 对齐,保持单点调整。
        internal const val DOWNSTREAM_MAX_HEADER_SIZE = 64 * 1024
    }

    internal val interceptId = AtomicLong(0)
    internal val historyId = AtomicLong(0)
    internal val wsHistoryId = AtomicLong(0)
    internal val wsSessionId = AtomicLong(0)
    // 在途 WebSocket 会话:握手请求(beforeRequest)阶段已分配 sessionId,但 101 响应(afterResponse)尚未到达。
    // key = System.identityHashCode(httpRequest),value = sessionId;afterResponse 时取出并补全 handshake_response。
    private val pendingWsSessionByIdentity = ConcurrentHashMap<Int, Long>()
    // 仍存活的代理 WS 连接句柄(供重放器复用原连接),key = sessionId;tap channelInactive 时移除。
    internal val wsLiveConnections = java.util.concurrent.ConcurrentHashMap<Long, org.jjgroup.xproxy.proxy.ws.WsLiveConnection>()
    internal val interceptEnabled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    internal val pendingIntercepts = ConcurrentHashMap<Long, ProxyInterceptItem>()
    internal val pendingInterceptRequests = ConcurrentHashMap<Long, FullHttpRequest>()
    internal val pendingInterceptResponses = ConcurrentHashMap<Long, FullHttpResponse>()
    internal val responseInterceptByRequestIdentity = ConcurrentHashMap<Int, Long>()
    internal val requestModifiedByRequestIdentity = ConcurrentHashMap<Int, Boolean>()
    internal val requestSnapshotByIdentity = ConcurrentHashMap<Int, String>()
    internal val requestOriginalSnapshotByIdentity = ConcurrentHashMap<Int, String>()
    internal val requestH2StreamIdByIdentity = ConcurrentHashMap<Int, Int>()
    // SSE 流式响应的累积状态,按请求 identity 索引。首部到达时创建,LastHttpContent 时移除。
    internal val sseStreamStates = ConcurrentHashMap<Int, SseStreamState>()
    internal val matchReplaceEngine = ProxyMatchReplaceEngine()
    internal val interceptRuleEngine = ProxyInterceptRuleEngine()
    internal val h2StreamIdAllocator = AtomicInteger(1)
    private var runtimeType: ProxyRuntimeType = ProxyRuntimeType.PROXYEE
    private var runtime: ProxyRuntime? = null
    private var bindHost = "127.0.0.1"
    private var bindPort = 8080

    var onStatusChanged: ((Boolean, String) -> Unit)? = null
    var onInterceptQueued: ((ProxyInterceptItem) -> Unit)? = null
    var onInterceptChanged: ((ProxyInterceptItem) -> Unit)? = null
    var onInterceptResolved: ((Long) -> Unit)? = null
    var onHistoryAdded: ((ProxyHistoryEntry) -> Unit)? = null
    // SSE 流式响应实时更新:(entry, finalized)。finalized=false 为流式过程中的增量刷新(UI 仅刷新显示,不落库);
    // finalized=true 为流结束的最终态(UI 落库完整 body)。
    var onHistoryUpdated: ((ProxyHistoryEntry, Boolean) -> Unit)? = null
    var onWsHistoryAdded: ((ProxyWsHistoryEntry) -> Unit)? = null
    // WebSocket 会话握手上下文(供重放器重建连接):新增时落库(初始 handshake_response 为空),
    // 后续 afterResponse 收到 101 时通过 [onWsSessionUpdated] 补全响应。
    var onWsSessionAdded: ((WsSession) -> Unit)? = null
    var onWsSessionUpdated: ((Long, String) -> Unit)? = null
    var onBeforeRequestRewrite: ((String, String, Boolean) -> String)? = null
    var onAfterResponseRewrite: ((String, String, String, Boolean) -> String)? = null
    var protocolPolicy: ProtocolPolicy = ProtocolPolicy.allowDowngrade()
    var h2InterceptHooks: Http2InterceptHooks = Http2InterceptHooks.NOOP
    // Http2PipelineAdapter 无状态(仅转发 hooks),h2InterceptHooks 仅在启动前配置、运行期不变,惰性复用单例,
    // 避免每个 H2 请求/响应事件分配。
    internal val h2PipelineAdapter by lazy { Http2PipelineAdapter(h2InterceptHooks) }
    var protocolRouteDecider: (String?, ByteArray?) -> ProxyProtocolRoute = { alpn, firstChunk ->
        ProtocolDispatcher.decideRoute(alpn, firstChunk)
    }

    fun isRunning() = running.get()

    fun isInterceptEnabled() = interceptEnabled.get()

    fun setInterceptEnabled(enabled: Boolean) {
        interceptEnabled.set(enabled)
    }

    fun setMatchReplaceRules(rules: List<ProxyMatchReplaceRule>) {
        matchReplaceEngine.setRules(rules)
    }

    fun setInterceptRules(rules: List<ProxyInterceptRule>) {
        interceptRuleEngine.setRules(rules)
    }

    fun setHistoryStartId(lastPersistedId: Long) {
        if (lastPersistedId > 0) {
            historyId.set(lastPersistedId)
        }
    }

    fun reserveHistoryId(): Long {
        return historyId.incrementAndGet()
    }

    fun setWsHistoryStartId(lastPersistedId: Long) {
        if (lastPersistedId > 0) {
            wsHistoryId.set(lastPersistedId)
        }
    }

    fun setWsSessionStartId(lastPersistedId: Long) {
        if (lastPersistedId > 0) {
            wsSessionId.set(lastPersistedId)
        }
    }

    /**
     * 在 WebSocket 握手请求阶段(beforeRequest)创建会话:分配 id、缓存握手上下文(host/port/tls/path/requestRaw)、
     * 触发 [onWsSessionAdded] 落库(handshake_response 暂为空),供重放器后续按 sessionId 取回。
     */
    fun beginWsSession(host: String, port: Int, tls: Boolean, path: String, handshakeRequest: String, requestIdentity: Int): Long {
        val id = wsSessionId.incrementAndGet()
        pendingWsSessionByIdentity[requestIdentity] = id
        val session = WsSession(
            id = id,
            timeMillis = System.currentTimeMillis(),
            host = host,
            port = port,
            tls = tls,
            path = path,
            handshakeRequest = handshakeRequest,
            handshakeResponse = ""
        )
        onWsSessionAdded?.invoke(session)
        return id
    }

    /**
     * 在 101 响应到达(afterResponse)时补全会话握手响应;返回 sessionId(供 ws_history 帧与 tap 标注),
     * 若该 identity 已不在途(异常路径)则返回 null。
     */
    fun completeWsSession(requestIdentity: Int, handshakeResponse: String): Long? {
        val id = pendingWsSessionByIdentity.remove(requestIdentity) ?: return null
        onWsSessionUpdated?.invoke(id, handshakeResponse)
        return id
    }

    fun currentStatusText(): String {
        if (!running.get()) {
            return "Proxy stopped"
        }
        return buildListeningStatus(bindHost, bindPort)
    }

    fun setRuntimeType(type: ProxyRuntimeType) {
        if (running.get()) {
            throw IllegalStateException("Cannot switch runtime while proxy is running")
        }
        runtimeType = type
    }

    fun start(bindHost: String, port: Int, handleSsl: Boolean = true) {
        this.bindHost = bindHost
        this.bindPort = port

        val initialUpstream = resolveUpstreamProxy(bindHost, port)
        if (UpstreamProxySettings.isEnabled() && initialUpstream == null) {
            onStatusChanged?.invoke(false, "Invalid or looped upstream proxy settings")
            return
        }

        try {
            val selectedRuntime = createRuntime(bindHost, port)
            runtime = selectedRuntime
            selectedRuntime.start(bindHost, port, handleSsl)
        } catch (ex: Exception) {
            runtime = null
            onStatusChanged?.invoke(false, "Failed to start proxy: ${ex.message}")
            Utils.err("Proxy start failed: ${ex.message}")
        }
    }

    fun stop() {
        val currentRuntime = runtime
        try {
            currentRuntime?.stop()
        } catch (ex: Exception) {
            Utils.err("Proxy stop failed: ${ex.message}")
        } finally {
            runtime = null
            running.set(false)
            onStatusChanged?.invoke(false, "Proxy stopped")
        }
    }

    private fun createRuntime(bindHost: String, bindPort: Int): ProxyRuntime {
        return when (runtimeType) {
            ProxyRuntimeType.PROXYEE -> ProxyeeRuntime(
                running = running,
                onStatusChanged = onStatusChanged,
                createCaCertFactory = {
                    object : HttpProxyCACertFactory {
                        override fun getCACert() = XproxyCaManager.loadCaCert()
                        override fun getCAPriKey() = XproxyCaManager.loadCaPrivateKey()
                    }
                },
                createInterceptInitializer = {
                    createInterceptInitializer(bindHost, bindPort)
                },
                configureServer = { config ->
                    config.setMitmMatcher(HttpProxyMitmMatcher { requestProto ->
                        shouldEnableMitmForRequest(requestProto, protocolPolicy)
                    })
                    // 抖音等重型站点单请求头总字节(大 Cookie + sec-ch-ua* + UA + ...)可达数十 KB,
                    // proxyee 默认 maxHeaderSize=8192 / maxInitialLineLength=4096 会让下游 HttpServerCodec
                    // 抛 TooLongHttpHeaderException -> 431 直接回浏览器,请求根本不转发(走代理二维码刷不出的根因)。
                    // 抬到 64KB,与主流抓包代理(mitmproxy/Burp)量级一致;经 HttpProxyServerConfig 流入
                    // 下游 HttpServerCodec 与上游 HttpClientCodec。H2 下游限制另见 HttpProxyServerHandler。
                    config.setMaxHeaderSize(DOWNSTREAM_MAX_HEADER_SIZE)
                    config.setMaxInitialLineLength(DOWNSTREAM_MAX_HEADER_SIZE)
                },
                registerServerRef = { _ -> },
                listeningStatusProvider = { buildListeningStatus(bindHost, bindPort) }
            )

            ProxyRuntimeType.NATIVE -> NativeProxyRuntime(
                onBeforeRequestRewrite = onBeforeRequestRewrite,
                onAfterResponseRewrite = onAfterResponseRewrite,
                protocolPolicyProvider = { protocolPolicy },
                onHistoryAdded = { entry -> onHistoryAdded?.invoke(entry) },
                nextHistoryId = { historyId.incrementAndGet() },
                onRunningChanged = { isRunning, status ->
                    running.set(isRunning)
                    onStatusChanged?.invoke(isRunning, status)
                }
            )
        }
    }

    fun forward(id: Long, editedRequestRaw: String? = null) {
        val item = pendingIntercepts[id] ?: return
        when (item.phase) {
            ProxyInterceptItem.Phase.REQUEST -> {
                val req = pendingInterceptRequests[id]
                if (req != null && !editedRequestRaw.isNullOrBlank()) {
                    try {
                        if (editedRequestRaw != item.originalRequestRaw) {
                            requestOriginalSnapshotByIdentity.putIfAbsent(
                                System.identityHashCode(req),
                                item.originalRequestRaw
                            )
                            item.requestModified = true
                        }
                        applyEditedRequest(req, editedRequestRaw)
                        item.requestRaw = editedRequestRaw
                        requestSnapshotByIdentity[System.identityHashCode(req)] = editedRequestRaw
                    } catch (ex: Exception) {
                        Utils.err("Apply edited request failed: ${ex.message}")
                    }
                }
                if (item.interceptThisResponse && req != null) {
                    responseInterceptByRequestIdentity[System.identityHashCode(req)] = id
                    pendingInterceptRequests.remove(id)
                    item.forward()
                    onInterceptChanged?.invoke(item)
                } else {
                    if (req != null && item.requestModified) {
                        requestOriginalSnapshotByIdentity[System.identityHashCode(req)] = item.originalRequestRaw
                        requestModifiedByRequestIdentity[System.identityHashCode(req)] = true
                    }
                    pendingInterceptRequests.remove(id)
                    pendingIntercepts.remove(id)
                    item.forward()
                    onInterceptResolved?.invoke(id)
                }
            }

            ProxyInterceptItem.Phase.RESPONSE -> {
                val resp = pendingInterceptResponses[id]
                if (resp != null && !editedRequestRaw.isNullOrBlank()) {
                    try {
                        if (editedRequestRaw != item.originalResponseRaw) {
                            item.responseModified = true
                        }
                        applyEditedResponse(resp, editedRequestRaw)
                        item.responseRaw = editedRequestRaw
                    } catch (ex: Exception) {
                        Utils.err("Apply edited response failed: ${ex.message}")
                    }
                }
                pendingInterceptResponses.remove(id)
                pendingIntercepts.remove(id)
                item.forward()
                onInterceptResolved?.invoke(id)
            }
        }
    }

    fun drop(id: Long) {
        pendingInterceptRequests.remove(id)
        pendingInterceptResponses.remove(id)
        pendingIntercepts.remove(id)?.let {
            it.drop()
            onInterceptResolved?.invoke(id)
        }
    }

    fun markInterceptThisResponse(id: Long) {
        val item = pendingIntercepts[id] ?: return
        if (item.phase == ProxyInterceptItem.Phase.REQUEST) {
            item.interceptThisResponse = true
            onInterceptChanged?.invoke(item)
        }
    }
}
