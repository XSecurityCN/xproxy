package org.jjgroup.xproxy.proxy.core

enum class DowngradeMode {
    PRESERVE_PROTOCOL,
    ALLOW_DOWNGRADE,
    FAIL_CLOSED
}

data class ProtocolPolicy(
    val downgradeMode: DowngradeMode
) {
    fun allowHttp2Downgrade(): Boolean = downgradeMode == DowngradeMode.ALLOW_DOWNGRADE
    fun failClosedOnDowngrade(): Boolean = downgradeMode == DowngradeMode.FAIL_CLOSED

    companion object {
        fun preserve(): ProtocolPolicy = ProtocolPolicy(DowngradeMode.PRESERVE_PROTOCOL)
        fun allowDowngrade(): ProtocolPolicy = ProtocolPolicy(DowngradeMode.ALLOW_DOWNGRADE)
        fun failClosed(): ProtocolPolicy = ProtocolPolicy(DowngradeMode.FAIL_CLOSED)
    }
}
