package org.jjgroup.xproxy.proxy.ui

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.RTextScrollPane
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.proxy.model.ProxyInterceptItem
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRule
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRuleAction
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRuleMode
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceAction
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceMode
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceRule
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceScope
import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry
import org.jjgroup.xproxy.proxy.ws.WsFrameCodec
import org.jjgroup.xproxy.proxy.ws.WsRepeaterTarget
import org.jjgroup.xproxy.ui.marking.TrafficHighlightRegistry
import org.jjgroup.xproxy.ui.marking.buildHighlightSubmenu
import javax.swing.*

internal fun ProxyPanel.installInterceptBulkMenus() {
    val forwardAllItem = JMenuItem(I18n.t("proxy.forward_all"))
    forwardAllItem.addActionListener { forwardAllInterceptItems() }
    forwardMenu.add(forwardAllItem)

    val dropAllItem = JMenuItem(I18n.t("proxy.drop_all"))
    dropAllItem.addActionListener { dropAllInterceptItems() }
    dropMenu.add(dropAllItem)
}

internal fun ProxyPanel.forwardAllInterceptItems() {
    persistCurrentInterceptEdits()
    val items = (0 until interceptModel.rowCount)
        .mapNotNull { interceptModel.getAt(it) }
        .toList()
    items.forEach { item ->
        if (item.phase == ProxyInterceptItem.Phase.REQUEST) {
            controller.forward(item.id, item.requestRaw)
        } else {
            controller.forward(item.id, item.responseRaw)
        }
    }
}

internal fun ProxyPanel.dropAllInterceptItems() {
    persistCurrentInterceptEdits()
    val items = (0 until interceptModel.rowCount)
        .mapNotNull { interceptModel.getAt(it) }
        .toList()
    items.forEach { item ->
        controller.drop(item.id)
    }
}

internal fun ProxyPanel.markSelectedInterceptThisResponse() {
    persistCurrentInterceptEdits()
    val row = selectedModelRow(interceptTable)
    val item = interceptModel.getAt(row)
    if (item != null && item.phase == ProxyInterceptItem.Phase.REQUEST) {
        controller.markInterceptThisResponse(item.id)
    }
}

internal fun ProxyPanel.persistCurrentInterceptEdits() {
    if (suppressInterceptEditPersist) {
        return
    }
    val activeId = currentInterceptItemId ?: return
    var activeItem: ProxyInterceptItem? = null
    for (i in 0 until interceptModel.rowCount) {
        val item = interceptModel.getAt(i)
        if (item?.id == activeId) {
            activeItem = item
            break
        }
    }
    val item = activeItem ?: return
    item.requestRaw = interceptDetailViewer.currentRequestTextForForward()
    if (item.phase == ProxyInterceptItem.Phase.RESPONSE) {
        item.responseRaw = interceptDetailViewer.currentResponseTextForForward()
    }
}

