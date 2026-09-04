package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.fuzzer.request.applySyntax
import org.jjgroup.xproxy.fuzzer.request.formatBody
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.proxy.model.ProxyInterceptItem
import org.jjgroup.xproxy.settings.core.ResponsePrettySettings
import org.jjgroup.xproxy.ui.highlight.HttpHighlighter
import org.jjgroup.xproxy.ui.http.truncateForViewer
import org.jjgroup.xproxy.ui.table.MimeFilterSnapshot
import java.util.HashSet
import javax.swing.*

internal fun ProxyPanel.bindActions() {
    interceptToggle.addActionListener {
        val enabled = interceptToggle.isSelected
        controller.setInterceptEnabled(enabled)
        applyInterceptToggleVisual(enabled)
        if (!enabled && interceptModel.rowCount > 0) {
            forwardAllInterceptItems()
        }
    }

    interceptTable.selectionModel.addListSelectionListener {
        updateInterceptDetailFromSelection()
    }
    interceptTable.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseReleased(e: java.awt.event.MouseEvent?) {
            updateInterceptDetailFromSelection()
        }
    })

    historyTable.selectionModel.addListSelectionListener { event ->
        if (event.valueIsAdjusting) {
            return@addListSelectionListener
        }
        if (historyDetailActivatedByUser) {
            updateHistoryDetailFromSelection()
        }
    }
    historyTable.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseReleased(e: java.awt.event.MouseEvent?) {
            if (e != null && SwingUtilities.isLeftMouseButton(e)) {
                historyDetailActivatedByUser = true
            }
            updateHistoryDetailFromSelection()
        }
    })
    wsHistoryTable.selectionModel.addListSelectionListener { event ->
        if (event.valueIsAdjusting) {
            return@addListSelectionListener
        }
        updateWsDetailFromSelection()
    }
    wsHistoryTable.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseReleased(e: java.awt.event.MouseEvent?) {
            updateWsDetailFromSelection()
        }
    })
    applyFilters()

    forwardButton.addActionListener {
        val item = interceptModel.getAt(selectedModelRow(interceptTable))
        if (item != null) {
            if (item.phase == ProxyInterceptItem.Phase.REQUEST) {
                controller.forward(item.id, interceptDetailViewer.currentRequestTextForForward())
            } else {
                controller.forward(item.id, interceptDetailViewer.currentResponseTextForForward())
            }
        }
    }

    dropButton.addActionListener {
        val item = interceptModel.getAt(selectedModelRow(interceptTable))
        if (item != null) {
            controller.drop(item.id)
        }
    }

    forwardMenuButton.addActionListener {
        val popupWidth = forwardMenu.preferredSize.width.coerceAtLeast(1)
        forwardMenu.show(forwardMenuButton, forwardMenuButton.width - popupWidth, forwardMenuButton.height)
    }

    dropMenuButton.addActionListener {
        val popupWidth = dropMenu.preferredSize.width.coerceAtLeast(1)
        dropMenu.show(dropMenuButton, dropMenuButton.width - popupWidth, dropMenuButton.height)
    }
}

