package org.jjgroup.xproxy.proxy.runtime.h2bridge

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.request.sendSingleRequest
import org.jjgroup.xproxy.proxy.core.ProtocolPolicy
import org.jjgroup.xproxy.proxy.runtime.native.h2.NativeHttp2ExchangeResult
import org.jjgroup.xproxy.proxy.runtime.native.h2.NativeHttp2UpstreamClient
import org.jjgroup.xproxy.settings.core.UpstreamProxyConfig
import org.jjgroup.xproxy.settings.core.UpstreamProxySettings
import java.net.URI

data class H2NettyForwardOptions(
    val requestRaw: String,
    val authority: String,
    val scheme: String = "https",
    val upstreamProxy: UpstreamProxyConfig? = null
)

class H2NettyResponseBridge {
    private val hopByHopHeaders = setOf(
        "connection",
        "keep-alive",
        "proxy-connection",
        "transfer-encoding",
        "upgrade"
    )

    fun send(requestRaw: String, authority: String): FullHttpResponse =
        send(H2NettyForwardOptions(requestRaw = requestRaw, authority = authority, upstreamProxy = UpstreamProxySettings.getEnabledProxy()))

    fun send(options: H2NettyForwardOptions): FullHttpResponse {
        val authority = deriveAuthority(options.requestRaw, options.authority)
        val upstreamProxy = options.upstreamProxy
        if (upstreamProxy != null) {
            return sendHttp2ViaEngineWithUpstream(options.requestRaw, authority)
        }
        val exchange = createClient(upstreamProxy).sendRawRequest(options.requestRaw, options.scheme, authority)
        return toNettyResponse(exchange)
    }

    fun sendHttp1Fallback(requestRaw: String, authority: String): FullHttpResponse =
        sendHttp1Fallback(H2NettyForwardOptions(requestRaw = requestRaw, authority = authority, upstreamProxy = UpstreamProxySettings.getEnabledProxy()))

    fun sendHttp1Fallback(options: H2NettyForwardOptions): FullHttpResponse {
        val authority = deriveAuthority(options.requestRaw, options.authority)
        val fallbackRequest = normalizeRequestForHttp11Fallback(options.requestRaw, authority)
        val http1Attempt = runCatching {
            val exchange = createClient(options.upstreamProxy).sendRawRequestHttp1(fallbackRequest, options.scheme, authority)
            toNettyResponse(exchange)
        }
        if (http1Attempt.isSuccess) {
            return http1Attempt.getOrThrow()
        }
        val http1Ex = http1Attempt.exceptionOrNull()
        return runCatching {
            sendHttp1FallbackViaSocket(fallbackRequest, authority)
        }.getOrElse { socketEx ->
            throw IllegalStateException(
                "h1 client fallback failed: ${http1Ex?.javaClass?.simpleName}: ${http1Ex?.message}; " +
                    "h1 socket fallback failed: ${socketEx::class.java.simpleName}: ${socketEx.message}",
                socketEx
            )
        }
    }

    private fun createClient(upstreamProxy: UpstreamProxyConfig?): NativeHttp2UpstreamClient =
        SHARED_CLIENTS.computeIfAbsent(clientCacheKey(upstreamProxy)) {
            // NativeHttp2UpstreamClient 内含 4 个 HttpClient(线程安全,可并发 send),按 upstreamProxy 共享,
            // 复用上游 TLS+H2 连接池。原每请求 new 一个,上游连接无法复用,H2 多路复用优势丧失。
            NativeHttp2UpstreamClient(upstreamProxy = upstreamProxy)
        }

    internal fun createClientForTest(upstreamProxy: UpstreamProxyConfig?): NativeHttp2UpstreamClient = createClient(upstreamProxy)

    private fun sendHttp2ViaEngineWithUpstream(requestRaw: String, authority: String): FullHttpResponse {
        val (host, port) = parseAuthority(authority)
        val rawResponse = sendSingleRequest(
            service = HttpService(host, port, "https"),
            requestText = requestRaw,
            protocolPolicy = ProtocolPolicy.preserve()
        )
        return toNettyResponse(parseRawResponse(rawResponse))
    }

