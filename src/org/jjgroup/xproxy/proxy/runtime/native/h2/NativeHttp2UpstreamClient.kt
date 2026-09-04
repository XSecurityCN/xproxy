package org.jjgroup.xproxy.proxy.runtime.native.h2

import java.net.URI
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.jjgroup.xproxy.settings.core.UpstreamProxyConfig
import org.jjgroup.xproxy.settings.core.UpstreamProxyProtocol

data class NativeHttp2ExchangeResult(
    val statusCode: Int,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val negotiatedProtocol: String
) {
    fun toRawResponse(): String {
        val sb = StringBuilder()
        sb.append(rawStatusLineProtocol()).append(' ').append(statusCode).append(" \r\n")
        for ((name, value) in headers) {
            if (name.startsWith(":")) {
                continue
            }
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        if (body.isNotEmpty()) {
            sb.append(String(body, Charsets.ISO_8859_1))
        }
        return sb.toString()
    }

    fun historyProtocol(): String {
        return when (negotiatedProtocol) {
            "HTTP_2" -> "http/2"
            "HTTP_1_1" -> "http/1.1"
            else -> "http/1.1"
        }
    }

    fun recordedRequestRaw(rawRequest: String): String {
        val lineEnd = rawRequest.indexOf("\r\n").takeIf { it >= 0 } ?: rawRequest.indexOf('\n')
        val firstLine = if (lineEnd >= 0) rawRequest.substring(0, lineEnd).trimEnd('\r') else rawRequest.trimEnd('\r', '\n')
        val targetProtocol = when (historyProtocol()) {
            "http/2" -> "HTTP/2"
            else -> "HTTP/1.1"
        }
        val rewrittenLine = rewriteRequestLineProtocol(firstLine, targetProtocol) ?: return rawRequest
        if (lineEnd < 0) {
            return rewrittenLine
        }
        return rewrittenLine + rawRequest.substring(lineEnd)
    }

    private fun rawStatusLineProtocol(): String {
        return when (historyProtocol()) {
            "http/2" -> "HTTP/2.0"
            else -> "HTTP/1.1"
        }
    }

    private fun rewriteRequestLineProtocol(firstLine: String, protocol: String): String? {
        val parts = firstLine.split(Regex("\\s+"))
        if (parts.size < 3) {
            return null
        }
        val current = parts.last()
        if (!current.startsWith("HTTP/", ignoreCase = true)) {
            return null
        }
        if (current.equals(protocol, ignoreCase = true) || (protocol == "HTTP/2" && current.equals("HTTP/2.0", ignoreCase = true))) {
            return null
        }
        return parts.dropLast(1).joinToString(" ") + " " + protocol
    }
}

class NativeHttp2UpstreamClient(
    private val timeout: Duration = Duration.ofSeconds(30),
    private val sslContext: SSLContext = createPermissiveSslContext(),
    private val upstreamProxy: UpstreamProxyConfig? = null
) {
    private val h2Client: HttpClient = createClient(HttpClient.Version.HTTP_2, tls12Only = false)
    private val h2ClientTls12: HttpClient = createClient(HttpClient.Version.HTTP_2, tls12Only = true)
    private val h1Client: HttpClient = createClient(HttpClient.Version.HTTP_1_1, tls12Only = false)
    private val h1ClientTls12: HttpClient = createClient(HttpClient.Version.HTTP_1_1, tls12Only = true)

    fun sendRawRequest(rawRequest: String, scheme: String, authority: String): NativeHttp2ExchangeResult {
        return sendRawRequestWithRetry(h2Client, h2ClientTls12, rawRequest, scheme, authority)
    }

    fun sendRawRequestHttp1(rawRequest: String, scheme: String, authority: String): NativeHttp2ExchangeResult {
        return sendRawRequestWithRetry(h1Client, h1ClientTls12, rawRequest, scheme, authority)
    }

    private fun sendRawRequestWithRetry(
        primary: HttpClient,
        tls12Fallback: HttpClient,
        rawRequest: String,
        scheme: String,
        authority: String
    ): NativeHttp2ExchangeResult {
        return try {
            sendRawRequestWithClient(primary, rawRequest, scheme, authority)
        } catch (ex: Exception) {
            if (isHandshakeTermination(ex)) {
                sendRawRequestWithClient(tls12Fallback, rawRequest, scheme, authority)
            } else {
                throw ex
            }
        }
    }

    private fun sendRawRequestWithClient(client: HttpClient, rawRequest: String, scheme: String, authority: String): NativeHttp2ExchangeResult {
        val parsed = parseRawHttpRequest(rawRequest)
        val uri = buildTargetUri(scheme, authority, parsed.path)
        val bodyPublisher = if (parsed.body.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(parsed.body)
        }
        val builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .method(parsed.method, bodyPublisher)

        for ((name, value) in parsed.headers) {
            val lower = name.lowercase()
            if (lower.startsWith(":")) continue
            if (isRestrictedRequestHeader(lower)) continue
            builder.header(name, value)
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return NativeHttp2ExchangeResult(
            statusCode = response.statusCode(),
            headers = response.headers().map().flatMap { (name, values) -> values.map { name to it } },
            body = response.body(),
            negotiatedProtocol = response.version().name
        )
    }

    fun requireHttp2(result: NativeHttp2ExchangeResult): NativeHttp2ExchangeResult {
        if (result.negotiatedProtocol != "HTTP_2") {
            throw IllegalStateException("Upstream downgraded to ${result.negotiatedProtocol}")
        }
        return result
    }

    private fun createClient(version: HttpClient.Version, tls12Only: Boolean): HttpClient {
        val sslParameters = SSLParameters().apply {
            protocols = if (tls12Only) arrayOf("TLSv1.2") else arrayOf("TLSv1.3", "TLSv1.2")
        }
        val builder = HttpClient.newBuilder()
            .version(version)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout)
            .sslContext(sslContext)
            .sslParameters(sslParameters)

        val selector = proxySelectorFor(upstreamProxy)
        if (selector != null) {
            builder.proxy(selector)
        }
        val authenticator = proxyAuthenticatorFor(upstreamProxy)
        if (authenticator != null) {
            builder.authenticator(authenticator)
        }
        return builder.build()
    }

    private fun isHandshakeTermination(ex: Throwable): Boolean {
        var current: Throwable? = ex
        while (current != null) {
            if (current is SSLHandshakeException) {
                return true
            }
            val message = current.message?.lowercase()
            if (message != null && message.contains("remote host terminated the handshake")) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

internal fun proxySelectorFor(proxy: UpstreamProxyConfig?): ProxySelector? {
    if (proxy == null) {
        return null
    }
    val address = InetSocketAddress(proxy.host, proxy.port)
    val type = when (proxy.protocol) {
        UpstreamProxyProtocol.HTTP -> Proxy.Type.HTTP
        UpstreamProxyProtocol.SOCKS5 -> Proxy.Type.SOCKS
    }
    val selected = Proxy(type, address)
    return object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(selected)
        override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
    }
}

internal fun proxyAuthenticatorFor(proxy: UpstreamProxyConfig?): Authenticator? {
    if (proxy == null || !proxy.hasAuthentication()) {
        return null
    }
    return object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestorType != Authenticator.RequestorType.PROXY) {
                return null
            }
            return PasswordAuthentication(proxy.username, proxy.password.toCharArray())
        }
    }
}

