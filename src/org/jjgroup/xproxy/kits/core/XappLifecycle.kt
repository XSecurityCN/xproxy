package org.jjgroup.xproxy.kits.core

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.request.sendSingleRequest
import org.jjgroup.xproxy.issue.core.ScriptIssueHub
import org.jjgroup.xproxy.issue.model.ReportedIssue
import org.jjgroup.xproxy.kits.model.XappPlugin
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.ui.marking.TrafficHighlight
import org.jjgroup.xproxy.ui.marking.TrafficHighlightRegistry
import org.python.core.CompileMode
import org.python.core.CompilerFlags
import org.python.core.Py
import org.python.core.PyCode
import org.python.util.PythonInterpreter
import java.io.PrintWriter
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.RejectedExecutionException

internal data class CompiledPluginScript(val mtimeMillis: Long, val pyCode: PyCode)

internal data class DeclaredHandlers(val mtimeMillis: Long, val names: Set<String>)

// xapp 脚本可声明的生命周期 handler 名。refreshContextMenuDefinitions exec 后探测哪些已定义,缓存到
// declaredHandlersByPath,供 rewrite/scan 入口按"是否声明对应 handler"短路过滤。
internal val XAPP_LIFECYCLE_HANDLERS: Set<String> = setOf(
    "on_proxy_http_message",
    "on_http_message",
    "on_before_request",
    "on_after_request"
)

/**
 * 把 xapp 脚本源码预编译为可复用的 [PyCode]。返回的 PyCode 不绑定特定 interpreter 的 globals,
 * 可在任意新建的 [PythonInterpreter] 上 `exec`。编译走 [Py.compile_flags] 静态入口,无需常驻 interpreter。
 */
internal fun compileXappScript(source: String, filename: String): PyCode =
    Py.compile_flags(source, filename, CompileMode.exec, CompilerFlags())

/**
 * 取插件用户脚本的预编译 [PyCode],按 path + 文件 mtime 缓存。mtime 未变则复用已编译 PyCode,
 * 避免每条代理消息 `Files.readString` + `exec(源码)` 的磁盘读 + parse/compile;mtime 变化(脚本被编辑)
 * 时自动重编译。编译失败(语法错误等)抛出,交由调用处既有 try/catch(handlePluginFailure)处理,与原逻辑一致。
 */
private fun XappManager.compiledPluginScript(plugin: XappPlugin): PyCode {
    val path = plugin.scriptPath
    val mtimeMillis = try {
        Files.getLastModifiedTime(path).toMillis()
    } catch (_: java.io.IOException) {
        0L
    }
    val cached = compiledPluginScripts[path]
    if (cached != null && cached.mtimeMillis == mtimeMillis) {
        return cached.pyCode
    }
    val source = Files.readString(path, Charsets.UTF_8)
    val pyCode = compileXappScript(source, "${plugin.manifest.id}/${plugin.manifest.entryFile}")
    compiledPluginScripts[path] = CompiledPluginScript(mtimeMillis, pyCode)
    return pyCode
}

/**
 * 探测插件脚本声明的生命周期 handler 名,连同 mtime 缓存到 [XappManager.declaredHandlersByPath]。
 * 仅在已 exec 脚本的 interpreter 上调用(复用 refreshContextMenuDefinitions 的 exec,零额外开销)。
 */
private fun XappManager.probeDeclaredHandlers(plugin: XappPlugin, interpreter: PythonInterpreter) {
    val path = plugin.scriptPath
    val mtimeMillis = try {
        Files.getLastModifiedTime(path).toMillis()
    } catch (_: java.io.IOException) {
        0L
    }
    val names = HashSet<String>()
    for (handler in XAPP_LIFECYCLE_HANDLERS) {
        if (interpreter.get(handler) != null) {
            names.add(handler)
        }
    }
    declaredHandlersByPath[path] = DeclaredHandlers(mtimeMillis, names)
}