internal fun ProxyPanel.installHistoryPopupMenu() {
    val popup = JPopupMenu()
    val sendToFuzzerItem = JMenuItem(I18n.t("menu.send_to_fuzzer"))
    val sendToCodecMenu = JMenu(I18n.t("menu.use_codec"))
    val deleteItem = JMenuItem(I18n.t("menu.delete_item"))
    val deleteAllItem = JMenuItem(I18n.t("menu.delete_all"))
    val highlightMenu = buildHighlightSubmenu(TrafficHighlightRegistry.Kind.HTTP) {
        selectedModelRows(historyTable).mapNotNull { historyModel.getAt(it)?.id }.toSet()
    }
    popup.add(sendToFuzzerItem)
    popup.add(sendToCodecMenu)
    popup.add(highlightMenu)
    popup.add(deleteItem)
    popup.add(deleteAllItem)

    sendToFuzzerItem.addActionListener {
        val row = selectedModelRow(historyTable)
        val selected = historyModel.getAt(row)
        val requestRaw = selected?.let { resolveHistoryDetail(it)?.requestRaw ?: it.requestRaw }
        if (!requestRaw.isNullOrBlank()) {
            onSendToFuzzer?.invoke(requestRaw, selected?.let { toHttpService(it) })
        }
    }

    fun rebuildSendToCodecMenu() {
        sendToCodecMenu.removeAll()
        val row = selectedModelRow(historyTable)
        val selected = historyModel.getAt(row)
        val requestRaw = selected?.let { resolveHistoryDetail(it)?.requestRaw ?: it.requestRaw }
        if (requestRaw.isNullOrBlank()) {
            sendToCodecMenu.isEnabled = false
            return
        }
        val defaultItem = JMenuItem(I18n.t("menu.send_to_default_codec"))
        defaultItem.addActionListener { onSendToCodec?.invoke(requestRaw, null) ?: org.jjgroup.xproxy.codec.core.CodecHub.send(requestRaw, null) }
        sendToCodecMenu.add(defaultItem)
        val tabTitles = org.jjgroup.xproxy.codec.core.CodecHub.tabTitles()
        if (tabTitles.size > 1) {
            sendToCodecMenu.addSeparator()
            tabTitles.drop(1).forEach { tabTitle ->
                val item = JMenuItem(tabTitle)
                item.addActionListener { onSendToCodec?.invoke(requestRaw, tabTitle) ?: org.jjgroup.xproxy.codec.core.CodecHub.send(requestRaw, tabTitle) }
                sendToCodecMenu.add(item)
            }
        }
        sendToCodecMenu.isEnabled = onSendToCodec != null || org.jjgroup.xproxy.codec.core.CodecHub.hasReceiver()
    }

    deleteItem.addActionListener {
        val ids = selectedModelRows(historyTable)
            .mapNotNull { historyModel.getAt(it)?.id }
            .toSet()
        deleteHistoryByIds(ids)
    }

    deleteAllItem.addActionListener {
        val confirm = JOptionPane.showConfirmDialog(
            this,
            I18n.t("proxy.delete_all_history_confirm"),
            I18n.t("menu.delete_all"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirm == JOptionPane.YES_OPTION) {
            val ids = (0 until historyModel.rowCount)
                .mapNotNull { historyModel.getAt(it)?.id }
                .toSet()
            deleteHistoryByIds(ids)
        }
    }

    historyTable.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) = maybeShow(e)
        override fun mouseReleased(e: java.awt.event.MouseEvent) = maybeShow(e)

        private fun maybeShow(e: java.awt.event.MouseEvent) {
            if (!e.isPopupTrigger) {
                return
            }
            val viewRow = historyTable.rowAtPoint(e.point)
            if (viewRow >= 0 && !historyTable.isRowSelected(viewRow)) {
                historyTable.setRowSelectionInterval(viewRow, viewRow)
            }
            val selectedRows = selectedModelRows(historyTable)
            val requestRaw = if (selectedRows.size == 1) {
                historyModel.getAt(selectedRows.first())?.let { resolveHistoryDetail(it)?.requestRaw ?: it.requestRaw }
            } else {
                null
            }
            sendToFuzzerItem.isEnabled = selectedRows.size == 1 && !requestRaw.isNullOrBlank()
            rebuildSendToCodecMenu()
            highlightMenu.isEnabled = selectedRows.isNotEmpty()
            deleteItem.text = if (selectedRows.size <= 1) I18n.t("menu.delete_item") else I18n.t("menu.delete_selected")
            deleteItem.isEnabled = selectedRows.isNotEmpty()
            deleteAllItem.isEnabled = historyModel.rowCount > 0
            popup.show(e.component, e.x, e.y)
        }
    })
}

