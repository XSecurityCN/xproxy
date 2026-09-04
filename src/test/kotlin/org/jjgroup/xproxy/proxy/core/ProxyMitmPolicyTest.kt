package org.jjgroup.xproxy.proxy.core

import com.github.monkeywie.proxyee.util.ProtoUtil
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyMitmPolicyTest {

    @Test
    fun `preserve policy disables mitm for tls traffic`() {
        val requestProto = ProtoUtil.RequestProto("example.com", 443, true)
        assertTrue(shouldEnableMitmForRequest(requestProto, ProtocolPolicy.preserve()))
    }

    @Test
    fun `allow downgrade policy keeps mitm enabled for tls traffic`() {
        val requestProto = ProtoUtil.RequestProto("example.com", 443, true)
        assertTrue(shouldEnableMitmForRequest(requestProto, ProtocolPolicy.allowDowngrade()))
    }

    @Test
    fun `fail closed policy keeps mitm enabled for tls traffic`() {
        val requestProto = ProtoUtil.RequestProto("example.com", 443, true)
        assertTrue(shouldEnableMitmForRequest(requestProto, ProtocolPolicy.failClosed()))
    }

    @Test
    fun `all policies keep non tls traffic interceptable`() {
        val requestProto = ProtoUtil.RequestProto("example.com", 80, false)
        assertTrue(shouldEnableMitmForRequest(requestProto, ProtocolPolicy.preserve()))
        assertTrue(shouldEnableMitmForRequest(requestProto, ProtocolPolicy.failClosed()))
        assertTrue(shouldEnableMitmForRequest(requestProto, ProtocolPolicy.allowDowngrade()))
    }
}
