package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.codec.core.CodecHub
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.project.core.FuzzerTabHistoryRecord
import org.jjgroup.xproxy.project.core.FuzzerTabRecord
import org.jjgroup.xproxy.project.core.FuzzerTabWsFrameRecord

import java.awt.Component
import java.util.UUID
import javax.swing.*
import javax.swing.event.CaretListener

internal fun IntruderUiContext.visibleRequestTabs(): List<Component> =
    tabOrder.filter { tabStates.containsKey(it) }

internal fun IntruderUiContext.groupMembers(groupName: String): List<Component> =
    visibleRequestTabs().filter { tabGroups[it].orEmpty() == groupName }

internal fun IntruderUiContext.groupTabCount(groupName: String): Int =
    visibleRequestTabs().count { tabGroups[it].orEmpty() == groupName }

internal fun IntruderUiContext.toggleTabGroupCollapsed(groupName: String) {
    val normalized = groupName.trim()
    if (normalized.isEmpty()) return
    if (!collapsedTabGroups.add(normalized)) {
        collapsedTabGroups.remove(normalized)
    }
    rebuildRequestTabBar()
    persistAllFuzzerTabs()
}


internal fun IntruderUiContext.persistAllFuzzerTabs(await: Boolean = false) {
    val store = projectDataStore ?: return
    if (restoreDepth > 0) {
        pendingPersistAfterRestore = true
        return
    }
    persistTimer?.stop()
    persistTimer = null
    pendingTabPersist = false

    val persistGeneration = tabPersistGeneration + 1
    tabPersistGeneration = persistGeneration
    val selectedComponent = requestTabBar.selectedComponent
    var position = 0
    val activeTabIds = LinkedHashSet<String>()
    val tabRecords = ArrayList<FuzzerTabRecord>()
    val historyRecords = ArrayList<Pair<String, List<FuzzerTabHistoryRecord>>>()
    val wsFrameRecords = ArrayList<Pair<String, List<FuzzerTabWsFrameRecord>>>()
    for (component in visibleRequestTabs()) {
        val state = tabStates[component] ?: continue
        val tabId = tabPersistentIds.getOrPut(component) { UUID.randomUUID().toString() }
        activeTabIds.add(tabId)
        val title = tabHeaderStates[component]?.label?.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: requestTabBar.indexOfComponent(component).takeIf { it != -1 }?.let { requestTabBar.getTitleAt(it).trim() }?.takeIf { it.isNotEmpty() }
            ?: "tab-${position + 1}"
        // WS 模式 tab:持久化重放器当前的握手/帧类型/载荷(握手写入 request_raw,重建时由 buildWsTargetFromState 取回)。
        val wsPanel = state.wsPanel
        val isWsMode = wsPanel != null
        tabRecords.add(
            FuzzerTabRecord(
                tabId = tabId,
                title = title,
                requestRaw = if (isWsMode) wsPanel!!.currentHandshake() else state.requestEditor.text,
                responseText = state.responseText,
                targetHost = state.target.host,
                targetPort = state.target.port,
                targetProtocol = state.target.protocol,
                positionIndex = position,
                selected = component == selectedComponent,
                groupName = tabGroups[component].orEmpty(),
                groupColor = tabGroups[component].orEmpty().takeIf { it.isNotBlank() }
                    ?.let { tabGroupColor(it)?.toHexString() }
                    .orEmpty(),
                isWsMode = isWsMode,
                wsOpcode = wsPanel?.currentOpcode() ?: 1,
                wsPayload = wsPanel?.currentPayload() ?: ""
            )
        )
        val history = tabSendHistories[component].orEmpty().map {
            FuzzerTabHistoryRecord(
                requestRaw = it.requestRaw,
                responseText = it.responseText,
                fullUrl = it.fullUrl,
                statusText = it.statusText,
                responseBytes = it.responseBytes,
                elapsedMillis = it.elapsedMillis
            )
        }
        historyRecords.add(tabId to history)
        // WS 模式 tab:快照已交换的帧(出站+入站)一并持久化;HTTP tab 存空列表以清理可能的残留。
        val wsFrames = if (isWsMode) wsPanel!!.snapshotFrames() else emptyList()
        wsFrameRecords.add(tabId to wsFrames)
        position += 1
    }

    // DB 写入:异步(默认,EDT 不阻塞)或同步(await=true,用于窗口关闭时确保落库)。
    fun write() {
        if (persistGeneration != tabPersistGeneration) {
            return
        }
        runCatching {
            tabRecords.forEach { store.upsertFuzzerTab(it) }
            historyRecords.forEach { (tabId, history) -> store.replaceFuzzerTabHistory(tabId, history) }
            wsFrameRecords.forEach { (tabId, frames) -> store.replaceFuzzerTabWsFrames(tabId, frames) }
            if (persistGeneration == tabPersistGeneration) {
                store.deleteFuzzerTabsNotIn(activeTabIds)
            }
        }
    }
    if (await) {
        write()
    } else {
        Thread({ write() }, "xproxy-fuzzer-tabs-persist").apply { isDaemon = true }.start()
    }
}

