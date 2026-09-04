package org.jjgroup.xproxy.engine.http

import org.jjgroup.xproxy.HttpRequestEngine
import org.jjgroup.xproxy.core.Utils
import com.github.luben.zstd.ZstdInputStream
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.ArrayList
import java.util.HashSet
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

fun createTrustingSSLSocketFactory(engine: HttpRequestEngine): SSLSocketFactory {
    val trustingSslContext = SSLContext.getInstance("TLS")
    trustingSslContext.init(null, arrayOf<TrustManager>(TrustingTrustManager(engine)), null)
    return trustingSslContext.socketFactory
}

fun uncompressIfNecessary(headers: String, body: String): String {
    if ("content-encoding: " !in headers.lowercase()) {
        return detectAndUncompressWithoutHeader(body)
    }
    val lowerHeaders = headers.lowercase()
    return when {
        lowerHeaders.contains("content-encoding: gzip") -> ungzip(body.toByteArray(Charsets.ISO_8859_1))
        lowerHeaders.contains("content-encoding: deflate") -> inflate(body.toByteArray(Charsets.ISO_8859_1))
        lowerHeaders.contains("content-encoding: zstd") || lowerHeaders.contains("content-encoding: zst") -> zstd(body.toByteArray(Charsets.ISO_8859_1))
        lowerHeaders.contains("content-encoding: br") -> brotli(body.toByteArray(Charsets.ISO_8859_1))

        else -> body
    }
}

private fun detectAndUncompressWithoutHeader(body: String): String {
    val compressed = body.toByteArray(Charsets.ISO_8859_1)
    if (compressed.size < 2) {
        return body
    }

    if (looksLikeGzip(compressed)) {
        tryUngzip(compressed)?.let { return it }
    }

    if (looksLikeZlib(compressed)) {
        (tryInflate(compressed, nowrap = false) ?: tryInflate(compressed, nowrap = true))?.let { return it }
    }

    if (looksLikeZstd(compressed)) {
        tryZstd(compressed)?.let { return it }
    }

    tryBrotli(compressed)?.let { return it }

    return body
}

private fun looksLikeGzip(bytes: ByteArray): Boolean {
    return bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B
}

private fun looksLikeZlib(bytes: ByteArray): Boolean {
    if (bytes.size < 2) {
        return false
    }
    val cmf = bytes[0].toInt() and 0xFF
    val flg = bytes[1].toInt() and 0xFF
    if ((cmf and 0x0F) != 8) {
        return false
    }
    val header = (cmf shl 8) or flg
    return header % 31 == 0
}

private fun looksLikeZstd(bytes: ByteArray): Boolean {
    if (bytes.size < 4) {
        return false
    }
    return (bytes[0].toInt() and 0xFF) == 0x28 &&
        (bytes[1].toInt() and 0xFF) == 0xB5 &&
        (bytes[2].toInt() and 0xFF) == 0x2F &&
        (bytes[3].toInt() and 0xFF) == 0xFD
}

