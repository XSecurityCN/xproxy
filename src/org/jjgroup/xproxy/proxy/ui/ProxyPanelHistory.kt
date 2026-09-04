package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.model.ProxyInterceptItem
import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry
import org.jjgroup.xproxy.ui.marking.TrafficHighlightRegistry
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

internal fun ProxyPanel.hydrateHistoryFromProjectStore() {
    val loaded = initialHistory ?: projectDataStore?.loadHistoryMetadata().orEmpty()
    var maxId = 0L
    historyModel.addAll(loaded)
    for (entry in loaded) {
        if (entry.id > maxId) {
            maxId = entry.id
        }
    }
    if (initialHistory == null) {
        for (entry in loaded) {
            onHistoryEntryAdded?.invoke(entry)
        }
    }
    if (maxId > 0L) {
        controller.setHistoryStartId(maxId)
    }
    historyIdAllocator.set(maxOf(historyIdAllocator.get(), maxId))
}

internal fun ProxyPanel.hydrateWsHistoryFromProjectStore() {
    val loaded = initialWsHistory ?: projectDataStore?.loadWsHistoryMetadata().orEmpty()
    var maxId = 0L
    wsHistoryModel.addAll(loaded)
    for (entry in loaded) {
        if (entry.id > maxId) {
            maxId = entry.id
        }
    }
    if (maxId > 0L) {
        controller.setWsHistoryStartId(maxId)
    }
    val maxSessionId = projectDataStore?.loadMaxWsSessionId() ?: 0L
    if (maxSessionId > 0L) {
        controller.setWsSessionStartId(maxSessionId)
    }
}

internal fun ProxyPanel.recordExternalHistory(entry: ProxyHistoryEntry) {
    val assignedId = if (entry.id > 0) {
        historyIdAllocator.accumulateAndGet(entry.id) { current, incoming -> maxOf(current, incoming) }
        entry.id
    } else {
        val nextFromController = controller.reserveHistoryId()
        historyIdAllocator.accumulateAndGet(nextFromController) { current, incoming -> maxOf(current, incoming) }
    }
    val resolved = entry.copy(id = assignedId, tool = "xapp")
    val metadata = resolved.copy(requestRaw = "", responseRaw = "", originalRequestRaw = "", originalResponseRaw = "")
    val keepInMemory = shouldKeepHistoryDetailInMemory(resolved)
    historyDetailCache[assignedId] = resolved
    SwingUtilities.invokeLater {
        historyModel.add(metadata)
        onHistoryEntryAdded?.invoke(metadata)
        // 同 onHistoryAdded:不在此重建 RowFilter,避免每条流量触发全表重过滤;新行可见性由 sorter 已有 RowFilter 即时评估。
    }
    persistExecutor.execute {
        projectDataStore?.saveHistory(resolved)
        if (!keepInMemory) {
            historyDetailCache.remove(assignedId)
        }
    }
}

internal fun ProxyPanel.deleteHistoryByIds(ids: Set<Long>) {
    if (ids.isEmpty()) {
        return
    }
    historyModel.removeByIds(ids)
    ids.forEach { historyDetailCache.remove(it) }
    projectDataStore?.deleteHistoryByIds(ids)
    TrafficHighlightRegistry.clearMany(TrafficHighlightRegistry.Kind.HTTP, ids)
    updateHistoryDetailFromSelection()
}

internal fun ProxyPanel.deleteWsHistoryByIds(ids: Set<Long>) {
    if (ids.isEmpty()) {
        return
    }
    wsHistoryModel.removeByIds(ids)
    ids.forEach { wsPayloadCache.remove(it) }
    projectDataStore?.deleteWsHistoryByIds(ids)
    TrafficHighlightRegistry.clearMany(TrafficHighlightRegistry.Kind.WS, ids)
    updateWsDetailFromSelection()
}

internal fun ProxyPanel.shouldKeepHistoryDetailInMemory(entry: ProxyHistoryEntry): Boolean {
    val totalChars = entry.requestRaw.length +
        entry.responseRaw.length +
        entry.originalRequestRaw.length +
        entry.originalResponseRaw.length
    return totalChars <= ProxyPanel.MAX_HISTORY_CACHE_CHARS
}

