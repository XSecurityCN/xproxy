package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.engine.http.uncompressIfNecessary
import org.jjgroup.xproxy.settings.core.CharsetPolicy
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.IllegalReferenceCountException

// extractTitle 每个响应都会调用,这里把正则预编译为常量,避免每次重新 Pattern.compile。
private val TITLE_TAG_REGEX =
    Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val WHITESPACE_REGEX = Regex("\\s+")

internal fun formatRequestRaw(request: HttpRequest): String {
    val sb = StringBuilder()
    sb.append(request.method().name()).append(' ').append(request.uri()).append(' ').append(displayProtocolVersion(request.protocolVersion().text())).append("\r\n")
    for (header in request.headers().entries()) {
        sb.append(header.key).append(": ").append(header.value).append("\r\n")
    }
    sb.append("\r\n")
    if (request is FullHttpRequest) {
        try {
            val body = ByteArray(request.content().readableBytes())
            request.content().getBytes(request.content().readerIndex(), body)
            sb.append(String(body, Charsets.ISO_8859_1))
        } catch (_: IllegalReferenceCountException) {
        }
    }
    return sb.toString()
}

internal fun formatRequestHeadersOnly(request: HttpRequest): String {
    val sb = StringBuilder()
    sb.append(request.method().name()).append(' ').append(request.uri()).append(' ').append(displayProtocolVersion(request.protocolVersion().text())).append("\r\n")
    for (header in request.headers().entries()) {
        sb.append(header.key).append(": ").append(header.value).append("\r\n")
    }
    sb.append("\r\n")
    return sb.toString()
}

internal fun formatResponseRaw(response: FullHttpResponse): String =
    formatResponseRawWithBodyLimit(response, Int.MAX_VALUE)

/**
 * 仅格式化响应首部(状态行 + headers + 空行),不含 body。用于 SSE 等流式响应在首部到达时先记录、
 * 随后按 chunk 追加 body 的场景(此时 httpResponse 通常是非 Full 的 HttpResponse,无可用 content)。
 */
internal fun formatResponseHeadersOnly(response: HttpResponse): String {
    val sb = StringBuilder()
    sb.append(displayProtocolVersion(response.protocolVersion().text())).append(' ').append(response.status().code()).append(' ').append(response.status().reasonPhrase()).append("\r\n")
    for (header in response.headers().entries()) {
        sb.append(header.key).append(": ").append(header.value).append("\r\n")
    }
    sb.append("\r\n")
    return sb.toString()
}

/**
 * 从已格式化的完整 responseRaw 截断 body 至 maxBodyBytes,等价于对同一响应状态调用 formatResponseRawWithBodyLimit,
 * 但避免再次从 ByteBuf 拷贝 body(ByteArray→String)。用于 extractTitle/detectMimeType 等分析用途。
 */
internal fun truncateBodyForAnalysis(responseRaw: String, maxBodyBytes: Int): String {
    if (maxBodyBytes <= 0) {
        return responseRaw
    }
    val sep = responseRaw.indexOf("\r\n\r\n")
    val bodyOffset = if (sep >= 0) sep + 4 else responseRaw.length
    val bodyEnd = (bodyOffset + maxBodyBytes).coerceAtMost(responseRaw.length)
    return if (bodyEnd >= responseRaw.length) responseRaw else responseRaw.substring(0, bodyEnd)
}

internal fun formatResponseRawWithBodyLimit(response: FullHttpResponse, maxBodyBytes: Int): String {
    val sb = StringBuilder()
    sb.append(displayProtocolVersion(response.protocolVersion().text())).append(' ').append(response.status().code()).append(' ').append(response.status().reasonPhrase()).append("\r\n")
    for (header in response.headers().entries()) {
        sb.append(header.key).append(": ").append(header.value).append("\r\n")
    }
    sb.append("\r\n")
    try {
        val readable = response.content().readableBytes()
        val capture = if (maxBodyBytes <= 0) readable else minOf(readable, maxBodyBytes)
        if (capture > 0) {
            val body = ByteArray(capture)
            response.content().getBytes(response.content().readerIndex(), body, 0, capture)
            sb.append(String(body, Charsets.ISO_8859_1))
        }
    } catch (_: IllegalReferenceCountException) {
    }
    return sb.toString()
}