/**
 * 插件是否声明了指定 handler。缓存命中且 mtime 匹配 -> 严格按声明判断;缓存未命中或 mtime 变化
 * (脚本被编辑、或测试路径未走 refresh) -> 保守返回 true,等价原 fallback 行为(由 runPlugin* exec 后检查)。
 */
private fun XappManager.declaresHandler(plugin: XappPlugin, handlerName: String): Boolean {
    val path = plugin.scriptPath
    val mtimeMillis = try {
        Files.getLastModifiedTime(path).toMillis()
    } catch (_: java.io.IOException) {
        0L
    }
    val cached = declaredHandlersByPath[path]
    if (cached == null || cached.mtimeMillis != mtimeMillis) {
        return true
    }
    return handlerName in cached.names
}

internal fun XappManager.updateEnabled(pluginId: String, enabled: Boolean) {
    updateState(pluginId) { current ->
        current.copy(enabled = enabled)
    }
}

internal fun XappManager.refreshContextMenuDefinitions(plugin: XappPlugin) {
    val generation = (contextMenuGenerations[plugin.manifest.id] ?: 0L) + 1L
    contextMenuGenerations[plugin.manifest.id] = generation
    if (!plugin.enabled || plugin.loadError != null || !Files.exists(plugin.scriptPath)) {
        XappContextMenuHub.removePluginDefinitions(plugin.manifest.id)
        return
    }
    val interpreter = PythonInterpreter()
    val warnings = mutableListOf<String>()
    val api = XappContextMenuRegistrationApi(
        pluginId = plugin.manifest.id,
        pluginName = plugin.manifest.name,
        generation = generation,
        warnings = warnings
    )
    try {
        interpreter.setOut(PrintWriter(XappLogWriter(plugin.manifest.id) { pluginId, line -> notifyLog(pluginId, line) }, true))
        interpreter.setErr(PrintWriter(XappLogWriter(plugin.manifest.id) { pluginId, line -> notifyLog(pluginId, line) }, true))
        interpreter.set("__xproxy_context_menu_api", api)
        interpreter.exec(XappManager.XAPP_RUNTIME_COMPILED)
        interpreter.exec(
            """
            class _XproxyContextMenuApi(object):
                def add_menu_item(self, label=None, contexts=None, tools=None, requires_editable=False, handler=None):
                    return __xproxy_context_menu_api.add_menu_item(label, contexts, tools, requires_editable, handler)
            api = _XproxyContextMenuApi()
            """.trimIndent()
        )
        interpreter.exec(compiledPluginScript(plugin))
        // exec 脚本后顺便探测声明的生命周期 handler,缓存到 declaredHandlersByPath(带 mtime)。
        // 生产路径 loadPlugins->refreshAllContextMenuDefinitions 已对每个 enabled 插件 exec 一次,
        // 此处零额外开销;rewrite/scan 入口据此短路,避免每请求新建 interpreter。
        probeDeclaredHandlers(plugin, interpreter)
        if (interpreter.get("register_context_menu") != null) {
            interpreter.exec("register_context_menu(api)")
        }
        warnings.forEach { warning -> notifyLog(plugin.manifest.id, "[context-menu-warning] $warning") }
        XappContextMenuHub.replacePluginDefinitions(plugin.manifest.id, api.definitions)
    } catch (ex: Exception) {
        XappContextMenuHub.removePluginDefinitions(plugin.manifest.id)
        handlePluginFailure(plugin.manifest.id, ex)
    } finally {
        runCatching { interpreter.close() }
    }
}

internal fun XappManager.refreshAllContextMenuDefinitions() {
    plugins.forEach { refreshContextMenuDefinitions(it) }
}

internal fun XappManager.executeContextMenuAction(definition: XappMenuItemDefinition, snapshot: XappHttpMenuSnapshot) {
    try {
        contextMenuExecutor.execute {
            runContextMenuAction(definition, snapshot)
        }
    } catch (ex: RejectedExecutionException) {
        notifyLog(definition.pluginId, "[context-menu-warning] action queue full: ${definition.pluginId}/${definition.handlerName}")
    }
}

