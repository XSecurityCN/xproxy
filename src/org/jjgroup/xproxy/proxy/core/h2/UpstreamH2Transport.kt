package org.jjgroup.xproxy.proxy.core.h2

import org.jjgroup.xproxy.engine.http2.H2Connection
import org.jjgroup.xproxy.proxy.model.MessageMetadata
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class UpstreamH2Prepared(
    val streamId: Int,
    val headers: List<Pair<String, String>>,
    val body: String?,
    val metadata: MessageMetadata
)

class UpstreamH2Transport {
    private val streamCounter = AtomicInteger(1)
    private val correlationToStreamId = ConcurrentHashMap<String, Int>()

    fun prepare(correlationId: String, rawRequest: String, metadata: MessageMetadata = MessageMetadata()): UpstreamH2Prepared {
        val streamId = reserveStreamId(correlationId)
        val parsed = org.jjgroup.xproxy.engine.http2.HTTP2Request(rawRequest)
        val headers = H2Connection.buildReq(parsed)
        return UpstreamH2Prepared(
            streamId = streamId,
            headers = headers,
            body = parsed.body,
            metadata = metadata
        )
    }

    fun reserveStreamId(correlationId: String): Int {
        val streamId = streamCounter.getAndAdd(2)
        correlationToStreamId[correlationId] = streamId
        return streamId
    }

    fun lookupStreamId(correlationId: String): Int? = correlationToStreamId[correlationId]
}
