package org.jjgroup.xproxy

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.engine.http2.Frame
import org.jjgroup.xproxy.engine.http2.HTTP2Utils
import org.jjgroup.xproxy.engine.http.uncompressIfNecessary
import com.twitter.hpack.Decoder
import com.twitter.hpack.HeaderListener
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SpikeConnection(val engine: SpikeEngine) {

    val inflight = ConcurrentHashMap<Int, Request>()
    private val headerStrings = ConcurrentHashMap<Int, StringBuilder>()
    private val bodyStrings = ConcurrentHashMap<Int, StringBuilder>()
    private val gates = ConcurrentHashMap<String, Int>()
    private val decoder = Decoder(4096, 4096)
    val alive = AtomicBoolean(true)

    fun readForever(socket: Socket, output: OutputStream) {
        try {
            val input = socket.inputStream
            while (alive.get() && !Utils.unloaded) {
                val sizeBuffer = ByteArray(3)
                var haveRead = 0
                while (haveRead < 3) {
                    if (!alive.get()) return
                    val justRead = input.read(sizeBuffer, haveRead, 3 - haveRead)
                    if (justRead == -1) {
                        alive.set(false)
                        return
                    }
                    haveRead += justRead
                }

                val size = HTTP2Utils.threeByteInt(sizeBuffer)
                val needToRead = size + 6
                val frameBuffer = ByteArray(needToRead)
                haveRead = 0
                while (haveRead < needToRead) {
                    if (!alive.get()) return
                    val justRead = input.read(frameBuffer, haveRead, needToRead - haveRead)
                    if (justRead == -1) {
                        alive.set(false)
                        return
                    }
                    haveRead += justRead
                }

                // sizeBuffer(3B length) 已解析为 size;frameBuffer = type(1)+flags(1)+streamID(4)+payload(size),
                // 直接从 frameBuffer 读取,避免 sizeBuffer+frameBuffer 的整帧拷贝分配。
                val frameType = frameBuffer[0].toInt() and 0xFF
                val flags = frameBuffer[1].toInt() and 0xFF
                val streamID = HTTP2Utils.fourByteInt(frameBuffer.sliceArray(2..5))
                val payload = if (size > 0) frameBuffer.sliceArray(6 until 6 + size) else byteArrayOf()

                processFrame(frameType, flags, streamID, payload, output)
            }
        } catch (e: Exception) {
            if (alive.get()) {
                Utils.out("Spike read error: ${e.message}")
            }
        } finally {
            alive.set(false)
        }
    }

    private fun processFrame(frameType: Int, flags: Int, streamID: Int, payload: ByteArray, output: OutputStream) {
        val endStream = (flags and 0x01) != 0

        when (frameType) {
            0x04 -> { // SETTINGS
                if (payload.isNotEmpty()) {
                    val ack = Frame(0x04, 0x01, 0, byteArrayOf())
                    synchronized(output) {
                        output.write(ack.asBytes())
                        output.flush()
                    }
                }
            }
            0x06 -> { // PING
                val pong = Frame(0x06, 0x01, 0, payload)
                synchronized(output) {
                    output.write(pong.asBytes())
                    output.flush()
                }
            }
            0x08 -> { } // WINDOW_UPDATE - ignore
            0x07 -> { // GOAWAY
                Utils.out("Spike: received GOAWAY")
                alive.set(false)
            }
            0x03 -> { // RST_STREAM
                val time = System.nanoTime()
                val req = inflight[streamID] ?: return
                req.arrival = time
                trackGateOrder(req)
                prepareCallback(streamID)
            }
            0x01 -> { // HEADERS
                val time = System.nanoTime()
                val req = inflight[streamID] ?: return
                if (req.arrival == 0L) {
                    req.arrival = time
                    trackGateOrder(req)
                }

                val headerBuilder = headerStrings.computeIfAbsent(streamID) { StringBuilder() }
                val inputStream = ByteArrayInputStream(payload)
                val listener = HeaderListener { name, value, _ ->
                    val nameStr = String(name)
                    val valueStr = String(value)
                    if (nameStr == ":status") {
                        headerBuilder.append("HTTP/2 $valueStr OK\r\n")
                    } else {
                        headerBuilder.append("$nameStr: $valueStr\r\n")
                    }
                }
                synchronized(decoder) {
                    decoder.decode(inputStream, listener)
                    decoder.endHeaderBlock()
                }

                if (endStream) {
                    prepareCallback(streamID)
                }
            }
            0x00 -> { // DATA
                val bodyBuilder = bodyStrings.computeIfAbsent(streamID) { StringBuilder() }
                bodyBuilder.append(String(payload, Charsets.ISO_8859_1))

                if (endStream) {
                    prepareCallback(streamID)
                }
            }
        }
    }

    private fun trackGateOrder(req: Request) {
        if (req.gate != null) {
            val gateName = req.gate!!.name
            val seen = gates.getOrDefault(gateName, 0)
            req.order = seen
            gates[gateName] = seen + 1
        }
    }

    private fun prepareCallback(streamID: Int) {
        val headers = headerStrings.remove(streamID)?.toString() ?: ""
        val body = bodyStrings.remove(streamID)?.toString() ?: ""

        val resp = StringBuilder()
        if (headers.isEmpty()) {
            resp.append("null")
        } else {
            resp.append(headers)
            resp.append("\r\n")
        }

        resp.append(uncompressIfNecessary(headers, body))

        val req = inflight.remove(streamID)
            ?: throw RuntimeException("Couldn't find $streamID in inflight: ${inflight.keys().asSequence()}")
        req.response = resp.toString()
        engine.responseQueue.put(req)
    }
}
