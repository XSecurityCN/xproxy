package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.HTTP2RequestEngine
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.ui.table.ConsolePrinter

import java.net.ConnectException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun sendSingleHttp2Request(service: HttpService, requestText: String, shouldCancel: () -> Boolean): String {
    if (shouldCancel()) {
        throw InterruptedException("Request cancelled")
    }

    val host = service.host.removePrefix("[").removeSuffix("]")
    val scheme = when (service.protocol.lowercase()) {
        "http", "http2", "h2c" -> "http"
        else -> "https"
    }
    val targetUrl = "$scheme://$host:${service.port}/"
    val responseRef = AtomicReference<String?>(null)
    val errorRef = AtomicReference<Exception?>(null)
    val latch = CountDownLatch(1)

    val engine = HTTP2RequestEngine(
        url = targetUrl,
        threads = 1,
        maxQueueSize = 1,
        requestsPerConnection = 1,
        maxRetriesPerRequest = 0,
        idleTimeout = 0,
        callback = { req, _ ->
            responseRef.set(req.response as? String)
            latch.countDown()
            false
        },
        readCallback = null
    )
    engine.setOutput(ConsolePrinter())

    try {
        val normalizedRequest = normalizeHttp2RequestForEngine(service, requestText)
        engine.start(10)
        engine.queue(normalizedRequest)
        val received = latch.await(15, TimeUnit.SECONDS)
        if (!received) {
            throw ConnectException("No HTTP/2 response within timeout")
        }
    } catch (ex: Exception) {
        errorRef.set(ex)
    } finally {
        runCatching { engine.cancel() }
    }

    if (shouldCancel()) {
        throw InterruptedException("Request cancelled")
    }

    val error = errorRef.get()
    if (error != null) {
        throw error
    }

    val rawResponse = responseRef.get() ?: throw ConnectException("No HTTP/2 response")
    return formatHttp2ResponseForDisplay(rawResponse)
}

internal fun normalizeHttp2RequestForEngine(service: HttpService, requestText: String): String {
    val normalized = normalizeRequestText(requestText)
    val parsed = splitMessage(normalized)
    val lines = parsed.headers.split(HEADER_LINE_SPLIT).filter { it.isNotBlank() }
    if (lines.isEmpty()) {
        return normalized
    }

    val requestLineParts = lines.first().split(" ", limit = 3)
    if (requestLineParts.size < 2) {
        return normalized
    }

    val method = requestLineParts[0]
    val rawTarget = requestLineParts[1]
    val path = normalizeHttp2Path(rawTarget)

    val forbiddenHopByHop = setOf("connection", "proxy-connection", "keep-alive", "upgrade", "transfer-encoding")
    val retainedHeaders = ArrayList<String>()
    var hasHost = false
    for (i in 1 until lines.size) {
        val line = lines[i]
        val name = line.substringBefore(':', "").trim().lowercase()
        if (name.isBlank()) {
            continue
        }
        if (name in forbiddenHopByHop) {
            continue
        }
        if (name == "te" && !line.substringAfter(':', "").trim().equals("trailers", ignoreCase = true)) {
            continue
        }
        if (name == "host") {
            hasHost = true
        }
        retainedHeaders.add(line)
    }

    if (!hasHost) {
        val hostHeader = if (service.port == 443 || service.port == 80) {
            service.host
        } else {
            "${service.host}:${service.port}"
        }
        retainedHeaders.add(0, "Host: $hostHeader")
    }

    val rebuiltHeaders = StringBuilder()
    rebuiltHeaders.append(method).append(' ').append(path).append(" HTTP/2\r\n")
    for (header in retainedHeaders) {
        rebuiltHeaders.append(header).append("\r\n")
    }

    return if (parsed.body.isEmpty()) {
        rebuiltHeaders.append("\r\n").toString()
    } else {
        rebuiltHeaders.append("\r\n").append(parsed.body).toString()
    }
}

private fun normalizeHttp2Path(target: String): String {
    if (target.isBlank()) {
        return "/"
    }
    if (target.startsWith("/")) {
        return target
    }
    if (target.startsWith("http://") || target.startsWith("https://")) {
        return try {
            val uri = URI(target)
            val p = if (uri.rawPath.isNullOrBlank()) "/" else uri.rawPath
            if (uri.rawQuery.isNullOrBlank()) p else "$p?${uri.rawQuery}"
        } catch (_: Exception) {
            target
        }
    }
    return target
}

internal fun formatHttp2ResponseForDisplay(rawResponse: String): String {
    if (!rawResponse.contains(":status:", ignoreCase = true)) {
        return rawResponse
    }
    val normalized = normalizeRequestText(rawResponse)
    val split = normalized.split("\r\n\r\n", limit = 2)
    val headerBlock = split.firstOrNull().orEmpty()
    val body = if (split.size > 1) split[1] else ""

    val lines = headerBlock.split("\r\n").filter { it.isNotBlank() }.toMutableList()
    val statusIndex = lines.indexOfFirst { it.startsWith(":status:", ignoreCase = true) }
    if (statusIndex < 0) {
        return normalized
    }

    val statusCode = lines[statusIndex].substringAfter(':').substringAfter(':').trim().toIntOrNull()
    if (statusCode == null) {
        return normalized
    }

    lines.removeAt(statusIndex)
    val reason = reasonPhraseForStatus(statusCode)
    val statusLine = if (reason.isEmpty()) {
        "HTTP/2 $statusCode"
    } else {
        "HTTP/2 $statusCode $reason"
    }

    val rebuiltHeaders = if (lines.isEmpty()) statusLine else statusLine + "\r\n" + lines.joinToString("\r\n")
    return if (body.isEmpty()) {
        rebuiltHeaders + "\r\n\r\n"
    } else {
        rebuiltHeaders + "\r\n\r\n" + body
    }
}

private fun reasonPhraseForStatus(code: Int): String {
    return when (code) {
        100 -> "Continue"
        101 -> "Switching Protocols"
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> ""
    }
}