internal fun XappManager.scan(entry: ProxyHistoryEntry) {
    if (entry.tool.equals("xapp", ignoreCase = true)) {
        return
    }
    val activePlugins = plugins.filter { plugin ->
        plugin.enabled && plugin.loadError == null && Files.exists(plugin.scriptPath) &&
            (declaresHandler(plugin, "on_proxy_http_message") || declaresHandler(plugin, "on_http_message"))
    }
    if (activePlugins.isEmpty()) {
        return
    }
    val emitHttpMessage = shouldEmitDedupedHttpMessage(entry)
    scanExecutor.execute {
        activePlugins.forEach { plugin ->
            runPluginPassiveScan(plugin, entry, emitHttpMessage)
        }
    }
}

internal fun XappManager.rewriteBeforeRequest(requestRaw: String, host: String, tls: Boolean): String {
    val activePlugins = plugins.filter { plugin ->
        plugin.enabled && plugin.loadError == null && Files.exists(plugin.scriptPath) &&
            declaresHandler(plugin, "on_before_request")
    }
    if (activePlugins.isEmpty()) {
        return requestRaw
    }
    var currentRequest = requestRaw
    activePlugins.forEach { plugin ->
        val rewritten = runPluginRewrite(
            plugin = plugin,
            requestRaw = currentRequest,
            responseRaw = "",
            host = host,
            tls = tls,
            dispatcher = "__xproxy_dispatch_before_request(ctx)"
        )
        if (rewritten != null && rewritten.first.isNotBlank()) {
            currentRequest = rewritten.first
        }
    }
    return currentRequest
}

internal fun XappManager.rewriteAfterRequest(requestRaw: String, responseRaw: String, host: String, tls: Boolean): String {
    val activePlugins = plugins.filter { plugin ->
        plugin.enabled && plugin.loadError == null && Files.exists(plugin.scriptPath) &&
            declaresHandler(plugin, "on_after_request")
    }
    if (activePlugins.isEmpty()) {
        return responseRaw
    }
    var currentResponse = responseRaw
    activePlugins.forEach { plugin ->
        val rewritten = runPluginRewrite(
            plugin = plugin,
            requestRaw = requestRaw,
            responseRaw = currentResponse,
            host = host,
            tls = tls,
            dispatcher = "__xproxy_dispatch_after_request(ctx)"
        )
        if (rewritten != null && rewritten.second.isNotBlank()) {
            currentResponse = rewritten.second
        }
    }
    return currentResponse
}

private fun highlightHistoryEntry(id: Long, color: String) {
    TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, id, TrafficHighlight.parse(color))
}

private fun XappManager.runPluginPassiveScan(plugin: XappPlugin, entry: ProxyHistoryEntry, emitHttpMessage: Boolean) {
    val interpreter = PythonInterpreter()
    val logger = { line: String -> notifyLog(plugin.manifest.id, line) }
    val context = XappProxyMessageContext(
        plugin = plugin,
        sourceEntry = entry,
        logSink = logger,
        sendAndRecord = { request -> sendAndRecord(plugin, request, logger) },
        issuePublisher = { issue -> publishIssueWithDedupe(issue) },
        highlightPublisher = { id, color -> highlightHistoryEntry(id, color) }
    )
    try {
        interpreter.setOut(PrintWriter(XappLogWriter(plugin.manifest.id) { pluginId, line -> notifyLog(pluginId, line) }, true))
        interpreter.setErr(PrintWriter(XappLogWriter(plugin.manifest.id) { pluginId, line -> notifyLog(pluginId, line) }, true))
        interpreter.set("ctx", context)
        interpreter.set("api", context)
        interpreter.exec(XappManager.XAPP_RUNTIME_COMPILED)
        interpreter.exec(compiledPluginScript(plugin))
        interpreter.exec("__xproxy_dispatch_proxy_message(ctx)")
        if (emitHttpMessage) {
            interpreter.exec("__xproxy_dispatch_http_message(ctx)")
        }
        pluginFailureCount.remove(plugin.manifest.id)
    } catch (ex: Exception) {
        handlePluginFailure(plugin.manifest.id, ex)
    } finally {
        runCatching { interpreter.close() }
    }
}

