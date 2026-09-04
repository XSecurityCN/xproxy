package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.core.Utils

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.Socket
import java.net.SocketTimeoutException

// 读响应/规范化请求热路径上的正则,预编译避免重复 Pattern.compile。
private val TRANSFER_ENCODING_CHUNKED_REGEX = Regex("(?im)^transfer-encoding\\s*:\\s*.*chunked.*$")
private val CONTENT_LENGTH_REGEX = Regex("(?im)^content-length\\s*:\\s*(\\d+)")

// SSE 流式捕获上限:避免长连接流式响应无限增长;与 proxy 侧 SSE_CAPTURE_MAX_BYTES 一致。
private const val MAX_SSE_CAPTURE_BYTES: Int = 1024 * 1024
// onProgress 节流:SSE 事件密集时避免每事件都重建完整响应字符串(O(n²))。
private const val SSE_PROGRESS_THROTTLE_MS: Long = 100

internal fun readHttpResponse(
    socket: Socket,
    requestMethod: String,
    shouldCancel: () -> Boolean,
    onProgress: ((String) -> Unit)? = null
): ByteArray {
    val input = socket.getInputStream()
    val timeoutMs = 10000L
    val idleTimeoutMs = 500L
    val deadline = System.currentTimeMillis() + timeoutMs
    socket.soTimeout = 250

    val headerBytes = readHeaders(input, deadline, shouldCancel)
    val headerText = String(headerBytes, Charsets.ISO_8859_1)
    val statusCode = headerText.lineSequence().firstOrNull()
        ?.split(' ')
        ?.getOrNull(1)
        ?.toIntOrNull()

    val lowerHeaders = headerText.lowercase()
    val isChunked = TRANSFER_ENCODING_CHUNKED_REGEX.containsMatchIn(lowerHeaders)
    val contentLength = CONTENT_LENGTH_REGEX.find(headerText)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    val noBodyByStatus = statusCode != null && (statusCode in 100..199 || statusCode == 204 || statusCode == 304)
    val noBodyByMethod = requestMethod.equals("HEAD", ignoreCase = true)
    if (noBodyByStatus || noBodyByMethod) {
        return headerBytes
    }

    val responseBytes = ByteArrayOutputStream()
    responseBytes.write(headerBytes)

    // SSE(text/event-stream)且请求方提供了 onProgress:按 chunk 实时回传,不走 10s 超时(覆盖客户端关闭/cancel)。
    val streamSse = onProgress != null && lowerHeaders.contains("text/event-stream")
    if (streamSse) {
        requireNotNull(onProgress).invoke(headerText)
        when {
            isChunked -> readSseChunkedStreaming(input, responseBytes, shouldCancel, onProgress!!)
            contentLength != null -> readFixedBody(input, responseBytes, contentLength, deadline, shouldCancel)
            else -> readSseUntilCloseStreaming(input, responseBytes, shouldCancel, onProgress!!)
        }
        onProgress!!.invoke(Utils.bytesToString(responseBytes.toByteArray()))
        return responseBytes.toByteArray()
    }

    when {
        isChunked -> readChunkedBody(input, responseBytes, deadline, shouldCancel)
        contentLength != null -> readFixedBody(input, responseBytes, contentLength, deadline, shouldCancel)
        else -> readBodyUntilIdle(input, responseBytes, deadline, idleTimeoutMs, shouldCancel)
    }

    return responseBytes.toByteArray()
}

/**
 * SSE chunked 流式读取(去分块):仅写入 chunk 数据,丢弃分块框架(size 行 `5a\r\n` 与每块尾的 `\r\n`、
 * 结束块的 trailers),使显示的 body 不含 `5a` 等传输编码噪声(与 proxy 侧 netty 去分块一致)。
 * 复用既有 readLineRaw/readExactToOutput(Long.MAX_VALUE deadline,不触发 10s 超时,仅按 shouldCancel 中断),
 * 每 chunk 回调 onProgress(节流),上限 MAX_SSE_CAPTURE_BYTES 截断。EOF(服务端关闭)与 cancel 视为流结束。
 */
