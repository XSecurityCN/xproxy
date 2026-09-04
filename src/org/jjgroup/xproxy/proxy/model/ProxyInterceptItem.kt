package org.jjgroup.xproxy.proxy.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProxyInterceptItem(
    val id: Long,
    val timeMillis: Long,
    val method: String,
    val host: String,
    val path: String,
    val tls: Boolean,
    var requestRaw: String
) {
    val originalRequestRaw = requestRaw

    enum class Phase {
        REQUEST,
        RESPONSE
    }

    @Volatile
    var phase = Phase.REQUEST

    @Volatile
    var interceptThisResponse = false

    @Volatile
    var responseRaw = ""

    @Volatile
    var originalResponseRaw = ""

    @Volatile
    var requestModified = false

    @Volatile
    var responseModified = false

    @Volatile
    private var latch = CountDownLatch(1)

    private val decision = AtomicReference(Decision.PENDING)

    enum class Decision {
        PENDING,
        FORWARD,
        DROP
    }

    fun forward() {
        decision.set(Decision.FORWARD)
        latch.countDown()
    }

    fun drop() {
        decision.set(Decision.DROP)
        latch.countDown()
    }

    fun awaitDecision(): Decision {
        latch.await()
        return decision.get()
    }

    fun awaitDecision(timeoutMillis: Long): Decision {
        val ok = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return if (ok) decision.get() else Decision.PENDING
    }

    @Synchronized
    fun enterResponsePhase(rawResponse: String) {
        responseRaw = rawResponse
        originalResponseRaw = rawResponse
        phase = Phase.RESPONSE
        decision.set(Decision.PENDING)
        latch = CountDownLatch(1)
    }
}
