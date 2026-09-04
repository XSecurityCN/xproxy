package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.model.RequestTabState

import javax.swing.JPanel

/**
 * 在指定 HTTP tab 上激活 WS 重放 UI 布局:创建 [WsRepeaterSessionPanel] 作为该 tab 卡片的覆盖视图,
 * 后续选中该 tab 时优先展示 WS 卡片(而非 HTTP 请求/响应视图)。HTTP 视图与编辑器保留在底层,不破坏。
 *
 * - 101 响应:用当前 tab 的请求作为握手(handshake = state.requestEditor.text),opcode=Text,payload 空。
 * - 其它模块发来 WS 信息:用传入 [target](含会话握手与初始 payload)。
 */
internal fun IntruderUiContext.activateWsMode(
    state: RequestTabState,
    target: org.jjgroup.xproxy.proxy.ws.WsRepeaterTarget,
    opcode: Int,
    payload: String,
    liveConnection: org.jjgroup.xproxy.proxy.ws.WsLiveConnection? = null
) {
    if (state.wsPanel != null) {
        return // 已处于 WS 模式,不重复激活
    }
    val wsPanel = org.jjgroup.xproxy.proxy.ui.WsRepeaterSessionPanel(target, opcode, payload, liveConnection)
    val wsCardId = "ws-card-${cardCounter++}"
    val wsContent = JPanel(java.awt.BorderLayout()).apply { add(wsPanel, java.awt.BorderLayout.CENTER) }
    cardPanel.add(wsContent, wsCardId)
    state.wsPanel = wsPanel
    state.wsCardId = wsCardId
    state.wsCardComponent = wsContent
    // WS 面板的任何内容变更(帧增删、载荷/握手编辑、帧类型切换)都触发去抖持久化,
    // 否则 activateWsMode 之后的 WS 模式与已交换帧不会落库,重启后 tab 退化为 HTTP。
    wsPanel.onContentChanged = { scheduleFuzzerTabsPersist() }
    if (requestTabBar.selectedComponent == state.tabComponent) {
        cardLayout.show(cardPanel, wsCardId)
        applyIntruderVisibility(null)
        updateAttackButtonState(null)
    }
    // 立即调度一次持久化,确保 isWsMode/wsOpcode/wsPayload(及空帧列表)写入。
    scheduleFuzzerTabsPersist()
}

/**
 * HTTP 发送完成后的钩子:若响应状态码为 101(Switching Protocols),自动在当前 tab 内激活 WS 重放布局。
 */
internal fun IntruderUiContext.maybeActivateWsOnResponse(state: RequestTabState) {
    if (state.wsPanel != null) return
    val code = parseResponseStatusCode(state.responseText) ?: return
    if (code != 101) return
    val target = buildWsTargetFromState(state)
    activateWsMode(state, target, org.jjgroup.xproxy.proxy.ws.WsFrameCodec.OPCODE_TEXT, "")
}

/** 由当前 tab 的请求行 + target 派生 WS 重放目标(握手请求 = 当前 HTTP 请求文本)。 */
internal fun IntruderUiContext.buildWsTargetFromState(state: RequestTabState): org.jjgroup.xproxy.proxy.ws.WsRepeaterTarget {
    val tls = state.target.protocol.lowercase() == "https"
    val handshake = state.requestEditor.text
    val path = handshake.lineSequence().firstOrNull()
        ?.split(' ')
        ?.getOrNull(1)
        ?.substringBefore('?')
        ?.ifBlank { "/" }
        ?: "/"
    return org.jjgroup.xproxy.proxy.ws.WsRepeaterTarget(
        host = state.target.host,
        port = state.target.port,
        tls = tls,
        path = path,
        handshakeRequest = handshake
    )
}
