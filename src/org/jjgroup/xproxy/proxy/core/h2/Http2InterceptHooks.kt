package org.jjgroup.xproxy.proxy.core.h2

interface Http2InterceptHooks {
    fun onHeaders(streamId: Int, headers: List<Pair<String, String>>, endStream: Boolean)
    fun onData(streamId: Int, chunk: ByteArray, endStream: Boolean)
    fun onTrailers(streamId: Int, trailers: List<Pair<String, String>>)
    fun onReset(streamId: Int, errorCode: Long)

    companion object {
        val NOOP: Http2InterceptHooks = object : Http2InterceptHooks {
            override fun onHeaders(streamId: Int, headers: List<Pair<String, String>>, endStream: Boolean) = Unit
            override fun onData(streamId: Int, chunk: ByteArray, endStream: Boolean) = Unit
            override fun onTrailers(streamId: Int, trailers: List<Pair<String, String>>) = Unit
            override fun onReset(streamId: Int, errorCode: Long) = Unit
        }
    }
}
