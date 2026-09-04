package org.jjgroup.xproxy.proxy.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProtocolDispatcherTest {

    @Test
    fun `routes h2 when alpn is h2`() {
        val route = ProtocolDispatcher.decideRoute(
            negotiatedAlpn = "h2",
            firstRequestChunk = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        )
        assertEquals(ProxyProtocolRoute.H2, route)
    }

    @Test
    fun `routes h1 when alpn is http1`() {
        val route = ProtocolDispatcher.decideRoute(
            negotiatedAlpn = "http/1.1",
            firstRequestChunk = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        )
        assertEquals(ProxyProtocolRoute.H1, route)
    }

    @Test
    fun `routes h2 when cleartext preface is detected`() {
        val route = ProtocolDispatcher.decideRoute(
            negotiatedAlpn = null,
            firstRequestChunk = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray()
        )
        assertEquals(ProxyProtocolRoute.H2, route)
    }

    @Test
    fun `routes h1 by default`() {
        val route = ProtocolDispatcher.decideRoute(
            negotiatedAlpn = null,
            firstRequestChunk = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        )
        assertEquals(ProxyProtocolRoute.H1, route)
    }
}
