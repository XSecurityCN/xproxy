package org.jjgroup.xproxy

import org.jjgroup.xproxy.core.Floodgate
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.ui.table.OutputHandler
import org.jjgroup.xproxy.ui.table.RequestTable
import java.io.*
import java.net.URL
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.HashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

abstract class RequestEngine {

    var start: Long = System.nanoTime()
    // 多发送线程并发读写 failedWords/userState,改用 ConcurrentHashMap 避免普通 HashMap 并发 resize 死循环/丢数据。
    val failedWords = ConcurrentHashMap<Int, AtomicInteger>()
    var successfulRequests = AtomicInteger(0)
    val userState = ConcurrentHashMap<String, Any>()
    val lastRequestID = AtomicInteger(0)
    var connections = AtomicInteger(0)
    val attackState = AtomicInteger(0) // 0 = connecting, 1 = live, 2 = fully queued, 3 = cancelled, 4 = completed
    lateinit var completedLatch: CountDownLatch
    private val baselines = LinkedList<MutableSet<ResponseFingerprint>>()
    val retries = AtomicInteger(0)
    val permaFails = AtomicInteger(0)
    lateinit var outputHandler: OutputHandler
    lateinit var requestQueue: LinkedBlockingQueue<Request>
    abstract val callback: (Request, Boolean) -> Boolean?
    abstract var readCallback: ((String) -> Boolean)?
    abstract val maxRetriesPerRequest: Int
    lateinit var target: URL
    val floodgates = HashMap<String, Floodgate>()
    private val paused = AtomicBoolean(false)
    var lastLife: Long = System.currentTimeMillis()
    abstract var idleTimeout: Long
    var fixContentLength: Boolean = true

    val internalSettings: HashMap<String, Any> = HashMap()

    init {
        lastLife = System.currentTimeMillis()

        if (attackState.get() == 3) {
            throw Exception("You cannot create a new request engine for a cancelled attack")
        }

    }

    fun invokeCallback(req: Request, interesting: Boolean){
        updateLastLife()
        try {
            req.invokeCallback(interesting)
        } catch (ex: Exception){
            Utils.out("Error in user-defined callback: $ex")
            ex.printStackTrace()
            permaFails.incrementAndGet()
        }
    }

    abstract fun start(timeout: Int = 10)

    abstract fun buildRequest(template: String, payloads: List<String?>, learnBoring: Int?, label: String): Request

    fun triggerReadCallback(data: String) {
        readCallback?.invoke(data)
    }

    fun queue(req: String) {
        queue(req, emptyList())
    }

    fun queue(req: String, payload: Any) {
        queue(req, listOf(payload), 0)
    }

    fun queue(template: String, payloads: List<Any?>) {
        queue(template, payloads, 0, null)
    }

    fun queue(template: String, payloads: List<Any?> = emptyList<Any>(), learnBoring: Int = 0, callback: ((Request, Boolean) -> Boolean)? = null, gateName: String? = null, label: String = "", pauseBefore: Int = 0, pauseTime: Int = 1000, pauseMarkers: List<String> = emptyList(), delay: Long = 0, endpoint: String? = null, pythonEngine: Any? = null, fixContentLength: Boolean = true) {
        updateLastLife()

        val markerCount = Request.PLACEHOLDER_REGEX.findAll(template).count()
        val payloadCount = payloads.size
        val noPayload = payloads.isEmpty()
        val noMarker = markerCount == 0

        if (noMarker && !noPayload) {
            throw Exception("The request has payloads specified, but no {{placeholder}} injection markers")
        }
        if (!noMarker && noPayload) {
            val bad = Request.PLACEHOLDER_REGEX.find(template)?.range?.first ?: 0
            val context = template.slice(max(bad-8, 0).. min(bad+16, template.length))
            throw Exception("The request has a {{placeholder}} injection point, but no payloads specified: '$context'")
        }
        if (!noMarker && !noPayload && markerCount != payloadCount) {
            throw Exception("Payload count mismatch: request contains $markerCount {{placeholder}} markers, but script provided $payloadCount payload values. They must be equal.")
        }

        val payloadsAsStrings = payloads.map { it.toString().replace("\$randomplz", randomAlphaNumeric(8), true) }

        val engine = this
        val request = buildRequest(template.replace("\$randomplz", randomAlphaNumeric(8), true), payloadsAsStrings, learnBoring, label).apply {
            _engine = engine
            this.engine = pythonEngine ?: engine
            id = lastRequestID.incrementAndGet()
            this.callback = callback
            this.pauseBefore = pauseBefore
            this.pauseTime = pauseTime
            this.pauseMarkers = pauseMarkers
            delayCompletion = delay
            endpointOverride = endpoint
            autoFixContentLength = fixContentLength
        }

        if (gateName != null) {
            synchronized(gateName) {
                request.gate = floodgates[gateName] ?: Floodgate(gateName, this)

                if (floodgates.containsKey(gateName)) {
                    floodgates[gateName]!!.addWaiter()
                } else {
                    floodgates[gateName] = request.gate!!
                }

                if (this is HttpRequestEngine && request.gate!!.remaining.get() > this.threads) {
                    throw Exception("You have queued more gated requests than concurrentConnections, so your attack will deadlock. Consider increasing concurrentConnections")
                }
            }
        }

        val state = attackState.get()

        if (state > 2) {
            throw IllegalStateException("Cannot queue any more items - the attack has finished")
        }

        val timeout = if (state == 0) 1L else 1800L

        var queued = false
        var attempt = 0L
        while (!queued && attackState.get() <= 2 && attempt < timeout) {
            queued = requestQueue.offer(request, 1, TimeUnit.SECONDS)
            attempt += 1
        }

        if (!queued) {
            if (state == 0 && requestQueue.size == 100) {
                Utils.out("Looks like a non-streaming attack, unlimiting the queue")
                requestQueue = LinkedBlockingQueue(requestQueue)
            }
            else if (attempt == timeout) {
                Utils.out("Timeout queuing request. Aborting.")
                this.cancel()
            } else {
                // the attack has been cancelled so we don't need to do anything
            }
        }
    }