private fun XappManager.runPluginRewrite(
    plugin: XappPlugin,
    requestRaw: String,
    responseRaw: String,
    host: String,
    tls: Boolean,
    dispatcher: String
): Pair<String, String>? {
    val interpreter = PythonInterpreter()
    val logger = { line: String -> notifyLog(plugin.manifest.id, line) }
    val context = XappProxyMessageContext(
        plugin = plugin,
        requestRaw = requestRaw,
        responseRaw = responseRaw,
        fallbackHost = host,
        fallbackTls = tls,
        logSink = logger,
        sendAndRecord = { request -> sendAndRecord(plugin, request, logger) },
        issuePublisher = { issue -> publishIssueWithDedupe(issue) },
        highlightPublisher = { id, color -> highlightHistoryEntry(id, color) }
    )
    val requestBefore = snapshotRequest(context.request)
    val responseBefore = snapshotResponse(context.response)
    return try {
        interpreter.setOut(PrintWriter(XappLogWriter(plugin.manifest.id) { pluginId, line -> notifyLog(pluginId, line) }, true))
        interpreter.setErr(PrintWriter(XappLogWriter(plugin.manifest.id) { pluginId, line -> notifyLog(pluginId, line) }, true))
        interpreter.set("ctx", context)
        interpreter.set("api", context)
        interpreter.exec(XappManager.XAPP_RUNTIME_COMPILED)
        interpreter.exec(compiledPluginScript(plugin))
        val handlerName = dispatcherToHandlerName(dispatcher)
        if (handlerName != null && interpreter.get(handlerName) == null) {
            pluginFailureCount.remove(plugin.manifest.id)
            return null
        }
        interpreter.exec(dispatcher)
        val requestChanged = requestBefore != snapshotRequest(context.request)
        val responseChanged = responseBefore != snapshotResponse(context.response)
        if (!requestChanged && !responseChanged) {
            pluginFailureCount.remove(plugin.manifest.id)
            return null
        }
        pluginFailureCount.remove(plugin.manifest.id)
        Pair(context.request.toRaw(), context.response.toRaw())
    } catch (ex: Exception) {
        handlePluginFailure(plugin.manifest.id, ex)
        null
    } finally {
        runCatching { interpreter.close() }
    }
}

private fun XappManager.runContextMenuAction(definition: XappMenuItemDefinition, snapshot: XappHttpMenuSnapshot) {
    val plugin = plugins.firstOrNull { it.manifest.id == definition.pluginId }
    if (plugin == null || !plugin.enabled || plugin.loadError != null || !Files.exists(plugin.scriptPath)) {
        notifyLog(definition.pluginId, "[context-menu-warning] skipped stale context-menu mutation: plugin disabled")
        return
    }
    val interpreter = PythonInterpreter()
    val context = XappHttpMenuContext(
        definition = definition,
        snapshot = snapshot.copy(logger = { line -> notifyLog(definition.pluginId, line) }),
        generationChecker = { pluginId, generation -> contextMenuMutationGate(pluginId, generation) }
    )
    try {
        interpreter.setOut(PrintWriter(XappLogWriter(plugin.manifest.id) { pluginId, line -> notifyLog(pluginId, line) }, true))
        interpreter.setErr(PrintWriter(XappLogWriter(plugin.manifest.id) { pluginId, line -> notifyLog(pluginId, line) }, true))
        interpreter.set("ctx", context)
        interpreter.set("api", context)
        interpreter.exec(XappManager.XAPP_RUNTIME_COMPILED)
        interpreter.exec(compiledPluginScript(plugin))
        val handler = interpreter.get(definition.handlerName)
        if (handler == null) {
            notifyLog(definition.pluginId, "[context-menu-error] handler not found: ${definition.handlerName}")
            return
        }
        interpreter.exec("${definition.handlerName}(ctx)")
        pluginFailureCount.remove(plugin.manifest.id)
    } catch (ex: Exception) {
        handlePluginFailure(plugin.manifest.id, ex)
    } finally {
        runCatching { interpreter.close() }
    }
}