internal fun ProxyPanel.installWsPopupMenu() {
    val popup = JPopupMenu()
    val sendToRepeaterItem = JMenuItem(I18n.t("menu.send_to_ws_repeater"))
    val deleteItem = JMenuItem(I18n.t("menu.delete_item"))
    val deleteAllItem = JMenuItem(I18n.t("menu.delete_all"))
    val highlightMenu = buildHighlightSubmenu(TrafficHighlightRegistry.Kind.WS) {
        selectedModelRows(wsHistoryTable).mapNotNull { wsHistoryModel.getAt(it)?.id }.toSet()
    }
    popup.add(sendToRepeaterItem)
    popup.addSeparator()
    popup.add(highlightMenu)
    popup.add(deleteItem)
    popup.add(deleteAllItem)

    sendToRepeaterItem.addActionListener {
        sendSelectedWsToRepeater()
    }

    deleteItem.addActionListener {
        val ids = selectedModelRows(wsHistoryTable)
            .mapNotNull { wsHistoryModel.getAt(it)?.id }
            .toSet()
        deleteWsHistoryByIds(ids)
    }

    deleteAllItem.addActionListener {
        val confirm = JOptionPane.showConfirmDialog(
            this,
            I18n.t("proxy.delete_all_ws_history_confirm"),
            I18n.t("menu.delete_all"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirm == JOptionPane.YES_OPTION) {
            val ids = (0 until wsHistoryModel.rowCount)
                .mapNotNull { wsHistoryModel.getAt(it)?.id }
                .toSet()
            deleteWsHistoryByIds(ids)
        }
    }

    wsHistoryTable.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) = maybeShow(e)
        override fun mouseReleased(e: java.awt.event.MouseEvent) = maybeShow(e)

        private fun maybeShow(e: java.awt.event.MouseEvent) {
            if (!e.isPopupTrigger) {
                return
            }
            val viewRow = wsHistoryTable.rowAtPoint(e.point)
            if (viewRow >= 0 && !wsHistoryTable.isRowSelected(viewRow)) {
                wsHistoryTable.setRowSelectionInterval(viewRow, viewRow)
            }
            val selectedRows = selectedModelRows(wsHistoryTable)
            // 仅单选且会话上下文可解析时启用重放(无 sessionId 的历史帧 / 握手帧无法重建连接)。
            val repeaterEnabled = selectedRows.size == 1 && wsHistoryModel.getAt(selectedRows.first())?.let { canReplayWsEntry(it) } == true
            sendToRepeaterItem.isEnabled = repeaterEnabled
            highlightMenu.isEnabled = selectedRows.isNotEmpty()
            deleteItem.text = if (selectedRows.size <= 1) I18n.t("menu.delete_item") else I18n.t("menu.delete_selected")
            deleteItem.isEnabled = selectedRows.isNotEmpty()
            deleteAllItem.isEnabled = wsHistoryModel.rowCount > 0
            popup.show(e.component, e.x, e.y)
        }
    })
}

