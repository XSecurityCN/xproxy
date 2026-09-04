package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.fuzzer.request.HEADER_LINE_SPLIT
import org.jjgroup.xproxy.fuzzer.request.detectBodyKind
import org.jjgroup.xproxy.fuzzer.request.parseHeaders
import org.jjgroup.xproxy.fuzzer.request.splitMessage
import org.jjgroup.xproxy.ui.http.RequestBodyEncodingTarget

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class FuzzerSendHistoryEntry(
    val requestRaw: String,
    val responseText: String,
    val fullUrl: String,
    val statusText: String = "Done",
    val responseBytes: Int = 0,
    val elapsedMillis: Long = 0L,
    // 该次发送的目标服务(host/port/protocol)。用于在 back/forward 切换历史时恢复 tab 的 target,
    // 使跨目标(MCP agent 连发不同主机请求、或跟随重定向)的历史条目切回时 targetLabel 与重发目标正确。
    // 仅内存态;持久化(FuzzerTabHistoryRecord)不存 target,重启后恢复的历史条目此字段为 null。
    val target: HttpService? = null,
    // confirm_vuln 上报的要在响应区高亮的证据片段(原始子串)。仅内存态,不持久化。
    val evidence: List<String> = emptyList()
)

internal data class BodyPair(val key: String, val value: String)

internal data class ClipboardUrlCandidate(val uri: URI, val rawQueryOverride: String?)

internal fun normalizeEditorLineEndings(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n')

internal fun toFullUrl(target: HttpService, requestRaw: String): String {
    val firstLine = requestRaw.lineSequence().firstOrNull()?.trim().orEmpty()
    val parts = firstLine.split(' ')
    val rawPath = if (parts.size >= 2) parts[1] else "/"
    if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
        return rawPath
    }
    val normalizedPath = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
    return "${target.protocol.lowercase()}://${target.host}:${target.port}$normalizedPath"
}

internal fun toggleRequestMethod(rawRequest: String): String? {
    val parsed = splitMessage(rawRequest)
    val separator = parsed.separator
    val headerLines = parsed.headers.split(separator).toMutableList()
    if (headerLines.isEmpty()) {
        return null
    }

    val requestLine = headerLines[0].trim()
    val parts = requestLine.split(' ')
    if (parts.size < 3) {
        return null
    }
    val method = parts[0].uppercase()
    val path = parts[1]
    val version = parts[2]
    val headersMap = parseHeaders(parsed.headers)

    return when (method) {
        "GET" -> {
            val split = splitPathAndQuery(path)
            val pairs = parseQueryPairs(split.second)
            val body = encodeFormPairs(pairs)
            headerLines[0] = "POST ${split.first} $version"
            if (body.isNotEmpty()) {
                setHeader(headerLines, "Content-Type", "application/x-www-form-urlencoded")
                setHeader(headerLines, "Content-Length", body.toByteArray(StandardCharsets.ISO_8859_1).size.toString())
            } else {
                removeHeader(headerLines, "Content-Type")
                removeHeader(headerLines, "Content-Length")
            }
            composeRequest(headerLines, body, separator)
        }

        "POST" -> {
            val pairs = extractBodyPairs(parsed.body, headersMap)
            val query = encodeFormPairs(pairs)
            val split = splitPathAndQuery(path)
            val nextPath = if (query.isNotEmpty()) "${split.first}?$query" else split.first
            headerLines[0] = "GET $nextPath $version"
            removeHeader(headerLines, "Content-Type")
            removeHeader(headerLines, "Content-Length")
            composeRequest(headerLines, "", separator)
        }

        else -> null
    }
}

