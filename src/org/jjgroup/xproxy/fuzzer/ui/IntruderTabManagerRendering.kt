package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.i18n.I18n

import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.*

private fun IntruderUiContext.normalizeGroupedTabOrder() {
    val current = visibleRequestTabs()
    val grouped = current.groupBy { tabGroups[it].orEmpty() }
    val emittedGroups = LinkedHashSet<String>()
    val normalized = ArrayList<Component>()
    for (component in current) {
        val group = tabGroups[component].orEmpty()
        if (group.isBlank()) {
            normalized.add(component)
        } else if (emittedGroups.add(group)) {
            normalized.addAll(grouped[group].orEmpty())
        }
    }
    tabOrder.clear()
    tabOrder.addAll(normalized)
}

private fun IntruderUiContext.groupMemberIndexes(groupName: String): List<Int> =
    tabOrder.indices.filter { tabGroups[tabOrder[it]].orEmpty() == groupName }

private fun IntruderUiContext.addTabComponentToBar(component: Component, index: Int = requestTabBar.tabCount - 1) {
    val title = tabHeaderStates[component]?.label?.text ?: "tab"
    val insertIndex = index.coerceIn(0, (requestTabBar.tabCount - 1).coerceAtLeast(0))
    requestTabBar.insertTab(title, null, component, null, insertIndex)
    requestTabBar.indexOfComponent(component).takeIf { it != -1 }?.let {
        requestTabBar.setTabComponentAt(it, tabHeaderStates[component]?.root ?: buildTabHeader(title, component))
    }
}

private fun IntruderUiContext.buildStandaloneGroupHeader(groupName: String): JComponent {
    val accent = tabGroupColor(groupName) ?: tabAccentColor
    val header = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fill = blendTabColor(tabIdleBg, accent, 0.22f)
            val border = blendTabColor(tabPillBorder, accent, 0.55f)
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.color = border
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.dispose()
            super.paintComponent(g)
        }
    }
    header.layout = BoxLayout(header, BoxLayout.X_AXIS)
    header.isOpaque = false
    header.border = BorderFactory.createEmptyBorder(4, 10, 4, 10)

    val icon = object : JComponent() {
        init {
            preferredSize = Dimension(22, 18)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = accent
            g2.fillRoundRect(2, 6, 18, 10, 3, 3)
            g2.fillRoundRect(2, 3, 9, 6, 3, 3)
            g2.dispose()
        }
    }
    val nameLabel = JLabel(groupName).apply {
        font = font.deriveFont(java.awt.Font.PLAIN, 13f)
        foreground = tabSelectedText
    }
    val countLabel = object : JLabel(groupTabCount(groupName).toString(), SwingConstants.CENTER) {
        init {
            font = font.deriveFont(java.awt.Font.BOLD, 12f)
            border = BorderFactory.createEmptyBorder(1, 7, 1, 7)
            foreground = tabSelectedText
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(176, 184, 186)
            g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
            g2.dispose()
            super.paintComponent(g)
        }
    }
    val toggleLabel = JLabel(if (collapsedTabGroups.contains(groupName)) "›" else "‹", SwingConstants.CENTER).apply {
        font = font.deriveFont(java.awt.Font.BOLD, 15f)
        foreground = tabSelectedText
        border = BorderFactory.createEmptyBorder(0, 8, 0, 2)
    }

    header.add(icon)
    header.add(Box.createHorizontalStrut(6))
    header.add(nameLabel)
    header.add(Box.createHorizontalStrut(12))
    header.add(countLabel)
    header.add(toggleLabel)

    val popup = JPopupMenu()
    val editItem = JMenuItem(I18n.t("tabs.edit_group"))
    editItem.addActionListener {
        val result = promptForGroupInfo(I18n.t("tabs.edit_group"), groupName, tabGroupColor(groupName))
            ?: return@addActionListener
        renameTabGroup(groupName, result.first, result.second)
    }
    val toggleItem = JMenuItem(if (collapsedTabGroups.contains(groupName)) I18n.t("tabs.expand_group") else I18n.t("tabs.collapse_group"))
    toggleItem.addActionListener { toggleTabGroupCollapsed(groupName) }
    popup.add(editItem)
    popup.add(toggleItem)
    header.componentPopupMenu = popup

    fun beginGroupDrag(e: java.awt.event.MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(e)) return
        dragGroupName = groupName
        dragTabComponent = null
        dragPendingTargetIndex = -1
        dragIndicatorX = -1
        requestTabBar.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.MOVE_CURSOR)
    }

    val mouseListener = object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            beginGroupDrag(e)
        }

        override fun mouseClicked(e: java.awt.event.MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) {
                // A click without a drag toggles the group. During a drag, mouseReleased will clear dragGroupName first.
            }
        }

        override fun mouseReleased(e: java.awt.event.MouseEvent) {
            val targetIndex = dragPendingTargetIndex
            val draggingGroup = dragGroupName
            endTabDragGhost()
            if (draggingGroup != null && targetIndex >= 0 && reorderTabGroup(draggingGroup, targetIndex)) {
                persistAllFuzzerTabs()
            } else if (draggingGroup != null && targetIndex < 0) {
                toggleTabGroupCollapsed(groupName)
            }
            dragGroupName = null
            dragPendingTargetIndex = -1
            dragInsertBefore = true
        }
    }
    val motionListener = object : java.awt.event.MouseMotionAdapter() {
        override fun mouseDragged(e: java.awt.event.MouseEvent) {
            val draggingGroup = dragGroupName ?: return
            val firstMember = groupMembers(draggingGroup).firstOrNull() ?: return
            updateTabDragGhost(firstMember, e)
            val pointInTabBar = SwingUtilities.convertPoint(e.component, e.point, requestTabBar)
            val targetIndex = requestTabBar.indexAtLocation(pointInTabBar.x, pointInTabBar.y)
            dragPendingTargetIndex = updateDragInsertionIndicator(pointInTabBar, targetIndex)
        }
    }
    header.addMouseListener(mouseListener)
    header.addMouseMotionListener(motionListener)
    listOf(icon, nameLabel, countLabel, toggleLabel).forEach {
        it.addMouseListener(mouseListener)
        it.addMouseMotionListener(motionListener)
        it.componentPopupMenu = popup
    }
    return header
}