internal fun isTlsRequest(request: HttpRequest, pipeline: HttpProxyInterceptPipeline): Boolean {
    val proto = pipeline.requestProto
    if (proto != null) {
        return proto.ssl
    }
    val method = request.method().name()
    val uri = request.uri()
    val host = request.headers().get("Host") ?: ""
    if (method.equals("CONNECT", ignoreCase = true)) {
        return true
    }
    if (uri.startsWith("https://", ignoreCase = true)) {
        return true
    }
    if (host.endsWith(":443")) {
        return true
    }
    return false
}

internal fun detectMimeType(response: FullHttpResponse, responseRaw: String): String {
    val contentType = response.headers().get("Content-Type")
    if (!contentType.isNullOrBlank()) {
        return normalizeMimeType(contentType.substringBefore(';').trim().lowercase())
    }

    val split = splitHeadersBody(responseRaw)
    val body = split.second.trimStart()
    val sniffed = when {
        body.startsWith("{") || body.startsWith("[") -> "application/json"
        body.startsWith("<?xml", true) -> "application/xml"
        body.startsWith("<!doctype html", true) || body.startsWith("<html", true) -> "text/html"
        body.startsWith("<") -> "application/xml"
        else -> "application/octet-stream"
    }
    return normalizeMimeType(sniffed)
}

internal fun normalizeMimeType(rawMime: String): String {
    val mime = rawMime.lowercase()
    return when {
        mime.startsWith("image/") -> "image"
        mime == "text/event-stream" -> "sse"
        mime == "text/html" || mime == "application/xhtml+xml" -> "html"
        mime == "text/css" -> "css"
        mime.contains("javascript") || mime.contains("ecmascript") || mime == "application/x-javascript" -> "script"
        mime == "application/json" || mime.endsWith("+json") || mime == "text/json" -> "json"
        mime == "application/xml" || mime == "text/xml" || mime.endsWith("+xml") -> "xml"
        mime.startsWith("text/") -> "text"
        mime == "application/octet-stream" || mime == "binary/octet-stream" -> "bin"
        mime.isBlank() -> "other"
        else -> "other"
    }
}

internal fun extractTitle(responseRaw: String): String {
    val split = splitHeadersBody(responseRaw)
    val headers = split.first
    val bodyRaw = split.second
    val bodyBinary = try {
        uncompressIfNecessary(headers, bodyRaw)
    } catch (_: Exception) {
        bodyRaw
    }
    // 快速路径:解压后 body 无 <title 子串且非 UTF-16 BOM 时,跳过 charset 解码(一次 toByteArray + 3 个正则
    // + 全量 UTF-8 校验)。多数代理流量(JSON/CSS/JS/图片)无 <title>,正则结果本就是空串。
    // 仅 UTF-16 BOM 内容会以非 ASCII 字节形式隐藏 <title,需走完整解码以保持完全等价。
    if (!hasUtf16Bom(bodyBinary) && !bodyBinary.contains("<title", ignoreCase = true)) {
        return ""
    }
    val body = CharsetPolicy.decodeBodyForDisplay(headers, bodyBinary)
    val title = TITLE_TAG_REGEX
        .find(body)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(WHITESPACE_REGEX, " ")
        ?.trim()
        ?: ""
    return title
}

private fun hasUtf16Bom(body: String): Boolean {
    if (body.length < 2) return false
    val a = body[0]; val b = body[1]
    // UTF-16LE BOM = FF FE,UTF-16BE BOM = FE FF(ISO-8859-1 视角下的字符)。
    return (a == 'ÿ' && b == 'þ') || (a == 'þ' && b == 'ÿ')
}

