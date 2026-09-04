package org.jjgroup.xproxy.kits.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.jjgroup.xproxy.codec.core.CodecOps
import org.jjgroup.xproxy.engine.http.uncompressIfNecessary
import org.jjgroup.xproxy.fuzzer.request.parseHeaders
import org.jjgroup.xproxy.fuzzer.request.splitMessage
import org.jjgroup.xproxy.kits.model.XappPlugin
import org.jjgroup.xproxy.kits.model.XappPluginState
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectPaths
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.issue.model.ReportedIssue
import io.netty.handler.codec.http.HttpResponseStatus
import org.python.core.PyCode
import java.io.Writer
import java.net.URI
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class XappManager(
    internal val projectDataStore: ProjectDataStore?,
    internal val onXappHistoryAdded: ((ProxyHistoryEntry) -> Unit)? = null
) {
    init {
        XappContextMenuInvoker.setHandler { definition, snapshot ->
            executeContextMenuAction(definition, snapshot)
        }
    }
    internal val listeners = CopyOnWriteArrayList<(List<XappPlugin>) -> Unit>()
    internal val logListeners = CopyOnWriteArrayList<(String, String) -> Unit>()
    internal val scanExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xproxy-kits-passive-scan").apply { isDaemon = true }
    }
    internal val contextMenuExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(32),
        { runnable -> Thread(runnable, "xproxy-xapp-context-menu").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    internal val objectMapper = ObjectMapper()
    internal val xappRoot: Path = ProjectPaths.globalRoot.resolve("xapp")
    internal val pluginFailureCount = ConcurrentHashMap<String, Int>()
    internal val recentIssueFingerprintMillis = ConcurrentHashMap<String, Long>()
    internal val dedupedHttpKeys = ConcurrentHashMap<String, Long>()
    internal val contextMenuGenerations = ConcurrentHashMap<String, Long>()
    // 用户脚本按 path+mtime 缓存预编译 PyCode,避免每条代理消息都从磁盘读源码 + 重新 parse/compile。
    // mtime 变化(用户编辑脚本)时自动重编译,语义与原"每调用 Files.readString + exec(源码)"等价。
    internal val compiledPluginScripts = ConcurrentHashMap<Path, CompiledPluginScript>()
    // 插件声明的生命周期 handler 名缓存(path + mtime)。refreshContextMenuDefinitions exec 脚本时顺便探测填入,
    // rewrite/scan 入口据此短路:没声明对应 handler 的插件直接跳过,避免在 Netty 事件循环上为每个请求
    // 新建 interpreter + exec runtime + exec 脚本(尤其被动扫描插件不声明 rewrite handler 时零开销)。
    // mtime 变化(脚本被编辑)时视为未命中,declaresHandler 回退保守 true,等价原 fallback 行为。
    internal val declaredHandlersByPath = ConcurrentHashMap<Path, DeclaredHandlers>()
    @Volatile
    internal var plugins: List<XappPlugin> = emptyList()

    fun xappDirectory(): Path = xappRoot

    fun addListener(listener: (List<XappPlugin>) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(plugins)
        return { listeners.remove(listener) }
    }

    fun addLogListener(listener: (String, String) -> Unit): () -> Unit {
        logListeners.add(listener)
        return { logListeners.remove(listener) }
    }

    fun shutdown() {
        scanExecutor.shutdownNow()
        contextMenuExecutor.shutdownNow()
    }

    internal fun updateState(pluginId: String, mapper: (XappPlugin) -> XappPlugin) {
        val next = plugins.map { plugin ->
            if (plugin.manifest.id == pluginId) mapper(plugin) else plugin
        }
        plugins = next
        notifyListeners(next)
        next.firstOrNull { it.manifest.id == pluginId }?.let { plugin ->
            projectDataStore?.upsertXappPluginState(XappPluginState(pluginId = plugin.manifest.id, enabled = plugin.enabled))
            refreshContextMenuDefinitions(plugin)
        }
    }

    internal fun notifyListeners(snapshot: List<XappPlugin>) {
        listeners.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }

    internal fun notifyLog(pluginId: String, line: String) {
        val normalized = line.trimEnd('\n', '\r')
        if (normalized.isBlank()) {
            return
        }
        logListeners.forEach { listener ->
            runCatching { listener(pluginId, normalized) }
        }
    }

    companion object {
        internal val XAPP_RUNTIME_SCRIPT: String by lazy {
            val stream = XappManager::class.java.classLoader.getResourceAsStream("XappRuntime.py")
            if (stream == null) {
                ""
            } else {
                stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }
        // runtime 脚本预编译为 PyCode 一次(lazy);每次派发在新 interpreter 里 exec(PyCode) 而非 exec(源码),
        // 消除每条代理消息对 runtime 源码的 parse/compile 开销。语义等价:PyCode 每次仍重跑模块级定义,
        // 保留 per-call 隔离(与原 exec(源码) 完全一致)。首次访问发生在首个 PythonInterpreter 构造之后,
        // 此时 PySystemState 已初始化,Py.compile_flags 可安全调用。
        internal val XAPP_RUNTIME_COMPILED: PyCode by lazy {
            compileXappScript(XAPP_RUNTIME_SCRIPT, "<xproxy-xapp-runtime>")
        }
    }
}

internal class XappLogWriter(
    private val pluginId: String,
    private val sink: (String, String) -> Unit
) : Writer() {
    private val buffer = StringBuilder()

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        if (len <= 0) return
        buffer.append(cbuf, off, len)
        flushCompleteLines()
    }

    override fun flush() {
        flushCompleteLines()
        if (buffer.isNotEmpty()) {
            sink(pluginId, buffer.toString())
            buffer.clear()
        }
    }

    override fun close() {
        flush()
    }

    private fun flushCompleteLines() {
        while (true) {
            val newlineIndex = buffer.indexOf("\n")
            if (newlineIndex < 0) return
            val line = buffer.substring(0, newlineIndex)
            sink(pluginId, line)
            buffer.delete(0, newlineIndex + 1)
        }
    }
}