    private fun sendHttp1FallbackViaSocket(requestRaw: String, authority: String): FullHttpResponse {
        val (host, port) = parseAuthority(authority)
        val rawResponse = sendSingleRequest(HttpService(host, port, "https"), requestRaw)
        return toNettyResponse(parseRawResponse(rawResponse))
    }

    internal fun normalizeRequestForHttp11Fallback(requestRaw: String, authority: String): String {
        val split = splitRawMessage(requestRaw)
        val lines = split.headers.split("\r\n")
        if (lines.isEmpty()) {
            return requestRaw
        }
        val first = lines[0]
        val downgradedLine = when {
            first.endsWith("HTTP/2", ignoreCase = true) -> normalizeHttp11RequestLine(first.dropLast(6), authority)
            first.endsWith("HTTP/2.0", ignoreCase = true) -> normalizeHttp11RequestLine(first.dropLast(8), authority)
            else -> normalizeHttp11RequestLine(first.substringBeforeLast(' ', first), authority)
        }
        val retained = mutableListOf<String>()
        var hasHost = false
        var hasConnection = false
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val name = line.substringBefore(':', "").trim()
            if (name.isBlank() || name.startsWith(":")) continue
            val lowerName = name.lowercase()
            if (lowerName in http11FallbackBlockedHeaders || lowerName.startsWith("x-http2-")) continue
            if (lowerName == "te" && !line.substringAfter(':', "").trim().equals("trailers", ignoreCase = true)) continue
            if (name.equals("host", ignoreCase = true)) hasHost = true
            if (name.equals("connection", ignoreCase = true)) hasConnection = true
            retained += line
        }
        if (!hasHost) {
            retained += "Host: $authority"
        }
        if (hasConnection) {
            for (i in retained.indices) {
                if (retained[i].substringBefore(':', "").trim().equals("connection", ignoreCase = true)) {
                    retained[i] = "Connection: close"
                    break
                }
            }
        } else {
            retained += "Connection: close"
        }
        val rebuiltHeaders = buildString {
            append(downgradedLine).append("\r\n")
            retained.forEach { append(it).append("\r\n") }
        }
        return rebuiltHeaders + "\r\n" + split.body
    }

    private fun normalizeHttp11RequestLine(prefix: String, authority: String): String {
        val parts = prefix.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size < 2) {
            return prefix.trimEnd() + " HTTP/1.1"
        }
        return parts[0] + " " + normalizeOriginFormTarget(parts[1], authority) + " HTTP/1.1"
    }

    private fun normalizeOriginFormTarget(target: String, authority: String): String {
        if (target.isBlank()) {
            return "/"
        }
        if (!target.startsWith("http://", ignoreCase = true) && !target.startsWith("https://", ignoreCase = true)) {
            return if (target.startsWith("/")) target else "/$target"
        }
        return try {
            val uri = URI(target)
            val path = uri.rawPath?.ifBlank { "/" } ?: "/"
            if (uri.rawQuery.isNullOrBlank()) path else "$path?${uri.rawQuery}"
        } catch (_: Exception) {
            "/"
        }
    }

    private fun parseAuthority(authority: String): Pair<String, Int> {
        val token = authority.trim()
        if (token.startsWith("[") && token.contains("]")) {
            val host = token.substringAfter("[").substringBefore("]")
            val port = token.substringAfter("]", "").removePrefix(":").toIntOrNull() ?: 443
            return host to port
        }
        val colonCount = token.count { it == ':' }
        if (colonCount == 1) {
            val host = token.substringBefore(':')
            val port = token.substringAfter(':').toIntOrNull() ?: 443
            return host to port
        }
        return token to 443
    }

    private data class RawSplit(val headers: String, val body: String)

    private val http11FallbackBlockedHeaders = setOf(
        "keep-alive",
        "proxy-connection",
        "transfer-encoding",
        "upgrade"
    )

    private fun splitRawMessage(raw: String): RawSplit {
        val marker = "\r\n\r\n"
        val index = raw.indexOf(marker)
        if (index >= 0) {
            return RawSplit(raw.substring(0, index), raw.substring(index + marker.length))
        }
        return RawSplit(raw, "")
    }

    private fun parseRawResponse(rawResponse: String): NativeHttp2ExchangeResult {
        val split = splitRawMessage(rawResponse)
        val lines = split.headers.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        val statusCode = lines.firstOrNull()
            ?.split(' ')
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 502
        val negotiatedProtocol = if (statusLine.startsWith("HTTP/2", ignoreCase = true)) {
            "HTTP_2"
        } else {
            "HTTP_1_1"
        }
        val headers = mutableListOf<Pair<String, String>>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isNotBlank()) {
                headers += name to value
            }
        }
        val bodyBytes = split.body.toByteArray(Charsets.ISO_8859_1)
        val isChunked = headers.any { (name, value) ->
            name.equals("transfer-encoding", ignoreCase = true) && value.contains("chunked", ignoreCase = true)
        }
        return NativeHttp2ExchangeResult(
            statusCode = statusCode,
            headers = headers,
            body = if (isChunked) decodeChunkedBody(bodyBytes) else bodyBytes,
            negotiatedProtocol = negotiatedProtocol
        )
    }

    internal fun decodeChunkedBody(rawBody: ByteArray): ByteArray {
        var pos = 0
        val out = java.io.ByteArrayOutputStream(rawBody.size)
        while (pos < rawBody.size) {
            val lineEnd = indexOfCrlf(rawBody, pos)
            if (lineEnd < 0) {
                return rawBody
            }
            val sizeLine = String(rawBody, pos, lineEnd - pos, Charsets.ISO_8859_1)
            val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return rawBody
            pos = lineEnd + 2
            if (chunkSize == 0) {
                return out.toByteArray()
            }
            if (pos + chunkSize + 2 > rawBody.size) {
                return rawBody
            }
            out.write(rawBody, pos, chunkSize)
            pos += chunkSize
            if (!(rawBody[pos] == '\r'.code.toByte() && rawBody[pos + 1] == '\n'.code.toByte())) {
                return rawBody
            }
            pos += 2
        }
        return out.toByteArray()
    }

    private fun indexOfCrlf(bytes: ByteArray, start: Int): Int {
        var i = start
        while (i + 1 < bytes.size) {
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
                return i
            }
            i++
        }
        return -1
    }

    private fun toNettyResponse(exchange: NativeHttp2ExchangeResult): FullHttpResponse {
        val body = Unpooled.buffer(exchange.body.size)
        body.writeBytes(exchange.body)
        val responseVersion = when (exchange.negotiatedProtocol) {
            "HTTP_2" -> HttpVersion.valueOf("HTTP/2.0")
            else -> HttpVersion.HTTP_1_1
        }
        val response = DefaultFullHttpResponse(responseVersion, HttpResponseStatus.valueOf(exchange.statusCode), body)
        for ((name, value) in exchange.headers) {
            if (name.startsWith(":")) {
                continue
            }
            val lower = name.lowercase()
            if (lower in hopByHopHeaders) {
                continue
            }
            response.headers().add(name, value)
        }
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, exchange.body.size)
        return response
    }

    companion object {
        private val SHARED_CLIENTS = java.util.concurrent.ConcurrentHashMap<String, NativeHttp2UpstreamClient>()
        private fun clientCacheKey(upstreamProxy: UpstreamProxyConfig?): String =
            upstreamProxy?.toString() ?: "DIRECT"

        fun toNettyResponseForTest(exchange: NativeHttp2ExchangeResult): FullHttpResponse =
            H2NettyResponseBridge().toNettyResponse(exchange)

        internal fun sharedClientCountForTest(): Int = SHARED_CLIENTS.size

        internal fun clearSharedClientsForTest() = SHARED_CLIENTS.clear()

        fun deriveAuthority(requestRaw: String, fallbackAuthority: String): String {
            val split = splitRawMessageStatic(requestRaw)
            val lines = split.headers.replace("\r\n", "\n").split('\n')
            for (line in lines.drop(1)) {
                val idx = if (line.startsWith(":")) line.indexOf(':', 1) else line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals(":authority", ignoreCase = true) || name.equals("host", ignoreCase = true)) {
                    if (value.isNotBlank()) return value
                }
            }
            return fallbackAuthority
        }

        private fun splitRawMessageStatic(raw: String): RawSplit {
            val marker = "\r\n\r\n"
            val index = raw.indexOf(marker)
            if (index >= 0) return RawSplit(raw.substring(0, index), raw.substring(index + marker.length))
            return RawSplit(raw, "")
        }
    }
}
