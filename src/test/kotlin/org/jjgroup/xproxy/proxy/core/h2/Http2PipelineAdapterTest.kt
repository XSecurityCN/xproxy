package org.jjgroup.xproxy.proxy.core.h2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Http2PipelineAdapterTest {

    @Test
    fun `invokes hooks in incoming event order`() {
        val trace = mutableListOf<String>()
        val hooks = object : Http2InterceptHooks {
            override fun onHeaders(streamId: Int, headers: List<Pair<String, String>>, endStream: Boolean) {
                trace.add("headers:$streamId:$endStream")
            }

            override fun onData(streamId: Int, chunk: ByteArray, endStream: Boolean) {
                trace.add("data:$streamId:$endStream")
            }

            override fun onTrailers(streamId: Int, trailers: List<Pair<String, String>>) {
                trace.add("trailers:$streamId")
            }

            override fun onReset(streamId: Int, errorCode: Long) {
                trace.add("reset:$streamId:$errorCode")
            }
        }

        val adapter = Http2PipelineAdapter(hooks)
        adapter.consume(Http2InboundEvent.Headers(3, listOf(":method" to "GET"), false))
        adapter.consume(Http2InboundEvent.Data(3, "abc".toByteArray(), false))
        adapter.consume(Http2InboundEvent.Trailers(3, listOf("x-end" to "1")))
        adapter.consume(Http2InboundEvent.Reset(3, 8))

        assertEquals(
            listOf("headers:3:false", "data:3:false", "trailers:3", "reset:3:8"),
            trace
        )
    }
}