internal fun ProxyPanel.resolveHistoryDetail(entry: ProxyHistoryEntry): ProxyHistoryEntry? {
    if (entry.requestRaw.isNotEmpty() || entry.responseRaw.isNotEmpty()) {
        return entry
    }
    historyDetailCache[entry.id]?.let { return it }
    val loaded = projectDataStore?.loadHistoryById(entry.id) ?: return null
    if (shouldKeepHistoryDetailInMemory(loaded)) {
        historyDetailCache[entry.id] = loaded
    }
    return loaded
}

internal fun ProxyPanel.resolveWsPayload(entry: ProxyWsHistoryEntry): String {
    if (entry.payload.isNotEmpty()) {
        return entry.payload
    }
    wsPayloadCache[entry.id]?.let { return it }
    val loaded = projectDataStore?.loadWsPayloadById(entry.id).orEmpty()
    wsPayloadCache[entry.id] = loaded
    return loaded
}

internal fun ProxyPanel.toHttpService(entry: ProxyHistoryEntry): HttpService {
    val protocol = when {
        entry.protocol.equals("http/2", ignoreCase = true) ||
            entry.protocol.equals("h2", ignoreCase = true) ||
            entry.protocol.equals("http2", ignoreCase = true) -> if (entry.tls) "https" else "http"
        entry.tls -> "https"
        else -> "http"
    }
    val defaultPort = if (entry.tls) 443 else 80
    val hostToken = entry.host.trim()
    if (hostToken.isBlank()) {
        return HttpService("", defaultPort, protocol)
    }

    if (hostToken.startsWith("[") && hostToken.contains("]")) {
        val end = hostToken.indexOf(']')
        val host = hostToken.substring(1, end)
        val rest = hostToken.substring(end + 1)
        val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: defaultPort else defaultPort
        return HttpService(host, port, protocol)
    }

    val colonCount = hostToken.count { it == ':' }
    return if (colonCount == 1) {
        val idx = hostToken.lastIndexOf(':')
        val host = hostToken.substring(0, idx)
        val port = hostToken.substring(idx + 1).toIntOrNull() ?: defaultPort
        HttpService(host, port, protocol)
    } else {
        HttpService(hostToken, defaultPort, protocol)
    }
}

internal fun ProxyPanel.toHttpService(entry: ProxyInterceptItem): HttpService {
    val protocol = when {
        entry.tls -> "https"
        else -> "http"
    }
    val defaultPort = if (entry.tls) 443 else 80
    val hostToken = entry.host.trim()
    if (hostToken.isBlank()) {
        return HttpService("", defaultPort, protocol)
    }

    if (hostToken.startsWith("[") && hostToken.contains("]")) {
        val end = hostToken.indexOf(']')
        val host = hostToken.substring(1, end)
        val rest = hostToken.substring(end + 1)
        val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: defaultPort else defaultPort
        return HttpService(host, port, protocol)
    }

    val colonCount = hostToken.count { it == ':' }
    return if (colonCount == 1) {
        val idx = hostToken.lastIndexOf(':')
        val host = hostToken.substring(0, idx)
        val port = hostToken.substring(idx + 1).toIntOrNull() ?: defaultPort
        HttpService(host, port, protocol)
    } else {
        HttpService(hostToken, defaultPort, protocol)
    }
}

internal fun ProxyPanel.autoStartProxy() {
    proxyOptionsPanel.setListeningPending("Auto starting...")
    thread {
        val host = proxyOptionsPanel.currentBindHost()
        for (port in 8080..65535) {
            if (!isPortAvailable(host, port)) {
                continue
            }
            SwingUtilities.invokeLater {
                proxyOptionsPanel.setBindPortText(port)
            }
            controller.start(host, port, true)
            return@thread
        }
        SwingUtilities.invokeLater {
            proxyOptionsPanel.setListeningState(false, "No available port from 8080-65535")
        }
    }
}

private fun isPortAvailable(host: String, port: Int): Boolean {
    return try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(host, port))
        }
        true
    } catch (_: Exception) {
        false
    }
}
