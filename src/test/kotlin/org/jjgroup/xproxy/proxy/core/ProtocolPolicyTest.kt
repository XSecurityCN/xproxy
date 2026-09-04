package org.jjgroup.xproxy.proxy.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolPolicyTest {

    @Test
    fun `preserve protocol policy does not allow downgrade`() {
        val policy = ProtocolPolicy.preserve()
        assertFalse(policy.allowHttp2Downgrade())
        assertFalse(policy.failClosedOnDowngrade())
    }

    @Test
    fun `allow downgrade policy enables downgrade`() {
        val policy = ProtocolPolicy.allowDowngrade()
        assertTrue(policy.allowHttp2Downgrade())
        assertFalse(policy.failClosedOnDowngrade())
    }

    @Test
    fun `fail closed policy denies downgrade and marks fail closed`() {
        val policy = ProtocolPolicy.failClosed()
        assertFalse(policy.allowHttp2Downgrade())
        assertTrue(policy.failClosedOnDowngrade())
    }
}