    open fun openGate(gateName: String) {
        // Utils.out("Requested gate open: $gateName")
        if (!floodgates.containsKey(gateName)) {
            throw Exception("Unrecognised gate name in openGate() invocation")
        }
        floodgates[gateName]!!.open()
    }

    fun shouldAbandonAttack(): Boolean {
        while (paused.get() && attackState.get() < 3 && !Thread.currentThread().isInterrupted && !Utils.unloaded) {
            Thread.sleep(50)
        }
        return when {
            Utils.unloaded -> true
            Thread.currentThread().isInterrupted -> true
            attackState.get() >= 3 -> true
            idleTimeout > 0 && System.currentTimeMillis() > lastLife + idleTimeout -> {
                Utils.out("Cancelling attack due to total timeout exceeded: $idleTimeout")
                cancel()
                true
            }
            else -> false
        }
    }

    fun pauseAttack() = paused.set(true)

    fun resumeAttack() = paused.set(false)

    fun isPausedAttack() = paused.get()

    fun updateLastLife() {
        if (idleTimeout == 0L) {
            return
        }
        lastLife = System.currentTimeMillis()
    }


    open fun showStats(timeout: Int = -1) {
        if (attackState.get() == 3) {
            return
        }

        var success = true
        attackState.set(2)
        if (timeout > 0) {
            success = completedLatch.await(timeout.toLong(), TimeUnit.SECONDS)
        }
        else {
            while (completedLatch.count > 0 && !Utils.unloaded && attackState.get() < 3) {
                completedLatch.await(10, TimeUnit.SECONDS)
            }
        }

        if (attackState.get() == 3) {
            return
        }

        if (!success) {
            Utils.out("Aborting attack due to timeout")
            attackState.set(3)
        }
        else {
            Utils.err("Completed attack on " +target)
            attackState.set(4)
        }
        showSummary()
    }

    fun cancel() {
        if (attackState.get() != 3) {
            attackState.set(3)
            Utils.out("Cancelled attack")

            // Wait for all worker threads to finish their callbacks before calculating anomaly rankings
            // This prevents ConcurrentModificationException when iterating the request list
            if (::completedLatch.isInitialized) {
                val timeout = 30L // seconds
                val finished = completedLatch.await(timeout, TimeUnit.SECONDS)
                if (!finished) {
                    Utils.err("Warning: Worker threads did not complete within ${timeout}s during cancellation")
                }
            }

            showSummary()
        }

        // Clean up memory to prevent leaks
        cleanup()
    }

    fun showSummary() {
        // todo or invoke completedCallback here?
        val duration = System.nanoTime().toFloat() - start
        val requests = successfulRequests.get().toFloat()
        Utils.err("Sent ${requests.toInt()} requests over ${connections.toInt()} connections in ${duration / 1000000000} seconds")
        Utils.err(String.format("RPS: %.0f\n", requests / ceil((duration / 1000000000).toDouble())))

        // Calculate anomaly rankings when attack is stopped or completed
        if (attackState.get() >= 3) {
            // All worker threads have finished (waited in cancel() or showStats())
            // so it's safe to iterate the request list for anomaly ranking
            calculateAnomalyRankings()
        }

        // Clean up memory when attack is completed
        if (attackState.get() >= 4) {
            cleanup()
        }
    }

    private fun calculateAnomalyRankings() {
        try {
            val allRequests = outputHandler.getAllRquests()
            if (allRequests.isEmpty()) {
                return
            }

            // 每个请求的 fingerprint 只算一次(原实现 groupingBy 算一次后 forEach 又对每个请求重算一次,
            // 1M 结果 = 2M 次 fingerprint 计算)。associateWith 单次建立 request->fingerprint 映射后直接查表。
            val fingerprints = allRequests.associateWith { ResponseFingerprint.fromRequest(it) }
            val counts = fingerprints.values.groupingBy { it }.eachCount()

            val maxCount = counts.values.maxOrNull() ?: 1
            fingerprints.forEach { (request, fingerprint) ->
                val count = counts[fingerprint] ?: 0
                request.anomalyRank = maxCount - count
            }

            // Notify the table model to update the UI
            val handler = outputHandler
            if (handler is RequestTable) {
                handler.model.updateRankings()

                // Auto-sort by anomaly rank if user hasn't customized sorting
                javax.swing.SwingUtilities.invokeLater {
                    if (!handler.hasSortBeenModified()) {
                        handler.autoSortByAnomalyRank()
                    }
                }
            }
        } catch (e: Exception) {
            Utils.err("Error calculating anomaly rankings: ${e.message}")
            e.printStackTrace()
        }
    }