private fun XappManager.contextMenuMutationGate(pluginId: String, generation: Long): XappContextMutationGate {
    val plugin = plugins.firstOrNull { it.manifest.id == pluginId }
    if (plugin == null || !plugin.enabled || plugin.loadError != null) {
        return XappContextMutationGate.BLOCK_PLUGIN_DISABLED
    }
    if ((contextMenuGenerations[pluginId] ?: -1L) != generation) {
        return XappContextMutationGate.BLOCK_GENERATION_MISMATCH
    }
    return XappContextMutationGate.ALLOW
}

private data class RequestSnapshot(
    val method: String,
    val path: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val version: String,
    val headers: List<Pair<String, String>>,
    val body: String
)

private data class ResponseSnapshot(
    val status: Int,
    val mimeType: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val raw: String,
    val title: String
)

private fun snapshotRequest(request: XappHttpRequest): RequestSnapshot {
    return RequestSnapshot(
        method = request.method,
        path = request.path,
        host = request.host,
        port = request.port,
        tls = request.tls,
        version = request.version,
        headers = request.headers.entries.map { it.key to it.value },
        body = request.body
    )
}

private fun snapshotResponse(response: XappHttpResponse): ResponseSnapshot {
    return ResponseSnapshot(
        status = response.status,
        mimeType = response.mime_type,
        headers = response.headers.entries.map { it.key to it.value },
        body = response.body,
        raw = response.raw,
        title = response.title
    )
}

private fun dispatcherToHandlerName(dispatcher: String): String? {
    return when (dispatcher) {
        "__xproxy_dispatch_before_request(ctx)" -> "on_before_request"
        "__xproxy_dispatch_after_request(ctx)" -> "on_after_request"
        "__xproxy_dispatch_proxy_message(ctx)" -> "on_proxy_http_message"
        "__xproxy_dispatch_http_message(ctx)" -> "on_http_message"
        else -> null
    }
}

private fun XappManager.handlePluginFailure(pluginId: String, ex: Exception) {
    val failures = (pluginFailureCount[pluginId] ?: 0) + 1
    pluginFailureCount[pluginId] = failures
    val errType = ex::class.java.simpleName
    val errMessage = ex.message ?: "<no-message>"
    Utils.err("xapp '$pluginId' execution failed [$errType]: $errMessage")
    notifyLog(pluginId, "[error][$errType] $errMessage")
    ex.stackTrace.take(16).forEach { frame ->
        notifyLog(pluginId, "[trace] $frame")
    }
    if (failures >= 5) {
        notifyLog(pluginId, "[circuit-breaker] disabled after $failures failures")
        updateEnabled(pluginId, false)
    }
}

private fun XappManager.sendAndRecord(plugin: XappPlugin, request: XappHttpRequest, logSink: (String) -> Unit): XappHttpResponse {
    val service = HttpService(
        host = request.host,
        port = request.port,
        protocol = if (request.tls) "https" else "http"
    )
    val requestRaw = request.toRaw()
    val responseRaw = sendSingleRequest(service, requestRaw)
    val response = XappHttpResponse.fromRaw(responseRaw)
    val historyEntry = ProxyHistoryEntry(
        id = 0L,
        timeMillis = System.currentTimeMillis(),
        method = request.method,
        host = request.hostWithPort(),
        path = request.path,
        statusCode = response.status,
        length = response.body.length,
        mimeType = response.mime_type,
        title = response.title,
        tls = request.tls,
        modified = false,
        tool = "xapp",
        requestRaw = requestRaw,
        responseRaw = responseRaw,
        originalRequestRaw = "",
        originalResponseRaw = "",
        protocol = inferHistoryProtocolFromRequestRaw(requestRaw)
    )
    onXappHistoryAdded?.invoke(historyEntry)
    logSink("[send] ${plugin.manifest.id}: ${request.method} ${request.path} -> ${response.status}")
    return response
}

