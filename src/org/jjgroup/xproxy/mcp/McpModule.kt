package org.jjgroup.xproxy.mcp

import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.mcp.attack.McpAttackRunner
import org.jjgroup.xproxy.mcp.server.McpDispatcher
import org.jjgroup.xproxy.mcp.server.McpServer
import org.jjgroup.xproxy.mcp.tools.McpToolContext
import org.jjgroup.xproxy.mcp.tools.McpToolRegistry
import org.jjgroup.xproxy.mcp.tools.advancedTools
import org.jjgroup.xproxy.mcp.tools.currentSelectionTools
import org.jjgroup.xproxy.mcp.tools.fuzzerTools
import org.jjgroup.xproxy.mcp.tools.metaTools
import org.jjgroup.xproxy.mcp.tools.scriptTools
import org.jjgroup.xproxy.mcp.tools.trafficTools
import org.jjgroup.xproxy.settings.core.McpSettings

/**
 * MCP 子系统门面:聚合工具注册表、JSON-RPC dispatcher、攻击运行器与 Netty 服务端,
 * 对外暴露 start/stop/restart/status,供应用生命周期与 Settings 面板调用。
 *
 * 单例(object):应用内唯一一个 MCP 服务端实例。[attackRunner] 与工具注册表在首次启动时创建并复用,
 * 服务端实例随 start/stop 重建(便于端口/开关变更后重启)。
 */
object McpModule {
    private val attackRunner = McpAttackRunner()
    private val registry: McpToolRegistry = McpToolRegistry().apply {
        registerAll(trafficTools())
        registerAll(scriptTools())
        registerAll(fuzzerTools())
        registerAll(metaTools())
        registerAll(currentSelectionTools())
        registerAll(advancedTools())
    }
    private val ctx = McpToolContext(attackRunner)
    private val dispatcher = McpDispatcher(registry, ctx)

    @Volatile
    private var server: McpServer? = null
    @Volatile
    private var shutdownHookRegistered = false

    // 状态变更监听(供 Settings 面板等在 start/stop 后刷新显示)。start/stop 在后台线程,
    // 监听器内须自行 invokeLater 回 EDT。autostart 在 Settings 面板构造之后才跑,若无此通知,
    // 状态行会停在构造时的"已停止"不自更新(显示 bug)。
    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 注册状态变更监听,返回取消注册的句柄。 */
    fun addStatusListener(listener: () -> Unit): () -> Unit {
        statusListeners.add(listener)
        return { statusListeners.remove(listener) }
    }

    private fun notifyStatusListeners() {
        statusListeners.forEach { runCatching { it() } }
    }

    /** 仅供测试:直接触发状态监听,验证注册/通知/取消机制。 */
    internal fun notifyStatusListenersForTests() = notifyStatusListeners()

    private fun registerShutdownHookIfNeeded() {
        if (shutdownHookRegistered) return
        shutdownHookRegistered = true
        runCatching {
            Runtime.getRuntime().addShutdownHook(Thread({ runCatching { shutdown() } }, "xproxy-mcp-shutdown"))
        }
    }

    fun toolCount(): Int = registry.all().size

    /** 是否已启动。 */
    fun isRunning(): Boolean = server?.isRunning() == true

    /**
     * 按 [McpSettings] 启动服务端。鉴权开启时确保 token 已生成。重复调用幂等(已运行则跳过)。
     * @return 启动结果消息(成功/失败原因),供调用方日志与 UI 反馈。
     */
    @Synchronized
    fun start(): String {
        if (server?.isRunning() == true) return "Already running."
        if (!McpSettings.isEnabled()) {
            notifyStatusListeners()
            return "MCP is disabled in settings."
        }
        // 鉴权强制开启:确保有持久化 token。
        McpSettings.ensureAuthToken()
        registerShutdownHookIfNeeded()
        val msg = try {
            val srv = McpServer(McpSettings.getBindHost(), McpSettings.getPort(), dispatcher)
            srv.start()
            server = srv
            "MCP server listening on ${endpointUrl()} (tools=${toolCount()}, auth=${McpSettings.isAuthEnabled()})"
        } catch (e: Throwable) {
            server = null
            AppLogger.error("MCP start failed", e)
            "Failed to start MCP: ${e.message ?: e.javaClass.simpleName}"
        }
        notifyStatusListeners()
        return msg
    }

    /** 停止服务端。攻击运行器保留(已在跑的攻击继续),仅关网络端点。 */
    @Synchronized
    fun stop(): String {
        val srv = server ?: return "Not running."
        srv.stop()
        server = null
        notifyStatusListeners()
        return "MCP server stopped."
    }

    /** 设置变更后重启(端口/主机/鉴权切换)。 */
    @Synchronized
    fun restart(): String {
        if (server?.isRunning() != true && !McpSettings.isEnabled()) return "MCP is disabled."
        stop()
        return start()
    }

    /** 应用退出时调用:停服务端 + 终止攻击线程。 */
    fun shutdown() {
        runCatching { server?.stop() }
        server = null
        runCatching { attackRunner.shutdown() }
        notifyStatusListeners()
    }

    fun endpointUrl(): String = "http://${McpSettings.getBindHost()}:${McpSettings.getPort()}/mcp"

    /** 供 Settings 面板展示与 agent 配置参考的状态快照。 */
    fun statusSummary(): Map<String, Any?> = mapOf(
        "running" to (server?.isRunning() == true),
        "enabled" to McpSettings.isEnabled(),
        "bindHost" to McpSettings.getBindHost(),
        "port" to McpSettings.getPort(),
        "authEnabled" to McpSettings.isAuthEnabled(),
        "endpoint" to endpointUrl(),
        "toolCount" to toolCount()
    )
}