internal fun ProxyPanel.bindControllerCallbacks() {
    controller.onStatusChanged = { running, message ->
        SwingUtilities.invokeLater {
            proxyOptionsPanel.setListeningState(running, message)
        }
    }

    controller.onInterceptQueued = { item ->
        SwingUtilities.invokeLater {
            interceptModel.upsert(item)
            val last = interceptModel.rowCount - 1
            if (last >= 0) {
                interceptTable.selectionModel.setSelectionInterval(last, last)
            }
            interceptDetailViewer.showRequest(item.requestRaw)
            interceptDetailViewer.showResponse("")
            refreshInterceptActionButtons(item)
        }
    }

    controller.onInterceptChanged = { item ->
        SwingUtilities.invokeLater {
            suppressInterceptEditPersist = true
            try {
                interceptModel.upsert(item)
                val row = findInterceptRowById(item.id)
                if (row >= 0) {
                    interceptTable.selectionModel.setSelectionInterval(row, row)
                }
                updateInterceptDetailFromSelection()
            } finally {
                suppressInterceptEditPersist = false
            }
        }
    }

    controller.onInterceptResolved = { id ->
        SwingUtilities.invokeLater {
            interceptModel.removeById(id)
            if (currentInterceptItemId == id) {
                currentInterceptItemId = null
            }

            if (interceptModel.rowCount > 0) {
                interceptTable.selectionModel.setSelectionInterval(0, 0)
                updateInterceptDetailFromSelection()
            } else {
                interceptDetailViewer.clear()
                refreshInterceptActionButtons(null)
            }
        }
    }

    controller.onHistoryAdded = { entry ->
        onHistoryEntryCaptured?.invoke(entry)
        historyIdAllocator.accumulateAndGet(entry.id) { current, incoming -> maxOf(current, incoming) }
        val metadata = entry.copy(requestRaw = "", responseRaw = "", originalRequestRaw = "", originalResponseRaw = "")
        historyDetailCache[entry.id] = entry
        val keepInMemory = shouldKeepHistoryDetailInMemory(entry)
        SwingUtilities.invokeLater {
            historyModel.add(metadata)
            onHistoryEntryAdded?.invoke(metadata)
            // 不在此处调用 applyFilters():sorter 上已存在的 RowFilter 会在 fireTableRowsInserted 时即时评估新行可见性,
            // 每条流量都重建 RowFilter 会触发全表重过滤(O(n²))。过滤条件变更时由 MimeFilterBar 触发 applyFilters。
        }
        persistExecutor.execute {
            projectDataStore?.saveHistory(entry)
            if (!keepInMemory) {
                historyDetailCache.remove(entry.id)
            }
        }
    }

    controller.onWsHistoryAdded = { entry ->
        val metadata = entry.copy(payload = "")
        wsPayloadCache[entry.id] = entry.payload
        SwingUtilities.invokeLater {
            wsHistoryModel.add(metadata)
            // 同 onHistoryAdded:不在此重建 RowFilter,避免每条 WS 消息触发全表重过滤。
        }
        persistExecutor.execute {
            projectDataStore?.saveWsHistory(entry)
        }
    }

    // WebSocket 会话握手上下文落库:beginWsSession(beforeRequest) 时新增,completeWsSession(afterResponse) 时补全响应。
    controller.onWsSessionAdded = { session ->
        persistExecutor.execute {
            projectDataStore?.saveWsSession(session)
        }
    }
    controller.onWsSessionUpdated = { sessionId, handshakeResponse ->
        persistExecutor.execute {
            projectDataStore?.updateWsSessionHandshakeResponse(sessionId, handshakeResponse)
        }
    }

    controller.onHistoryUpdated = { entry, finalized ->
        // 始终更新缓存(线程安全),保证后续重选该行能看到最新 body。
        historyDetailCache[entry.id] = entry
        // 最终态落库(流式过程中的增量不落库,避免每 chunk 写库)。
        if (finalized) {
            persistExecutor.execute {
                projectDataStore?.saveHistory(entry)
            }
        }
        // latest-wins 合并显示刷新:只保留最新 entry,单次 invokeLater 落地。
        pendingSseEntry.set(entry)
        if (sseRefreshScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                sseRefreshScheduled.set(false)
                val e = pendingSseEntry.getAndSet(null) ?: return@invokeLater
                val metadata = e.copy(requestRaw = "", responseRaw = "", originalRequestRaw = "", originalResponseRaw = "")
                historyModel.update(metadata)
                // 仅当前选中的是 SSE 流式条目时刷新查看器;preserveUserTab 保留用户当前 tab。
                if (selectedHistoryId() == e.id) {
                    historyDetailViewer.showResponse(e.responseRaw, e.originalResponseRaw, preserveUserTab = true)
                }
            }
        }
    }
}