internal fun convertRequestBodyEncoding(rawRequest: String, target: RequestBodyEncodingTarget): String? {
    val parsed = splitMessage(rawRequest)
    val separator = parsed.separator
    val headerLines = parsed.headers.split(separator).toMutableList()
    if (headerLines.isEmpty()) {
        return null
    }
    val headersMap = parseHeaders(parsed.headers)
    var pairs = extractBodyPairs(parsed.body, headersMap)
    if (pairs.isEmpty()) {
        val requestLine = headerLines.firstOrNull().orEmpty().trim().split(' ')
        if (requestLine.size >= 2) {
            val query = splitPathAndQuery(requestLine[1]).second
            pairs = parseQueryPairs(query)
        }
    }

    val body = when (target) {
        RequestBodyEncodingTarget.JSON -> encodeJsonPairs(pairs)
        RequestBodyEncodingTarget.FORM_DATA -> encodeFormPairs(pairs)
        RequestBodyEncodingTarget.MULTIPART -> {
            val boundary = generateMultipartBoundary()
            val multipart = encodeMultipartPairs(pairs, boundary)
            setHeader(headerLines, "Content-Type", "multipart/form-data; boundary=$boundary")
            multipart
        }
        RequestBodyEncodingTarget.XML -> encodeXmlPairs(pairs)
    }

    if (target != RequestBodyEncodingTarget.MULTIPART) {
        val contentType = when (target) {
            RequestBodyEncodingTarget.JSON -> "application/json"
            RequestBodyEncodingTarget.FORM_DATA -> "application/x-www-form-urlencoded"
            RequestBodyEncodingTarget.XML -> "application/xml"
            else -> "application/octet-stream"
        }
        setHeader(headerLines, "Content-Type", contentType)
    }

    if (body.isNotEmpty()) {
        setHeader(headerLines, "Content-Length", body.toByteArray(StandardCharsets.ISO_8859_1).size.toString())
    } else {
        removeHeader(headerLines, "Content-Length")
    }
    return composeRequest(headerLines, body, separator)
}

private fun splitPathAndQuery(path: String): Pair<String, String?> {
    val idx = path.indexOf('?')
    return if (idx == -1) path to null else path.substring(0, idx) to path.substring(idx + 1)
}

private fun parseQueryPairs(query: String?): List<BodyPair> {
    if (query.isNullOrBlank()) {
        return emptyList()
    }
    return query.split('&')
        .filter { it.isNotEmpty() }
        .map {
            val idx = it.indexOf('=')
            if (idx >= 0) {
                BodyPair(urlDecode(it.substring(0, idx)), urlDecode(it.substring(idx + 1)))
            } else {
                BodyPair(urlDecode(it), "")
            }
        }
}

private fun extractBodyPairs(body: String, headers: Map<String, String>): List<BodyPair> {
    if (body.isBlank()) {
        return emptyList()
    }
    val contentType = headers["content-type"]?.lowercase().orEmpty()
    val kind = detectBodyKind(headers, body)
    return when {
        contentType.contains("multipart/form-data") -> parseMultipartPairs(body, contentType)
        contentType.contains("application/x-www-form-urlencoded") || kind == BodyKind.FORM -> parseQueryPairs(body)
        kind == BodyKind.JSON -> parseJsonPairs(body)
        contentType.contains("xml") -> parseXmlPairs(body)
        else -> parseQueryPairs(body)
    }
}

private fun parseJsonPairs(body: String): List<BodyPair> {
    val regex = Regex("\\\"((?:\\\\.|[^\\\"])*)\\\"\\s*:\\s*(\\\"((?:\\\\.|[^\\\"])*)\\\"|[^,}\\r\\n]+)")
    return regex.findAll(body).mapNotNull { m ->
        val key = m.groupValues.getOrNull(1)?.replace("\\\"", "\"") ?: return@mapNotNull null
        val rawValue = if (m.groupValues.getOrNull(3).isNullOrEmpty()) {
            m.groupValues.getOrNull(2)?.trim().orEmpty()
        } else {
            m.groupValues[3].replace("\\\"", "\"")
        }
        BodyPair(key, rawValue.trim())
    }.toList()
}

private fun parseXmlPairs(body: String): List<BodyPair> {
    val regex = Regex("<([A-Za-z_][A-Za-z0-9_.-]*)>([^<>]*)</\\1>")
    return regex.findAll(body).map {
        BodyPair(it.groupValues[1], decodeXml(it.groupValues[2].trim()))
    }.toList()
}

