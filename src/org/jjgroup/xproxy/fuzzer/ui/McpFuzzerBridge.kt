package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.codec.core.CodecHub
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.project.core.ProjectDataStore
import java.awt.Component
import java.awt.Frame

/**
 * MCP agent 与 fuzzer UI 之间的桥接门面:把 agent 的 send_request / run_attack / confirm_vuln 操作落到
 * 可见的 fuzzer tab,使人工能像查看手工测试一样审查 agent 的行为。
 *
 * - send_request:进对应站点 host 分组的 probe tab 的 back/forward 历史。
 * - run_attack:新建 tab(基础请求模板)+ 脚本载入编辑器 + 弹出 RequestTable 结果窗口(非 headless)。
 * - confirm_vuln:为确认漏洞的请求单独建 `* <漏洞名>` 子 tab,归入 host 分组。
 *
 * 均为 [IntruderUiContext] 上的 internal 扩展,由 `org.jjgroup.xproxy.mcp` 包在同模块内调用
 * (Kotlin internal = 模块可见,跨包可访问,与 get_current_request 复用 currentTabState() 同模式)。
 * 必须在 EDT 上调用(创建 tab / 改编辑器文本);调用方负责线程切换与超时回退。
 */

private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

private fun isIpAddress(host: String): Boolean {
    if (host.isBlank()) return false
    if (IPV4_REGEX.matches(host)) {
        return host.split('.').all { it.toIntOrNull()?.let { v -> v in 0..255 } ?: false }
    }
    // IPv6:含 ':' 且仅由十六进制 / ':' / '.' 构成。
    if (host.contains(':')) {
        return host.all { it.isHexDigit() || it == ':' || it == '.' }
    }
    return false
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * 站点分组键:域名 -> 域名;IP -> `ip:port`(IPv6 加方括号)。
 * 与用户约定一致("域名或者ip:port"):同一域名的不同端口归同一分组;IP 站点按 ip:port 区分。
 */
internal fun hostGroupKey(target: HttpService): String {
    val host = target.host
    return if (isIpAddress(host)) {
        if (host.contains(':')) "[$host]:${target.port}" else "$host:${target.port}"
    } else {
        host
    }
}

/** 把主窗口带到前台(若最小化则还原),使人工能即时看到 agent 的活动。macOS 上通常表现为 dock 图标跳动。 */
private fun IntruderUiContext.bringFrameToFront() {
    val f = frame
    if (f.extendedState != Frame.NORMAL) {
        f.extendedState = Frame.NORMAL
    }
    f.toFront()
}

/**
 * 把一次 send_request 的交换(请求+响应)记录进对应站点 host 分组的 probe tab 的 back/forward 历史。
 *
 * 每个站点 host 复用一个 probe tab(不存在或已关闭则新建,归入 [hostGroupKey] 分组):agent 连发同站请求
 * 累积进同一 tab 的历史,人工可用 < / > 按钮前后切换查看每条测试数据包的详情(与手工 repeater 一致);
 * 切到另一站点则另建一个 probe tab。每条历史携带 target,切换时恢复 targetLabel 与重发目标。
 *
 * @return 记录所在的 tab 组件;UI 未就绪或创建失败返回 null(调用方应作 best-effort,不影响 agent 拿响应)
 */
internal fun IntruderUiContext.recordMcpExchange(
    requestRaw: String,
    responseText: String,
    target: HttpService,
    statusText: String,
    responseBytes: Int,
    elapsedMillis: Long
): Component? {
    val groupKey = hostGroupKey(target)
    var tab = mcpSendTabs[groupKey]?.let { if (tabStates.containsKey(it)) it else null }
    if (tab == null) {
        tab = createAndSelectRequestTabWithRequest(requestRaw, triggerNotification = true, targetHint = target)
        if (tab == addTabPanel) return null
        mcpSendTabs[groupKey] = tab
        setTabGroup(tab, groupKey)
        tabHeaderStates[tab]?.label?.text = "AgentAutoTest"
        refreshRequestTabStyles()
        // 首次创建该站点的 probe tab 时把主窗口带到前台,提示人工 agent 开始活动;后续连发不重复打扰。
        bringFrameToFront()
    } else {
        // 切回该站点的 probe tab,使人工即时看到 agent 最新一次发送(显性)。
        requestTabBar.selectedComponent = tab
    }
    val state = tabStates[tab] ?: return null
    val entry = FuzzerSendHistoryEntry(
        requestRaw = requestRaw,
        responseText = responseText,
        fullUrl = toFullUrl(target, requestRaw),
        statusText = statusText,
        responseBytes = responseBytes,
        elapsedMillis = elapsedMillis,
        target = target
    )
    // makeCurrent=true:把该交换载入请求/响应编辑器,人工立即看到这次测试的数据包详情。
    tabRecordExchange[tab]?.invoke(entry, true)
    return tab
}

/**
 * 为"确认存在漏洞"的请求单独建一个 `* <漏洞名>` 子 tab(归入对应站点 host 分组),方便人工查看。
 * 每次调用新建一个 tab(一个漏洞一个子 tab),载入确认请求+响应作为其首条历史。
 *
 * @return 子 tab 组件;UI 未就绪或创建失败返回 null
 */
internal fun IntruderUiContext.recordMcpVuln(
    requestRaw: String,
    responseText: String,
    target: HttpService,
    vulnName: String,
    statusText: String,
    responseBytes: Int,
    elapsedMillis: Long,
    evidence: List<String> = emptyList()
): Component? {
    val tab = createAndSelectRequestTabWithRequest(requestRaw, triggerNotification = true, targetHint = target)
    if (tab == addTabPanel) return null
    val state = tabStates[tab] ?: return null
    val groupKey = hostGroupKey(state.target)
    setTabGroup(tab, groupKey)
    val safeName = vulnName.trim().replace("\r", " ").replace("\n", " ").take(60)
    tabHeaderStates[tab]?.label?.text = "* $safeName"
    refreshRequestTabStyles()
    bringFrameToFront()
    val entry = FuzzerSendHistoryEntry(
        requestRaw = requestRaw,
        responseText = responseText,
        fullUrl = toFullUrl(state.target, requestRaw),
        statusText = statusText,
        responseBytes = responseBytes,
        elapsedMillis = elapsedMillis,
        target = state.target,
        evidence = evidence
    )
    tabRecordExchange[tab]?.invoke(entry, true)
    // 确认漏洞是显著事件:同步落库一次(await=true),保证 `* <漏洞名>` 标题 + host 分组在返回前已持久化。
    // 否则仅靠上面 recordExchange -> onHistoryChanged -> persistAllFuzzerTabs() 的异步写线程落库,
    // 一旦应用被强杀(hard-kill,shutdownFuzzer 未跑),DB 只留下 createAndSelectRequestTabWithRequest
    // 在 setTabGroup/改标题之前拍下的预变更快照(计数标题 + 无分组),reload 后子 tab 脱离分组且标题丢失。
    persistAllFuzzerTabs(await = true)
    return tab
}

/**
 * 以可见方式启动一次 attack:新建 fuzzer tab 装入基础请求模板(归入站点 host 分组)+ 把 agent 脚本载入
 * 共享脚本编辑器 + 经 [launchAttackOnTab] 装配 RequestTable 与结果窗口(JFrame 弹出,非 headless),
 * 再 [AttackLaunch.runEval] 驱动 evalJython。完成后回调 [onCompleted](Throwable? 为 null=正常)。
 *
 * @return 攻击启动句柄(含 handler / 结果表 / baseRequestHash);UI 未就绪或 tab 创建失败返回 null
 */
internal fun IntruderUiContext.launchMcpAttack(
    rawRequest: String,
    scriptCode: String,
    baseInput: String,
    projectDataStore: ProjectDataStore?,
    onCompleted: (Throwable?) -> Unit
): AttackLaunch? {
    val frame = this.frame
    val tab = createAndSelectRequestTabWithRequest(rawRequest, triggerNotification = true, targetHint = null)
    if (tab == addTabPanel) return null
    val state = tabStates[tab] ?: return null
    setTabGroup(tab, hostGroupKey(state.target))
    tabHeaderStates[tab]?.label?.text = I18n.t("fuzzer.mcp_attack_tab")
    refreshRequestTabStyles()
    // 把 agent 的内联攻击脚本载入共享脚本编辑器,使人工能看到 agent 实际运行的脚本(显性)。
    scriptEditor?.text = scriptCode
    // 攻击是显著事件:把主窗口带到前台,结果窗口也会随后弹出并 toFront。
    bringFrameToFront()
    val onSendToFuzzer: (String, HttpService?) -> Unit = { requestRaw, targetHint ->
        createAndSelectRequestTabWithRequest(requestRaw, true, targetHint ?: state.target)
    }
    val onSendToCodec: (String, String?) -> Unit = { text, tabTitle -> CodecHub.send(text, tabTitle) }
    val launch = launchAttackOnTab(
        state = state,
        scriptCode = scriptCode,
        baseRequest = rawRequest,
        baseInput = baseInput,
        projectDataStore = projectDataStore,
        frame = frame,
        tabStates = tabStates,
        onSendToFuzzer = onSendToFuzzer,
        onSendToCodec = onSendToCodec
    )
    launch.runEval(onCompleted)
    return launch
}