internal fun ProxyPanel.applyFilters() {
    val selectedHistoryId = selectedHistoryId()
    val selectedWsId = selectedWsHistoryId()
    val snapshot = mimeFilterState.snapshot()
    val keywordActive = snapshot.isKeywordActive()
    val store = projectDataStore
    // 每次 applyFilters 自增一次代际 token,history 与 WS 扫描共享,使下一次 applyFilters 能取消本次在途扫描。
    val myGen = filterGeneration.incrementAndGet()

    val historySorter = historyTable.rowSorter
    if (historySorter is javax.swing.table.TableRowSorter<*>) {
        if (keywordActive && store != null) {
            // 关键词激活且有持久层:后台流式扫描全表构建可见 id 集(O(1) 内存),
            // 避免有界缓存启用后 RowFilter.include 逐行 resolveHistoryDetail -> loadHistoryById 的 N+1 EDT 冻结
            // (13 万行冷缓存 ≈ 13 万次同步 DB 查询)。扫描期间保留旧 RowFilter,完成后再换。
            scheduleHistoryKeywordFilter(snapshot, store, selectedHistoryId, myGen)
        } else {
            // 无关键词:mime/status 仅依赖 metadata(无需 raw body),EDT 同步过滤即可。
            // 无持久层时关键词也走此路径(resolveHistoryDetail 仅查缓存,不触 DB)。
            historySorter.rowFilter = object : RowFilter<javax.swing.table.TableModel, Int>() {
                override fun include(entry: RowFilter.Entry<out javax.swing.table.TableModel, out Int>): Boolean {
                    val modelRow = entry.identifier
                    val item = historyModel.getAt(modelRow) ?: return false
                    val forFilter = if (keywordActive) resolveHistoryDetail(item) ?: item else item
                    return snapshot.matchesHttp(forFilter.mimeType, forFilter.statusCode, forFilter.requestRaw, forFilter.responseRaw)
                }
            }
            restoreHistorySelectionById(selectedHistoryId)
        }
    }

    val wsSorter = wsHistoryTable.rowSorter
    if (wsSorter is javax.swing.table.TableRowSorter<*>) {
        if (keywordActive && store != null) {
            scheduleWsKeywordFilter(snapshot, store, selectedWsId, myGen)
        } else {
            wsSorter.rowFilter = object : RowFilter<javax.swing.table.TableModel, Int>() {
                override fun include(entry: RowFilter.Entry<out javax.swing.table.TableModel, out Int>): Boolean {
                    val modelRow = entry.identifier
                    val item = wsHistoryModel.getAt(modelRow) ?: return false
                    val payload = if (keywordActive) resolveWsPayload(item) else item.payload
                    val searchable = "${item.host} ${item.path} ${item.direction} ${item.messageType} ${item.mimeType} ${item.preview} $payload"
                    return snapshot.matchesWs(item.mimeType, searchable)
                }
            }
            restoreWsSelectionById(selectedWsId)
        }
    }
}

/**
 * 关键词过滤-history:后台流式扫描 proxy_history 全表,对每行用 [snapshot] 评估,收集命中 id 进 [visible]。
 * 扫描完成后在 EDT 装一个 RowFilter:已扫描范围(id <= maxId)查可见集;扫描后新到的流量(id > maxId)live 评估
 * (刚捕获通常命中 historyDetailCache)。代际 token [myGen] 过期则提前终止扫描并丢弃结果。
 */
private fun ProxyPanel.scheduleHistoryKeywordFilter(
    snapshot: MimeFilterSnapshot,
    store: ProjectDataStore,
    selectedHistoryId: Long?,
    myGen: Long
) {
    keywordFilterExecutor.execute {
        val visible = HashSet<Long>()
        val maxId = store.scanHistoryDetails { id, mimeType, statusCode, requestRaw, responseRaw ->
            if (snapshot.matchesHttp(mimeType, statusCode, requestRaw, responseRaw)) {
                visible.add(id)
            }
            filterGeneration.get() == myGen
        }
        SwingUtilities.invokeLater {
            if (filterGeneration.get() != myGen) return@invokeLater
            val historySorter = historyTable.rowSorter
            if (historySorter is javax.swing.table.TableRowSorter<*>) {
                historySorter.rowFilter = object : RowFilter<javax.swing.table.TableModel, Int>() {
                    override fun include(entry: RowFilter.Entry<out javax.swing.table.TableModel, out Int>): Boolean {
                        val modelRow = entry.identifier
                        val item = historyModel.getAt(modelRow) ?: return false
                        val id = item.id
                        return if (id <= maxId) {
                            visible.contains(id)
                        } else {
                            val detail = resolveHistoryDetail(item) ?: item
                            snapshot.matchesHttp(detail.mimeType, detail.statusCode, detail.requestRaw, detail.responseRaw)
                        }
                    }
                }
            }
            restoreHistorySelectionById(selectedHistoryId)
        }
    }
}