private fun tryUngzip(compressed: ByteArray): String? {
    return try {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { stream ->
            val buffer = ByteArray(1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) {
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        String(out.toByteArray(), Charsets.ISO_8859_1)
    } catch (_: Exception) {
        null
    }
}

private fun tryInflate(compressed: ByteArray, nowrap: Boolean): String? {
    return try {
        val out = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(compressed), Inflater(nowrap)).use { stream ->
            val buffer = ByteArray(1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) {
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        String(out.toByteArray(), Charsets.ISO_8859_1)
    } catch (_: Exception) {
        null
    }
}

private fun tryBrotli(compressed: ByteArray): String? {
    return try {
        val out = ByteArrayOutputStream()
        BrotliInputStream(ByteArrayInputStream(compressed)).use { stream ->
            val buffer = ByteArray(1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) {
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        String(out.toByteArray(), Charsets.ISO_8859_1)
    } catch (_: Exception) {
        null
    }
}

private fun tryZstd(compressed: ByteArray): String? {
    return try {
        val out = ByteArrayOutputStream()
        ZstdInputStream(ByteArrayInputStream(compressed)).use { stream ->
            val buffer = ByteArray(1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) {
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        String(out.toByteArray(), Charsets.ISO_8859_1)
    } catch (_: Exception) {
        null
    }
}

fun ungzip(compressed: ByteArray): String {
    if (compressed.isEmpty()) {
        return ""
    }

    val out = ByteArrayOutputStream()
    try {
        GZIPInputStream(ByteArrayInputStream(compressed)).use { unzipped ->
            while (true) {
                val bytes = ByteArray(1024)
                val read = unzipped.read(bytes, 0, 1024)
                if (read <= 0) {
                    break
                }
                out.write(bytes, 0, read)
            }
        }
    } catch (e: IOException) {
        Utils.err("GZIP decompression failed - possible partial response. Using undecompressed bytes instead.")
        return String(compressed, Charsets.ISO_8859_1)
    }
    return String(out.toByteArray(), Charsets.ISO_8859_1)
}

fun inflate(compressed: ByteArray): String {
    if (compressed.isEmpty()) {
        return ""
    }

    val out = ByteArrayOutputStream()
    try {
        InflaterInputStream(ByteArrayInputStream(compressed)).use { stream ->
            val buffer = ByteArray(1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) {
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    } catch (e: Exception) {
        try {
            out.reset()
            InflaterInputStream(ByteArrayInputStream(compressed), Inflater(true)).use { stream ->
                val buffer = ByteArray(1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    out.write(buffer, 0, read)
                }
            }
            return String(out.toByteArray(), Charsets.ISO_8859_1)
        } catch (ex: Exception) {
            Utils.err("Deflate decompression failed - using raw bytes")
            return String(compressed, Charsets.ISO_8859_1)
        }
    }
}

fun brotli(compressed: ByteArray): String {
    if (compressed.isEmpty()) {
        return ""
    }

    tryBrotli(compressed)?.let { return it }

    Utils.err("Brotli decompression failed - using raw bytes")
    return String(compressed, Charsets.ISO_8859_1)
}

fun zstd(compressed: ByteArray): String {
    if (compressed.isEmpty()) {
        return ""
    }

    tryZstd(compressed)?.let { return it }

    Utils.err("Zstd decompression failed - using raw bytes")
    return String(compressed, Charsets.ISO_8859_1)
}

fun waitForData(socket: Socket, pauseTime: Int, readSize: Int, explodeOnEarlyRead: Boolean): String {
    val oldTimeout = socket.soTimeout
    socket.soTimeout = pauseTime
    var len = -1
    val readBuffer = ByteArray(readSize)
    try {
        len = socket.getInputStream().read(readBuffer)
    } catch (e: Exception) {
    }
    socket.soTimeout = oldTimeout
    if (explodeOnEarlyRead && len != -1) {
        throw IllegalStateException()
    }
    if (len != -1) {
        return Utils.bytesToString(readBuffer.copyOfRange(0, len))
    }
    return ""
}

fun getContentLength(buf: String): Int {
    val cstart = buf.indexOf("Content-Length: ") + 16
    if (cstart == 15) {
        return -1
    }
    val cend = buf.indexOf("\r", cstart)
    try {
        return buf.substring(cstart, cend).trim().toInt()
    } catch (e: NumberFormatException) {
        throw RuntimeException("Can't parse content length in $buf")
    }
}

data class ChunkResult(val skip: Int, val length: Int)

fun getNextChunkLength(buf: String): ChunkResult {
    if (buf.isEmpty()) {
        return ChunkResult(-1, -1)
    }
    val chunkLengthStart = 0
    val chunkLengthEnd = buf.indexOf("\r\n")
    if (chunkLengthEnd == -1) {
        return ChunkResult(-1, -1)
    }
    try {
        val skip = 2 + chunkLengthEnd - chunkLengthStart
        return ChunkResult(skip, buf.substring(chunkLengthStart, chunkLengthEnd).trim().toInt(16) + skip)
    } catch (e: NumberFormatException) {
        throw RuntimeException("Can't parse followup chunk length '${buf.substring(chunkLengthStart, chunkLengthEnd)}' in $buf")
    }
}

private class TrustingTrustManager(private val engine: HttpRequestEngine) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate>? {
        return null
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val altNames = chain[0].subjectAlternativeNames ?: return
        altNames.forEach { engine.domains.add(it[1].toString()) }
    }
}

fun cleanupHttpEngineResources(domains: HashSet<String>, threadPool: ArrayList<Thread>) {
    domains.clear()
    threadPool.filter { it.isAlive }.forEach { runCatching { it.interrupt(); it.join(1000) } }
    threadPool.clear()
}