internal fun IntruderUiContext.scheduleFuzzerTabsPersist(delayMillis: Int = 450) {
    if (projectDataStore == null) return
    if (restoreDepth > 0) {
        pendingPersistAfterRestore = true
        return
    }
    pendingTabPersist = true
    persistTimer?.stop()
    persistTimer = Timer(delayMillis) {
        if (pendingTabPersist) {
            persistAllFuzzerTabs()
        }
    }.apply {
        isRepeats = false
        start()
    }
}

internal fun IntruderUiContext.createAndSelectRequestTab(
    triggerNotification: Boolean = true,
    fillSeedContent: Boolean = false
) {
    if (creatingTab) {
        return
    }
    creatingTab = true
    try {
        val nextName = (tabCounter++).toString()
        // fillSeedContent=false:手动点 "+" 新建空白 tab(不填内容,target 也清空,留给粘贴/编辑/发送时按需识别填充)。
        // fillSeedContent=true:创建项目时的 example request tab,填充示例请求并保留默认 target。
        val newTab = createRequestTab(nextName, if (fillSeedContent) initialRequestText else "")
        if (!fillSeedContent) {
            val state = tabStates[newTab]
            if (state != null) {
                state.target = HttpService("", 0, "")
                updateTargetDisplay(state)
            }
        }
        requestTabBar.selectedComponent = newTab
        persistAllFuzzerTabs()
        if (triggerNotification) {
            notifyFuzzerNewTab?.invoke()
        }
    } finally {
        creatingTab = false
    }
}

internal fun IntruderUiContext.createAndSelectRequestTabWithRequest(
    requestText: String,
    triggerNotification: Boolean = true,
    targetHint: HttpService? = null
): Component {
    if (creatingTab) {
        return addTabPanel
    }
    creatingTab = true
    try {
        val nextName = (tabCounter++).toString()
        val newTab = createRequestTab(nextName, requestText)
        val newState = tabStates[newTab]
        if (newState != null) {
            newState.target = inferTargetFromRequest(requestText, targetHint ?: initialService)
            updateTargetDisplay(newState)
        }
        requestTabBar.selectedComponent = newTab
        persistAllFuzzerTabs()
        if (triggerNotification) {
            notifyFuzzerNewTab?.invoke()
        }
        return newTab
    } finally {
        creatingTab = false
    }
}

internal fun IntruderUiContext.closeRequestTab(target: Component) {
    suppressAddTabAutoCreate = true
    try {
        val index = requestTabBar.indexOfComponent(target)
        val wasSelected = requestTabBar.selectedComponent == target
        val tabIdToDelete = tabPersistentIds[target]
        if (index != -1) {
            requestTabBar.removeTabAt(index)
        }
        tabStates.remove(target)?.let { state ->
            cardPanel.remove(state.cardComponent)
            // WS 覆盖视图:关闭连接/执行器并移除其卡片。
            state.wsPanel?.shutdown()
            state.wsCardComponent?.let { cardPanel.remove(it) }
        }
        tabHeaderStates.remove(target)
        tabPersistentIds.remove(target)
        tabSendHistories.remove(target)
        tabRecordExchange.remove(target)
        mcpSendTabs.entries.removeIf { it.value === target }
        tabOrder.remove(target)
        val removedGroup = tabGroups.remove(target).orEmpty()
        if (removedGroup.isNotBlank() && groupTabCount(removedGroup) == 0) {
            collapsedTabGroups.remove(removedGroup)
        }
        if (tabIdToDelete != null) {
            projectDataStore?.deleteFuzzerTab(tabIdToDelete)
        }
        if (tabStates.isEmpty()) {
            rebuildRequestTabBar(addTabPanel)
            cardLayout.show(cardPanel, emptyCardId)
            applyIntruderVisibility(null)
            updateAttackButtonState(null)
        } else {
            val nextSelection = if (wasSelected) visibleRequestTabs().getOrNull((index - 1).coerceAtLeast(0)) else requestTabBar.selectedComponent
            rebuildRequestTabBar(nextSelection)
            if (requestTabBar.selectedComponent == addTabPanel) {
                selectFirstRequestTab()
            }
        }
        persistAllFuzzerTabs()
    } finally {
        suppressAddTabAutoCreate = false
    }
}