internal fun ProxyPanel.installSendToFuzzerMenu(
    table: JTable,
    requestProvider: (Int) -> String?,
    serviceProvider: ((Int) -> HttpService?)? = null,
    interceptResponseAction: ((Int) -> Unit)? = null
) {
    val popup = JPopupMenu()
    val sendToFuzzerItem = JMenuItem(I18n.t("menu.send_to_fuzzer"))
    val sendToCodecMenu = JMenu(I18n.t("menu.use_codec"))
    val interceptResponseItem = JMenuItem(I18n.t("menu.intercept_this_response"))
    sendToFuzzerItem.addActionListener {
        val row = selectedModelRow(table)
        val requestRaw = requestProvider(row)
        if (!requestRaw.isNullOrBlank()) {
            onSendToFuzzer?.invoke(requestRaw, serviceProvider?.invoke(row))
        }
    }
    popup.add(sendToFuzzerItem)
    popup.add(sendToCodecMenu)

    fun rebuildSendToCodecMenu() {
        sendToCodecMenu.removeAll()
        val requestRaw = requestProvider(selectedModelRow(table))
        if (requestRaw.isNullOrBlank()) {
            sendToCodecMenu.isEnabled = false
            return
        }
        val defaultItem = JMenuItem(I18n.t("menu.send_to_default_codec"))
        defaultItem.addActionListener { onSendToCodec?.invoke(requestRaw, null) ?: org.jjgroup.xproxy.codec.core.CodecHub.send(requestRaw, null) }
        sendToCodecMenu.add(defaultItem)
        val tabTitles = org.jjgroup.xproxy.codec.core.CodecHub.tabTitles()
        if (tabTitles.size > 1) {
            sendToCodecMenu.addSeparator()
            tabTitles.drop(1).forEach { tabTitle ->
                val item = JMenuItem(tabTitle)
                item.addActionListener { onSendToCodec?.invoke(requestRaw, tabTitle) ?: org.jjgroup.xproxy.codec.core.CodecHub.send(requestRaw, tabTitle) }
                sendToCodecMenu.add(item)
            }
        }
        sendToCodecMenu.isEnabled = onSendToCodec != null || org.jjgroup.xproxy.codec.core.CodecHub.hasReceiver()
    }

    if (interceptResponseAction != null) {
        popup.add(interceptResponseItem)
        interceptResponseItem.addActionListener {
            val row = selectedModelRow(table)
            if (row >= 0) {
                interceptResponseAction.invoke(row)
            }
        }
    }

    table.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            maybeShowPopup(e)
        }

        override fun mouseReleased(e: java.awt.event.MouseEvent) {
            maybeShowPopup(e)
        }

        private fun maybeShowPopup(e: java.awt.event.MouseEvent) {
            if (!e.isPopupTrigger) {
                return
            }
            val row = table.rowAtPoint(e.point)
            if (row >= 0) {
                table.setRowSelectionInterval(row, row)
            }
            sendToFuzzerItem.isEnabled = row >= 0 && !requestProvider(selectedModelRow(table)).isNullOrBlank()
            rebuildSendToCodecMenu()
            if (interceptResponseAction != null) {
                val item = if (row >= 0) interceptModel.getAt(selectedModelRow(table)) else null
                interceptResponseItem.isEnabled = item != null && item.phase == ProxyInterceptItem.Phase.REQUEST
            }
            popup.show(e.component, e.x, e.y)
        }
    })
}

internal fun ProxyPanel.ensureDemoMatchReplaceRule(rules: List<ProxyMatchReplaceRule>): List<ProxyMatchReplaceRule> {
    val presets = listOf(
        ProxyMatchReplaceRule(
            ruleId = "preset-mr-url-path-rewrite",
            enabled = false,
            name = "URL path rewrite",
            scope = ProxyMatchReplaceScope.REQUEST_FIRST_LINE,
            mode = ProxyMatchReplaceMode.TEXT,
            matchText = "/legacy-api",
            replaceText = "/api"
        ),
        ProxyMatchReplaceRule(
            ruleId = "preset-mr-force-https",
            enabled = false,
            name = "Force HTTPS absolute URL",
            scope = ProxyMatchReplaceScope.REQUEST_FIRST_LINE,
            mode = ProxyMatchReplaceMode.TEXT,
            matchText = "http://",
            replaceText = "https://"
        ),
        ProxyMatchReplaceRule(
            ruleId = "preset-mr-add-token-header",
            enabled = false,
            name = "Add request header Token",
            scope = ProxyMatchReplaceScope.REQUEST_HEADER,
            mode = ProxyMatchReplaceMode.TEXT,
            action = ProxyMatchReplaceAction.ADD,
            matchText = "",
            replaceText = "Token:login"
        ),
        ProxyMatchReplaceRule(
            ruleId = "preset-mr-add-remember-cookie",
            enabled = false,
            name = "Add shiro cookie RememberMe",
            scope = ProxyMatchReplaceScope.REQUEST_HEADER,
            mode = ProxyMatchReplaceMode.TEXT,
            action = ProxyMatchReplaceAction.ADD,
            matchText = "",
            replaceText = "Cookie: RememberMe=1"
        ),
        ProxyMatchReplaceRule(
            ruleId = "preset-mr-redact-token",
            enabled = false,
            name = "Redact token in request body",
            scope = ProxyMatchReplaceScope.REQUEST_BODY,
            mode = ProxyMatchReplaceMode.REGEX,
            matchText = "\\\"token\\\"\\s*:\\s*\\\"[^\\\"]+\\\"",
            replaceText = "\"token\":\"<redacted>\""
        ),
        ProxyMatchReplaceRule(
            ruleId = "preset-mr-mask-server-header",
            enabled = false,
            name = "Mask response server header",
            scope = ProxyMatchReplaceScope.RESPONSE_HEADER,
            mode = ProxyMatchReplaceMode.TEXT,
            matchText = "nginx",
            replaceText = "xproxy"
        ),
        ProxyMatchReplaceRule(
            ruleId = "preset-mr-redact-email",
            enabled = false,
            name = "Redact email in response body",
            scope = ProxyMatchReplaceScope.RESPONSE_BODY,
            mode = ProxyMatchReplaceMode.REGEX,
            matchText = "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
            replaceText = "redacted@example.com"
        )
    )

    val normalized = rules.map { rule ->
        when (rule.ruleId) {
            "demo-http-match-replace-rule" -> rule.copy(name = "URL path rewrite", enabled = false)
            else -> rule
        }
    }

    val existingIds = normalized.map { it.ruleId }.toHashSet()
    val missing = presets.filter { it.ruleId !in existingIds }
    return normalized + missing
}