private fun readSseChunkedStreaming(
    input: InputStream,
    out: ByteArrayOutputStream,
    shouldCancel: () -> Boolean,
    onProgress: (String) -> Unit
) {
    val noDeadline = Long.MAX_VALUE
    var lastProgressAt = 0L
    try {
        while (true) {
            if (shouldCancel()) return
            val chunkLine = readLineRaw(input, noDeadline, shouldCancel) // size 行,丢弃
            val lineText = String(chunkLine, Charsets.ISO_8859_1).trim().substringBefore(';').trim()
            val chunkSize = lineText.toIntOrNull(16)
            if (chunkSize == null) {
                return
            }
            if (chunkSize == 0) {
                // 结束块:丢弃 trailers(通常仅末尾 \r\n)
                while (true) {
                    val trailerLine = readLineRaw(input, noDeadline, shouldCancel)
                    val trailer = String(trailerLine, Charsets.ISO_8859_1).trimEnd('\r', '\n')
                    if (trailer.isEmpty()) {
                        break
                    }
                }
                return
            }
            readExactToOutput(input, out, chunkSize, noDeadline, shouldCancel) // 仅数据
            readExactDiscard(input, 2, noDeadline, shouldCancel) // 丢弃块尾 \r\n
            if (out.size() >= MAX_SSE_CAPTURE_BYTES) {
                out.write("\n\n[... SSE stream truncated at $MAX_SSE_CAPTURE_BYTES bytes ...]".toByteArray(Charsets.ISO_8859_1))
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastProgressAt >= SSE_PROGRESS_THROTTLE_MS) {
                lastProgressAt = now
                onProgress(Utils.bytesToString(out.toByteArray()))
            }
        }
    } catch (_: InterruptedException) {
        // 用户取消
    } catch (_: ConnectException) {
        // EOF:服务端关闭流
    }
}

private fun readExactDiscard(input: InputStream, length: Int, deadline: Long, shouldCancel: () -> Boolean) {
    val buffer = ByteArray(minOf(length, 8192))
    var remaining = length
    while (remaining > 0) {
        val read = readWithDeadline(input, buffer, 0, minOf(buffer.size, remaining), deadline, shouldCancel)
        if (read <= 0) {
            throw ConnectException("Unexpected EOF while reading chunked body")
        }
        remaining -= read
    }
}

/** SSE 非分块(读至连接关闭)流式读取。 */
private fun readSseUntilCloseStreaming(
    input: InputStream,
    out: ByteArrayOutputStream,
    shouldCancel: () -> Boolean,
    onProgress: (String) -> Unit
) {
    val buffer = ByteArray(8192)
    var lastProgressAt = 0L
    try {
        while (true) {
            if (shouldCancel()) return
            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                continue // soTimeout:SSE 事件间可能 idle,继续等待(由 cancel/EOF 终止)
            }
            if (read <= 0) return // EOF:服务端关闭
            out.write(buffer, 0, read)
            if (out.size() >= MAX_SSE_CAPTURE_BYTES) {
                out.write("\n\n[... SSE stream truncated at $MAX_SSE_CAPTURE_BYTES bytes ...]".toByteArray(Charsets.ISO_8859_1))
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastProgressAt >= SSE_PROGRESS_THROTTLE_MS) {
                lastProgressAt = now
                onProgress(Utils.bytesToString(out.toByteArray()))
            }
        }
    } catch (_: InterruptedException) {
        // 用户取消
    } catch (_: ConnectException) {
        // 连接关闭
    }
}

private fun readHeaders(input: InputStream, deadline: Long, shouldCancel: () -> Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    var b0 = -1
    var b1 = -1
    var b2 = -1
    var b3 = -1
    while (true) {
        if (shouldCancel()) {
            throw InterruptedException("Request cancelled")
        }
        val b = readByteWithDeadline(input, deadline, shouldCancel)
        out.write(b)
        b0 = b1
        b1 = b2
        b2 = b3
        b3 = b
        val hasCrlfTerminator = b0 == '\r'.code && b1 == '\n'.code && b2 == '\r'.code && b3 == '\n'.code
        val bytes = out.toByteArray()
        val hasLfTerminator = bytes.size >= 2 && bytes[bytes.size - 2] == '\n'.code.toByte() && bytes[bytes.size - 1] == '\n'.code.toByte()
        if (hasCrlfTerminator || hasLfTerminator) {
            return bytes
        }
        if (out.size() > 262144) {
            throw ConnectException("Response headers too large")
        }
    }
}

