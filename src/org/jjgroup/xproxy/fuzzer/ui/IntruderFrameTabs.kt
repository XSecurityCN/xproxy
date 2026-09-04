package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.model.RequestTabState
import org.jjgroup.xproxy.fuzzer.request.sendSingleRequest
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.kits.core.HttpViewerToolContext
import org.jjgroup.xproxy.ui.http.HttpRequestResponseViewer

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.concurrent.thread

fun createRequestTabComponent(
    title: String,
    requestText: String,
    cardId: String,
    owner: IntruderFrame?,
    requestTabBar: JTabbedPane,
    tabStates: MutableMap<Component, RequestTabState>,
    cardPanel: JPanel,
    initialService: HttpService,
    buildTabHeader: (String, Component) -> Component,
    updateRequestFromRaw: (RequestTabState, String) -> Unit,
    updateRequestFromPretty: (RequestTabState, String) -> Unit,
    inferTargetFromRequest: (String, HttpService) -> HttpService,
    updateResponseDerived: (RequestTabState) -> Unit,
    updateTargetDisplay: (RequestTabState) -> Unit,
    applyIntruderVisibility: (RequestTabState?) -> Unit,
    updateAttackButtonState: (RequestTabState?) -> Unit,
    onSendToFuzzer: ((String, HttpService?) -> Unit)? = null,
    onSendToCodec: ((String, String?) -> Unit)? = null,
    initialResponseText: String = "",
    initialHistory: List<FuzzerSendHistoryEntry> = emptyList(),
    onTabSnapshotChanged: (String, HttpService) -> Unit = { _, _ -> },
    onHistoryChanged: (List<FuzzerSendHistoryEntry>) -> Unit = {},
    onResponseFinalized: (RequestTabState) -> Unit = {},
    // 由调用方提供:把"外部注入一条发送历史"(如 MCP agent 的 send_request)的回调注册到 tab 持有者,
    // 使外部交换能进入该 tab 的 back/forward 历史。参数:tab 组件,记录回调。
    registerRecordExchange: (Component, (FuzzerSendHistoryEntry, Boolean) -> Unit) -> Unit = { _, _ -> }
): Component {
    var requestEditorRef: RSyntaxTextArea? = null
    var requestSyncRef: (() -> Boolean)? = null
    var setRequestSyncRef: ((Boolean) -> Unit)? = null
    var stateRef: RequestTabState? = null

    fun applyRequestMutation(
        transform: (String, RequestTabState) -> String?,
        targetResolver: ((String, RequestTabState) -> HttpService)? = null
    ) {
        val editor = requestEditorRef ?: return
        val state = stateRef ?: return
        val requestSyncGetter = requestSyncRef ?: return
        val requestSyncSetter = setRequestSyncRef ?: return
        val updated = transform(editor.text, state) ?: return
        val normalizedUpdated = normalizeEditorLineEndings(updated)
        SwingUtilities.invokeLater {
            if (requestSyncGetter.invoke()) {
                return@invokeLater
            }
            requestSyncSetter.invoke(true)
            try {
                editor.text = normalizedUpdated
                updateRequestFromRaw(state, normalizedUpdated)
                state.target = targetResolver?.invoke(normalizedUpdated, state)
                    ?: inferTargetFromRequest(normalizedUpdated, state.target)
                updateTargetDisplay(state)
                onTabSnapshotChanged(normalizedUpdated, state.target)
                editor.revalidate()
                editor.repaint()
                state.requestPretty.revalidate()
                state.requestPretty.repaint()
            } finally {
                requestSyncSetter.invoke(false)
            }
        }
    }

    val viewer = HttpRequestResponseViewer(
        requestEditable = true,
        responseRenderVisible = true,
        showExchangeStatusStrip = true,
        onSendToFuzzer = { requestRaw -> onSendToFuzzer?.invoke(requestRaw, stateRef?.target) },
        onSendToCodec = onSendToCodec,
        onPasteHostUrlAsRequest = {
            val pasted = readUrlFromClipboard() ?: return@HttpRequestResponseViewer
            applyRequestMutation(
                transform = { currentRaw, _ -> applyUrlToRequestAsChrome(currentRaw, pasted.uri, pasted.rawQueryOverride) },
                targetResolver = { updatedRequest, state ->
                    resolveTargetForPaste(state.target, pasted.uri, updatedRequest)
                }
            )
        },
        onChangeRequestMethod = {
            applyRequestMutation(transform = { currentRaw, _ -> toggleRequestMethod(currentRaw) })
        },
        onChangeBodyEncoding = { targetEncoding ->
            applyRequestMutation(transform = { currentRaw, _ -> convertRequestBodyEncoding(currentRaw, targetEncoding) })
        },
        requestSchemeProvider = { stateRef?.target?.protocol },
        toolContext = HttpViewerToolContext.FUZZER,
        onApplyRequestMutation = { newRaw ->
            applyRequestMutation(transform = { _, _ -> newRaw })
            true
        }
    )
    val requestRaw = viewer.requestRawArea
    requestEditorRef = requestRaw
    val requestPretty = viewer.requestPrettyArea
    val responseRaw = viewer.responseRawArea
    val responsePretty = viewer.responsePrettyArea
    val responseRender = viewer.responseRenderArea

    requestRaw.font = requestRaw.font.deriveFont(12f)
    requestRaw.text = normalizeEditorLineEndings(requestText)
    requestPretty.font = requestPretty.font.deriveFont(12f)
    responseRaw.font = responseRaw.font.deriveFont(12f)
    responsePretty.font = responsePretty.font.deriveFont(12f)
    responseRender.font = responseRender.font.deriveFont(12f)

    val toolbar = JPanel(BorderLayout())
    val toolbarLeft = JPanel(FlowLayout(FlowLayout.LEFT))
    val editTargetButton = JButton(I18n.t("common.edit"))
    val settingsButton = JButton(I18n.t("fuzzer.options"))
    I18nBinder.bindText(editTargetButton, "common.edit")
    I18nBinder.bindText(settingsButton, "fuzzer.options")
    val targetLabel = JLabel()
    toolbarLeft.add(targetLabel)
    toolbarLeft.add(editTargetButton)
    toolbarLeft.add(settingsButton)
    toolbar.add(toolbarLeft, BorderLayout.WEST)

    val toolbarRight = JPanel(FlowLayout(FlowLayout.RIGHT))
    val backButton = SplitNavButton("<")
    val forwardButton = SplitNavButton(">")
    val followRedirectButton = JButton(I18n.t("fuzzer.follow_redirect"))
    val sendButton = OrangeForwardStyleButton(I18n.t("fuzzer.send"))
    val cancelButton = JButton(I18n.t("common.cancel"))
    val intruderButton = JButton(I18n.t("fuzzer.intruder"))
    I18nBinder.bindText(followRedirectButton, "fuzzer.follow_redirect")
    I18nBinder.bindText(sendButton, "fuzzer.send")
    I18nBinder.bindText(cancelButton, "common.cancel")
    I18nBinder.bindText(intruderButton, "fuzzer.intruder")
    sendButton.apply {
        preferredSize = intruderButton.preferredSize
        minimumSize = intruderButton.minimumSize
        maximumSize = intruderButton.maximumSize
    }
    cancelButton.apply {
        preferredSize = intruderButton.preferredSize
        minimumSize = intruderButton.minimumSize
        maximumSize = intruderButton.maximumSize
        isEnabled = false
    }
    toolbarRight.add(backButton)
    toolbarRight.add(forwardButton)
    toolbarRight.add(followRedirectButton)
    toolbarRight.add(sendButton)
    toolbarRight.add(cancelButton)
    toolbarRight.add(intruderButton)
    toolbar.add(toolbarRight, BorderLayout.EAST)

    val contentPanel = JPanel(BorderLayout())
    contentPanel.add(toolbar, BorderLayout.NORTH)
    contentPanel.add(viewer, BorderLayout.CENTER)

    val tabComponent = JPanel().apply {
        preferredSize = java.awt.Dimension(0, 0)
        minimumSize = java.awt.Dimension(0, 0)
        maximumSize = java.awt.Dimension(0, 0)
    }
    val state = RequestTabState(
        tabComponent = tabComponent,
        cardId = cardId,
        cardComponent = contentPanel,
        requestEditor = requestRaw,
        requestPretty = requestPretty,
        responseRaw = responseRaw,
        responsePretty = responsePretty,
        responseRender = responseRender,
        responseViewer = viewer,
        targetLabel = targetLabel,
        intruderVisible = false,
        responseText = initialResponseText,
        target = HttpService(initialService.host, initialService.port, initialService.protocol)
    )
    stateRef = state
    I18nBinder.bind { updateTargetDisplay(state) }

    var requestSync = false
    requestSyncRef = { requestSync }
    setRequestSyncRef = { value -> requestSync = value }
    requestRaw.document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) {
            if (requestSync) {
                return
            }
            requestSync = true
            updateRequestFromRaw(state, requestRaw.text)
            if (isTargetBlank(state.target)) {
                val inferred = inferTargetForBlankFromRequest(requestRaw.text, state.target)
                if (inferred != state.target) {
                    state.target = inferred
                    updateTargetDisplay(state)
                }
            }
            onTabSnapshotChanged(state.requestEditor.text, state.target)
            requestSync = false
        }

        override fun removeUpdate(e: DocumentEvent?) {
            if (requestSync) {
                return
            }
            requestSync = true
            updateRequestFromRaw(state, requestRaw.text)
            if (isTargetBlank(state.target)) {
                val inferred = inferTargetForBlankFromRequest(requestRaw.text, state.target)
                if (inferred != state.target) {
                    state.target = inferred
                    updateTargetDisplay(state)
                }
            }
            onTabSnapshotChanged(state.requestEditor.text, state.target)
            requestSync = false
        }

        override fun changedUpdate(e: DocumentEvent?) {
            if (requestSync) {
                return
            }
            requestSync = true
            updateRequestFromRaw(state, requestRaw.text)
            if (isTargetBlank(state.target)) {
                val inferred = inferTargetForBlankFromRequest(requestRaw.text, state.target)
                if (inferred != state.target) {
                    state.target = inferred
                    updateTargetDisplay(state)
                }
            }
            onTabSnapshotChanged(state.requestEditor.text, state.target)
            requestSync = false
        }
    })
    requestPretty.document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) {
            if (requestSync) {
                return
            }
            requestSync = true
            updateRequestFromPretty(state, requestPretty.text)
            onTabSnapshotChanged(state.requestEditor.text, state.target)
            requestSync = false
        }

        override fun removeUpdate(e: DocumentEvent?) {
            if (requestSync) {
                return
            }
            requestSync = true
            updateRequestFromPretty(state, requestPretty.text)
            onTabSnapshotChanged(state.requestEditor.text, state.target)
            requestSync = false
        }

        override fun changedUpdate(e: DocumentEvent?) {
            if (requestSync) {
                return
            }
            requestSync = true
            updateRequestFromPretty(state, requestPretty.text)
            onTabSnapshotChanged(state.requestEditor.text, state.target)
            requestSync = false
        }
    })
    requestSync = true
    try {
        updateRequestFromRaw(state, requestRaw.text)
    } finally {
        requestSync = false
    }
    updateResponseDerived(state)
    updateTargetDisplay(state)

    val sendHistory = ArrayList<FuzzerSendHistoryEntry>()
    sendHistory.addAll(initialHistory)
    var historyCursor = if (sendHistory.isEmpty()) -1 else sendHistory.lastIndex
    var autoUpdateContentLength = true
    var autoFollowRedirect = false
    var syncHost = false

    val settingsMenu = JPopupMenu()
    val updateContentLengthItem = JCheckBoxMenuItem(I18n.t("fuzzer.update_content_length"), true)
    val autoFollowRedirectItem = JCheckBoxMenuItem(I18n.t("fuzzer.follow_redirect"), false)
    val syncHostItem = JCheckBoxMenuItem(I18n.t("fuzzer.sync_host"), false)
    I18nBinder.bindText(updateContentLengthItem, "fuzzer.update_content_length")
    I18nBinder.bindText(autoFollowRedirectItem, "fuzzer.follow_redirect")
    I18nBinder.bindText(syncHostItem, "fuzzer.sync_host")
    settingsMenu.add(updateContentLengthItem)
    settingsMenu.add(autoFollowRedirectItem)
    settingsMenu.add(syncHostItem)
    updateContentLengthItem.addActionListener {
        autoUpdateContentLength = updateContentLengthItem.isSelected
    }
    autoFollowRedirectItem.addActionListener {
        autoFollowRedirect = autoFollowRedirectItem.isSelected
    }
    syncHostItem.addActionListener {
        syncHost = syncHostItem.isSelected
    }
    settingsButton.addActionListener {
        settingsMenu.show(settingsButton, 0, settingsButton.height)
    }

    fun updateFollowRedirectVisibility() {
        val statusCode = parseResponseStatusCode(state.responseText)
        val isRedirect = statusCode != null && statusCode in 300..399
        val hasLocation = parseResponseHeaders(state.responseText)["location"]?.isNotBlank() == true
        val visible = isRedirect && hasLocation
        followRedirectButton.isVisible = visible
        followRedirectButton.isEnabled = visible
    }

    fun applyExchange(exchange: FuzzerSendHistoryEntry) {
        requestRaw.text = normalizeEditorLineEndings(exchange.requestRaw)
        // 切换到该历史条目时同步恢复其目标服务,使 targetLabel 与后续重发目标正确
        // (跨主机历史:如 MCP agent 连发不同主机、或跟随重定向产生的多条目)。
        exchange.target?.let {
            state.target = it
            updateTargetDisplay(state)
        }
        state.responseText = exchange.responseText
        // 带 evidence 高亮(confirm_vuln 上报的关键证据片段),等价于 updateResponseDerived 但附带证据。
        state.responseViewer.showResponse(state.responseText, evidence = exchange.evidence)
        state.responseViewer.showExchangeStatus(exchange.statusText, exchange.responseBytes, exchange.elapsedMillis)
        updateFollowRedirectVisibility()
        onTabSnapshotChanged(state.requestEditor.text, state.target)
    }

    fun updateNavControls() {
        backButton.isEnabled = historyCursor > 0
        forwardButton.isEnabled = historyCursor >= 0 && historyCursor < sendHistory.lastIndex
    }

    updateNavControls()

    editTargetButton.addActionListener {
        showEditTargetDialog(owner, state, updateTargetDisplay, onTabSnapshotChanged)
    }

    var isSending = false
    var cancelRequested = false
    var sendWorker: Thread? = null
    followRedirectButton.isVisible = false
    followRedirectButton.isEnabled = false

    fun sendCurrentRequest() {
        if (isSending) {
            return
        }
        cancelRequested = false
        var requestToSend = state.requestEditor.text
        // 请求为空时:不发送。target 空先弹 target 编辑框让用户填;有 target 后生成基础 GET 请求
        // 放入编辑器并交给用户编辑后再点发送,避免给空请求加 Content-Length: 0 后无意义地发送失败。
        if (requestToSend.isBlank()) {
            if (isTargetBlank(state.target)) {
                showEditTargetDialog(owner, state, updateTargetDisplay, onTabSnapshotChanged)
            }
            if (isTargetBlank(state.target)) {
                return
            }
            val baseRequest = buildBlankRequestFromTarget(state.target)
            syncRequestEditorPreservingView(
                state = state,
                newRequestRaw = baseRequest,
                setRequestSync = { value -> requestSync = value },
                updateRequestFromRaw = updateRequestFromRaw,
                onTabSnapshotChanged = onTabSnapshotChanged
            )
            return
        }
        if (autoUpdateContentLength) {
            val updated = updateRequestContentLength(requestToSend)
            if (updated != requestToSend) {
                syncRequestEditorPreservingView(
                    state = state,
                    newRequestRaw = updated,
                    setRequestSync = { value -> requestSync = value },
                    updateRequestFromRaw = updateRequestFromRaw,
                    onTabSnapshotChanged = onTabSnapshotChanged
                )
                requestToSend = updated
            }
        }

        isSending = true
        sendButton.isEnabled = false
        cancelButton.isEnabled = true
        state.responseViewer.showExchangeStatus(I18n.t("fuzzer.sending"), 0, 0)
        var initialTarget = state.target
        // 新建空白 tab(target 空)时,发送前从请求文本自动识别填充 host/port/协议,无需勾选 Sync Host
        if (isTargetBlank(state.target)) {
            val inferred = inferTargetForBlankFromRequest(requestToSend, state.target)
            if (inferred != state.target) {
                state.target = inferred
                updateTargetDisplay(state)
                onTabSnapshotChanged(state.requestEditor.text, state.target)
            }
            initialTarget = inferred
        } else if (syncHost) {
            val syncedTarget = inferTargetFromRequest(requestToSend, state.target)
            if (syncedTarget != state.target) {
                state.target = syncedTarget
                updateTargetDisplay(state)
                onTabSnapshotChanged(state.requestEditor.text, state.target)
            }
            initialTarget = syncedTarget
        }
        sendWorker = thread {
            val exchanges = ArrayList<FuzzerSendHistoryEntry>()
            var currentRequest = requestToSend
            var currentTarget = initialTarget
            var redirects = 0
            while (true) {
                if (cancelRequested || Thread.currentThread().isInterrupted) {
                    break
                }
                val startedAt = System.nanoTime()
                val response = try {
                    sendSingleRequest(
                        service = currentTarget,
                        requestText = currentRequest,
                        shouldCancel = { cancelRequested || Thread.currentThread().isInterrupted },
                        onProgress = { partial ->
                            val partialBytes = partial.toByteArray(Charsets.ISO_8859_1).size
                            SwingUtilities.invokeLater {
                                state.responseText = partial
                                state.responseViewer.showResponse(partial, preserveUserTab = true)
                                state.responseViewer.showExchangeStatus(I18n.t("fuzzer.sending"), partialBytes, 0)
                            }
                        }
                    ).also {
                        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                        exchanges.add(
                            FuzzerSendHistoryEntry(
                                requestRaw = currentRequest,
                                responseText = it,
                                fullUrl = toFullUrl(currentTarget, currentRequest),
                                statusText = I18n.t("fuzzer.done"),
                                responseBytes = it.toByteArray(Charsets.ISO_8859_1).size,
                                elapsedMillis = elapsedMillis,
                                target = currentTarget
                            )
                        )
                    }
                } catch (ex: Exception) {
                    val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
                    if (cancelRequested || Thread.currentThread().isInterrupted) {
                        break
                    }
                    exchanges.add(
                        FuzzerSendHistoryEntry(
                            requestRaw = currentRequest,
                            responseText = "",
                            fullUrl = toFullUrl(currentTarget, currentRequest),
                            statusText = I18n.t("fuzzer.send_error", "error" to (ex.message ?: ex.javaClass.simpleName)),
                            responseBytes = 0,
                            elapsedMillis = elapsedMillis,
                            target = currentTarget
                        )
                    )
                    break
                }

                if (cancelRequested || Thread.currentThread().isInterrupted) {
                    break
                }
                if (!autoFollowRedirect || redirects >= 10) {
                    break
                }
                val statusCode = parseResponseStatusCode(response) ?: break
                if (statusCode !in 300..399) {
                    break
                }
                val redirectUri = resolveRedirectUri(currentRequest, response, currentTarget) ?: break
                val updatedRequest = applyUrlToRequest(currentRequest, redirectUri) ?: break
                currentRequest = if (autoUpdateContentLength) updateRequestContentLength(updatedRequest) else updatedRequest
                currentTarget = targetFromUri(redirectUri, currentTarget)
                redirects += 1
            }

            SwingUtilities.invokeLater {
                val last = exchanges.lastOrNull()
                if (last != null) {
                    syncRequestEditorPreservingView(
                        state = state,
                        newRequestRaw = last.requestRaw,
                        setRequestSync = { value -> requestSync = value },
                        updateRequestFromRaw = updateRequestFromRaw,
                        onTabSnapshotChanged = onTabSnapshotChanged
                    )
                    state.target = currentTarget
                    updateTargetDisplay(state)
                    state.responseText = last.responseText
                    updateResponseDerived(state)
                }
                if (historyCursor < sendHistory.lastIndex) {
                    sendHistory.subList(historyCursor + 1, sendHistory.size).clear()
                }
                sendHistory.addAll(exchanges)
                historyCursor = sendHistory.lastIndex
                updateNavControls()
                updateFollowRedirectVisibility()
                onTabSnapshotChanged(state.requestEditor.text, state.target)
                onHistoryChanged(sendHistory.toList())
                // 响应已落定:检测 101 自动激活 WS 重放布局(在当前 tab 内)。
                onResponseFinalized(state)
                val finalStatus = when {
                    last == null -> I18n.t("fuzzer.done")
                    last.statusText.startsWith("Error", ignoreCase = true) -> last.statusText
                    else -> I18n.t("fuzzer.done")
                }
                val finalBytes = last?.responseBytes ?: 0
                val finalElapsed = last?.elapsedMillis ?: 0
                state.responseViewer.showExchangeStatus(finalStatus, finalBytes, finalElapsed)
                sendButton.isEnabled = true
                cancelButton.isEnabled = false
                isSending = false
                cancelRequested = false
                sendWorker = null
            }
        }
    }
    backButton.onPrimaryClick = {
        if (historyCursor > 0) {
            historyCursor -= 1
            applyExchange(sendHistory[historyCursor])
            updateNavControls()
        }
    }

    forwardButton.onPrimaryClick = {
        if (historyCursor >= 0 && historyCursor < sendHistory.lastIndex) {
            historyCursor += 1
            applyExchange(sendHistory[historyCursor])
            updateNavControls()
        }
    }

    backButton.menuItemsProvider = {
        val menus = ArrayList<Pair<String, () -> Unit>>()
        for (idx in 0 until historyCursor) {
            menus.add(
                "${idx + 1}.${sendHistory[idx].fullUrl}" to {
                    historyCursor = idx
                    applyExchange(sendHistory[historyCursor])
                    updateNavControls()
                }
            )
        }
        menus
    }

    forwardButton.menuItemsProvider = {
        val menus = ArrayList<Pair<String, () -> Unit>>()
        for (idx in (historyCursor + 1)..sendHistory.lastIndex) {
            if (idx >= 0) {
                menus.add(
                    "${idx + 1}.${sendHistory[idx].fullUrl}" to {
                        historyCursor = idx
                        applyExchange(sendHistory[historyCursor])
                        updateNavControls()
                    }
                )
            }
        }
        menus
    }

    sendButton.addActionListener { sendCurrentRequest() }
    cancelButton.addActionListener {
        if (!isSending) {
            return@addActionListener
        }
        cancelRequested = true
        cancelButton.isEnabled = false
        sendWorker?.interrupt()
    }

    followRedirectButton.addActionListener {
        if (isSending) {
            return@addActionListener
        }
        val statusCode = parseResponseStatusCode(state.responseText) ?: return@addActionListener
        if (statusCode !in 300..399) {
            updateFollowRedirectVisibility()
            return@addActionListener
        }
        val redirectUri = resolveRedirectUri(state.requestEditor.text, state.responseText, state.target) ?: return@addActionListener
        val updatedRequest = applyUrlToRequest(state.requestEditor.text, redirectUri) ?: return@addActionListener
        requestRaw.text = normalizeEditorLineEndings(updatedRequest)
        state.target = targetFromUri(redirectUri, state.target)
        updateTargetDisplay(state)
        onTabSnapshotChanged(state.requestEditor.text, state.target)
        sendCurrentRequest()
    }

    intruderButton.addActionListener {
        state.intruderVisible = true
        applyIntruderVisibility(state)
        updateAttackButtonState(state)
    }

    cardPanel.add(contentPanel, cardId)
    tabStates[tabComponent] = state
    requestTabBar.insertTab(title, null, tabComponent, null, requestTabBar.tabCount - 1)
    val index = requestTabBar.indexOfComponent(tabComponent)
    if (index != -1) {
        requestTabBar.setTabComponentAt(index, buildTabHeader(title, tabComponent))
    }
    updateFollowRedirectVisibility()
    if (historyCursor >= 0 && historyCursor < sendHistory.size) {
        val current = sendHistory[historyCursor]
        state.responseViewer.showExchangeStatus(current.statusText, current.responseBytes, current.elapsedMillis)
    } else {
        state.responseViewer.clearExchangeStatus()
    }

    // 外部(如 MCP agent 的 send_request)注入一条发送历史:截断当前游标之后的分支(与手工发送一致),
    // 追加条目,推进游标,按需把该交换载入编辑器/响应区,刷新导航控件并通知持久化。
    val recordExchange: (FuzzerSendHistoryEntry, Boolean) -> Unit = { entry, makeCurrent ->
        if (historyCursor < sendHistory.lastIndex) {
            sendHistory.subList(historyCursor + 1, sendHistory.size).clear()
        }
        sendHistory.add(entry)
        historyCursor = sendHistory.lastIndex
        if (makeCurrent) {
            applyExchange(entry)
        }
        updateNavControls()
        updateFollowRedirectVisibility()
        onHistoryChanged(sendHistory.toList())
    }
    registerRecordExchange(tabComponent, recordExchange)

    return tabComponent
}