private fun IntruderUiContext.addGroupHeaderTabToBar(groupName: String) {
    val panel = JPanel().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
    }
    groupHeaderTabGroups[panel] = groupName
    val insertIndex = (requestTabBar.tabCount - 1).coerceAtLeast(0)
    requestTabBar.insertTab(groupName, null, panel, null, insertIndex)
    requestTabBar.setTabComponentAt(insertIndex, buildStandaloneGroupHeader(groupName))
}

internal fun IntruderUiContext.rebuildRequestTabBar(selectComponent: Component? = requestTabBar.selectedComponent) {
    val selectedBefore = when {
        selectComponent != null && tabStates.containsKey(selectComponent) -> selectComponent
        selectComponent != null && groupHeaderTabGroups.containsKey(selectComponent) ->
            groupMembers(groupHeaderTabGroups[selectComponent].orEmpty()).firstOrNull()
        else -> null
    }
    normalizeGroupedTabOrder()
    suppressAddTabAutoCreate = true
    try {
        requestTabBar.removeAll()
        groupHeaderTabGroups.clear()
        // 先放回 "+" 占位 tab:addTabComponentToBar / addGroupHeaderTabToBar 的默认插入点是
        // `tabCount - 1`(意在插到 "+" 之前)。若 "+" 在循环之后才 addTab,循环期间它不存在,
        // 每次都会插到"最后一个真实 tab"之前,把首个 tab(如 example request)一路挤到末尾。
        // 先把 "+" 放回,后续 insertTab 即等价于"追加到真实 tab 末尾",顺序与 tabOrder 一致。
        requestTabBar.addTab("+", addTabPanel)
        (requestTabBar.getClientProperty("xproxy.applyAddTabHeader") as? Runnable)?.run()
        val emittedGroups = LinkedHashSet<String>()
        for (component in visibleRequestTabs()) {
            val group = tabGroups[component].orEmpty()
            if (group.isBlank()) {
                addTabComponentToBar(component)
                continue
            }
            if (emittedGroups.add(group)) {
                addGroupHeaderTabToBar(group)
            }
            if (!collapsedTabGroups.contains(group)) {
                addTabComponentToBar(component)
            }
        }
    } finally {
        suppressAddTabAutoCreate = false
    }
    val selectedGroup = selectedBefore?.let { tabGroups[it].orEmpty() }.orEmpty()
    val groupHeaderTarget = if (selectedGroup.isNotBlank() && collapsedTabGroups.contains(selectedGroup)) {
        groupHeaderTabGroups.entries.firstOrNull { it.value == selectedGroup }?.key
    } else null
    val target = when {
        selectedBefore != null && requestTabBar.indexOfComponent(selectedBefore) != -1 -> selectedBefore
        groupHeaderTarget != null -> groupHeaderTarget
        else -> visibleRequestTabs().firstOrNull { requestTabBar.indexOfComponent(it) != -1 }
            ?: groupHeaderTabGroups.keys.firstOrNull()
    }
    if (target != null) {
        requestTabBar.selectedComponent = target
    } else if (tabStates.isEmpty()) {
        requestTabBar.selectedComponent = addTabPanel
    }
    refreshRequestTabStyles()
}

