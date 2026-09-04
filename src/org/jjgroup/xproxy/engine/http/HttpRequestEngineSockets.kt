package org.jjgroup.xproxy

import org.jjgroup.xproxy.engine.http.createTrustingSSLSocketFactory
import org.jjgroup.xproxy.settings.core.UpstreamProxyProtocol

import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

internal fun HttpRequestEngine.createSocket(url: URL, trustingSslSocketFactory: SSLSocketFactory, ipAddress: java.net.InetAddress?, port: Int, reuseSSL: Boolean): Socket {
    val upstream = upstreamProxy
    if (upstream == null) {
        return if (url.protocol == "https") {
            if (reuseSSL) {
                trustingSslSocketFactory.createSocket(ipAddress, port)
            } else {
                createTrustingSSLSocketFactory(this).createSocket(ipAddress, port)
            }
        } else {
            SocketFactory.getDefault().createSocket(ipAddress, port)
        }
    }

    if (upstream.protocol == UpstreamProxyProtocol.SOCKS5) {
        val proxySocket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(upstream.host, upstream.port)))
        proxySocket.connect(InetSocketAddress(url.host, port), 10000)
        if (url.protocol == "https") {
            val sslFactory = if (reuseSSL) {
                trustingSslSocketFactory
            } else {
                createTrustingSSLSocketFactory(this)
            }
            return sslFactory.createSocket(proxySocket, url.host, port, true)
        }
        return proxySocket
    }

    if (url.protocol == "https") {
        val tunnelSocket = Socket(upstream.host, upstream.port)
        establishConnectTunnel(tunnelSocket, url.host, port, upstream.proxyAuthorizationHeaderValue())
        val sslFactory = if (reuseSSL) {
            trustingSslSocketFactory
        } else {
            createTrustingSSLSocketFactory(this)
        }
        return sslFactory.createSocket(tunnelSocket, url.host, port, true)
    }

    return Socket(upstream.host, upstream.port)
}

internal fun establishConnectTunnel(proxySocket: Socket, targetHost: String, targetPort: Int, proxyAuthorization: String?) {
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

internal fun readHttpHeader(socket: Socket): String {
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

internal fun rewriteRequestForHttpProxy(rawRequest: ByteArray, url: URL, proxyAuthorization: String?): ByteArray {
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

    val host = if (url.host.contains(":")) "[${url.host}]" else url.host
    val portPart = if (url.port != -1) ":${url.port}" else ""
    val absolute = "http://$host$portPart$path"
    var updated = "$method $absolute $version" + text.substring(lineEnd)
    if (!proxyAuthorization.isNullOrBlank()) {
        if (updated.contains("\r\nProxy-Authorization:", ignoreCase = true)) {
            updated = updated.replace(Regex("(?im)^Proxy-Authorization:.*$"), "Proxy-Authorization: $proxyAuthorization")
        } else {
            updated = updated.replaceFirst("\r\n", "\r\nProxy-Authorization: $proxyAuthorization\r\n")
        }
    }
    return updated.toByteArray(Charsets.ISO_8859_1)
}