class XappProxyMessageContext(
    private val plugin: XappPlugin,
    requestData: XappHttpRequest,
    responseData: XappHttpResponse,
    private val logSink: (String) -> Unit,
    private val sendAndRecord: (XappHttpRequest) -> XappHttpResponse,
    private val issuePublisher: (ReportedIssue) -> Unit,
    private val historyId: Long = 0L,
    private val highlightPublisher: (Long, String) -> Unit = { _, _ -> }
) {
    val codec: XappCodecHelper = XappCodecHelper()

    constructor(
        plugin: XappPlugin,
        sourceEntry: ProxyHistoryEntry,
        logSink: (String) -> Unit,
        sendAndRecord: (XappHttpRequest) -> XappHttpResponse,
        issuePublisher: (ReportedIssue) -> Unit,
        highlightPublisher: (Long, String) -> Unit = { _, _ -> }
    ) : this(
        plugin = plugin,
        requestData = XappHttpRequest.fromRaw(sourceEntry),
        responseData = XappHttpResponse.fromRaw(sourceEntry.responseRaw),
        logSink = logSink,
        sendAndRecord = sendAndRecord,
        issuePublisher = issuePublisher,
        historyId = sourceEntry.id,
        highlightPublisher = highlightPublisher
    )

    constructor(
        plugin: XappPlugin,
        requestRaw: String,
        responseRaw: String,
        fallbackHost: String,
        fallbackTls: Boolean,
        logSink: (String) -> Unit,
        sendAndRecord: (XappHttpRequest) -> XappHttpResponse,
        issuePublisher: (ReportedIssue) -> Unit,
        highlightPublisher: (Long, String) -> Unit = { _, _ -> }
    ) : this(
        plugin = plugin,
        requestData = XappHttpRequest.fromRaw(requestRaw, fallbackHost, fallbackTls),
        responseData = XappHttpResponse.fromRaw(responseRaw),
        logSink = logSink,
        sendAndRecord = sendAndRecord,
        issuePublisher = issuePublisher,
        historyId = 0L,
        highlightPublisher = highlightPublisher
    )

    var request: XappHttpRequest = requestData
    var response: XappHttpResponse = responseData
    val plugin_id: String get() = plugin.manifest.id
    val plugin_name: String get() = plugin.manifest.name
    val method: String get() = request.method
    val host: String get() = request.hostWithPort()
    val path: String get() = request.path
    val status_code: Int get() = response.status
    val mime_type: String get() = response.mime_type
    val request_raw: String get() = request.toRaw()
    val response_raw: String get() = response.raw
    val url: String get() = request.absoluteUrl()
    /** 当前流量条目的代理历史 id(被动扫描入口已就绪);rewrite 入口为 0(尚无落库 id)。 */
    val history_id: Long get() = historyId

    /**
     * 给当前(或指定)流量条目标记高亮颜色,便于关联定位。颜色名见 [org.jjgroup.xproxy.ui.marking.TrafficHighlight]
     * (red/orange/yellow/green/cyan/blue/pink/gray);"none"/"clear"/空 等价于清除。
     *
     * 仅作用于 HTTP 历史条目(代理历史表 + Target 内容表共享同一 id);history_id<=0 时忽略并记 warning。
     * 线程安全:可在 scan 线程调用,UI 经注册表监听在 EDT repaint。
     */
    @JvmOverloads
    fun highlight(color: String = "none", history_id: Long? = null) {
        val targetId = history_id ?: this.historyId
        if (targetId <= 0L) {
            logSink("[highlight-warning] no history id; highlight ignored (only valid in on_proxy_http_message)")
            return
        }
        highlightPublisher(targetId, color)
    }

    fun send(req: XappHttpRequest?): XappHttpResponse {
        val requestToSend = (req ?: request).copy()
        return sendAndRecord(requestToSend)
    }

    fun request_contains(token: String): Boolean {
        return request_raw.contains(token, ignoreCase = true)
    }

    fun response_contains(token: String): Boolean {
        return response_raw.contains(token, ignoreCase = true)
    }

    fun response_regex(pattern: String): Boolean {
        return runCatching {
            Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(response_raw)
        }.getOrDefault(false)
    }

    fun report_issuse(req: XappHttpRequest?, resp: XappHttpResponse?, name: String, detail: String) {
        report_issuse(req, resp, name, detail, "Information", "Firm", "", "")
    }

    fun report_issuse(
        req: XappHttpRequest?,
        resp: XappHttpResponse?,
        name: String,
        detail: String,
        severity: String,
        confidence: String,
        remediation: String,
        tagsCsv: String
    ) {
        val requestData = req ?: request
        val responseData = resp ?: response
        val issueName = name.trim()
        if (issueName.isBlank()) {
            return
        }
        issuePublisher(
            ReportedIssue(
                issueId = UUID.randomUUID().toString(),
                source = "xapp:${plugin.manifest.id}",
                name = issueName,
                severity = severity.ifBlank { "Information" },
                confidence = confidence.ifBlank { "Firm" },
                detail = detail,
                remediation = remediation,
                url = requestData.absoluteUrl(),
                host = requestData.hostWithPort(),
                path = requestData.path,
                method = requestData.method,
                requestRaw = requestData.toRaw(),
                responseRaw = responseData.raw,
                tagsCsv = tagsCsv,
                createdAtMillis = System.currentTimeMillis()
            )
        )
    }

    // 推荐用法:拼写正确的 report_issue(转发到 report_issuse)。旧名 report_issuse 保留以兼容已有 xapp 脚本。
    fun report_issue(req: XappHttpRequest?, resp: XappHttpResponse?, name: String, detail: String) {
        report_issuse(req, resp, name, detail)
    }

    fun report_issue(
        req: XappHttpRequest?,
        resp: XappHttpResponse?,
        name: String,
        detail: String,
        severity: String,
        confidence: String,
        remediation: String,
        tagsCsv: String
    ) {
        report_issuse(req, resp, name, detail, severity, confidence, remediation, tagsCsv)
    }

    fun log(message: String) {
        logSink(message)
    }
}

