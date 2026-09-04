package org.jjgroup.xproxy.mcp

import org.jjgroup.xproxy.core.Settings
import org.jjgroup.xproxy.settings.core.McpSettings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpSettingsTest {

    @BeforeEach
    fun setUp() {
        // 隔离:测试期间所有 Settings.setX 的 flush 走 no-op,不写盘,避免污染生产 Java Preferences
        // (否则 setAuthToken("") 等会持久化到 plist,导致开发机每次重启应用时 token 被迫重新生成)。
        Settings.setFlushHookForTests { }
    }

    @AfterEach
    fun cleanup() {
        // 恢复默认状态(内存),供同 JVM 后续测试;再恢复 flush hook。
        McpSettings.setEnabled(true)
        McpSettings.setBindHost("127.0.0.1")
        McpSettings.setPort(9527)
        McpSettings.setAuthToken("")
        Settings.setFlushHookForTests(null)
    }

    @Test
    fun `setters round-trip the documented defaults`() {
        McpSettings.setEnabled(true)
        McpSettings.setBindHost("127.0.0.1")
        McpSettings.setPort(9527)
        assertTrue(McpSettings.isEnabled())
        assertTrue(McpSettings.isAuthEnabled()) // 鉴权强制开启
        assertEquals("127.0.0.1", McpSettings.getBindHost())
        assertEquals(9527, McpSettings.getPort())
    }

    @Test
    fun `registerSettings is idempotent and leaves valid values`() {
        // registerSettings 不应抛异常,且不应覆盖已有非空 token。
        McpSettings.setAuthToken("preset-token")
        McpSettings.registerSettings()
        assertEquals("preset-token", McpSettings.getAuthToken(), "registerSettings must not clobber an existing token")
        assertTrue(McpSettings.getPort() in 1..65535)
    }

    @Test
    fun `ensureAuthToken generates when blank and is idempotent`() {
        McpSettings.setAuthToken("")
        val first = McpSettings.ensureAuthToken()
        assertTrue(first.isNotBlank())
        assertTrue(first.startsWith("xproxy_"))
        // 第二次应返回相同 token(幂等)——这是 token 跨重启持久化的关键保证。
        assertEquals(first, McpSettings.ensureAuthToken())
        assertEquals(first, McpSettings.getAuthToken())
    }

    @Test
    fun `auth is mandatory and always enabled`() {
        // 鉴权强制开启:isAuthEnabled 恒 true,无 setter,ensureAuthToken 总是确保有 token。
        assertTrue(McpSettings.isAuthEnabled())
        McpSettings.setAuthToken("")
        assertTrue(McpSettings.ensureAuthToken().isNotBlank())
    }

    @Test
    fun `regenerateAuthToken produces a new token`() {
        McpSettings.setAuthToken("seed-token")
        val fresh = McpSettings.regenerateAuthToken()
        assertNotEquals("seed-token", fresh)
        assertEquals(fresh, McpSettings.getAuthToken())
    }

    @Test
    fun `setPort clamps into valid range`() {
        McpSettings.setPort(0)
        assertEquals(1, McpSettings.getPort())
        McpSettings.setPort(70000)
        assertEquals(65535, McpSettings.getPort())
        McpSettings.setPort(9527)
        assertEquals(9527, McpSettings.getPort())
    }

    @Test
    fun `blank bind host falls back to loopback`() {
        McpSettings.setBindHost("   ")
        assertEquals("127.0.0.1", McpSettings.getBindHost())
    }

    @Test
    fun `disable flag is observable`() {
        McpSettings.setEnabled(false)
        assertFalse(McpSettings.isEnabled())
        McpSettings.setEnabled(true)
        assertTrue(McpSettings.isEnabled())
    }
}