internal fun splitHeadersBody(raw: String): Pair<String, String> {
    val crlf = raw.indexOf("\r\n\r\n")
    if (crlf >= 0) {
        return raw.substring(0, crlf) to raw.substring(crlf + 4)
    }
    val lf = raw.indexOf("\n\n")
    if (lf >= 0) {
        return raw.substring(0, lf) to raw.substring(lf + 2)
    }
    return raw to ""
}

internal fun applyEditedRequest(httpRequest: FullHttpRequest, rawRequest: String) {
    val split = splitHeadersBody(rawRequest)
    val headersPart = split.first.replace("\r\n", "\n")
    val bodyPart = split.second

    val lines = headersPart.split("\n").filter { it.isNotBlank() }
    if (lines.isEmpty()) {
        return
    }

    val requestLine = lines[0].trim()
    val requestLineParts = requestLine.split(" ")
    if (requestLineParts.size >= 3) {
        httpRequest.setMethod(HttpMethod.valueOf(requestLineParts[0]))
        httpRequest.setUri(requestLineParts[1])
        httpRequest.setProtocolVersion(parseProtocolVersionToken(requestLineParts[2]))
    }

    httpRequest.headers().clear()
    for (i in 1 until lines.size) {
        val line = lines[i]
        val idx = line.indexOf(':')
        if (idx > 0) {
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isNotEmpty()) {
                httpRequest.headers().add(name, value)
            }
        }
    }

    val bodyBytes = bodyPart.toByteArray(Charsets.ISO_8859_1)
    httpRequest.content().clear()
    if (bodyBytes.isNotEmpty()) {
        httpRequest.content().writeBytes(bodyBytes)
    }
    httpRequest.headers().set("Content-Length", bodyBytes.size.toString())
}

internal fun applyEditedResponse(httpResponse: FullHttpResponse, rawResponse: String) {
    val split = splitHeadersBody(rawResponse)
    val headersPart = split.first.replace("\r\n", "\n")
    val bodyPart = split.second

    val lines = headersPart.split("\n").filter { it.isNotBlank() }
    if (lines.isEmpty()) {
        return
    }

    val statusLine = lines[0].trim()
    val statusParts = statusLine.split(" ")
    if (statusParts.size >= 2) {
        httpResponse.setProtocolVersion(parseProtocolVersionToken(statusParts[0]))
        val code = statusParts[1].toIntOrNull()
        if (code != null) {
            httpResponse.setStatus(HttpResponseStatus.valueOf(code))
        }
    }

    httpResponse.headers().clear()
    for (i in 1 until lines.size) {
        val line = lines[i]
        val idx = line.indexOf(':')
        if (idx > 0) {
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isNotEmpty()) {
                httpResponse.headers().add(name, value)
            }
        }
    }

    val bodyBytes = bodyPart.toByteArray(Charsets.ISO_8859_1)
    httpResponse.content().clear()
    if (bodyBytes.isNotEmpty()) {
        httpResponse.content().writeBytes(bodyBytes)
    }
    removeBodyTransformHeaders(httpResponse)
    httpResponse.headers().set("Content-Length", bodyBytes.size.toString())
}

internal fun removeBodyTransformHeaders(httpResponse: FullHttpResponse) {
    val names = httpResponse.headers().names().toList()
    for (name in names) {
        if (name.equals("Transfer-Encoding", ignoreCase = true) || name.equals("Content-Encoding", ignoreCase = true)) {
            httpResponse.headers().remove(name)
        }
    }
}

private fun displayProtocolVersion(protocolText: String): String {
    return if (protocolText.equals("HTTP/2.0", ignoreCase = true)) "HTTP/2" else protocolText
}

private fun parseProtocolVersionToken(token: String): HttpVersion {
    val normalized = if (token.equals("HTTP/2", ignoreCase = true)) "HTTP/2.0" else token
    return HttpVersion.valueOf(normalized)
}