class XappCodecHelper {
    fun to_base64(input: String): String = CodecOps.toBase64(input, false)

    fun from_base64(input: String): String = CodecOps.fromBase64(input, false)

    fun to_base64_url(input: String): String = CodecOps.toBase64(input, true)

    fun from_base64_url(input: String): String = CodecOps.fromBase64(input, true)

    fun url_encode(input: String): String = CodecOps.urlEncode(input, false)

    fun url_encode_all(input: String): String = CodecOps.urlEncode(input, true)

    fun url_decode(input: String): String = CodecOps.urlDecode(input)

    fun to_hex(input: String, delimiter: String = "None"): String = CodecOps.toHex(input, delimiter)

    fun from_hex(input: String, delimiter: String = "None"): String = CodecOps.fromHex(input, delimiter)

    fun html_encode(input: String): String = CodecOps.htmlEncode(input)

    fun html_decode(input: String): String = CodecOps.htmlDecode(input)

    fun jwt_decode_payload(input: String): String = CodecOps.jwtDecodePayload(input)

    fun md5(input: String): String = CodecOps.md5(input)

    fun sha1(input: String): String = CodecOps.sha1(input)

    fun sha256(input: String): String = CodecOps.sha256(input)

    fun sha512(input: String): String = CodecOps.sha512(input)