    fun statusString(): String {
        val duration = ceil(((System.nanoTime().toFloat() - start) / 1000000000).toDouble()).toInt().coerceAtLeast(1)
        val requests = successfulRequests.get().toFloat()
        val nextWord = requestQueue.peek()?.words?.joinToString(separator = "/").orEmpty()
        val numberFormat = NumberFormat.getIntegerInstance()
        val line = I18n.t(
            "fuzzer.status_line",
            "reqs" to numberFormat.format(requests.toInt()),
            "queued" to numberFormat.format(requestQueue.count()),
            "duration" to numberFormat.format(duration),
            "rps" to String.format("%.0f", requests / duration),
            "connections" to numberFormat.format(connections.get()),
            "retries" to numberFormat.format(retries.get()),
            "fails" to numberFormat.format(permaFails.get()),
            "next" to nextWord
        )
        val state = attackState.get()
        return when {
            state < 3 -> line
            state == 3 -> line + " | " + I18n.t("fuzzer.status_cancelled")
            else -> line + " | " + I18n.t("fuzzer.status_completed")
        }
    }

    fun setOutput(outputHandler: OutputHandler) {
        this.outputHandler = outputHandler
    }

    fun processResponse(req: Request, response: ByteArray): Boolean {
        val fingerprint = ResponseFingerprint.fromResponseBytes(response)

        if (baselines.any { it.contains(fingerprint) }) return false

        if (req.learnBoring != 0) {
            var base = baselines.getOrNull(req.learnBoring - 1)
            if (base == null) {
                base = mutableSetOf()
                baselines.add(base)
            }
            base.add(fingerprint)
            return false
        }

        return true
    }

    fun shouldRetry(req: Request): Boolean {
        if (maxRetriesPerRequest < 1) {
            permaFails.getAndIncrement()
            return false
        }

        val reqID = req.id // req.getRequest().hashCode().toString() +

        val fails = failedWords[reqID]
        if (fails == null) {
            failedWords[reqID] = AtomicInteger(1)
        } else if (fails.incrementAndGet() > maxRetriesPerRequest) {
            permaFails.getAndIncrement()
            Utils.out("Skipping word due to multiple failures: $reqID")
            return false
        }

        retries.getAndIncrement()
        return true
    }

    fun applySetting(settingName: String, settingValue: Any) {
        if (!internalSettings.containsKey(settingName)) {
            val msg = "Unrecognised setting name: $settingName. This engine supports the following settings: ${internalSettings.keys}"
            throw Exception(msg)
        }
        internalSettings[settingName] = settingValue
    }

    fun clearErrors() {
        failedWords.clear()
    }

    private fun randomAlphaNumeric(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.util.concurrent.ThreadLocalRandom.current()
        val builder = StringBuilder(length)
        repeat(length) {
            builder.append(chars[random.nextInt(chars.length)])
        }
        return builder.toString()
    }

    open fun cleanup() {
        // Clear collections to free memory
        failedWords.clear()
        baselines.clear()
        floodgates.clear()
        requestQueue.clear()
        userState.clear()
    }

}

// wordcount 切词正则:每响应都会调用(ResponseFingerprint.fromResponseBytes + Request.wordcount),
// 预编译避免重复 Pattern.compile。保留 split 语义(连续分隔符产生空串并计入 .size),仅消除编译成本,零行为变更。
internal val WORD_BOUNDARY_REGEX = Regex("[^a-zA-Z0-9]")

data class ResponseFingerprint(val status: Int, val length: Int, val wordcount: Int) {
    companion object {
        fun fromResponseBytes(response: ByteArray): ResponseFingerprint {
            val text = Utils.bytesToString(response)
            val status = parseStatusCode(text)
            val length = text.length
            val wordcount = text.split(WORD_BOUNDARY_REGEX).size
            return ResponseFingerprint(status, length, wordcount)
        }

        fun fromRequest(request: Request): ResponseFingerprint {
            return ResponseFingerprint(request.code, request.length, request.wordcount)
        }

        private fun parseStatusCode(response: String): Int {
            if (response.isEmpty()) {
                return 0
            }
            return try {
                // HTTP/1("HTTP/1.1 200 OK")与 HTTP/2 伪头(":status 200")都按空格/换行切分取第二段,
                // 两种情形的索引与解析一致,无需分支区分(原 if/else 两分支实现完全相同)。
                response.split(" ", "\r", "\n", limit = 3)[1].toInt()
            } catch (e: Exception) {
                0
            }
        }
    }
}
