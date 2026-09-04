package org.jjgroup.xproxy.settings.core

import org.jjgroup.xproxy.core.Settings
import java.util.Base64

enum class UpstreamProxyProtocol {
    HTTP,
    SOCKS5
}

data class UpstreamProxyConfig(
    val host: String,
    val port: Int,
    val protocol: UpstreamProxyProtocol,
    val username: String,
    val password: String
) {
    fun hasAuthentication(): Boolean = username.isNotBlank()

    fun proxyAuthorizationHeaderValue(): String? {
        if (!hasAuthentication()) {
            return null
        }
        val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.ISO_8859_1))
        return "Basic $token"
    }
}

object UpstreamProxySettings {
    private const val KEY_ENABLED = "upstream-proxy.enabled"
    private const val KEY_HOST = "upstream-proxy.host"
    private const val KEY_PORT = "upstream-proxy.port"
    private const val KEY_PROTOCOL = "upstream-proxy.protocol"
    private const val KEY_USERNAME = "upstream-proxy.username"
    private const val KEY_PASSWORD = "upstream-proxy.password"

    fun registerSettings() {
        Settings.registerSetting(KEY_ENABLED, false)
        Settings.registerSetting(KEY_HOST, "")
        Settings.registerSetting(KEY_PORT, 8080)
        Settings.registerSetting(KEY_PROTOCOL, UpstreamProxyProtocol.HTTP.name)
        Settings.registerSetting(KEY_USERNAME, "")
        Settings.registerSetting(KEY_PASSWORD, "")

        if (getHost().isBlank()) {
            setHost("127.0.0.1")
        }
        if (getPort() !in 1..65535) {
            setPort(8080)
        }
    }

    fun isEnabled(): Boolean = Settings.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) = Settings.setBoolean(KEY_ENABLED, enabled)

    fun getHost(): String = Settings.getString(KEY_HOST, "").trim()

    fun setHost(host: String) = Settings.setString(KEY_HOST, host.trim())

    fun getPort(): Int = Settings.getInt(KEY_PORT, 8080)

    fun setPort(port: Int) = Settings.setInt(KEY_PORT, port)

    fun getProtocol(): UpstreamProxyProtocol =
        runCatching { UpstreamProxyProtocol.valueOf(Settings.getString(KEY_PROTOCOL, UpstreamProxyProtocol.HTTP.name).trim().uppercase()) }
            .getOrDefault(UpstreamProxyProtocol.HTTP)

    fun setProtocol(protocol: UpstreamProxyProtocol) = Settings.setString(KEY_PROTOCOL, protocol.name)

    fun getUsername(): String = Settings.getString(KEY_USERNAME, "")

    fun setUsername(username: String) = Settings.setString(KEY_USERNAME, username)

    fun getPassword(): String = Settings.getString(KEY_PASSWORD, "")

    fun setPassword(password: String) = Settings.setString(KEY_PASSWORD, password)

    fun getEnabledProxy(): UpstreamProxyConfig? {
        if (!isEnabled()) {
            return null
        }
        val host = getHost()
        val port = getPort()
        val protocol = getProtocol()
        val username = getUsername()
        val password = getPassword()
        if (host.isBlank() || port !in 1..65535) {
            return null
        }
        return UpstreamProxyConfig(host, port, protocol, username, password)
    }
}
