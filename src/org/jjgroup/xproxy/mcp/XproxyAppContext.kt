package org.jjgroup.xproxy.mcp

import org.jjgroup.xproxy.fuzzer.ui.IntruderFrame
import org.jjgroup.xproxy.fuzzer.ui.IntruderUiContext
import org.jjgroup.xproxy.kits.ui.KitsPanel
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectRecord
import org.jjgroup.xproxy.proxy.ui.ProxyPanel
import org.jjgroup.xproxy.target.ui.TargetPanel

/**
 * 应用运行时上下文:把主窗口装配出来的活跃组件(projectDataStore / 各 dock 面板 / frame)暴露给 MCP 服务端。
 *
 * 背景:dock 面板在 [org.jjgroup.xproxy.fuzzer.ui.IntruderDockLayout.renderMainDockLayout] 内是局部 `val`,
 * 没有全局持有者。MCP 服务端需要访问同一个活跃实例(而非另建 ProjectDataStore 连接,避免 SQLITE_BUSY
 * 与状态不一致),故在此提供进程内单例,由 renderMainDockLayout 尾部填充、窗口关闭时清空。
 *
 * 所有字段 `@Volatile`:写入在 EDT(装配/关闭)发生,读取在 Netty I/O 线程与攻击线程,volatile 保证可见性。
 * 字段可空:应用启动早期或关闭后为 null,工具调用时通过 [requireActive] 守卫并返回友好错误。
 */
object XproxyAppContext {
    @Volatile private var projectRecord: ProjectRecord? = null
    @Volatile private var projectDataStore: ProjectDataStore? = null
    @Volatile private var proxyPanel: ProxyPanel? = null
    @Volatile private var targetPanel: TargetPanel? = null
    @Volatile private var kitsPanel: KitsPanel? = null
    @Volatile private var frame: IntruderFrame? = null

    // Fuzzer tab 上下文(持有 requestTabBar/tabStates 等),由 buildIntruderUI 装配后写入。
    // MCP 的 get_current_request 工具据此读取用户当前正在查看的 fuzzer 请求 tab。
    @Volatile private var intruderUiContext: IntruderUiContext? = null
    // 当前激活的 dock 卡片名(target/proxy/fuzzer/codec/kits/settings)提供者,在 EDT 上读取 dock 按钮选中态。
    @Volatile private var activeDockCardProvider: (() -> String?)? = null

    // 最近一次 send_request 的 请求原文 -> 响应原文 映射(线程安全 LRU)。
    // 供 confirm_vuln 在 agent 未传 response(或误用 responseRaw 字段名)时按请求原文自动回填响应,
    // 杜绝"确认漏洞后 issue 响应为空"。agent 典型流程:send_request 拿到响应 -> confirm_vuln 确认同一请求;
    // 此处在 send_request 落库前(MCP 线程)写入,confirm_vuln 同线程读取,无 EDT 依赖。
    private val recentExchanges: MutableMap<String, String> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
        }
    )

    fun bind(
        projectRecord: ProjectRecord?,
        projectDataStore: ProjectDataStore?,
        proxyPanel: ProxyPanel?,
        targetPanel: TargetPanel?,
        kitsPanel: KitsPanel?,
        frame: IntruderFrame?
    ) {
        this.projectRecord = projectRecord
        this.projectDataStore = projectDataStore
        this.proxyPanel = proxyPanel
        this.targetPanel = targetPanel
        this.kitsPanel = kitsPanel
        this.frame = frame
    }

    /** 装配阶段写入 fuzzer tab 上下文(由 buildIntruderUI 调用)。 */
    internal fun bindIntruderUiContext(ctx: IntruderUiContext?) {
        this.intruderUiContext = ctx
    }

    /** 装配阶段写入激活卡片提供者(由 renderMainDockLayout 调用,捕获 dockButtons)。 */
    fun setActiveDockCardProvider(provider: (() -> String?)?) {
        this.activeDockCardProvider = provider
    }

    fun clear() {
        projectRecord = null
        projectDataStore = null
        proxyPanel = null
        targetPanel = null
        kitsPanel = null
        frame = null
        intruderUiContext = null
        activeDockCardProvider = null
        recentExchanges.clear()
    }

    fun projectRecord(): ProjectRecord? = projectRecord
    fun projectDataStore(): ProjectDataStore? = projectDataStore
    fun proxyPanel(): ProxyPanel? = proxyPanel
    fun targetPanel(): TargetPanel? = targetPanel
    fun kitsPanel(): KitsPanel? = kitsPanel
    fun frame(): IntruderFrame? = frame
    internal fun intruderUiContext(): IntruderUiContext? = intruderUiContext
    /** 返回当前激活 dock 卡片名;须在 EDT 上调用(读取 JToggleButton.isSelected)。 */
    fun activeDockCard(): String? = activeDockCardProvider?.invoke()

    /** 项目数据存储;未就绪时抛 [IllegalStateException],由工具层捕获转成 MCP 错误结果。 */
    fun requireDataStore(): ProjectDataStore =
        projectDataStore ?: error("No project is currently loaded in xproxy.")

    /** Kits 面板(提供 xapp/attack 脚本管理器);未就绪时抛 [IllegalStateException]。 */
    fun requireKitsPanel(): KitsPanel =
        kitsPanel ?: error("The Kits panel is not ready yet.")

    /** 记录一次 send_request 的请求->响应,供 confirm_vuln 自动回填。 */
    fun rememberMcpExchange(requestRaw: String, responseText: String) {
        if (requestRaw.isBlank()) return
        recentExchanges[requestRaw] = responseText
    }

    /** 按请求原文回填最近一次 send_request 的响应;无匹配返回 null。 */
    fun lookupMcpExchange(requestRaw: String): String? =
        if (requestRaw.isBlank()) null else recentExchanges[requestRaw]
}
