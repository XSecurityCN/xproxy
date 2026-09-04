package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.settings.core.CharsetPolicy
import org.jjgroup.xproxy.settings.core.UpstreamProxyProtocol
import org.jjgroup.xproxy.settings.core.UpstreamProxySettings
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.proxy.core.ProtocolPolicy

import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// 单发请求改写上游代理鉴权头时用;每请求触发,预编译避免重复 Pattern.compile。
private val PROXY_AUTHORIZATION_HEADER_REGEX = Regex("(?im)^Proxy-Authorization:.*$")

private fun syncContentLengthHeader(headersText: String, bodyIso: String, separator: String): String {
    if (headersText.isBlank()) {
        return headersText
    }
    val lines = headersText.split(HEADER_LINE_SPLIT).toMutableList()
    if (lines.isEmpty()) {
        return headersText
    }
    val bodyLength = bodyIso.toByteArray(Charsets.ISO_8859_1).size
    var found = false
    for (index in 1 until lines.size) {
        val line = lines[index]
        val headerName = line.substringBefore(':').trim()
        if (headerName.equals("Content-Length", ignoreCase = true)) {
            lines[index] = "Content-Length: $bodyLength"
            found = true
            break
        }
    }
    if (!found && bodyIso.isNotEmpty()) {
        lines.add("Content-Length: $bodyLength")
    }
    return lines.joinToString(separator)
}

fun sendSingleRequest(
    service: HttpService,
    requestText: String,
    protocolPolicy: ProtocolPolicy = ProtocolPolicy.preserve(),
    shouldCancel: () -> Boolean = { false },
    onProgress: ((partialResponseRaw: String) -> Unit)? = null
): String {
    val host = service.host.removePrefix("[").removeSuffix("]")
    val port = service.port
    val protocol = service.protocol.lowercase()

    val parsedRequest = splitMessage(requestText)
    val headersMap = parseHeaders(parsedRequest.headers)
    val bodyKind = detectBodyKind(headersMap, parsedRequest.body)
    val normalizedBodyForWire = if (bodyKind == BodyKind.JSON && parsedRequest.body.isNotBlank()) {
        compactJson(parsedRequest.body)
    } else {
        parsedRequest.body
    }
    val encodedBodyIso = CharsetPolicy.encodeBodyForForward(parsedRequest.headers, normalizedBodyForWire)
    val headersForWire = if (bodyKind == BodyKind.JSON && normalizedBodyForWire != parsedRequest.body) {
        syncContentLengthHeader(parsedRequest.headers, encodedBodyIso, parsedRequest.separator)
    } else {
        parsedRequest.headers
    }
    val requestForWire = if (parsedRequest.headers.isEmpty() && parsedRequest.body.isEmpty()) {
        requestText
    } else {
        headersForWire + parsedRequest.separator + parsedRequest.separator + encodedBodyIso
    }

    var prepared = normalizeRequestText(requestForWire)
    if (FuzzerProtocolSendSelector.shouldSendAsHttp2(prepared, protocolPolicy)) {
        return sendSingleHttp2Request(service, prepared, shouldCancel)
    }
    if (FuzzerProtocolSendSelector.shouldDowngradeHttp2ToHttp11(service, prepared, protocolPolicy)) {
        prepared = prepared.replaceFirst("HTTP/2\r\n", "HTTP/1.1\r\n")
    } else if (protocolPolicy.failClosedOnDowngrade() && Utils.isHttp2(prepared.toByteArray(Charsets.ISO_8859_1)) && service.protocol.lowercase() != "http2") {
        throw IllegalStateException("Downgrade denied by protocol policy (fail closed)")
    }
    prepared = ensureConnectionClose(prepared)
    val bytes = Utils.stringToBytes(prepared)

    val upstream = UpstreamProxySettings.getEnabledProxy()
    if (UpstreamProxySettings.isEnabled() && upstream == null) {
        throw IllegalArgumentException("Upstream proxy is enabled but host/port is invalid")
    }

    val socket: Socket = when {
        upstream == null && protocol == "https" -> {
            openDirectHttpsSocket(host, port)
        }
        upstream == null -> Socket(host, port)
        upstream.protocol == UpstreamProxyProtocol.SOCKS5 -> {
            if (protocol == "https") {
                openHttpsViaSocks5Proxy(upstream.host, upstream.port, host, port)
            } else {
                val proxySocket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(upstream.host, upstream.port)))
                proxySocket.connect(InetSocketAddress(host, port), 10000)
                proxySocket
            }
        }
        protocol == "https" -> {
            openHttpsViaHttpProxy(upstream.host, upstream.port, host, port, upstream.proxyAuthorizationHeaderValue())
        }
        else -> Socket(upstream.host, upstream.port)
    }

    val payload = if (upstream != null && protocol == "http" && upstream.protocol == UpstreamProxyProtocol.HTTP) {
        rewriteRequestForHttpProxy(bytes, host, port, upstream.proxyAuthorizationHeaderValue())
    } else {
        bytes
    }

    socket.soTimeout = 10000
    socket.tcpNoDelay = true
    try {
        if (shouldCancel()) {
            throw InterruptedException("Request cancelled")
        }
        val out = socket.getOutputStream()
        out.write(payload)
        out.flush()
        val requestMethod = prepared.lineSequence().firstOrNull()?.substringBefore(' ')?.uppercase().orEmpty()
        val responseBytes = readHttpResponse(socket, requestMethod, shouldCancel, onProgress)
        return Utils.bytesToString(responseBytes)
    } finally {
        socket.close()
    }
}