/** 关键词过滤-WS:语义同 [scheduleHistoryKeywordFilter],扫描 ws_history 全表。 */
private fun ProxyPanel.scheduleWsKeywordFilter(
    snapshot: MimeFilterSnapshot,
    store: ProjectDataStore,
    selectedWsId: Long?,
    myGen: Long
) {
    keywordFilterExecutor.execute {
        val visible = HashSet<Long>()
        val maxId = store.scanWsDetails { id, host, path, direction, messageType, mimeType, preview, payload ->
            val searchable = "$host $path $direction $messageType $mimeType $preview $payload"
            if (snapshot.matchesWs(mimeType, searchable)) {
                visible.add(id)
            }
            filterGeneration.get() == myGen
        }
        SwingUtilities.invokeLater {
            if (filterGeneration.get() != myGen) return@invokeLater
            val wsSorter = wsHistoryTable.rowSorter
            if (wsSorter is javax.swing.table.TableRowSorter<*>) {
                wsSorter.rowFilter = object : RowFilter<javax.swing.table.TableModel, Int>() {
                    override fun include(entry: RowFilter.Entry<out javax.swing.table.TableModel, out Int>): Boolean {
                        val modelRow = entry.identifier
                        val item = wsHistoryModel.getAt(modelRow) ?: return false
                        val id = item.id
                        return if (id <= maxId) {
                            visible.contains(id)
                        } else {
                            val payload = resolveWsPayload(item)
                            val searchable = "${item.host} ${item.path} ${item.direction} ${item.messageType} ${item.mimeType} ${item.preview} $payload"
                            snapshot.matchesWs(item.mimeType, searchable)
                        }
                    }
                }
            }
            restoreWsSelectionById(selectedWsId)
        }
    }
}

private fun ProxyPanel.selectedHistoryId(): Long? {
    val row = selectedModelRow(historyTable)
    return historyModel.getAt(row)?.id
}

private fun ProxyPanel.selectedWsHistoryId(): Long? {
    val row = selectedModelRow(wsHistoryTable)
    return wsHistoryModel.getAt(row)?.id
}

private fun ProxyPanel.restoreHistorySelectionById(id: Long?) {
    if (id == null) {
        return
    }
    val modelRow = findHistoryRowById(id)
    if (modelRow < 0) {
        return
    }
    val viewRow = if (historyTable.rowSorter != null) historyTable.convertRowIndexToView(modelRow) else modelRow
    if (viewRow >= 0) {
        historyTable.selectionModel.setSelectionInterval(viewRow, viewRow)
    }
}

private fun ProxyPanel.restoreWsSelectionById(id: Long?) {
    if (id == null) {
        return
    }
    val modelRow = findWsHistoryRowById(id)
    if (modelRow < 0) {
        return
    }
    val viewRow = if (wsHistoryTable.rowSorter != null) wsHistoryTable.convertRowIndexToView(modelRow) else modelRow
    if (viewRow >= 0) {
        wsHistoryTable.selectionModel.setSelectionInterval(viewRow, viewRow)
    }
}

internal fun ProxyPanel.updateInterceptDetailFromSelection() {
    persistCurrentInterceptEdits()

    val row = selectedModelRow(interceptTable)
    val item = interceptModel.getAt(row)
    if (item == null) {
        interceptDetailViewer.clear()
        currentInterceptItemId = null
    } else {
        interceptDetailViewer.showRequest(item.requestRaw)
        if (item.phase == ProxyInterceptItem.Phase.RESPONSE) {
            interceptDetailViewer.showResponse(item.responseRaw)
        } else {
            interceptDetailViewer.showResponse("")
        }
        currentInterceptItemId = item.id
    }
    refreshInterceptActionButtons(item)
    interceptDetailViewer.revalidate()
    interceptDetailViewer.repaint()
}

