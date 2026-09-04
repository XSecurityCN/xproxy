package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.settings.core.UpstreamProxyConfig
import org.jjgroup.xproxy.settings.core.UpstreamProxySettings
import java.net.InetAddress

/**
 * 上游代理解析与监听状态文案、MITM 开关判定等纯辅助函数。
 *
 * 这些函数不依赖 [ProxyController] 实例状态,仅以参数或静态配置([UpstreamProxySettings])为输入,
 * 故从 [ProxyLifecycle] 中抽出为同包顶层 `internal`/`private` 函数,保持行为完全一致。
 */

internal fun resolveUpstreamProxy(bindHost: String, bindPort: Int): UpstreamProxyConfig? {
    val upstream = UpstreamProxySettings.getEnabledProxy() ?: return null
    if (isSelfLoop(bindHost, bindPort, upstream.host, upstream.port)) {
        return null
    }
    return upstream
}

internal fun buildListeningStatus(bindHost: String, bindPort: Int): String {
    val upstream = resolveUpstreamProxy(bindHost, bindPort)
    if (upstream != null) {
        return "Listening on $bindHost:$bindPort via upstream ${upstream.host}:${upstream.port}"
    }
    return if (UpstreamProxySettings.isEnabled()) {
        "Listening on $bindHost:$bindPort (upstream ignored: invalid/looped)"
    } else {
        "Listening on $bindHost:$bindPort"
    }
}

private fun isSelfLoop(bindHost: String, bindPort: Int, upstreamHost: String, upstreamPort: Int): Boolean {
    if (bindPort <= 0 || bindPort != upstreamPort) {
        return false
    }
    val bind = normalizeHost(bindHost)
    val upstream = normalizeHost(upstreamHost)
    if (bind == upstream) {
        return true
    }
    if (isAnyHost(bind) && isLoopbackHost(upstream)) {
        return true
    }
    return false
}

// 上游代理 host 的归一化结果在代理运行期不变(配置不变),按输入 host 缓存,避免每请求 InetAddress.getByName
// 在事件循环上做 DNS(域名型上游代理缓存 miss 时阻塞数秒)。
private val HOST_NORMALIZE_CACHE = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun normalizeHost(host: String): String {
    val value = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    return HOST_NORMALIZE_CACHE.computeIfAbsent(value) { v ->
        when (v) {
            "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> "loopback"
            "0.0.0.0", "::" -> "any"
            else -> {
                try {
                    val resolved = InetAddress.getByName(v)
                    if (resolved.isLoopbackAddress) "loopback" else resolved.hostAddress.lowercase()
                } catch (_: Exception) {
                    v
                }
            }
        }
    }
}

private fun isAnyHost(host: String): Boolean {
    return host == "any"
}

private fun isLoopbackHost(host: String): Boolean {
    return host == "loopback"
}

internal fun shouldEnableMitmForRequest(
    requestProto: com.github.monkeywie.proxyee.util.ProtoUtil.RequestProto?,
    policy: ProtocolPolicy
): Boolean {
    if (requestProto == null) {
        return true
    }
    if (!requestProto.ssl) {
        return true
    }
    return true
}