private fun parseMultipartPairs(body: String, contentType: String): List<BodyPair> {
    val boundary = Regex("boundary=([^;]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.getOrNull(1)
        ?.trim('"')
        ?: return emptyList()
    val marker = "--$boundary"
    val parts = body.split(marker)
    val pairs = ArrayList<BodyPair>()
    for (part in parts) {
        val trimmed = part.trim()
        if (trimmed.isEmpty() || trimmed == "--") {
            continue
        }
        val normalized = trimmed.removeSuffix("--").trim()
        val separator = if (normalized.contains("\r\n\r\n")) "\r\n\r\n" else "\n\n"
        val idx = normalized.indexOf(separator)
        if (idx <= 0) {
            continue
        }
        val headers = normalized.substring(0, idx)
        val value = normalized.substring(idx + separator.length).trimEnd('\r', '\n')
        val name = Regex("name=\\\"([^\\\"]+)\\\"").find(headers)?.groupValues?.getOrNull(1) ?: continue
        pairs.add(BodyPair(name, value))
    }
    return pairs
}

private fun encodeFormPairs(pairs: List<BodyPair>): String =
    pairs.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }

private fun encodeJsonPairs(pairs: List<BodyPair>): String {
    val lines = pairs.map { "  \"${escapeJson(it.key)}\": \"${escapeJson(it.value)}\"" }
    return if (lines.isEmpty()) "{}" else "{\n${lines.joinToString(",\n")}\n}"
}

private fun encodeMultipartPairs(pairs: List<BodyPair>, boundary: String): String {
    val sb = StringBuilder()
    for (pair in pairs) {
        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Disposition: form-data; name=\"").append(pair.key).append("\"\r\n\r\n")
        sb.append(pair.value).append("\r\n")
    }
    sb.append("--").append(boundary).append("--\r\n")
    return sb.toString()
}

// 模拟 Chrome(WebKit/Blink)生成的 multipart boundary:`----WebKitFormBoundary` + 16 个随机字母数字,
// 与真实浏览器表单提交一致,避免暴露 xproxy- 时间戳特征。Chrome 自身用的也是非加密 PRNG,这里同理。
private fun generateMultipartBoundary(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    val random = java.util.concurrent.ThreadLocalRandom.current()
    val sb = StringBuilder(34)
    sb.append("----WebKitFormBoundary")
    repeat(16) { sb.append(chars[random.nextInt(chars.length)]) }
    return sb.toString()
}

private fun encodeXmlPairs(pairs: List<BodyPair>): String {
    val sb = StringBuilder()
    sb.append("<root>\n")
    for (pair in pairs) {
        sb.append("  <").append(pair.key).append(">")
        sb.append(escapeXml(pair.value))
        sb.append("</").append(pair.key).append(">\n")
    }
    sb.append("</root>")
    return sb.toString()
}

internal fun composeRequest(headerLines: List<String>, body: String, separator: String): String {
    val headersText = headerLines.joinToString(separator)
    return headersText + separator + separator + body
}

internal fun setHeader(headerLines: MutableList<String>, name: String, value: String) {
    val matchIndexes = headerLines.indices.filter { index ->
        headerLines[index].substringBefore(':').trim().equals(name, ignoreCase = true)
    }
    if (matchIndexes.isNotEmpty()) {
        val firstIndex = matchIndexes.first()
        headerLines[firstIndex] = "$name: $value"
        for (duplicateIndex in matchIndexes.drop(1).asReversed()) {
            headerLines.removeAt(duplicateIndex)
        }
    } else {
        headerLines.add("$name: $value")
    }
}

internal fun removeHeader(headerLines: MutableList<String>, name: String) {
    headerLines.removeAll { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
}

private fun urlDecode(value: String): String =
    try {
        URLDecoder.decode(sanitizeInvalidPercentEscapes(value), StandardCharsets.UTF_8)
    } catch (_: Exception) {
        value
    }

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun escapeJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

private fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun decodeXml(value: String): String = value
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")

// --- URL clipboard utilities ---

internal fun readUrlFromClipboard(): ClipboardUrlCandidate? {
    return try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val text = clipboard.getData(DataFlavor.stringFlavor)?.toString().orEmpty()
        val trimmed = text.trim().trim('"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', '<', '>', '`', '(', ')')
        val normalized = when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            trimmed.startsWith("ws://", true) -> "http://${trimmed.substring(5)}"
            trimmed.startsWith("wss://", true) -> "https://${trimmed.substring(6)}"
            trimmed.startsWith("//") -> "http:$trimmed"
            else -> "http://$trimmed"
        }
        val rawQuery = extractRawQuery(normalized)
        val uri = URI(sanitizeInvalidPercentEscapes(normalized))
        if (uri.host.isNullOrBlank()) null else ClipboardUrlCandidate(uri, rawQuery)
    } catch (_: Exception) {
        null
    }
}