internal fun ProxyPanel.updateHistoryDetailFromSelection() {
    if (!historyDetailActivatedByUser) {
        clearHistoryDetailSelection()
        return
    }
    val row = selectedModelRow(historyTable)
    val metadata = historyModel.getAt(row)
    if (metadata == null) {
        clearHistoryDetailSelection()
        return
    }

    if (lastHistoryDetailId == metadata.id) {
        return
    }

    val generation = historyDetailGeneration + 1
    historyDetailGeneration = generation
    historyDetailViewer.clear()
    historyDetailLayout.show(historyDetailCard, historyContentCardId)
    showHistoryDetailDrawer()
    historyDetailCard.revalidate()
    historyDetailCard.repaint()

    historyDetailExecutor.execute {
        val item = resolveHistoryDetail(metadata) ?: metadata
        SwingUtilities.invokeLater {
            if (generation != historyDetailGeneration) {
                return@invokeLater
            }
            if (lastHistoryDetailId == item.id &&
                lastHistoryDetailRequestRaw == item.requestRaw &&
                lastHistoryDetailResponseRaw == item.responseRaw
            ) {
                return@invokeLater
            }
            historyDetailViewer.showRequest(item.requestRaw, item.originalRequestRaw)
            historyDetailViewer.showResponse(item.responseRaw, item.originalResponseRaw)
            historyDetailLayout.show(historyDetailCard, historyContentCardId)
            showHistoryDetailDrawer()
            lastHistoryDetailId = item.id
            lastHistoryDetailRequestRaw = item.requestRaw
            lastHistoryDetailResponseRaw = item.responseRaw
            historyDetailCard.revalidate()
            historyDetailCard.repaint()
        }
    }
}

private fun ProxyPanel.clearHistoryDetailSelection() {
    historyDetailGeneration += 1
    if (lastHistoryDetailId != null || historyDetailCard.isShowing) {
        historyDetailViewer.clear()
        historyDetailLayout.show(historyDetailCard, historyEmptyCardId)
        hideHistoryDetailDrawer()
        lastHistoryDetailId = null
        lastHistoryDetailRequestRaw = ""
        lastHistoryDetailResponseRaw = ""
    }
}

internal fun ProxyPanel.updateWsDetailFromSelection() {
    val row = selectedModelRow(wsHistoryTable)
    val item = wsHistoryModel.getAt(row)
    if (item == null) {
        wsRawArea.text = ""
        wsPrettyArea.text = ""
        wsDetailLayout.show(wsDetailCard, wsEmptyCardId)
        hideWsDetailDrawer()
        lastWsDetailId = null
        lastWsDetailPayload = ""
        wsRenderPendingId = null
        wsRenderPendingPayload = ""
        wsDeferredPrettyRaw = null
        return
    }

    val rawPayload = resolveWsPayload(item)
    if (lastWsDetailId == item.id && lastWsDetailPayload == rawPayload) {
        return
    }
    if (wsRenderInProgress && wsRenderPendingId == item.id && wsRenderPendingPayload == rawPayload) {
        return
    }

    val generation = wsRenderGeneration + 1
    wsRenderGeneration = generation
    wsRenderInProgress = true
    wsRenderPendingId = item.id
    wsRenderPendingPayload = rawPayload
    wsDeferredPrettyRaw = null
    wsRawArea.text = "Rendering payload..."
    wsPrettyArea.text = "Rendering payload..."

    wsRenderExecutor.execute {
        val state = buildWsDisplayState(item.mimeType, rawPayload)
        SwingUtilities.invokeLater {
            if (generation != wsRenderGeneration) {
                return@invokeLater
            }
            applyWsDisplayState(state, generation, item.id, rawPayload)
        }
    }
}

