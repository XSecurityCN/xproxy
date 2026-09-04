package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.proxy.core.ProtocolPolicy
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuzzerProtocolSendSelectorTest {

    @Test
    fun `does not downgrade http2 request by default`() {
        val service = HttpService("example.com", 443, "https")
        val request = "GET / HTTP/2\r\nHost: example.com\r\n\r\n"

        val shouldDowngrade = FuzzerProtocolSendSelector.shouldDowngradeHttp2ToHttp11(
            service = service,
            requestText = request,
            policy = ProtocolPolicy.preserve()
        )

        assertFalse(shouldDowngrade)
    }

    @Test
    fun `uses native http2 send when downgrade is not allowed`() {
        val request = "GET / HTTP/2\r\nHost: example.com\r\n\r\n"
        val shouldUseHttp2 = FuzzerProtocolSendSelector.shouldSendAsHttp2(
            requestText = request,
            policy = ProtocolPolicy.preserve()
        )
        assertTrue(shouldUseHttp2)
    }

    @Test
    fun `downgrades only when explicitly allowed`() {
        val service = HttpService("example.com", 443, "https")
        val request = "GET / HTTP/2\r\nHost: example.com\r\n\r\n"

        val shouldDowngrade = FuzzerProtocolSendSelector.shouldDowngradeHttp2ToHttp11(
            service = service,
            requestText = request,
            policy = ProtocolPolicy.allowDowngrade()
        )

        assertTrue(shouldDowngrade)
        assertFalse(FuzzerProtocolSendSelector.shouldSendAsHttp2(request, ProtocolPolicy.allowDowngrade()))
    }
}