internal fun applyUrlToRequest(rawRequest: String, uri: URI, rawQueryOverride: String? = null): String? {
    val lines = rawRequest.split("\r\n", "\n")
    if (lines.isEmpty()) {
        return null
    }
    val separator = if (rawRequest.contains("\r\n")) "\r\n" else "\n"

    val requestLine = lines.firstOrNull()?.trim().orEmpty()
    val parts = requestLine.split(" ")
    if (parts.size < 3) {
        return null
    }

    val method = parts[0]
    val version = parts[2]
    val rawQuery = rawQueryOverride ?: uri.rawQuery
    val path = buildString {
        append(if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath)
        if (!rawQuery.isNullOrBlank()) {
            append("?")
            append(rawQuery)
        }
    }
    val newRequestLine = "$method $path $version"

    val host = normalizeUriHost(uri.host) ?: return null
    val isHttps = uri.scheme.equals("https", true) || uri.scheme.equals("wss", true)
    val isHttp = uri.scheme.equals("http", true) || uri.scheme.equals("ws", true)
    val port = if (uri.port > 0) uri.port else if (isHttps) 443 else if (isHttp) 80 else 80
    val normalizedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    val hostHeaderValue = if ((isHttps && port == 443) || (isHttp && port == 80)) {
        normalizedHost
    } else {
        "$normalizedHost:$port"
    }

    val mutableLines = lines.toMutableList()
    mutableLines[0] = newRequestLine
    var hostHeaderUpdated = false
    for (i in 1 until mutableLines.size) {
        val line = mutableLines[i]
        if (line.lowercase().startsWith("host:")) {
            mutableLines[i] = "Host: $hostHeaderValue"
            hostHeaderUpdated = true
            break
        }
        if (line.isBlank()) {
            break
        }
    }
    if (!hostHeaderUpdated) {
        val insertAt = mutableLines.indexOfFirst { it.isBlank() }.let { if (it == -1) mutableLines.size else it }
        mutableLines.add(insertAt, "Host: $hostHeaderValue")
    }

    return mutableLines.joinToString(separator)
}

// --- Chrome 默认请求头(用于"粘贴 host/url 为请求"时重建一个浏览器指纹完整的请求) ---

private const val CHROME_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
private const val CHROME_SEC_CH_UA =
    "sec-ch-ua: \"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\""
private const val CHROME_ACCEPT_NAV =
    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
private const val CHROME_ACCEPT_ENCODING = "gzip, deflate, br, zstd"
private const val CHROME_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"

// 浏览器指纹头:粘贴 URL 重建请求时一律用 Chrome 默认值覆盖,不保留原值(curl UA 等工具默认值会被替换)。
// Cookie / Authorization / 自定义 X-* 头不在此集合内,会被原样保留。
private val CHROME_FINGERPRINT_HEADER_NAMES = setOf(
    "host", "connection",
    "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
    "sec-ch-ua-arch", "sec-ch-ua-bitness", "sec-ch-ua-full-version-list",
    "sec-ch-ua-model", "sec-ch-ua-platform-version", "sec-ch-ua-wow64",
    "upgrade-insecure-requests", "user-agent", "accept",
    "sec-fetch-site", "sec-fetch-mode", "sec-fetch-user", "sec-fetch-dest",
    "accept-encoding", "accept-language", "origin", "referer"
)

/**
 * 将粘贴的 host/url 应用到请求,并重建为一套完整的 Chrome 浏览器默认请求头。
 *
 * 与 [applyUrlToRequest] 的区别:后者只改请求行 + Host,原样保留其余头(供跟随重定向使用,
 * 需保留原始 Cookie/鉴权等);本函数用于"粘贴为请求"菜单,目标是生成一个可即发的、
 * 指纹贴近真实 Chrome 的请求,因此会覆盖所有浏览器指纹头,但保留会话/自定义头与正文。
 */