internal fun createPermissiveSslContext(): SSLContext {
    val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
    }
}

internal fun buildTargetUri(scheme: String, authority: String, target: String): URI {
    val sanitizedTarget = sanitizeUriToken(target)
    if (sanitizedTarget.startsWith("http://", ignoreCase = true) || sanitizedTarget.startsWith("https://", ignoreCase = true)) {
        return URI(sanitizedTarget)
    }

    val idx = sanitizedTarget.indexOf('?')
    val path = when {
        idx < 0 -> sanitizedTarget
        else -> sanitizedTarget.substring(0, idx)
    }.ifBlank { "/" }
    val normalizedPath = if (path.startsWith("/")) path else "/$path"
    val query = if (idx < 0) null else sanitizedTarget.substring(idx + 1).ifBlank { null }
    val querySuffix = if (query == null) "" else "?$query"
    return URI("$scheme://$authority$normalizedPath$querySuffix")
}

private fun sanitizeUriToken(token: String): String {
    return token
        .replace("|", "%7C")
        .replace(" ", "%20")
        .replace("[", "%5B")
        .replace("]", "%5D")
}

internal fun isRestrictedRequestHeader(lowerName: String): Boolean {
    return lowerName.startsWith("x-http2-") ||
        lowerName == "host" ||
        lowerName == "connection" ||
        lowerName == "proxy-connection" ||
        lowerName == "keep-alive" ||
        lowerName == "content-length" ||
        lowerName == "expect" ||
        lowerName == "te" ||
        lowerName == "transfer-encoding" ||
        lowerName == "upgrade"
}

data class NativeParsedRawRequest(
    val method: String,
    val path: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray
)

fun parseRawHttpRequest(raw: String): NativeParsedRawRequest {
    val split = splitRawRequestPreservingBody(raw)
    val head = split.headers.replace("\r\n", "\n").trim()
    val body = split.body
    val lines = head.split('\n').filter { it.isNotBlank() }
    if (lines.isEmpty()) throw IllegalArgumentException("Missing request line")
    val requestLine = lines.first().split(' ').filter { it.isNotBlank() }
    if (requestLine.size < 3) throw IllegalArgumentException("Malformed request line")
    val method = requestLine[0]
    val path = requestLine[1]
    val headers = ArrayList<Pair<String, String>>()
    for (line in lines.drop(1)) {
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val name = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim()
        if (name.isNotBlank()) {
            headers += name to value
        }
    }
    return NativeParsedRawRequest(
        method = method,
        path = path,
        headers = headers,
        body = body.toByteArray(Charsets.ISO_8859_1)
    )
}

private data class RawRequestSplit(val headers: String, val body: String)

private fun splitRawRequestPreservingBody(raw: String): RawRequestSplit {
    val crlfMarker = "\r\n\r\n"
    val crlfIndex = raw.indexOf(crlfMarker)
    if (crlfIndex >= 0) {
        return RawRequestSplit(raw.substring(0, crlfIndex), raw.substring(crlfIndex + crlfMarker.length))
    }
    val lfMarker = "\n\n"
    val lfIndex = raw.indexOf(lfMarker)
    if (lfIndex >= 0) {
        return RawRequestSplit(raw.substring(0, lfIndex), raw.substring(lfIndex + lfMarker.length))
    }
    return RawRequestSplit(raw, "")
}
