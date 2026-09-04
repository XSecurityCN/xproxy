package org.jjgroup.xproxy.settings.core

import org.jjgroup.xproxy.core.Settings
import java.security.SecureRandom

/**
 * MCP(Model Context Protocol)服务端配置。
 *
 * - 全局持久化:经 [Settings](Java Preferences `userRoot`/node "xproxy"),跨项目共享,与各模块设置一致。
 * - 默认开启:服务端 [isEnabled] 与鉴权 [isAuthEnabled] 均默认 true,首次启动时自动生成不可猜的
 *   [authToken],用户在 Settings 面板查看/复制后配置给 agent。绑定地址默认 `127.0.0.1`(仅本机)。
 *
 * 参考 [UpstreamProxySettings] 的 KEY_* + registerSettings + typed getter/setter 模式。
 */
object McpSettings {
    private const val KEY_ENABLED = "mcp.enabled"
    private const val KEY_PORT = "mcp.port"
    private const val KEY_BIND_HOST = "mcp.bind-host"
    private const val KEY_AUTH_TOKEN = "mcp.auth-token"
    private const val KEY_PORT_MIGRATED = "mcp.port-migrated-v1"

    private const val DEFAULT_PORT = 9527
    private const val LEGACY_DEFAULT_PORT = 27990 // 预发布期旧默认;一次性迁移到 9527。
    private const val DEFAULT_BIND_HOST = "127.0.0.1"
    private const val TOKEN_BYTES = 32 // 256-bit, 64 hex chars

    private val secureRandom = SecureRandom()

    @JvmStatic
    fun registerSettings() {
        Settings.registerSetting(KEY_ENABLED, true)
        Settings.registerSetting(KEY_PORT, DEFAULT_PORT)
        Settings.registerSetting(KEY_BIND_HOST, DEFAULT_BIND_HOST)
        Settings.registerSetting(KEY_AUTH_TOKEN, "")
        Settings.registerSetting(KEY_PORT_MIGRATED, false)
        migrateLegacyPort()
        normalizePort()
    }

    /**
     * 一次性把预发布期旧默认端口 27990 迁移到 9527。用 [KEY_PORT_MIGRATED] flag 保证只跑一次,
     * 不会影响用户之后显式设置的任何端口(包括再次设回 27990)。本功能发布后此迁移可移除。
     */
    private fun migrateLegacyPort() {
        if (Settings.getBoolean(KEY_PORT_MIGRATED, false)) return
        Settings.setBoolean(KEY_PORT_MIGRATED, true)
        if (Settings.getInt(KEY_PORT, DEFAULT_PORT) == LEGACY_DEFAULT_PORT) {
            setPort(DEFAULT_PORT)
        }
    }

    private fun normalizePort() {
        val port = getPort()
        if (port !in 1..65535) {
            setPort(DEFAULT_PORT)
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean = Settings.getBoolean(KEY_ENABLED, true)

    @JvmStatic
    fun setEnabled(value: Boolean) {
        Settings.setBoolean(KEY_ENABLED, value)
    }

    @JvmStatic
    fun getPort(): Int = Settings.getInt(KEY_PORT, DEFAULT_PORT)

    @JvmStatic
    fun setPort(value: Int) {
        Settings.setInt(KEY_PORT, value.coerceIn(1, 65535))
    }

    @JvmStatic
    fun getBindHost(): String = Settings.getString(KEY_BIND_HOST, DEFAULT_BIND_HOST).ifBlank { DEFAULT_BIND_HOST }

    @JvmStatic
    fun setBindHost(value: String) {
        Settings.setString(KEY_BIND_HOST, value.trim().ifBlank { DEFAULT_BIND_HOST })
    }

    /**
     * 鉴权是否开启。MCP 鉴权**强制开启**(不可选):服务端一旦监听即必须携带 bearer token,
     * 避免本机其它进程或同网段主机无鉴权调用攻击工具。恒返回 true,无 setter。
     */
    @JvmStatic
    fun isAuthEnabled(): Boolean = true

    @JvmStatic
    fun getAuthToken(): String = Settings.getString(KEY_AUTH_TOKEN, "")

    @JvmStatic
    fun setAuthToken(value: String) {
        Settings.setString(KEY_AUTH_TOKEN, value)
    }

    /**
     * 若 token 为空则生成一个不可猜的随机 token 并持久化,返回当前有效 token。幂等:已有则原样返回。
     * 鉴权强制开启,故总是确保有 token。在服务端启动与 UI 面板加载时调用。
     */
    @JvmStatic
    fun ensureAuthToken(): String {
        val existing = getAuthToken()
        if (existing.isNotBlank()) {
            return existing
        }
        val token = generateToken()
        setAuthToken(token)
        return token
    }

    @JvmStatic
    fun regenerateAuthToken(): String {
        val token = generateToken()
        setAuthToken(token)
        return token
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return buildString(TOKEN_BYTES * 2 + 8) {
            append("xproxy_")
            for (b in bytes) {
                append(HEX[(b.toInt() ushr 4) and 0x0F])
                append(HEX[b.toInt() and 0x0F])
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
