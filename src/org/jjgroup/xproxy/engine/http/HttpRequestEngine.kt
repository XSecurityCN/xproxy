package org.jjgroup.xproxy

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.engine.http.createTrustingSSLSocketFactory
import org.jjgroup.xproxy.engine.http.cleanupHttpEngineResources
import org.jjgroup.xproxy.engine.http.getContentLength
import org.jjgroup.xproxy.engine.http.getNextChunkLength
import org.jjgroup.xproxy.engine.http.uncompressIfNecessary
import org.jjgroup.xproxy.engine.http.waitForData
import org.jjgroup.xproxy.settings.core.UpstreamProxySettings
import org.jjgroup.xproxy.settings.core.UpstreamProxyProtocol

//import jdk.net.ExtendedSocketOptions
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import kotlin.IllegalStateException
import kotlin.concurrent.thread
import kotlin.math.pow

open class HttpRequestEngine(url: String, val threads: Int, maxQueueSize: Int, val readFreq: Int, val requestsPerConnection: Int, override val maxRetriesPerRequest: Int, override var idleTimeout: Long = 0, override val callback: (Request, Boolean) -> Boolean, var timeout: Int, override var readCallback: ((String) -> Boolean)?, val readSize: Int, val resumeSSL: Boolean, var explodeOnEarlyRead: Boolean = false): RequestEngine() {

    private val connectedLatch = CountDownLatch(threads)

    private val threadPool = ArrayList<Thread>()
    internal val upstreamProxy = UpstreamProxySettings.getEnabledProxy()

    var domains = HashSet<String>()

    init {

        internalSettings["ignoreLength"] = false

        idleTimeout *= 1000
        lastLife = System.currentTimeMillis()

        target = URL(url)

        if (UpstreamProxySettings.isEnabled() && upstreamProxy == null) {
            throw IllegalArgumentException("Upstream proxy is enabled but host/port is invalid")
        }

        requestQueue = if (maxQueueSize > 0) {
            LinkedBlockingQueue(maxQueueSize)
        }
        else {
            LinkedBlockingQueue()
        }

        completedLatch = CountDownLatch(threads)
        val retryQueue = LinkedBlockingQueue<Request>()
        val ipAddress = InetAddress.getByName(target.host)
        val port = if (target.port == -1) { target.defaultPort } else { target.port }

        val trustingSslSocketFactory = createTrustingSSLSocketFactory(this)

        Utils.err("Establishing $threads connection to $url ...");
        for(j in 1..threads) {
            threadPool.add(
                thread {
                    sendRequests(target, trustingSslSocketFactory, ipAddress, port, retryQueue, completedLatch, readFreq, requestsPerConnection, connectedLatch)
                }
            )
        }

    }

    // val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("localhost", 6574))

    override fun start(timeout: Int) {
        connectedLatch.await(timeout.toLong(), TimeUnit.SECONDS)
        attackState.set(1)
        start = System.nanoTime()
    }

    override fun buildRequest(template: String, payloads: List<String?>, learnBoring: Int?, label: String): Request {
        var prepared = template

        if (Utils.isHttp2(prepared.toByteArray())) {
            prepared = prepared.replaceFirst("HTTP/2\r\n", "HTTP/1.1\r\n")
        }

        if(Utils.getHeaders(prepared).contains("Connection: close")) {
            prepared = prepared.replaceFirst("Connection: close", "Connection: keep-alive")
        }

        return Request(prepared, payloads, learnBoring?: 0, label)
    }