internal fun IntruderUiContext.createRequestTabWithState(
    title: String,
    requestText: String,
    tabId: String = UUID.randomUUID().toString(),
    responseText: String = "",
    target: HttpService = HttpService(initialService.host, initialService.port, initialService.protocol),
    initialHistory: List<FuzzerSendHistoryEntry> = emptyList(),
    groupName: String = "",
    groupColorHex: String = ""
): Component {
    var createdComponent: Component? = null
    val tabComponent = createRequestTabComponent(
        title = title,
        requestText = requestText,
        cardId = "card-${cardCounter++}",
        owner = frame,
        requestTabBar = requestTabBar,
        tabStates = tabStates,
        cardPanel = cardPanel,
        initialService = initialService,
        buildTabHeader = ::buildTabHeader,
        updateRequestFromRaw = ::updateRequestFromRaw,
        updateRequestFromPretty = ::updateRequestFromPretty,
        inferTargetFromRequest = ::inferTargetFromRequest,
        updateResponseDerived = ::updateResponseDerived,
        updateTargetDisplay = ::updateTargetDisplay,
        applyIntruderVisibility = { state -> applyIntruderVisibility(state) },
        updateAttackButtonState = { state -> updateAttackButtonState(state) },
        onSendToFuzzer = { requestRaw, targetHint ->
            val source = createdComponent
            val sourceGroup = source?.let { tabGroups[it] }.orEmpty()
            val newTab = createAndSelectRequestTabWithRequest(requestRaw, true, targetHint)
            if (sourceGroup.isNotBlank() && newTab != addTabPanel) {
                // 源 tab 处于某个分组时,新 tab 沿用同一分组(含分组颜色)并聚合到该分组末位成员之后,
                // 而不是落到分组之外。
                setTabGroup(newTab, sourceGroup)
            }
        },
        onSendToCodec = { text, tabTitle ->
            CodecHub.send(text, tabTitle)
        },
        initialResponseText = responseText,
        initialHistory = initialHistory,
        onTabSnapshotChanged = { _, _ ->
            if (createdComponent != null) {
                // 每次按键都会触发;走已有 debounce 版,避免每键同步序列化全部 tab 写 DB。
                scheduleFuzzerTabsPersist()
            }
        },
        onHistoryChanged = { history ->
            val component = createdComponent
            if (component != null) {
                tabSendHistories[component] = history.toMutableList()
                persistAllFuzzerTabs()
            }
        },
        onResponseFinalized = { state ->
            // HTTP 发送完成:若 101 则在当前 tab 内自动激活 WS 重放布局。
            maybeActivateWsOnResponse(state)
        },
        registerRecordExchange = { comp, fn -> tabRecordExchange[comp] = fn }
    )

    createdComponent = tabComponent
    if (!tabOrder.contains(tabComponent)) {
        tabOrder.add(tabComponent)
    }
    tabPersistentIds[tabComponent] = tabId
    tabSendHistories[tabComponent] = initialHistory.toMutableList()
    if (groupName.isNotBlank()) {
        tabGroups[tabComponent] = groupName
        colorFromHex(groupColorHex)?.let { tabGroupColors[groupName] = it }
        tabHeaderStates[tabComponent]?.groupName = groupName
        refreshRequestTabStyles()
    }

    val state = tabStates[tabComponent]
    if (state != null) {
        state.requestEditor.addCaretListener(CaretListener { refreshPlaceholderButtons() })
        state.requestPretty.addCaretListener(CaretListener { refreshPlaceholderButtons() })
        state.target = target.copy()
        state.responseText = responseText
        updateTargetDisplay(state)
        updateResponseDerived(state)
    }
    persistAllFuzzerTabs()
    return tabComponent
}

internal fun IntruderUiContext.nextDuplicateTitle(sourceTitle: String): String {
    val used = tabHeaderStates.values.map { it.label.text }.toSet()
    val base = "$sourceTitle-copy"
    if (!used.contains(base)) {
        return base
    }
    var i = 2
    while (true) {
        val candidate = "$base-$i"
        if (!used.contains(candidate)) {
            return candidate
        }
        i += 1
    }
}

internal fun IntruderUiContext.beginRestorePhase() {
    restoreDepth += 1
    suppressAddTabAutoCreate = true
}

internal fun IntruderUiContext.endRestorePhase() {
    restoreDepth = (restoreDepth - 1).coerceAtLeast(0)
    suppressAddTabAutoCreate = restoreDepth > 0
    if (restoreDepth == 0 && pendingPersistAfterRestore) {
        pendingPersistAfterRestore = false
        persistAllFuzzerTabs()
    }
}