internal fun inferHistoryProtocolFromRequestRaw(requestRaw: String): String {
    val normalized = requestRaw.trimStart()
    val pseudoHeaderStarts =
        normalized.startsWith(":method", ignoreCase = true) ||
            normalized.startsWith(":path", ignoreCase = true) ||
            normalized.startsWith(":authority", ignoreCase = true) ||
            normalized.startsWith(":scheme", ignoreCase = true)
    return if (pseudoHeaderStarts || Utils.isHttp2(requestRaw.toByteArray(Charsets.ISO_8859_1))) {
        "http/2"
    } else {
        "http/1.1"
    }
}

private fun XappManager.publishIssueWithDedupe(issue: ReportedIssue) {
    val now = System.currentTimeMillis()
    val fingerprint = buildIssueFingerprint(issue)
    val seenAt = recentIssueFingerprintMillis[fingerprint]
    if (seenAt != null && now - seenAt <= 60_000L) {
        return
    }
    recentIssueFingerprintMillis[fingerprint] = now
    if (recentIssueFingerprintMillis.size > 4096) {
        val threshold = now - 10 * 60_000L
        recentIssueFingerprintMillis.entries.removeIf { it.value < threshold }
    }
    ScriptIssueHub.publish(issue)
}

private fun buildIssueFingerprint(issue: ReportedIssue): String {
    val detailHash = issue.detail.trim().lowercase(Locale.getDefault()).take(160)
    return listOf(
        issue.source,
        issue.name.lowercase(Locale.getDefault()),
        issue.severity.lowercase(Locale.getDefault()),
        issue.host.lowercase(Locale.getDefault()),
        issue.path,
        issue.method.uppercase(Locale.getDefault()),
        detailHash
    ).joinToString("|")
}

private fun XappManager.shouldEmitDedupedHttpMessage(entry: ProxyHistoryEntry): Boolean {
    val protocol = if (entry.tls) "https" else "http"
    val hostPort = parseHostPort(entry.host, entry.tls)
    val method = entry.method.trim().uppercase(Locale.getDefault())
    val key = listOf(
        method,
        protocol,
        hostPort.first,
        hostPort.second.toString(),
        normalizePathForDedupe(entry.path)
    ).joinToString("|")
    val now = System.currentTimeMillis()
    val emitted = dedupedHttpKeys.putIfAbsent(key, now) == null
    // 有界化:超阈值时清理 10 分钟前的旧条目,避免长会话无界增长(与 recentIssueFingerprintMillis 策略一致)。
    if (emitted && dedupedHttpKeys.size > 4096) {
        val threshold = now - 10 * 60_000L
        dedupedHttpKeys.entries.removeIf { it.value < threshold }
    }
    return emitted
}

private fun parseHostPort(hostToken: String, tls: Boolean): Pair<String, Int> {
    val token = hostToken.trim()
    val defaultPort = if (tls) 443 else 80
    if (token.isBlank()) {
        return Pair("", defaultPort)
    }
    if (token.startsWith("[") && token.contains("]")) {
        val host = token.substringAfter("[").substringBefore("]").lowercase(Locale.getDefault())
        val port = token.substringAfter("]", "").removePrefix(":").toIntOrNull() ?: defaultPort
        return Pair(host, port)
    }
    val colonCount = token.count { it == ':' }
    return if (colonCount == 1) {
        val host = token.substringBefore(':').trim().lowercase(Locale.getDefault())
        val port = token.substringAfter(':', "").toIntOrNull() ?: defaultPort
        Pair(host, port)
    } else {
        Pair(token.lowercase(Locale.getDefault()), defaultPort)
    }
}

private fun normalizePathForDedupe(path: String): String {
    if (path.isBlank()) {
        return "/"
    }
    return runCatching {
        val token = path.trim()
        if (token.startsWith("http://") || token.startsWith("https://")) {
            val uri = URI(token)
            val normalizedPath = uri.path.ifBlank { "/" }
            val query = uri.rawQuery?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
            normalizedPath + query
        } else {
            if (token.startsWith('/')) token else "/$token"
        }
    }.getOrDefault(
        if (path.startsWith('/')) path else "/$path"
    )
}
