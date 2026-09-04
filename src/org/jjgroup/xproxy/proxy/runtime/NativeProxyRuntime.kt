package org.jjgroup.xproxy.proxy.runtime

import org.jjgroup.xproxy.proxy.core.ProtocolPolicy
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.portal.ProxyPortal
import org.jjgroup.xproxy.proxy.portal.ProxyPortalResult
import org.jjgroup.xproxy.proxy.runtime.native.h2.NativeHttp2UpstreamClient
import org.jjgroup.xproxy.settings.core.UpstreamProxySettings
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class NativeProxyRuntime(
    private val onBeforeRequestRewrite: ((String, String, Boolean) -> String)? = null,
    private val onAfterResponseRewrite: ((String, String, String, Boolean) -> String)? = null,
    private val protocolPolicyProvider: () -> ProtocolPolicy = { ProtocolPolicy.preserve() },
    private val onHistoryAdded: ((ProxyHistoryEntry) -> Unit)? = null,
    private val nextHistoryId: () -> Long = { System.nanoTime() },
    private val onRunningChanged: ((Boolean, String) -> Unit)? = null
) : ProxyRuntime {
    private val running = AtomicBoolean(false)
    private val openSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var currentBindHost: String? = null

    @Volatile
    private var currentBindPort: Int = -1


    override fun start(bindHost: String, bindPort: Int, handleSsl: Boolean) {
        if (!running.compareAndSet(false, true)) return
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(bindHost, bindPort))
            server.soTimeout = ACCEPT_SO_TIMEOUT_MS
            serverSocket = server
            currentBindHost = bindHost
            currentBindPort = bindPort
            onRunningChanged?.invoke(true, "Listening on $bindHost:$bindPort")
            acceptThread = thread(name = "native-proxy-accept-$bindHost:$bindPort", isDaemon = true) {
                acceptLoop(server, handleSsl)
            }
        } catch (ex: Exception) {
            running.set(false)
            closeQuietly(serverSocket)
            serverSocket = null
            onRunningChanged?.invoke(false, "Failed to start proxy: ${ex.message}")
            throw ex
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        closeQuietly(serverSocket)
        serverSocket = null
        currentBindHost = null
        currentBindPort = -1
        acceptThread?.interrupt()
        acceptThread = null
        for (socket in openSockets.toList()) {
            closeQuietly(socket)
        }
        openSockets.clear()
        onRunningChanged?.invoke(false, "Proxy stopped")
    }

    override fun isRunning(): Boolean = running.get()

    private fun acceptLoop(server: ServerSocket, handleSsl: Boolean) {
        while (running.get()) {
            try {
                val client = server.accept()
                registerSocket(client)
                thread(name = "native-proxy-client-${client.remoteSocketAddress}", isDaemon = true) {
                    handleClient(client, handleSsl)
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun handleClient(client: Socket, handleSsl: Boolean) {
        try {
            client.soTimeout = CLIENT_READ_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val request = readHttpRequest(input) ?: return
            val parsedLine = NativeProxyParsing.parseRequestLine(request.requestLine)
            if (parsedLine == null) {
                writeSimpleResponse(output, 400, "Bad Request", "Invalid request line")
                return
            }
            if (parsedLine.method.equals("CONNECT", ignoreCase = true)) {
                if (!handleSsl) {
                    writeSimpleResponse(output, 501, "Not Implemented", "CONNECT disabled")
                    return
                }
                handleConnectTunnel(request, input, output, parsedLine.target)
                return
            }
            handleHttpForward(request, parsedLine, output)
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        } finally {
            unregisterSocket(client)
            closeQuietly(client)
        }
    }

    private fun handleHttpForward(request: NativeParsedHttpRequest, line: NativeProxyParsing.RequestLine, clientOutput: OutputStream) {
        var effectiveLine = line
        var effectiveRequest = request
        val portal = ProxyPortal.handleRequest(
            effectiveLine.method,
            effectiveLine.target,
            headerValue(effectiveRequest.headers, "Host"),
            currentBindHost,
            currentBindPort
        )
        if (portal != null) {
            clientOutput.write(serializePortalResult(portal))
            clientOutput.flush()
            return
        }
        val originalRequestRaw = request.toRaw(line)
        var requestWasRewritten = false
        val upstreamTargetInitial = NativeProxyParsing.resolveUpstreamTarget(line, request.headers)
        if (upstreamTargetInitial != null) {
            val originalRaw = request.toRaw(effectiveLine)
            val rewritten = onBeforeRequestRewrite?.invoke(originalRaw, upstreamTargetInitial.host, false)
            if (!rewritten.isNullOrBlank() && rewritten != originalRaw) {
                val rewrittenParsed = NativeProxyParsing.parseRawRequest(rewritten)
                if (rewrittenParsed != null) {
                    effectiveLine = rewrittenParsed.first
                    effectiveRequest = rewrittenParsed.second
                    requestWasRewritten = true
                }
            }
        }

        val upstreamTarget = NativeProxyParsing.resolveUpstreamTarget(effectiveLine, effectiveRequest.headers)
        if (upstreamTarget == null) {
            writeSimpleResponse(clientOutput, 400, "Bad Request", "Unable to resolve upstream target")
            return
        }
        if (upstreamTarget.scheme == "https" && !protocolPolicyProvider().allowHttp2Downgrade()) {
            try {
                val requestRaw = effectiveRequest.toRaw(effectiveLine)
                val response = createHttp2Client().sendRawRequest(
                    rawRequest = requestRaw,
                    scheme = "https",
                    authority = NativeProxyParsing.hostHeaderValue(upstreamTarget.host, upstreamTarget.port, "https")
                )
                val rawResponse = response.toRawResponse()
                val rewrittenResponse = onAfterResponseRewrite?.invoke(
                    requestRaw,
                    rawResponse,
                    upstreamTarget.host,
                    true
                )
                val responseWasRewritten = !rewrittenResponse.isNullOrBlank() && rewrittenResponse != rawResponse
                val payload = (rewrittenResponse ?: rawResponse).toByteArray(Charsets.ISO_8859_1)
                clientOutput.write(payload)
                clientOutput.flush()
                emitHistory(
                    requestRaw = response.recordedRequestRaw(requestRaw),
                    responseRaw = String(payload, Charsets.ISO_8859_1),
                    originalRequestRaw = if (requestWasRewritten) originalRequestRaw else "",
                    originalResponseRaw = if (responseWasRewritten) rawResponse else "",
                    method = effectiveLine.method,
                    host = effectiveRequest.headers.firstOrNull { it.first.equals("Host", true) }?.second
                        ?: NativeProxyParsing.hostHeaderValue(upstreamTarget.host, upstreamTarget.port, "https"),
                    path = effectiveLine.target,
                    tls = true,
                    protocol = response.historyProtocol(),
                    modified = requestWasRewritten || responseWasRewritten,
                    streamId = null,
                    wasDowngraded = false
                )
            } catch (_: Exception) {
                writeSimpleResponse(clientOutput, 502, "Bad Gateway", "HTTP/2 upstream failed")
            }
            return
        }
        if (upstreamTarget.scheme != "http") {
            writeSimpleResponse(clientOutput, 400, "Bad Request", "HTTPS absolute-form requires CONNECT")
            return
        }

        val headers = rewriteHeadersForUpstream(effectiveRequest.headers, upstreamTarget.host, upstreamTarget.port)
        val upstreamRequest = buildRequestBytes(effectiveLine.method, upstreamTarget.originForm, effectiveLine.version, headers, effectiveRequest.body)
        try {
            val upstream = Socket()
            registerSocket(upstream)
            try {
                upstream.soTimeout = UPSTREAM_READ_TIMEOUT_MS
                upstream.connect(InetSocketAddress(upstreamTarget.host, upstreamTarget.port), CONNECT_TIMEOUT_MS)
                val upstreamOut = upstream.getOutputStream()
                upstreamOut.write(upstreamRequest)
                upstreamOut.flush()
                val upstreamResponseBytes = readAllBytes(upstream.getInputStream(), MAX_RESPONSE_BYTES)
                var responseBytes = upstreamResponseBytes
                val responseRaw = upstreamResponseBytes.toString(Charsets.ISO_8859_1)
                val requestRaw = upstreamRequest.toString(Charsets.ISO_8859_1)
                val rewrittenResponse = onAfterResponseRewrite?.invoke(requestRaw, responseRaw, upstreamTarget.host, false)
                val responseWasRewritten = !rewrittenResponse.isNullOrBlank() && rewrittenResponse != responseRaw
                if (!rewrittenResponse.isNullOrBlank() && rewrittenResponse != responseRaw) {
                    responseBytes = rewrittenResponse.toByteArray(Charsets.ISO_8859_1)
                }
                clientOutput.write(responseBytes)
                clientOutput.flush()
                emitHistory(
                    requestRaw = requestRaw,
                    responseRaw = String(responseBytes, Charsets.ISO_8859_1),
                    originalRequestRaw = if (requestWasRewritten) originalRequestRaw else "",
                    originalResponseRaw = if (responseWasRewritten) responseRaw else "",
                    method = effectiveLine.method,
                    host = effectiveRequest.headers.firstOrNull { it.first.equals("Host", true) }?.second
                        ?: NativeProxyParsing.hostHeaderValue(upstreamTarget.host, upstreamTarget.port, "http"),
                    path = effectiveLine.target,
                    tls = false,
                    protocol = "http/1.1",
                    modified = requestWasRewritten || responseWasRewritten,
                    streamId = null,
                    wasDowngraded = false
                )
            } finally {
                unregisterSocket(upstream)
                closeQuietly(upstream)
            }
        } catch (_: Exception) {
            writeSimpleResponse(clientOutput, 502, "Bad Gateway", "Upstream connection failed")
        }
    }

    private fun createHttp2Client(): NativeHttp2UpstreamClient {
        return NativeHttp2UpstreamClient(upstreamProxy = UpstreamProxySettings.getEnabledProxy())
    }

    private fun handleConnectTunnel(connectRequest: NativeParsedHttpRequest, clientInput: InputStream, clientOutput: OutputStream, authority: String) {
        val endpoint = NativeProxyParsing.parseConnectAuthority(authority)
        if (endpoint == null) {
            writeSimpleResponse(clientOutput, 400, "Bad Request", "Invalid CONNECT authority")
            return
        }
        val upstream = Socket()
        registerSocket(upstream)
        try {
            upstream.connect(InetSocketAddress(endpoint.first, endpoint.second), CONNECT_TIMEOUT_MS)
            upstream.soTimeout = 0
            val connectResponseRaw = "HTTP/1.1 200 Connection Established\r\n\r\n"
            clientOutput.write(connectResponseRaw.toByteArray(Charsets.ISO_8859_1))
            clientOutput.flush()

            emitHistory(
                requestRaw = connectRequest.toRaw(
                    NativeProxyParsing.RequestLine(
                        method = "CONNECT",
                        target = authority,
                        version = "HTTP/1.1"
                    )
                ),
                responseRaw = connectResponseRaw,
                originalRequestRaw = "",
                originalResponseRaw = "",
                method = "CONNECT",
                host = authority,
                path = authority,
                tls = true,
                protocol = "http/2",
                modified = false,
                streamId = null,
                wasDowngraded = false
            )

            val upstreamInput = upstream.getInputStream()
            val upstreamOutput = upstream.getOutputStream()
            val forwardThread = thread(name = "native-proxy-connect-forward", isDaemon = true) {
                pipeUntilClose(clientInput, upstreamOutput)
                closeQuietly(upstreamOutput)
            }
            pipeUntilClose(upstreamInput, clientOutput)
            forwardThread.join(TUNNEL_JOIN_TIMEOUT_MS)
        } catch (_: Exception) {
            writeSimpleResponse(clientOutput, 502, "Bad Gateway", "CONNECT upstream failed")
        } finally {
            unregisterSocket(upstream)
            closeQuietly(upstream)
        }
    }

    private fun readHttpRequest(input: InputStream): NativeParsedHttpRequest? {
        var requestLine = readLine(input, MAX_REQUEST_LINE_LENGTH) ?: return null
        while (requestLine.isBlank()) {
            requestLine = readLine(input, MAX_REQUEST_LINE_LENGTH) ?: return null
        }

        val headers = mutableListOf<Pair<String, String>>()
        var totalHeaderChars = 0
        for (i in 0 until MAX_HEADER_COUNT) {
            val line = readLine(input, MAX_HEADER_LINE_LENGTH) ?: throw EOFException("Unexpected EOF while reading headers")
            if (line.isEmpty()) break
            totalHeaderChars += line.length
            if (totalHeaderChars > MAX_HEADER_CHARS) throw IllegalArgumentException("Headers too large")
            val idx = line.indexOf(':')
            if (idx <= 0) throw IllegalArgumentException("Malformed header")
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isEmpty()) throw IllegalArgumentException("Empty header name")
            headers += name to value
        }
        if (headers.size >= MAX_HEADER_COUNT) throw IllegalArgumentException("Too many headers")

        val transferEncoding = headerValue(headers, "Transfer-Encoding")
        if (transferEncoding?.contains("chunked", ignoreCase = true) == true) {
            throw IllegalArgumentException("Chunked requests are not supported")
        }

        val contentLength = headerValue(headers, "Content-Length")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (contentLength > MAX_BODY_BYTES) throw IllegalArgumentException("Request body too large")
        val body = if (contentLength == 0) ByteArray(0) else readFixedBytes(input, contentLength)
        return NativeParsedHttpRequest(requestLine = requestLine, headers = headers, body = body)
    }

    private fun rewriteHeadersForUpstream(headers: List<Pair<String, String>>, host: String, port: Int): List<Pair<String, String>> {
        val rewritten = mutableListOf<Pair<String, String>>()
        val hasHost = headers.any { it.first.equals("Host", ignoreCase = true) }
        for ((name, value) in headers) {
            val lower = name.lowercase(Locale.ROOT)
            if (lower == "proxy-connection" || lower == "proxy-authorization" || lower == "connection") continue
            rewritten += name to value
        }
        if (!hasHost) rewritten += "Host" to NativeProxyParsing.hostHeaderValue(host, port, "http")
        rewritten += "Connection" to "close"
        return rewritten
    }

    private fun buildRequestBytes(
        method: String,
        target: String,
        version: String,
        headers: List<Pair<String, String>>,
        body: ByteArray
    ): ByteArray {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(target).append(' ').append(version).append("\r\n")
        for ((name, value) in headers) {
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        sb.append("\r\n")
        val head = sb.toString().toByteArray(Charsets.ISO_8859_1)
        if (body.isEmpty()) return head
        val result = ByteArray(head.size + body.size)
        System.arraycopy(head, 0, result, 0, head.size)
        System.arraycopy(body, 0, result, head.size, body.size)
        return result
    }

    private fun readLine(input: InputStream, maxLen: Int): String? {
        val buffer = ByteArray(maxLen)
        var idx = 0
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (idx == 0) return null
                break
            }
            if (b == '\n'.code) break
            if (idx >= maxLen) throw IllegalArgumentException("Line too long")
            buffer[idx++] = b.toByte()
        }
        var line = String(buffer, 0, idx, Charsets.ISO_8859_1)
        if (line.endsWith("\r")) line = line.substring(0, line.length - 1)
        return line
    }

    private fun readFixedBytes(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read < 0) throw EOFException("Unexpected EOF while reading body")
            offset += read
        }
        return data
    }

    private fun copyFully(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(RELAY_BUFFER_SIZE)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read < 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    private fun readAllBytes(input: InputStream, maxBytes: Int): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0
        val buffer = ByteArray(RELAY_BUFFER_SIZE)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read < 0) {
                break
            }
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("Response exceeds max capture size")
            }
            chunks += buffer.copyOf(read)
        }
        val result = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }

    private fun pipeUntilClose(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(RELAY_BUFFER_SIZE)
        while (running.get()) {
            val read = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (read < 0) break
            try {
                output.write(buffer, 0, read)
                output.flush()
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun writeSimpleResponse(output: OutputStream, status: Int, reason: String, body: String) {
        val response = simpleResponseBytes(status, reason, body)
        try {
            output.write(response)
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun emitHistory(
        requestRaw: String,
        responseRaw: String,
        originalRequestRaw: String,
        originalResponseRaw: String,
        method: String,
        host: String,
        path: String,
        tls: Boolean,
        protocol: String,
        modified: Boolean,
        streamId: Int?,
        wasDowngraded: Boolean
    ) {
        val statusCode = parseStatusCode(responseRaw)
        val body = responseBody(responseRaw)
        val mimeType = parseMimeType(responseRaw, body)
        val title = extractTitle(body)
        onHistoryAdded?.invoke(
            ProxyHistoryEntry(
                id = nextHistoryId(),
                timeMillis = System.currentTimeMillis(),
                method = method,
                host = host,
                path = path,
                statusCode = statusCode,
                length = body.length, // body 为 ISO-8859-1 解码字符串,1 字符 == 1 字节,无需再 toByteArray 取长度
                mimeType = mimeType,
                title = title,
                tls = tls,
                modified = modified,
                tool = "proxy",
                requestRaw = requestRaw,
                responseRaw = responseRaw,
                originalRequestRaw = if (originalRequestRaw != requestRaw) originalRequestRaw else "",
                originalResponseRaw = if (originalResponseRaw != responseRaw) originalResponseRaw else "",
                protocol = protocol,
                streamId = streamId,
                wasDowngraded = wasDowngraded
            )
        )
    }

    private fun registerSocket(socket: Socket) {
        openSockets += socket
    }

    private fun unregisterSocket(socket: Socket) {
        openSockets -= socket
    }

    companion object {
        private const val ACCEPT_SO_TIMEOUT_MS = 1000
        private const val CLIENT_READ_TIMEOUT_MS = 30_000
        private const val UPSTREAM_READ_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TUNNEL_JOIN_TIMEOUT_MS = 1_000L
        private const val MAX_REQUEST_LINE_LENGTH = 8192
        private const val MAX_HEADER_LINE_LENGTH = 8192
        private const val MAX_HEADER_COUNT = 128
        private const val MAX_HEADER_CHARS = 64 * 1024
        private const val MAX_BODY_BYTES = 10 * 1024 * 1024
        private const val RELAY_BUFFER_SIZE = 8192
        private const val MAX_RESPONSE_BYTES = 25 * 1024 * 1024

        internal fun serializePortalResultForTest(result: ProxyPortalResult): ByteArray = serializePortalResult(result)

        internal fun simpleResponseForTest(status: Int, reason: String, body: String): ByteArray =
            simpleResponseBytes(status, reason, body)

        private fun simpleResponseBytes(status: Int, reason: String, body: String): ByteArray {
            return serializePortalResult(
                ProxyPortal.errorPage(
                    statusCode = status,
                    reason = reason,
                    title = body.ifBlank { reason },
                    phase = "native",
                    cause = RuntimeException(body)
                )
            )
        }

        private fun serializePortalResult(result: ProxyPortalResult): ByteArray {
            val head = StringBuilder()
                .append("HTTP/1.1 ").append(result.statusCode).append(' ').append(result.reason).append("\r\n")
            for ((name, value) in result.headers) {
                if (!name.equals("Content-Length", ignoreCase = true)) {
                    head.append(name).append(": ").append(value).append("\r\n")
                }
            }
            head.append("Content-Length: ").append(result.body.size).append("\r\n")
            head.append("\r\n")
            val headBytes = head.toString().toByteArray(Charsets.ISO_8859_1)
            return ByteArray(headBytes.size + result.body.size).also {
                System.arraycopy(headBytes, 0, it, 0, headBytes.size)
                System.arraycopy(result.body, 0, it, headBytes.size, result.body.size)
            }
        }
    }
}