internal fun ProxyPanel.ensureDemoInterceptRule(rules: List<ProxyInterceptRule>): List<ProxyInterceptRule> {
    val presets = listOf(
        ProxyInterceptRule(
            ruleId = "preset-ir-forward-health-check",
            enabled = false,
            name = "Auto forward health check",
            mode = ProxyInterceptRuleMode.TEXT,
            matchText = "/health",
            action = ProxyInterceptRuleAction.FORWARD,
            matchRequestHeader = true,
            matchRequestBody = false,
            matchResponseHeader = false,
            matchResponseBody = false
        ),
        ProxyInterceptRule(
            ruleId = "preset-ir-drop-image-response",
            enabled = false,
            name = "Auto drop image response",
            mode = ProxyInterceptRuleMode.TEXT,
            matchText = "Content-Type: image/",
            action = ProxyInterceptRuleAction.DROP,
            matchRequestHeader = false,
            matchRequestBody = false,
            matchResponseHeader = true,
            matchResponseBody = false
        ),
        ProxyInterceptRule(
            ruleId = "preset-ir-forward-websocket-upgrade",
            enabled = false,
            name = "Auto forward websocket upgrade",
            mode = ProxyInterceptRuleMode.TEXT,
            matchText = "Upgrade: websocket",
            action = ProxyInterceptRuleAction.FORWARD,
            matchRequestHeader = true,
            matchRequestBody = false,
            matchResponseHeader = false,
            matchResponseBody = false
        ),
        ProxyInterceptRule(
            ruleId = "preset-ir-drop-tracking-pixel",
            enabled = false,
            name = "Auto drop tracking pixel",
            mode = ProxyInterceptRuleMode.TEXT,
            matchText = "/pixel.gif",
            action = ProxyInterceptRuleAction.DROP,
            matchRequestHeader = true,
            matchRequestBody = false,
            matchResponseHeader = false,
            matchResponseBody = false
        )
    )

    val normalized = rules.map { rule ->
        when (rule.ruleId) {
            "demo-intercept-rule" -> rule.copy(name = "Auto drop image response", enabled = false)
            else -> rule
        }
    }

    val existingIds = normalized.map { it.ruleId }.toHashSet()
    val missing = presets.filter { it.ruleId !in existingIds }
    return normalized + missing
}

/**
 * 该 WS 历史帧是否可重放:需有 sessionId 且能从会话表取回握手上下文。
 * 握手请求/响应帧本身(messageType 以 "Handshake" 开头)不作为可重放数据帧。
 */
