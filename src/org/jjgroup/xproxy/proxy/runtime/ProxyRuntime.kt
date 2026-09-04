package org.jjgroup.xproxy.proxy.runtime

interface ProxyRuntime {
    fun start(bindHost: String, bindPort: Int, handleSsl: Boolean = true)
    fun stop()
    fun isRunning(): Boolean
}

enum class ProxyRuntimeType {
    PROXYEE,
    NATIVE
}
