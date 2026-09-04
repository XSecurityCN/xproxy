package org.jjgroup.xproxy.codec.ui

import org.jjgroup.xproxy.codec.core.CodecSettings
import org.jjgroup.xproxy.codec.core.CodecState
import org.jjgroup.xproxy.codec.core.CodecTabRecord

internal fun CodecPanel.restoreState(state: CodecState) {
    suspendPersist = true
    try {
        defaultTabId = state.defaultTabId
        if (state.tabs.isEmpty()) {
            createAndSelectTab(title = "default", makeDefault = true)
        } else {
            state.tabs.forEach { tab ->
                createAndSelectTab(
                    title = tab.title,
                    tabId = tab.tabId,
                    input = tab.input,
                    rules = tab.rules,
                    select = false,
                    makeDefault = tab.tabId == state.defaultTabId
                )
            }
            val selected = tabById[state.selectedTabId]
                ?: tabById[defaultTabId]
                ?: tabByComponent.values.firstOrNull()
            if (selected != null) {
                tabBar.selectedComponent = selected.tabComponent
                cardLayout.show(cardPanel, selected.cardId)
            }
        }
        refreshTabStyles()
        refreshTabHeaderTitles()
    } finally {
        suspendPersist = false
    }
}

internal fun CodecPanel.nextTabTitle(prefix: String): String {
    val existing = tabByComponent.values.map { it.title }.toSet()
    if (!existing.contains(prefix)) return prefix
    var index = 2
    while (true) {
        val candidate = "$prefix-$index"
        if (!existing.contains(candidate)) return candidate
        index += 1
    }
}

internal fun CodecPanel.nextDuplicateTitle(base: String): String {
    val normalized = base.trim().ifBlank { "codec" }
    val existing = tabByComponent.values.map { it.title }.toSet()
    if (!existing.contains("$normalized-copy")) return "$normalized-copy"
    var index = 2
    while (true) {
        val candidate = "$normalized-copy-$index"
        if (!existing.contains(candidate)) return candidate
        index += 1
    }
}

internal fun CodecPanel.uniqueTitle(baseTitle: String, exceptId: String? = null): String {
    val base = baseTitle.trim().ifBlank { "codec" }
    val existing = tabByComponent.values
        .filter { it.recordId != exceptId }
        .map { it.title }
        .toSet()
    if (!existing.contains(base)) return base
    var index = 2
    while (true) {
        val candidate = "$base-$index"
        if (!existing.contains(candidate)) return candidate
        index += 1
    }
}

internal fun CodecPanel.persistState() {
    if (suspendPersist) return
    if (persistTimer.isRunning) {
        persistTimer.restart()
    } else {
        persistTimer.start()
    }
}

internal fun CodecPanel.persistStateNow() {
    if (suspendPersist) return
    val selected = tabByComponent[tabBar.selectedComponent]?.recordId
        ?: tabByComponent.values.firstOrNull()?.recordId.orEmpty()
    val state = CodecState(
        tabs = tabByComponent.values.map { tab ->
            CodecTabRecord(
                tabId = tab.recordId,
                title = tab.title,
                rules = CodecTabContentFactory.modelRules(tab.recipeModel),
                input = tab.inputArea.text
            )
        },
        selectedTabId = selected,
        defaultTabId = defaultTabId
    )
    CodecSettings.saveState(state)
}