    fun hmac(input: String, key: String, algorithm: String = "SHA-256", output: String = "hex"): String {
        return CodecOps.hmac(input, key, algorithm, output)
    }

    fun aes_encrypt(
        input: String,
        key: String,
        mode: String = "CBC",
        iv: String = "0000000000000000",
        output: String = "base64"
    ): String {
        return CodecOps.aesEncrypt(input, key, mode, iv, output)
    }

    fun aes_decrypt(
        input: String,
        key: String,
        mode: String = "CBC",
        iv: String = "0000000000000000",
        input_format: String = "base64"
    ): String {
        return CodecOps.aesDecrypt(input, key, mode, iv, input_format)
    }

    fun rot13(input: String): String = CodecOps.rot13(input)

    fun reverse(input: String): String = CodecOps.reverse(input)

    fun uppercase(input: String): String = CodecOps.uppercase(input)

    fun lowercase(input: String): String = CodecOps.lowercase(input)

    fun strip(input: String): String = CodecOps.strip(input)
}

data class XappHttpRequest(
    var method: String,
    var path: String,
    var host: String,
    var port: Int,
    var tls: Boolean,
    var version: String,
    var headers: LinkedHashMap<String, String>,
    var body: String
) {
    fun hostWithPort(): String {
        val defaultPort = if (tls) 443 else 80
        return if (port == defaultPort) host else "$host:$port"
    }

    fun absoluteUrl(): String {
        val scheme = if (tls) "https" else "http"
        return "$scheme://${hostWithPort()}$path"
    }

    fun toRaw(): String {
        val normalizedMethod = method.ifBlank { "GET" }.uppercase(Locale.getDefault())
        val normalizedVersion = if (version.startsWith("HTTP/")) version else "HTTP/1.1"
        val normalizedPath = normalizePathForWire(path)
        val outputHeaders = LinkedHashMap<String, String>()
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && !name.startsWith(":")) {
                outputHeaders[name] = value
            }
        }
        if (outputHeaders.keys.none { it.equals("Host", ignoreCase = true) }) {
            outputHeaders["Host"] = hostWithPort()
        }
        val builder = StringBuilder()
        builder.append(normalizedMethod).append(' ').append(normalizedPath).append(' ').append(normalizedVersion).append("\r\n")
        outputHeaders.forEach { (name, value) ->
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        builder.append("\r\n")
        builder.append(body)
        return builder.toString()
    }

    companion object {
        fun fromRaw(entry: ProxyHistoryEntry): XappHttpRequest {
            return fromRaw(entry.requestRaw, entry.host, entry.tls)
        }

        fun fromRaw(raw: String, fallbackHost: String = "", fallbackTls: Boolean = false): XappHttpRequest {
            val parsed = splitMessage(raw)
            val headerLines = parsed.headers.split(Regex("\\r?\\n"))
            val requestLine = headerLines.firstOrNull().orEmpty().trim()
            val requestTokens = requestLine.split(' ')
            val pseudoHeaders = LinkedHashMap<String, String>()
            val headers = LinkedHashMap<String, String>()
            headerLines.drop(1).forEach { line ->
                val idx = if (line.startsWith(":")) line.indexOf(':', 1) else line.indexOf(':')
                if (idx > 0) {
                    val name = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (name.isNotBlank()) {
                        if (name.startsWith(":")) {
                            pseudoHeaders[name.lowercase(Locale.getDefault())] = value
                        } else {
                            headers[name] = value
                        }
                    }
                }
            }
            val method = requestTokens.getOrNull(0)
                .orEmpty()
                .ifBlank { pseudoHeaders[":method"].orEmpty() }
                .ifBlank { "GET" }
            val path = requestTokens.getOrNull(1)
                .orEmpty()
                .ifBlank { pseudoHeaders[":path"].orEmpty() }
                .ifBlank { "/" }
            val version = requestTokens.getOrNull(2)
                .orEmpty()
                .ifBlank {
                    if (pseudoHeaders.isNotEmpty()) "HTTP/2" else "HTTP/1.1"
                }
            val hostHeader = headers.entries.firstOrNull {
                it.key.equals("Host", ignoreCase = true) || it.key.equals(":authority", ignoreCase = true)
            }?.value.orEmpty().ifBlank { pseudoHeaders[":authority"].orEmpty() }
            val hostToken = extractHost(hostHeader).ifBlank {
                extractHost(fallbackHost).ifBlank { "localhost" }
            }
            val portFromHost = extractPort(hostHeader)
            val fallbackPort = extractPort(fallbackHost) ?: if (fallbackTls) 443 else 80
            val port = portFromHost ?: fallbackPort
            return XappHttpRequest(
                method = method,
                path = path,
                host = hostToken,
                port = port,
                tls = fallbackTls,
                version = version,
                headers = headers,
                body = parsed.body
            )
        }

        private fun extractHost(rawHost: String): String {
            val token = rawHost.trim()
            if (token.isBlank()) {
                return ""
            }
            if (token.startsWith("[") && token.contains("]")) {
                return token.substringAfter("[").substringBefore("]")
            }
            val colonCount = token.count { it == ':' }
            return if (colonCount == 1) token.substringBefore(':') else token
        }

        private fun extractPort(rawHost: String): Int? {
            val token = rawHost.trim()
            if (token.isBlank()) {
                return null
            }
            if (token.startsWith("[") && token.contains("]")) {
                return token.substringAfter("]", "").removePrefix(":").toIntOrNull()
            }
            val colonCount = token.count { it == ':' }
            return if (colonCount == 1) token.substringAfter(':', "").toIntOrNull() else null
        }

        private fun normalizePathForWire(rawPath: String): String {
            val path = rawPath.trim()
            if (path.isBlank()) {
                return "/"
            }
            if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) {
                return runCatching {
                    val uri = URI(path)
                    val p = if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath
                    val query = if (uri.rawQuery.isNullOrBlank()) "" else "?${uri.rawQuery}"
                    "$p$query"
                }.getOrDefault(path)
            }
            return if (path.startsWith('/')) path else "/$path"
        }
    }
}