    private fun sendRequests(url: URL, trustingSslSocketFactory: SSLSocketFactory, ipAddress: InetAddress?, port: Int, retryQueue: LinkedBlockingQueue<Request>, completedLatch: CountDownLatch, baseReadFreq: Int, baseRequestsPerConnection: Int, connectedLatch: CountDownLatch) {
        val readFreq = baseReadFreq
        val inflight = ArrayDeque<Request>()
        val requestsPerConnection = baseRequestsPerConnection
        var connected = false
        var reqWithResponse: Request? = null
        var answeredRequests = 0
        val badWords = HashSet<String>()
        var consecutiveFailedConnections = 0
        var startTime: Long = 0
        var reuseSSL = resumeSSL

        try {
            while (!shouldAbandonAttack()) {
                try {

                val socket: Socket?
                try {
                    socket = createSocket(url, trustingSslSocketFactory, ipAddress, port, reuseSSL)
                }
                catch (ex: Exception) {
                    Utils.out("Thread failed to connect")
                    retries.getAndIncrement()
                    val stackTrace = StringWriter()
                    ex.printStackTrace(PrintWriter(stackTrace))
                    Utils.err(stackTrace.toString())
                    consecutiveFailedConnections += 1
                    val sleep = 2.0.pow(consecutiveFailedConnections.toDouble())
                    Thread.sleep(sleep.toLong() * 200)
                    continue
                }
                val connectionID = connections.incrementAndGet()
                //(socket as SSLSocket).session.peerCertificates
                socket!!.apply {
                    soTimeout = timeout * 1000
                    tcpNoDelay = true
                    receiveBufferSize = readSize
                    keepAlive = true
                }
                // socket.setOption(ExtendedSocketOptions.TCP_KEEPIDLE, 30)
                // todo tweak other TCP options for max performance

                if(!connected) {
                    connected = true
                    connectedLatch.countDown()
                    while(!Utils.unloaded && attackState.get() == 0 && !shouldAbandonAttack()) {
                        Thread.sleep(10)
                    }
                }

                consecutiveFailedConnections = 0

                var requestsSent = 0
                answeredRequests = 0
                while (requestsSent < requestsPerConnection && !shouldAbandonAttack()) {
                    val ignoreLength = internalSettings["ignoreLength"] as Boolean
                    var ditchConnection = false;
                    var readCount = 0
                    startTime = 0
                    var endTime: Long = 0
                    var buffer = ""

                    for (j in 1..readFreq) {
                        if (requestsSent >= requestsPerConnection) {
                            break
                        }

                        var req = retryQueue.poll()
                        while (req == null && !shouldAbandonAttack()) {
                            req = requestQueue.poll(100, TimeUnit.MILLISECONDS)

                            if (req == null) {
                                if (readCount > 0) {
                                    break
                                }
                                if(attackState.get() >= 2) {
                                    return
                                }
                            }
                        }

                        if (req == null) break

                        inflight.addLast(req)
                        val byteReq = if (upstreamProxy != null && url.protocol == "http" && upstreamProxy.protocol == UpstreamProxyProtocol.HTTP) {
                            rewriteRequestForHttpProxy(req.getRequestAsBytes(), url, upstreamProxy.proxyAuthorizationHeaderValue())
                        } else {
                            req.getRequestAsBytes()
                        }
                        val outputstream = socket.getOutputStream()
                        when {
                            req.gate != null -> {
                                val withHold = 1
                                outputstream.write(byteReq, 0, byteReq.size-withHold)
                                req.gate!!.waitForGo()
                                startTime = System.nanoTime()
                                outputstream.write(byteReq, byteReq.size-withHold, withHold)
                            }
                            req.pauseBefore != 0 -> {
                                val end: Int
                                if (req.pauseBefore < 0) {
                                    end = byteReq.size + req.pauseBefore
                                } else {
                                    end = req.pauseBefore - 1 // since it's 0-indexed
                                }
                                val part1 = byteReq.sliceArray(0 until end)
                                //Utils.out("'"+Utilities.helpers.bytesToString(part1)+"'")
                                outputstream.write(part1)
                                startTime = System.nanoTime()

                                buffer = waitForData(socket, req.pauseTime, readSize, explodeOnEarlyRead)

                                val part2 = byteReq.sliceArray(end until byteReq.size)
                                outputstream.write(part2)
                                //Utils.out("'"+Utilities.helpers.bytesToString(part2)+"'")
                            }
                            req.pauseMarkers.isNotEmpty() -> {
                                var i = 0
                                startTime = System.nanoTime()
                                // pauses *after* sending the pauseMarker
                                while (i < byteReq.size && !shouldAbandonAttack()) {
                                    var pausePoint = -1
                                    //val z: ByteArray = req.pauseMarkers.get(0)
                                    for (pauseMarker in req.pauseMarkers) {
                                        val pauseBytes = pauseMarker.toByteArray(Charsets.ISO_8859_1)
                                        pausePoint = Utils.indexOf(byteReq, pauseBytes, true, i, byteReq.size)
                                        if (pausePoint != -1) {
                                            outputstream.write(byteReq.sliceArray(i until (pausePoint+pauseBytes.size)))
                                            buffer = waitForData(socket, req.pauseTime, readSize, explodeOnEarlyRead)
                                            i = pausePoint + pauseBytes.size
                                            break
                                        }
                                    }

                                    if (pausePoint == -1) {
                                        outputstream.write(byteReq.sliceArray(i until byteReq.size))
                                        break
                                    }

                                }
                            }
                            else -> {
                                outputstream.write(byteReq)
                                startTime = System.nanoTime()
                            }
                        }

                        readCount++
                        requestsSent++

                    }

                    val readBuffer = ByteArray(readSize)

                    for (k in 1..readCount) {

                        var bodyStart = buffer.indexOf("\r\n\r\n")
                        if (bodyStart != -1) {
                            endTime = System.nanoTime()
                        }

                        var consumeFirstBlock = buffer.startsWith("HTTP/1.1 100")
                        var ateContinue = false
                        var continueBlock = ""


                        while (bodyStart == -1 && !shouldAbandonAttack()) {
                            val len = socket.getInputStream().read(readBuffer)
                            if(len == -1) {
                                break
                            }
                            endTime = System.nanoTime()

                            val read = Utils.bytesToString(readBuffer.copyOfRange(0, len))
                            triggerReadCallback(read)
                            buffer += read
                            bodyStart = buffer.indexOf("\r\n\r\n")
                        }

                        while ((bodyStart == -1 || (consumeFirstBlock && !ateContinue)) && !shouldAbandonAttack()) {
                            try {
                                val len = socket.getInputStream().read(readBuffer)
                                if(len == -1) {
                                    break
                                }
                                endTime = System.nanoTime()

                                val read = Utils.bytesToString(readBuffer.copyOfRange(0, len))
                                triggerReadCallback(read)
                                buffer += read
                                consumeFirstBlock = buffer.startsWith("HTTP/1.1 100")
                                bodyStart = buffer.indexOf("\r\n\r\n")
                                if (consumeFirstBlock && bodyStart != -1 && !ateContinue && !ignoreLength) {
                                    consumeFirstBlock = false
                                    ateContinue = true
                                    continueBlock = buffer.substring(0, bodyStart+4)
                                    buffer = buffer.substring(bodyStart+4)
                                    bodyStart = buffer.indexOf("\r\n\r\n")
                                }
                            } catch (ex: SocketTimeoutException) {
                                break
                            }
                        }

                        if (buffer.isEmpty() && ateContinue) {
                            buffer = continueBlock
                            continueBlock = ""
                            bodyStart = buffer.length
                            // todo handle missing body
                        }

                        val contentLength = getContentLength(buffer)

                        if (buffer.isEmpty()) {
                            throw ConnectException("No response")
                        } else if (bodyStart == -1) {
                            throw ConnectException("Unterminated response: '$buffer'")
                        }

                        if (contentLength > 10000000) {
                            throw ConnectException("Response too large - 10mb max")
                        }

                        if (bodyStart+4 > buffer.length) {
                            bodyStart = buffer.length - 4
                        }

                        val headers = buffer.substring(0, bodyStart+4)
                        val bodyBuilder = StringBuilder()

                        if (contentLength != -1 && !ignoreLength) {
                            val responseLength = bodyStart + contentLength + 4

                            while (buffer.length < responseLength && !shouldAbandonAttack()) {
                                val len = socket.getInputStream().read(readBuffer)
                                if (len == -1) {
                                    ditchConnection = true
                                    bodyBuilder.setLength(0)
                                    bodyBuilder.append(buffer.substring(bodyStart + 4))
                                    buffer = ""
                                    break
                                    //throw RuntimeException("CL response finished unexpectedly")
                                }
                                val read =  Utils.bytesToString(readBuffer.copyOfRange(0, len))
                                triggerReadCallback(read)
                                buffer += read
                            }

                            if (!ditchConnection && !shouldAbandonAttack()) {
                                bodyBuilder.setLength(0)
                                bodyBuilder.append(buffer.substring(bodyStart + 4, responseLength))
                                buffer = buffer.substring(responseLength)
                            }
                        }
                        else if (headers.lowercase().contains("transfer-encoding: chunked") || headers.contains("^transfer-encoding:[ ]*chunked".toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)))  && !ignoreLength) {

                            buffer = buffer.substring(bodyStart + 4)

                            while (!shouldAbandonAttack()) {
                                var chunk = getNextChunkLength(buffer)
                                while (chunk.length == -1 || buffer.length < (chunk.length+2)) {
                                    val len = socket.getInputStream().read(readBuffer)
                                    if (len == -1) {
                                        throw RuntimeException("Chunked response finished unexpectedly")
                                    }
                                    val read = Utils.bytesToString(readBuffer.copyOfRange(0, len))
                                    triggerReadCallback(read)
                                    buffer += read
                                    chunk = getNextChunkLength(buffer)
                                }

                                bodyBuilder.append(buffer.substring(chunk.skip, chunk.length))
                                buffer = buffer.substring(chunk.length + 2)

                                if (chunk.length == chunk.skip) {
                                    break
                                }
                            }
                        }
                        else {

                            if (ignoreLength) {
                                socket.soTimeout = 5000
                            } else if (ateContinue) {
                                socket.soTimeout = 100
                            } else {
                                Utils.err("Response has no content-length - doing a one-second socket read instead. This is slow!")
                                socket.soTimeout = 1000
                                ditchConnection = true
                            }

                            try {
                                bodyBuilder.append(buffer.substring(bodyStart + 4))
                                while (!shouldAbandonAttack()) {
                                    val len = socket.getInputStream().read(readBuffer)

                                    if (len == -1) {
                                        break
                                    }

                                    buffer = Utils.bytesToString(readBuffer.copyOfRange(0, len))
                                    bodyBuilder.append(buffer)
                                }
                            } catch (ex: SocketTimeoutException) {

                            } catch (ex: SSLProtocolException) {

                            } catch (ex: java.lang.Exception) {
                                Utils.err("Exception during timed read: $ex")
                            }
                        }

                        if (shouldAbandonAttack()) {
                            break
                        }

                        if (!headers.startsWith("HTTP")) {
                            throw Exception("no http")
                        }

                        var msg = headers
                        if (continueBlock.isNotEmpty()) {
                            msg = continueBlock + msg
                        }

                        msg += uncompressIfNecessary(headers, bodyBuilder.toString())

                        reqWithResponse = inflight.removeFirst()
                        successfulRequests.getAndIncrement()
                        reqWithResponse.response = msg
                        reqWithResponse.connectionID = connectionID
                        reqWithResponse.time = (endTime - startTime) / 1000 // convert ns to microseconds
                        reqWithResponse.arrival = (endTime - start) / 1000

                        answeredRequests += 1
                        val interesting = processResponse(reqWithResponse, (reqWithResponse.response as String).toByteArray(Charsets.ISO_8859_1))

                        invokeCallback(reqWithResponse, interesting)

                    }
                    badWords.clear()

                    if (ditchConnection) {
                        break
                    }
                }
            } catch (ex: Exception) {

                if (reuseSSL && (ex is SSLHandshakeException || ex is SSLException)) {
                    reuseSSL = false
                }
                else {
                    // todo distinguish couldn't send vs couldn't read
                    val activeRequest = inflight.peek()
                    if (activeRequest != null) {
                        val activeWord = activeRequest.words.joinToString(separator="/")
                        if (shouldRetry(activeRequest)) {
                            if (reqWithResponse != null) {
                                Utils.out("Autorecovering error after $answeredRequests answered requests. After '${reqWithResponse.words.joinToString(separator = "/")}' during '$activeWord'")
                            } else {
                                Utils.out("Autorecovering first-request error during '$activeWord'")
                            }
                        } else {
                            ex.printStackTrace()
                            Utils.err("Ignoring error: $ex")
                            val badReq = inflight.pop()
                            if (ex is IllegalStateException) {
                                badReq.response = "early-response"
                            } else {
                                badReq.response = "null"
                            }
                            if (startTime != 0L) {
                                badReq.time = (System.nanoTime() - startTime) / 1000000 // convert to NS and lose precision
                            }
                            invokeCallback(badReq, true)
                        }
                    } else {
                        if (ex !is InterruptedException) {
                            Utils.out("Autorecovering error with empty queue: ${ex.message}")
                            ex.printStackTrace()
                        }
                    }
                }

                // do callback here (allow user code change
                //readFreq = max(1, readFreq / 2)
                //requestsPerConnection = max(1, requestsPerConnection/2)
                //println("Lost ${inflight.size} requests. Changing requestsPerConnection to $requestsPerConnection and readFreq to $readFreq")
                retryQueue.addAll(inflight)
                inflight.clear()
                }
            }
        } finally {
            completedLatch.countDown()
        }
    }

    override fun cleanup() {
        cleanupHttpEngineResources(domains, threadPool)
        super.cleanup()
    }

}
