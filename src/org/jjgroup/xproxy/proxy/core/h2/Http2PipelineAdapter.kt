package org.jjgroup.xproxy.proxy.core.h2

sealed class Http2InboundEvent {
    data class Headers(val streamId: Int, val headers: List<Pair<String, String>>, val endStream: Boolean) : Http2InboundEvent()
    data class Data(val streamId: Int, val chunk: ByteArray, val endStream: Boolean) : Http2InboundEvent()
    data class Trailers(val streamId: Int, val trailers: List<Pair<String, String>>) : Http2InboundEvent()
    data class Reset(val streamId: Int, val errorCode: Long) : Http2InboundEvent()
}

class Http2PipelineAdapter(
    private val hooks: Http2InterceptHooks
) {
    fun consume(event: Http2InboundEvent) {
        when (event) {
            is Http2InboundEvent.Headers -> hooks.onHeaders(event.streamId, event.headers, event.endStream)
            is Http2InboundEvent.Data -> hooks.onData(event.streamId, event.chunk, event.endStream)
            is Http2InboundEvent.Trailers -> hooks.onTrailers(event.streamId, event.trailers)
            is Http2InboundEvent.Reset -> hooks.onReset(event.streamId, event.errorCode)
        }
    }
}