data class XappHttpResponse(
    var status: Int,
    var mime_type: String,
    var headers: LinkedHashMap<String, String>,
    var body: String,
    var raw: String,
    var title: String,
    private var decodedBodyFromContentEncoding: Boolean = false,
    // fromRaw 时缓存的初始 body(已按需解压)。toRaw 据此判断 body 是否被插件改写,避免再 fromRaw(raw) 全量解析。
    private val originalBody: String = ""
) {
    val status_code: Int get() = status

    fun toRaw(): String {
        if (raw.isNotBlank() && status <= 0 && headers.isEmpty() && body.isBlank()) {
            return raw
        }
        val protocol = raw.lineSequence().firstOrNull()?.trim()?.split(' ')?.firstOrNull()
            ?.takeIf { it.startsWith("HTTP/") }
            ?: "HTTP/1.1"
        val safeStatus = if (status in 100..599) status else 200
        val reason = runCatching { HttpResponseStatus.valueOf(safeStatus).reasonPhrase() }.getOrDefault("")
        val outputHeaders = LinkedHashMap<String, String>()
        headers.forEach { (name, value) ->
            if (name.isNotBlank()) {
                outputHeaders[name] = value
            }
        }
        if (raw.isNotBlank() && (decodedBodyFromContentEncoding || body != originalBody)) {
            outputHeaders.entries.removeIf { it.key.equals("Content-Encoding", ignoreCase = true) }
        }
        val bodyBytes = body.toByteArray(Charsets.ISO_8859_1)
        outputHeaders.entries.removeIf { it.key.equals("Content-Length", ignoreCase = true) }
        outputHeaders["Content-Length"] = bodyBytes.size.toString()
        outputHeaders.entries.removeIf { it.key.equals("Transfer-Encoding", ignoreCase = true) }

        val builder = StringBuilder()
        builder.append(protocol)
            .append(' ')
            .append(safeStatus)
            .append(' ')
            .append(reason)
            .append("\r\n")
        outputHeaders.forEach { (name, value) ->
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        builder.append("\r\n")
        builder.append(body)
        raw = builder.toString()
        return raw
    }

    companion object {
        fun fromRaw(raw: String): XappHttpResponse {
            val parsed = splitMessage(raw)
            val status = parsed.headers.lineSequence()
                .firstOrNull()
                ?.split(' ')
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 0
            val headers = LinkedHashMap<String, String>()
            val parsedHeaders = parseHeaders(parsed.headers)
            parsedHeaders.forEach { (name, value) -> headers[name] = value }
            val mime = detectMime(parsedHeaders)
            val bodyDecodedFromContentEncoding = shouldExposeDecodedBody(parsedHeaders, mime)
            val exposedBody = if (bodyDecodedFromContentEncoding) {
                uncompressIfNecessary(parsed.headers, parsed.body)
            } else {
                parsed.body
            }
            val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(exposedBody)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
            return XappHttpResponse(
                status = status,
                mime_type = mime,
                headers = headers,
                body = exposedBody,
                raw = raw,
                title = title,
                decodedBodyFromContentEncoding = bodyDecodedFromContentEncoding,
                originalBody = exposedBody
            )
        }

        private fun shouldExposeDecodedBody(headers: Map<String, String>, mime: String): Boolean {
            if (mime !in setOf("json", "text", "html", "xml", "script")) {
                return false
            }
            return headers.entries.any { (name, value) ->
                name.equals("Content-Encoding", ignoreCase = true) && value.isNotBlank()
            }
        }

        private fun detectMime(headers: Map<String, String>): String {
            val raw = headers["content-type"].orEmpty().substringBefore(';').trim().lowercase(Locale.getDefault())
            return when {
                raw.startsWith("image/") -> "image"
                raw.contains("json") -> "json"
                raw.contains("xml") -> "xml"
                raw.contains("html") -> "html"
                raw.startsWith("text/") -> "text"
                raw.contains("javascript") -> "script"
                raw.isBlank() -> "other"
                else -> "other"
            }
        }
    }
}