internal fun ProxyPanel.buildWsDisplayState(mimeType: String, rawPayload: String): WsDisplayState {
    val isJson = mimeType.equals("json", ignoreCase = true)
    val bodySize = rawPayload.length
    val threshold = ResponsePrettySettings.getAutoPrettyMaxBytes().coerceAtLeast(0)
    val disableAutoPretty = bodySize > threshold
    val disableHighlight = bodySize > threshold
    val allowAutoPrettyByMime = ResponsePrettySettings.isMimeAllowed("application/json")
    val allowAutoPretty = isJson && allowAutoPrettyByMime && !disableAutoPretty

    if (!isJson) {
        val payloadPreview = truncateForViewer(rawPayload, ResponsePrettySettings.getLargeResponsePreviewMaxChars(), "WebSocket payload").first
        return WsDisplayState(
            rawText = payloadPreview,
            prettyText = payloadPreview,
            syntaxKind = BodyKind.OTHER,
            selectedTabIndex = 1,
            bodySize = bodySize,
            disableHighlight = disableHighlight,
            deferredPrettyRaw = null
        )
    }

    if (!allowAutoPretty) {
        return WsDisplayState(
            rawText = truncateForViewer(rawPayload, ResponsePrettySettings.getLargeResponsePreviewMaxChars(), "WebSocket payload").first,
            prettyText = "Large message detected (${bodySize} bytes). Auto-pretty is disabled. Raw shows a preview; switch to Pretty to render full content on demand.",
            syntaxKind = BodyKind.JSON,
            selectedTabIndex = 1,
            bodySize = bodySize,
            disableHighlight = disableHighlight,
            deferredPrettyRaw = rawPayload
        )
    }

    val prettyPayload = formatBody(rawPayload, BodyKind.JSON, "\n")
    return WsDisplayState(
        rawText = rawPayload,
        prettyText = prettyPayload,
        syntaxKind = BodyKind.JSON,
        selectedTabIndex = 0,
        bodySize = bodySize,
        disableHighlight = disableHighlight,
        deferredPrettyRaw = null
    )
}

internal fun ProxyPanel.applyWsDisplayState(state: WsDisplayState, generation: Long, itemId: Long, rawPayload: String) {
    wsDeferredPrettyRaw = state.deferredPrettyRaw

    wsRawArea.text = state.rawText
    wsRawArea.caretPosition = 0
    wsPrettyArea.text = state.prettyText
    wsPrettyArea.caretPosition = 0

    if (state.disableHighlight) {
        HttpHighlighter.setPlain(wsRawArea)
        HttpHighlighter.setPlain(wsPrettyArea)
    } else {
        applySyntax(wsRawArea, state.syntaxKind)
        applySyntax(wsPrettyArea, state.syntaxKind)
    }

    val shouldRespectUserSelection = wsUserChangedTabGeneration == generation
    if (!shouldRespectUserSelection) {
        wsProgrammaticTabChange = true
        wsDetailTabs.selectedIndex = state.selectedTabIndex
        wsProgrammaticTabChange = false
    }

    wsDetailLayout.show(wsDetailCard, wsContentCardId)
    showWsDetailDrawer()
    wsRenderInProgress = false
    wsRenderPendingId = null
    wsRenderPendingPayload = ""
    lastWsDetailId = itemId
    lastWsDetailPayload = rawPayload
}

internal fun ProxyPanel.materializeDeferredWsPrettyIfNeeded() {
    val deferred = wsDeferredPrettyRaw ?: return
    val generation = wsRenderGeneration
    wsPrettyArea.text = "Formatting pretty payload..."
    wsPrettyArea.caretPosition = 0

    wsRenderExecutor.execute {
        val pretty = runCatching { formatBody(deferred, BodyKind.JSON, "\n") }
            .getOrElse { "Failed to render Pretty payload: ${it.message}" }
        SwingUtilities.invokeLater {
            if (generation != wsRenderGeneration) {
                return@invokeLater
            }
            wsPrettyArea.text = pretty
            wsPrettyArea.caretPosition = 0
            val threshold = ResponsePrettySettings.getAutoPrettyMaxBytes().coerceAtLeast(0)
            if (deferred.length > threshold) {
                HttpHighlighter.setPlain(wsPrettyArea)
            } else {
                applySyntax(wsPrettyArea, BodyKind.JSON)
            }
            wsDeferredPrettyRaw = null
        }
    }
}