internal fun ProxyPanel.canReplayWsEntry(entry: ProxyWsHistoryEntry): Boolean {
    val sessionId = entry.sessionId ?: return false
    if (sessionId <= 0L) return false
    if (entry.messageType.startsWith("Handshake")) return false
    return projectDataStore?.loadWsSession(sessionId) != null
}

internal fun ProxyPanel.sendSelectedWsToRepeater() {
    val row = selectedModelRow(wsHistoryTable)
    val entry = wsHistoryModel.getAt(row) ?: return
    val sessionId = entry.sessionId
    if (sessionId == null || sessionId <= 0L) {
        JOptionPane.showMessageDialog(this, I18n.t("wsrepeater.error.no_session"), I18n.t("menu.send_to_ws_repeater"), JOptionPane.WARNING_MESSAGE)
        return
    }
    val session = projectDataStore?.loadWsSession(sessionId)
    if (session == null) {
        JOptionPane.showMessageDialog(this, I18n.t("wsrepeater.error.no_session"), I18n.t("menu.send_to_ws_repeater"), JOptionPane.WARNING_MESSAGE)
        return
    }
    val payload = resolveWsPayload(entry)
    val opcode = opcodeForWsMessageType(entry.messageType)
    val target = WsRepeaterTarget(
        host = session.host,
        port = session.port,
        tls = session.tls,
        path = session.path,
        handshakeRequest = session.handshakeRequest
    )
    // 复用代理侧仍存活的原始 WS 连接(若已断开则为 null,重放器将提示用户重连->独立新连接)。
    val liveConnection = entry.sessionId?.let { controller.wsLiveConnections[it] }
    onSendToWsRepeater?.invoke(target, opcode, payload, liveConnection)
}

internal fun opcodeForWsMessageType(messageType: String): Int = when (messageType) {
    "Text", "Continuation" -> WsFrameCodec.OPCODE_TEXT
    "Binary" -> WsFrameCodec.OPCODE_BINARY
    "Ping" -> WsFrameCodec.OPCODE_PING
    "Close" -> WsFrameCodec.OPCODE_CLOSE
    else -> WsFrameCodec.OPCODE_TEXT
}

/**
 * 为 WS 详情视图(pretty/raw 编辑器)挂载右键菜单:发送到重放器 + 复制 + 全选。
 * 复用 [sendSelectedWsToRepeater](作用于当前选中的 WS 历史条目,与表格右键一致)。
 * 通过 RTextArea 的 setPopupMenu(reflection)替换默认菜单,同时挂到滚动窗格与视口,确保区域内任意位置右键均可用。
 */
internal fun ProxyPanel.attachWsDetailPopup(area: RSyntaxTextArea, pane: RTextScrollPane) {
    val popup = JPopupMenu()
    val sendItem = JMenuItem(I18n.t("menu.send_to_ws_repeater"))
    val copyItem = JMenuItem(I18n.t("menu.copy"))
    val selectAllItem = JMenuItem(I18n.t("menu.select_all"))
    popup.add(sendItem)
    popup.addSeparator()
    popup.add(copyItem)
    popup.add(selectAllItem)

    sendItem.addActionListener { sendSelectedWsToRepeater() }
    copyItem.addActionListener { area.copy() }
    selectAllItem.addActionListener {
        area.selectAll()
        area.requestFocusInWindow()
    }

    popup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {
            val entry = wsHistoryModel.getAt(selectedModelRow(wsHistoryTable))
            sendItem.isEnabled = entry != null && canReplayWsEntry(entry)
            copyItem.isEnabled = area.selectedText?.isNotBlank() == true
        }

        override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {}
        override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
    })

    runCatching {
        area.javaClass.getMethod("setPopupMenu", JPopupMenu::class.java).invoke(area, popup)
    }.onFailure { area.componentPopupMenu = popup }
    pane.componentPopupMenu = popup
    pane.viewport.componentPopupMenu = popup
}