private fun readFixedBody(input: InputStream, out: ByteArrayOutputStream, length: Int, deadline: Long, shouldCancel: () -> Boolean) {
    var remaining = length
    val buffer = ByteArray(8192)
    while (remaining > 0) {
        if (shouldCancel()) {
            throw InterruptedException("Request cancelled")
        }
        val read = readWithDeadline(input, buffer, 0, minOf(buffer.size, remaining), deadline, shouldCancel)
        if (read <= 0) {
            throw ConnectException("Unexpected EOF while reading response body")
        }
        out.write(buffer, 0, read)
        remaining -= read
    }
}

private fun readChunkedBody(input: InputStream, out: ByteArrayOutputStream, deadline: Long, shouldCancel: () -> Boolean) {
    while (true) {
        val chunkLine = readLineRaw(input, deadline, shouldCancel)
        out.write(chunkLine)
        val lineText = String(chunkLine, Charsets.ISO_8859_1).trim().substringBefore(';').trim()
        val chunkSize = lineText.toIntOrNull(16)
            ?: throw ConnectException("Invalid chunk size: $lineText")
        if (chunkSize == 0) {
            while (true) {
                val trailerLine = readLineRaw(input, deadline, shouldCancel)
                out.write(trailerLine)
                val trailer = String(trailerLine, Charsets.ISO_8859_1).trimEnd('\r', '\n')
                if (trailer.isEmpty()) {
                    return
                }
            }
        }
        readExactToOutput(input, out, chunkSize + 2, deadline, shouldCancel)
    }
}

private fun readBodyUntilIdle(
    input: InputStream,
    out: ByteArrayOutputStream,
    deadline: Long,
    idleTimeoutMs: Long,
    shouldCancel: () -> Boolean
) {
    val buffer = ByteArray(8192)
    var lastReadAt = System.currentTimeMillis()
    while (true) {
        if (shouldCancel()) {
            throw InterruptedException("Request cancelled")
        }
        try {
            val read = input.read(buffer)
            if (read <= 0) {
                return
            }
            out.write(buffer, 0, read)
            lastReadAt = System.currentTimeMillis()
        } catch (_: SocketTimeoutException) {
            val now = System.currentTimeMillis()
            if (now > deadline) {
                return
            }
            if (out.size() > 0 && now - lastReadAt >= idleTimeoutMs) {
                return
            }
        }
    }
}

private fun readLineRaw(input: InputStream, deadline: Long, shouldCancel: () -> Boolean): ByteArray {
    val out = ByteArrayOutputStream()
    while (true) {
        val b = readByteWithDeadline(input, deadline, shouldCancel)
        out.write(b)
        if (b == '\n'.code) {
            return out.toByteArray()
        }
        if (out.size() > 65536) {
            throw ConnectException("Response line too long")
        }
    }
}

private fun readExactToOutput(input: InputStream, out: ByteArrayOutputStream, length: Int, deadline: Long, shouldCancel: () -> Boolean) {
    var remaining = length
    val buffer = ByteArray(8192)
    while (remaining > 0) {
        val read = readWithDeadline(input, buffer, 0, minOf(buffer.size, remaining), deadline, shouldCancel)
        if (read <= 0) {
            throw ConnectException("Unexpected EOF while reading chunked body")
        }
        out.write(buffer, 0, read)
        remaining -= read
    }
}

private fun readByteWithDeadline(input: InputStream, deadline: Long, shouldCancel: () -> Boolean): Int {
    val one = ByteArray(1)
    val read = readWithDeadline(input, one, 0, 1, deadline, shouldCancel)
    if (read <= 0) {
        throw ConnectException("Unexpected EOF while reading response")
    }
    return one[0].toInt() and 0xFF
}

private fun readWithDeadline(
    input: InputStream,
    buffer: ByteArray,
    offset: Int,
    length: Int,
    deadline: Long,
    shouldCancel: () -> Boolean
): Int {
    while (true) {
        if (shouldCancel()) {
            throw InterruptedException("Request cancelled")
        }
        try {
            return input.read(buffer, offset, length)
        } catch (_: SocketTimeoutException) {
            if (System.currentTimeMillis() > deadline) {
                throw SocketTimeoutException("Timed out while reading response")
            }
        }
    }
}