/**
 * 建立到目标的底层 socket(供 WebSocket 重放等非 HTTP/1.1 request-response 路径复用):
 * 直连 / 走上游 HTTP 代理(CONNECT 隧道)/ 走上游 SOCKS5 代理,tls 时升级为 SSL。
 * 与 [sendSingleRequest] 内的 socket 分支保持一致(含 TLS 1.3->1.2 握手重试)。
 */
internal fun openProxyAwareSocket(host: String, port: Int, tls: Boolean): Socket {
    val resolvedHost = host.removePrefix("[").removeSuffix("]")
    val upstream = UpstreamProxySettings.getEnabledProxy()
    if (UpstreamProxySettings.isEnabled() && upstream == null) {
        throw IllegalArgumentException("Upstream proxy is enabled but host/port is invalid")
    }
    return when {
        upstream == null && tls -> openDirectHttpsSocket(resolvedHost, port)
        upstream == null -> Socket(resolvedHost, port)
        upstream.protocol == UpstreamProxyProtocol.SOCKS5 -> {
            if (tls) {
                openHttpsViaSocks5Proxy(upstream.host, upstream.port, resolvedHost, port)
            } else {
                val proxySocket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(upstream.host, upstream.port)))
                proxySocket.connect(InetSocketAddress(resolvedHost, port), 10000)
                proxySocket
            }
        }
        tls -> openHttpsViaHttpProxy(upstream.host, upstream.port, resolvedHost, port, upstream.proxyAuthorizationHeaderValue())
        else -> Socket(upstream.host, upstream.port)
    }
}

private fun openDirectHttpsSocket(host: String, port: Int): Socket {
    return runCatching {
        val context = createPermissiveSslContext()
        configureEnabledProtocols(context.socketFactory.createSocket(host, port), arrayOf("TLSv1.3", "TLSv1.2"))
    }.recoverCatching { ex ->
        if (!isRetryableHandshakeTermination(ex)) throw ex
        val context = createPermissiveSslContext()
        configureEnabledProtocols(context.socketFactory.createSocket(host, port), arrayOf("TLSv1.2"))
    }.getOrThrow()
}

private fun openHttpsViaSocks5Proxy(proxyHost: String, proxyPort: Int, targetHost: String, targetPort: Int): Socket {
    return runCatching {
        val proxySocket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort)))
        proxySocket.connect(InetSocketAddress(targetHost, targetPort), 10000)
        val context = createPermissiveSslContext()
        configureEnabledProtocols(
            context.socketFactory.createSocket(proxySocket, targetHost, targetPort, true),
            arrayOf("TLSv1.3", "TLSv1.2")
        )
    }.recoverCatching { ex ->
        if (!isRetryableHandshakeTermination(ex)) throw ex
        val retryProxySocket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort)))
        retryProxySocket.connect(InetSocketAddress(targetHost, targetPort), 10000)
        val retryContext = createPermissiveSslContext()
        configureEnabledProtocols(
            retryContext.socketFactory.createSocket(retryProxySocket, targetHost, targetPort, true),
            arrayOf("TLSv1.2")
        )
    }.getOrThrow()
}

private fun openHttpsViaHttpProxy(
    proxyHost: String,
    proxyPort: Int,
    targetHost: String,
    targetPort: Int,
    proxyAuthorization: String?
): Socket {
    return runCatching {
        val tunnelSocket = Socket(proxyHost, proxyPort)
        tunnelSocket.soTimeout = 10000
        establishConnectTunnel(tunnelSocket, targetHost, targetPort, proxyAuthorization)
        val context = createPermissiveSslContext()
        configureEnabledProtocols(
            context.socketFactory.createSocket(tunnelSocket, targetHost, targetPort, true),
            arrayOf("TLSv1.3", "TLSv1.2")
        )
    }.recoverCatching { ex ->
        if (!isRetryableHandshakeTermination(ex)) throw ex
        val retryTunnelSocket = Socket(proxyHost, proxyPort)
        retryTunnelSocket.soTimeout = 10000
        establishConnectTunnel(retryTunnelSocket, targetHost, targetPort, proxyAuthorization)
        val retryContext = createPermissiveSslContext()
        configureEnabledProtocols(
            retryContext.socketFactory.createSocket(retryTunnelSocket, targetHost, targetPort, true),
            arrayOf("TLSv1.2")
        )
    }.getOrThrow()
}