internal fun applyUrlToRequestAsChrome(rawRequest: String, uri: URI, rawQueryOverride: String? = null): String? {
    val host = normalizeUriHost(uri.host) ?: return null
    val isHttps = uri.scheme.equals("https", true) || uri.scheme.equals("wss", true)
    val isHttp = uri.scheme.equals("http", true) || uri.scheme.equals("ws", true)
    val port = if (uri.port > 0) uri.port else if (isHttps) 443 else if (isHttp) 80 else 80
    val normalizedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    val hostHeaderValue = if ((isHttps && port == 443) || (isHttp && port == 80)) normalizedHost else "$normalizedHost:$port"
    val originBase = "${if (isHttps) "https" else "http"}://$hostHeaderValue"

    val parsed = splitMessage(rawRequest)
    val separator = "\r\n" // 重建的请求统一按 HTTP 标准输出 CRLF(发送前 normalizeRequestText/编辑器会再做归一)
    val headerLines = parsed.headers.split(HEADER_LINE_SPLIT).filter { it.isNotBlank() }

    // 方法/版本沿用原请求(默认 GET / HTTP/1.1),原请求为空时也能从 URL 直接生成完整请求。
    var method = "GET"
    var version = "HTTP/1.1"
    if (headerLines.isNotEmpty()) {
        val parts = headerLines[0].trim().split(" ")
        if (parts.isNotEmpty() && parts[0].isNotBlank()) {
            method = parts[0].uppercase()
        }
        if (parts.size >= 3) {
            version = parts[2]
        }
    }
    val isGet = method == "GET"

    val rawQuery = rawQueryOverride ?: uri.rawQuery
    val path = buildString {
        append(if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath)
        if (!rawQuery.isNullOrBlank()) {
            append("?")
            append(rawQuery)
        }
    }

    val body = parsed.body
    var contentType: String? = null
    val preserved = mutableListOf<Pair<String, String>>()
    for (i in 1 until headerLines.size) {
        val line = headerLines[i]
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val name = line.substring(0, idx).trim()
        val lower = name.lowercase()
        val value = line.substring(idx + 1).trim()
        when {
            lower == "content-type" -> contentType = value
            lower == "content-length" -> Unit // 按正文字节长度重算
            lower in CHROME_FINGERPRINT_HEADER_NAMES -> Unit // 用 Chrome 默认值覆盖
            else -> preserved.add(name to value) // Cookie / Authorization / X-* / 自定义头原样保留
        }
    }

    val out = StringBuilder()
    out.append("$method $path $version").append(separator)
    out.append("Host: $hostHeaderValue").append(separator)
    out.append(CHROME_SEC_CH_UA).append(separator)
    out.append("sec-ch-ua-mobile: ?0").append(separator)
    out.append("sec-ch-ua-platform: \"macOS\"").append(separator)
    if (body.isNotEmpty() && contentType != null) {
        out.append("Content-Type: $contentType").append(separator)
    }
    if (isGet) {
        out.append("Upgrade-Insecure-Requests: 1").append(separator)
    }
    out.append("User-Agent: $CHROME_USER_AGENT").append(separator)
    out.append("Accept: ${if (isGet) CHROME_ACCEPT_NAV else "*/*"}").append(separator)
    if (isGet) {
        // 地址栏直达导航:无 Origin/Referer,Sec-Fetch-Site=none。
        out.append("Sec-Fetch-Site: none").append(separator)
        out.append("Sec-Fetch-Mode: navigate").append(separator)
        out.append("Sec-Fetch-User: ?1").append(separator)
        out.append("Sec-Fetch-Dest: document").append(separator)
    } else {
        // 非 GET 视作同源 fetch/XHR:补 Origin/Referer,便于命中校验这俩头的接口。
        out.append("Origin: $originBase").append(separator)
        out.append("Sec-Fetch-Site: same-origin").append(separator)
        out.append("Sec-Fetch-Mode: cors").append(separator)
        out.append("Sec-Fetch-Dest: empty").append(separator)
        out.append("Referer: $originBase/").append(separator)
    }
    out.append("Accept-Encoding: $CHROME_ACCEPT_ENCODING").append(separator)
    out.append("Accept-Language: $CHROME_ACCEPT_LANGUAGE").append(separator)
    for ((name, value) in preserved) {
        out.append("$name: $value").append(separator)
    }
    if (body.isNotEmpty()) {
        out.append("Content-Length: ${body.toByteArray(StandardCharsets.ISO_8859_1).size}").append(separator)
    }
    out.append(separator)
    out.append(body)
    return out.toString()
}

private fun extractRawQuery(url: String): String? {
    val q = url.indexOf('?')
    if (q == -1) {
        return null
    }
    val hash = url.indexOf('#', q + 1)
    return if (hash == -1) url.substring(q + 1) else url.substring(q + 1, hash)
}

internal fun sanitizeInvalidPercentEscapes(url: String): String {
    val sb = StringBuilder(url.length + 8)
    var i = 0
    while (i < url.length) {
        val c = url[i]
        if (c == '%') {
            val hasTwo = i + 2 < url.length
            val hexLike = hasTwo && url[i + 1].isHexDigit() && url[i + 2].isHexDigit()
            if (!hexLike) {
                sb.append("%25")
                i += 1
                continue
            }
        }
        sb.append(c)
        i += 1
    }
    return sb.toString()
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal fun normalizeUriHost(rawHost: String?): String? {
    if (rawHost.isNullOrBlank()) {
        return null
    }
    return if (rawHost.startsWith("[") && rawHost.endsWith("]") && rawHost.length > 2) {
        rawHost.substring(1, rawHost.length - 1)
    } else {
        rawHost
    }
}

internal fun targetFromUri(uri: URI, fallback: HttpService): HttpService {
    val host = normalizeUriHost(uri.host) ?: fallback.host
    val scheme = uri.scheme?.lowercase()
    val protocol = when (scheme) {
        "https", "wss" -> "https"
        "http", "ws" -> "http"
        else -> fallback.protocol
    }
    val port = if (uri.port > 0) {
        uri.port
    } else if (protocol == "https") {
        443
    } else {
        80
    }
    return HttpService(host, port, protocol)
}

/** target 是否视为"未设置":host/协议为空且 port<=0(新建空白 tab 的初始态)。 */
internal fun isTargetBlank(target: HttpService): Boolean =
    target.host.isBlank() && target.protocol.isBlank() && target.port <= 0

/**
 * 粘贴 host/url 为请求时的 target 解析:仅当当前 target 为空(新建空白 tab)才用 URL 自动识别填充,
 * 否则保留用户已设置的 target,避免粘贴 URL 覆盖手动配置。
 */
internal fun resolveTargetForPaste(
    currentTarget: HttpService,
    uri: URI,
    updatedRequest: String
): HttpService =
    if (isTargetBlank(currentTarget)) {
        targetFromUri(uri, inferTargetFromRequest(updatedRequest, currentTarget))
    } else {
        currentTarget
    }

/**
 * target 为空时,从请求文本推断 host/port/协议(用于新建空白 tab 粘贴整段请求后直接发送)。
 *
 * 与 [inferTargetFromRequest] 的区别:对未显式带协议的普通请求(如 `GET / HTTP/1.1` + `Host: x`),
 * [inferTargetFromRequest] 会把 protocol 留成 fallback(空 target 时即空),这里按端口兜底
 * (443->https,否则 http),避免 protocol 留空导致发送走错协议。请求里没有任何 host 线索时返回 [fallback]。
 */
internal fun inferTargetForBlankFromRequest(rawRequest: String, fallback: HttpService): HttpService {
    val inferred = inferTargetFromRequest(rawRequest, fallback)
    if (inferred.host.isBlank()) {
        return fallback
    }
    val protocol = when {
        !inferred.protocol.isBlank() -> inferred.protocol
        isHttpsLikePort(inferred.port) -> "https"
        else -> "http"
    }
    val port = if (inferred.port > 0) inferred.port else if (protocol == "https") 443 else 80
    return HttpService(inferred.host, port, protocol)
}

/** 常见 https 端口:443 或以 443 结尾的端口(8443/9443 等 https 备用端口)。 */
internal fun isHttpsLikePort(port: Int): Boolean = port > 0 && port.toString().endsWith("443")

/**
 * 用 target 生成一个基础 GET 请求(请求为空且点发送时,弹 target 编辑框填完后用它填充编辑器)。
 * Host 头对默认端口(http:80 / https:443)省略端口,不带 Content-Length(GET 无 body)。
 */
internal fun buildBlankRequestFromTarget(target: HttpService): String {
    val host = target.host.trim()
    val isDefaultPort = (target.protocol.equals("http", true) && target.port == 80) ||
        (target.protocol.equals("https", true) && target.port == 443)
    val hostHeader = if (isDefaultPort || target.port <= 0) host else "$host:${target.port}"
    return "GET / HTTP/1.1\r\nHost: $hostHeader\r\n\r\n"
}
