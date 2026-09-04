package org.jjgroup.xproxy

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.engine.http2.Frame
import org.jjgroup.xproxy.engine.http2.H2Connection
import org.jjgroup.xproxy.engine.http2.H2Connection.Companion.buildReq
import org.jjgroup.xproxy.engine.http2.HTTP2Request
import org.jjgroup.xproxy.engine.http2.HTTP2Utils
import org.jjgroup.xproxy.engine.http2.HeaderEncoder
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class SpikeEngine(
    url: String,
    val threads: Int,
    maxQueueSize: Int,
    val requestsPerConnection: Int,
    override val maxRetriesPerRequest: Int,
    override var idleTimeout: Long = 0,
    override val callback: (Request, Boolean) -> Boolean,
    override var readCallback: ((String) -> Boolean)?,
    val warmLocalConnection: Boolean = true,
    val fatPacket: Boolean = false
) : RequestEngine() {

    val responseQueue = LinkedBlockingQueue<Request>(50)
    private val nextStreamID = AtomicInteger(1)
    // 每条(重)连接的 createSocket 原本都新建 SSLContext+init;TrustingTrustManager 无状态,惰性构造一次复用。
    private val trustingSslContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(TrustingTrustManager()), null) }
    }

    init {
        requestQueue = if (maxQueueSize > 0) {
            LinkedBlockingQueue(maxQueueSize)
        } else {
            LinkedBlockingQueue()
        }

        idleTimeout *= 1000
        target = URL(url)
        val retryQueue = LinkedBlockingQueue<Request>()

        completedLatch = CountDownLatch(threads)
        for (j in 1..threads) {
            thread { sendRequests(retryQueue) }
        }
        thread { processRequests() }
    }

    private fun processRequests() {
        while (!Utils.unloaded && !shouldAbandonAttack()) {
            val resp = responseQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            successfulRequests.getAndIncrement()

            // Wait for sent timestamp to be set
            while (resp.sent == 0L && !shouldAbandonAttack()) {
                Thread.sleep(10)
            }

            resp.time = (resp.arrival - resp.sent) / 1000  // microseconds
            resp.arrival = (resp.arrival - start) / 1000    // microseconds from attack start
            val interesting = processResponse(resp, resp.getResponseAsBytes()!!)
            invokeCallback(resp, interesting)
        }
    }

    private fun sendRequests(retryQueue: LinkedBlockingQueue<Request>) {
        var responseStreamHandler: SpikeConnection? = null

        while (!Utils.unloaded && !shouldAbandonAttack()) {
            val socket: Socket
            try {
                socket = createSocket()
            } catch (e: Exception) {
                Utils.out("Spike connection failed: ${e.message}")
                Thread.sleep(1000)
                continue
            }

            socket.soTimeout = 10000
            socket.tcpNoDelay = false
            responseStreamHandler = SpikeConnection(this)
            val connectionID = connections.incrementAndGet()

            val output = socket.outputStream

            // Send HTTP/2 connection preface
            val preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
            output.write(preface.toByteArray())
            val settingsPayload = byteArrayOf(
                0, 4, 0x7f, -1, -1, -1,   // MAXINT window size
                0, 2, 0, 0, 0, 0,          // no PUSH
                0, 1, 0, 0, 0x10, 0,       // 4096 header table size
                0, 3, 0, 0, 0x01, 0        // 256 max concurrent streams
            )
            val settingsFrame = Frame(0x04, 0x00, 0, settingsPayload)
            output.write(settingsFrame.asBytes())
            val windowFrame = Frame(0x08, 0x00, 0, HTTP2Utils.intToFourBytes(2147418112))
            output.write(windowFrame.asBytes())
            output.flush()

            // Start read thread
            thread { responseStreamHandler.readForever(socket, output) }

            var requestsSent = 0

            try {
                while (requestsSent < requestsPerConnection && !shouldAbandonAttack() && responseStreamHandler.alive.get()) {
                    // Wait if there are inflight requests (one at a time for non-gated)
                    if (responseStreamHandler.inflight.size >= 1) {
                        Thread.sleep(10)
                        continue
                    }

                    var req = retryQueue.poll()
                    if (req == null) {
                        req = requestQueue.poll(100, TimeUnit.MILLISECONDS)
                    }
                    if (req == null) {
                        if (attackState.get() == 2) {
                            waitForPendingRequests(responseStreamHandler)
                            return
                        }
                        continue
                    }

                    // Ungated request - send immediately
                    if (req.gate == null) {
                        val frames = reqToFrames(req)
                        responseStreamHandler.inflight[frames[0].streamID] = req
                        req.connectionID = connectionID
                        req.sent = System.nanoTime()
                        for (frame in frames) {
                            output.write(frame.asBytes())
                        }
                        output.flush()
                        requestsSent += 1
                        continue
                    }

                    // Gated request - collect all requests for this gate
                    val gatedReqs = ArrayList<Request>(10)
                    req.gate!!.reportReadyWithoutWaiting()
                    req.connectionID = connectionID
                    gatedReqs.add(req)

                    // Wait for all gated requests to be queued and inflight to drain
                    while ((!req.gate!!.fullyQueued.get() || responseStreamHandler.inflight.size != 0) && !shouldAbandonAttack()) {
                        Thread.sleep(10)
                    }

                    // Collect remaining gated requests
                    while (!req.gate!!.isOpen.get() && !shouldAbandonAttack()) {
                        val nextReq = requestQueue.poll(50, TimeUnit.MILLISECONDS)
                            ?: throw RuntimeException("Gate deadlock")
                        if (nextReq.gate!!.name != req.gate!!.name) {
                            throw RuntimeException("Over-read while waiting for gate to open")
                        }
                        nextReq.connectionID = connectionID
                        gatedReqs.add(nextReq)
                        if (nextReq.gate!!.reportReadyWithoutWaiting()) {
                            break
                        }
                    }

                    if (fatPacket) {
                        // Fat packet mode: bundle all frames together
                        val prepFrames = ArrayList<Frame>(gatedReqs.size * 2)
                        for (gatedReq in gatedReqs) {
                            val reqFrames = reqToFrames(gatedReq)
                            prepFrames.addAll(reqFrames)
                            responseStreamHandler.inflight[reqFrames[0].streamID] = gatedReq
                            requestsSent += 1
                        }

                        if (warmLocalConnection) {
                            val ping = Frame(0x06, 0x00, 0, "12345678".toByteArray())
                            output.write(ping.asBytes())
                            output.flush()
                        }

                        for (gatedReq in gatedReqs) {
                            gatedReq.sent = System.nanoTime()
                        }

                        // Write all frames in one batch
                        for (frame in prepFrames) {
                            output.write(frame.asBytes())
                        }
                        output.flush()
                    } else {
                        // Small frame mode: split headers from final data frames
                        val prepFrames = ArrayList<Frame>(gatedReqs.size)
                        val finalFrames = ArrayList<Pair<Frame, Long>>(gatedReqs.size)

                        for (gatedReq in gatedReqs) {
                            val reqFrames = reqToFrames(gatedReq)
                            for (frame in reqFrames) {
                                if ((frame.flags.toInt() and 0x01) != 0) { // END_STREAM
                                    finalFrames.add(Pair(frame, gatedReq.delayCompletion))
                                } else {
                                    prepFrames.add(frame)
                                }
                            }
                            responseStreamHandler.inflight[reqFrames[0].streamID] = gatedReq
                            requestsSent += 1
                        }

                        socket.tcpNoDelay = false

                        if (warmLocalConnection) {
                            val ping = Frame(0x06, 0x00, 0, "12345678".toByteArray())
                            output.write(ping.asBytes())
                            output.flush()
                        }

                        // Send prep frames (headers only)
                        for (frame in prepFrames) {
                            output.write(frame.asBytes())
                        }
                        output.flush()

                        Thread.sleep(100) // headstart

                        for (gatedReq in gatedReqs) {
                            gatedReq.sent = System.nanoTime()
                        }

                        if (warmLocalConnection) {
                            val ping = Frame(0x06, 0x00, 0, "12345678".toByteArray())
                            output.write(ping.asBytes())
                            output.flush()
                        }

                        // Send final frames (data with END_STREAM)
                        for (pair in finalFrames) {
                            if (pair.second != 0L) {
                                Thread.sleep(pair.second)
                            }
                            output.write(pair.first.asBytes())
                            output.flush()
                        }
                    }
                }
            } catch (ex: Exception) {
                if (responseStreamHandler.inflight.isNotEmpty()) {
                    for (inflightReq in responseStreamHandler.inflight.values) {
                        if (shouldRetry(inflightReq)) {
                            retryQueue.add(inflightReq)
                        }
                    }
                }
                ex.printStackTrace()
                Utils.out("Spike error: ${ex.message}")
                continue
            } finally {
                responseStreamHandler.alive.set(false)
            }
        }

        waitForPendingRequests(responseStreamHandler)
    }

    private fun reqToFrames(req: Request): List<Frame> {
        val parsedRequest = HTTP2Request(req.getRequest())
        val headerList = buildReq(parsedRequest, false)
        val encoder = HeaderEncoder()
        for ((key, value) in headerList) {
            encoder.addHeader(key, value)
        }

        val streamID = nextStreamID.getAndAdd(2)
        val headerBytes = encoder.headers.toByteArray()

        if (fatPacket) {
            if (parsedRequest.body.isNullOrEmpty()) {
                // HEADERS with END_HEADERS + END_STREAM (0x04 | 0x01 = 0x05)
                return listOf(Frame(0x01, 0x05, streamID, headerBytes))
            }
            // HEADERS with END_HEADERS, then DATA with END_STREAM
            return listOf(
                Frame(0x01, 0x04, streamID, headerBytes),
                Frame(0x00, 0x01, streamID, parsedRequest.body!!.toByteArray(Charsets.ISO_8859_1))
            )
        } else {
            // Small frame mode: HEADERS with END_HEADERS only, then minimal DATA with END_STREAM
            if (parsedRequest.body.isNullOrEmpty()) {
                return listOf(
                    Frame(0x01, 0x04, streamID, headerBytes),
                    Frame(0x00, 0x01, streamID, byteArrayOf())
                )
            }
            return listOf(
                Frame(0x01, 0x04, streamID, headerBytes),
                Frame(0x00, 0x01, streamID, "x".toByteArray())
            )
        }
    }

    private fun waitForPendingRequests(responseStreamHandler: SpikeConnection?) {
        for (x in 1..100) {
            if (responseStreamHandler != null && responseStreamHandler.inflight.isNotEmpty()) {
                Thread.sleep(100)
            } else {
                break
            }
        }
        completedLatch.countDown()
    }

    private fun createSocket(): Socket {
        val port = if (target.port == -1) target.defaultPort else target.port

        return if (target.protocol == "https") {
            val sslsf = trustingSslContext.socketFactory
            val sslSocket = sslsf.createSocket(target.host, port) as SSLSocket
            val sslp = sslSocket.sslParameters
            sslp.applicationProtocols = arrayOf("h2")
            sslSocket.sslParameters = sslp
            sslSocket.startHandshake()
            sslSocket
        } else {
            Socket(target.host, port)
        }
    }

    override fun start(timeout: Int) {
        attackState.set(1)
        start = System.nanoTime()
    }

    override fun buildRequest(template: String, payloads: List<String?>, learnBoring: Int?, label: String): Request {
        return Request(template, payloads, learnBoring ?: 0, label)
    }

    private class TrustingTrustManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate>? = null
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    }
}