private fun createPermissiveSslContext(): SSLContext {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }), null)
    return context
}

private fun configureEnabledProtocols(socket: Socket, protocols: Array<String>): Socket {
    if (socket is SSLSocket) {
        socket.enabledProtocols = protocols
    }
    return socket
}

private fun isRetryableHandshakeTermination(ex: Throwable): Boolean {
    var current: Throwable? = ex
    while (current != null) {
        if (current is SSLHandshakeException) {
            return true
        }
        val message = current.message?.lowercase()
        if (message != null && (
                message.contains("remote host terminated the handshake") ||
                    message.contains("no_application_protocol") ||
                    message.contains("application protocol")
                )) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun rewriteRequestForHttpProxy(rawRequest: ByteArray, host: String, port: Int, proxyAuthorization: String?): ByteArray {
    val text = String(rawRequest, Charsets.ISO_8859_1)
    val lineEnd = text.indexOf("\r\n")
    if (lineEnd <= 0) {
        return rawRequest
    }
    val requestLine = text.substring(0, lineEnd)
    val parts = requestLine.split(" ", limit = 3)
    if (parts.size != 3) {
        return rawRequest
    }

    val method = parts[0]
    val path = parts[1]
    val version = parts[2]
    if (method.equals("CONNECT", ignoreCase = true) || path.startsWith("http://") || path.startsWith("https://")) {
        return rawRequest
    }

    val fixedHost = if (host.contains(":")) "[$host]" else host
    val absolute = "http://$fixedHost:$port$path"
    var updated = "$method $absolute $version" + text.substring(lineEnd)
    if (!proxyAuthorization.isNullOrBlank()) {
        if (updated.contains("\r\nProxy-Authorization:", ignoreCase = true)) {
            updated = updated.replace(PROXY_AUTHORIZATION_HEADER_REGEX, "Proxy-Authorization: $proxyAuthorization")
        } else {
            updated = updated.replaceFirst("\r\n", "\r\nProxy-Authorization: $proxyAuthorization\r\n")
        }
    }
    return updated.toByteArray(Charsets.ISO_8859_1)
}

private fun establishConnectTunnel(proxySocket: Socket, targetHost: String, targetPort: Int, proxyAuthorization: String?) {
    val authority = if (targetHost.contains(":")) "[$targetHost]:$targetPort" else "$targetHost:$targetPort"
    val proxyAuthHeader = if (proxyAuthorization.isNullOrBlank()) "" else "Proxy-Authorization: $proxyAuthorization\r\n"
    val connectReq = (
        "CONNECT $authority HTTP/1.1\r\n" +
            "Host: $authority\r\n" +
            proxyAuthHeader +
            "Proxy-Connection: Keep-Alive\r\n" +
            "Connection: Keep-Alive\r\n\r\n"
        ).toByteArray(Charsets.ISO_8859_1)
    proxySocket.getOutputStream().write(connectReq)
    proxySocket.getOutputStream().flush()

    val responseHeader = readHttpHeader(proxySocket)
    val success = responseHeader.startsWith("HTTP/1.1 200") || responseHeader.startsWith("HTTP/1.0 200")
    if (!success) {
        throw ConnectException("Upstream CONNECT failed: ${responseHeader.lineSequence().firstOrNull() ?: "unknown"}")
    }
}

private fun readHttpHeader(socket: Socket): String {
    val input = socket.getInputStream()
    val output = ByteArrayOutputStream()
    var b0 = -1
    var b1 = -1
    var b2 = -1
    var b3 = -1
    while (true) {
        val b = input.read()
        if (b == -1) {
            throw ConnectException("Unexpected EOF while reading upstream proxy response")
        }
        output.write(b)
        b0 = b1
        b1 = b2
        b2 = b3
        b3 = b
        if (b0 == '\r'.code && b1 == '\n'.code && b2 == '\r'.code && b3 == '\n'.code) {
            break
        }
        if (output.size() > 65536) {
            throw ConnectException("Upstream proxy response headers too large")
        }
    }
    return output.toString(Charsets.ISO_8859_1.name())
}