internal fun IntruderUiContext.reorderTabGroup(groupNameRaw: String, targetIndexRaw: Int): Boolean {
    val groupName = groupNameRaw.trim()
    if (groupName.isEmpty()) return false
    if (targetIndexRaw < 0 || targetIndexRaw >= requestTabBar.tabCount) return false
    val targetComponent = requestTabBar.getComponentAt(targetIndexRaw)
    if (targetComponent == addTabPanel) return false

    normalizeGroupedTabOrder()
    val members = groupMembers(groupName)
    if (members.isEmpty()) return false
    val sourceIndexes = members.map { tabOrder.indexOf(it) }.filter { it >= 0 }.sorted()
    if (sourceIndexes.isEmpty()) return false

    val targetOrderIndex = when {
        tabStates.containsKey(targetComponent) -> {
            val targetGroup = tabGroups[targetComponent].orEmpty()
            when {
                targetGroup == groupName -> return false
                targetGroup.isNotBlank() -> {
                    val indexes = groupMemberIndexes(targetGroup)
                    if (indexes.isEmpty()) return false
                    if (dragInsertBefore) indexes.first() else indexes.last() + 1
                }
                else -> {
                    val index = tabOrder.indexOf(targetComponent)
                    if (index < 0) return false
                    if (dragInsertBefore) index else index + 1
                }
            }
        }
        groupHeaderTabGroups.containsKey(targetComponent) -> {
            val targetGroup = groupHeaderTabGroups[targetComponent].orEmpty()
            if (targetGroup == groupName) return false
            val indexes = groupMemberIndexes(targetGroup)
            if (indexes.isEmpty()) return false
            if (dragInsertBefore) indexes.first() else indexes.last() + 1
        }
        else -> return false
    }

    val block = members.toList()
    val removedBeforeTarget = sourceIndexes.count { it < targetOrderIndex }
    tabOrder.removeAll(block.toSet())
    val insertIndex = (targetOrderIndex - removedBeforeTarget).coerceIn(0, tabOrder.size)
    tabOrder.addAll(insertIndex, block)
    normalizeGroupedTabOrder()
    rebuildRequestTabBar(block.firstOrNull())
    return true
}

internal fun IntruderUiContext.reorderRequestTab(dragged: Component, targetIndexRaw: Int): Boolean {
    if (!tabStates.containsKey(dragged)) {
        return false
    }
    if (targetIndexRaw < 0 || targetIndexRaw >= requestTabBar.tabCount) {
        return false
    }
    val targetComponent = requestTabBar.getComponentAt(targetIndexRaw)
    if (targetComponent == addTabPanel || targetComponent == dragged) {
        return false
    }

    normalizeGroupedTabOrder()
    val sourceOrderIndex = tabOrder.indexOf(dragged)
    if (sourceOrderIndex < 0) {
        return false
    }

    var targetGroupToJoin: String? = null
    val targetOrderIndex = when {
        tabStates.containsKey(targetComponent) -> {
            val targetGroup = tabGroups[targetComponent].orEmpty()
            val draggedGroup = tabGroups[dragged].orEmpty()
            if (targetGroup.isNotBlank() && !collapsedTabGroups.contains(targetGroup) && draggedGroup != targetGroup) {
                targetGroupToJoin = targetGroup
            }
            val index = tabOrder.indexOf(targetComponent)
            if (index < 0) return false
            if (dragInsertBefore) index else index + 1
        }
        groupHeaderTabGroups.containsKey(targetComponent) -> {
            val groupName = groupHeaderTabGroups[targetComponent].orEmpty()
            val memberIndexes = groupMemberIndexes(groupName)
            if (memberIndexes.isEmpty()) {
                -1
            } else if (dragInsertBefore) {
                memberIndexes.first()
            } else {
                memberIndexes.last() + 1
            }
        }
        else -> -1
    }

    if (targetOrderIndex < 0 || sourceOrderIndex == targetOrderIndex) {
        return false
    }
    tabOrder.removeAt(sourceOrderIndex)
    val adjustedTarget = if (sourceOrderIndex < targetOrderIndex) targetOrderIndex - 1 else targetOrderIndex
    if (adjustedTarget == sourceOrderIndex && targetGroupToJoin == null) {
        tabOrder.add(sourceOrderIndex, dragged)
        return false
    }
    tabOrder.add(adjustedTarget.coerceIn(0, tabOrder.size), dragged)
    targetGroupToJoin?.let { groupName ->
        tabGroups[dragged] = groupName
        tabHeaderStates[dragged]?.groupName = groupName
        tabGroupColors[groupName] = tabGroupColor(groupName) ?: tabAccentColor
    }
    normalizeGroupedTabOrder()
    rebuildRequestTabBar(dragged)
    return true
}
