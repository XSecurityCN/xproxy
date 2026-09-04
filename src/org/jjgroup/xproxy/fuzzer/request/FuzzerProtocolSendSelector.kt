package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.proxy.core.ProtocolPolicy

object FuzzerProtocolSendSelector {
    fun shouldSendAsHttp2(requestText: String, policy: ProtocolPolicy): Boolean {
        if (!Utils.isHttp2(requestText.toByteArray(Charsets.ISO_8859_1))) {
            return false
        }
        return !policy.allowHttp2Downgrade()
    }

    fun shouldDowngradeHttp2ToHttp11(service: HttpService, requestText: String, policy: ProtocolPolicy): Boolean {
        if (!policy.allowHttp2Downgrade()) {
            return false
        }
        if (!Utils.isHttp2(requestText.toByteArray(Charsets.ISO_8859_1))) {
            return false
        }
        return service.protocol.lowercase() != "http2"
    }
}
