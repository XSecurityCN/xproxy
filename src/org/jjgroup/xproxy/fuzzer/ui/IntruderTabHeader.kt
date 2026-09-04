package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.i18n.I18n
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.Graphics
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseMotionAdapter
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.*

// 拖动位移阈值:mouseDragged 累计位移未超过该值时不进入拖动视觉,避免单击抖动一闪而过。
private const val TAB_DRAG_SLOP_PX = 5

private fun IntruderUiContext.knownTabGroups(): List<String> =
    tabGroups.values
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

internal fun IntruderUiContext.buildTabHeader(title: String, component: Component): Component {
    fun startInlineTabRename(headerPanel: JPanel, titlePanel: JPanel, titleLabel: JLabel) {
        if (titlePanel.components.any { it is JTextField }) {
            return
        }
        // 双击重命名时,编辑器会接管随后的 mouseReleased(落点在编辑器上而非 label),
        // label 自身的 mouseReleased 不会触发,故在此显式清理按下残留的拖动状态,避免橙框/拖影残留。
        setTabDragVisual(component, false)
        endTabDragGhost()
        dragTabComponent = null
        dragPressPoint = null
        val original = titleLabel.text
        val initialLabelWidth = maxOf(titleLabel.width, titleLabel.preferredSize.width, tabMinWidth)
        val editor = object : JTextField(original) {
            override fun getPreferredSize(): Dimension {
                val base = super.getPreferredSize()
                return Dimension(maxOf(initialLabelWidth, base.width), base.height)
            }

            override fun getMinimumSize(): Dimension = Dimension(initialLabelWidth, super.getMinimumSize().height)
        }
        editor.columns = 0
        editor.border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
        editor.font = titleLabel.font
        editor.foreground = titleLabel.foreground
        editor.caretColor = titleLabel.foreground
        editor.selectionColor = tabAccentColor
        editor.selectedTextColor = Color(255, 255, 255)
        editor.background = (headerPanel.getClientProperty("xproxy.tab.fill") as? Color) ?: tabSelectedBg
        editor.isOpaque = true
        editor.toolTipText = I18n.t("tabs.rename_hint")
        editor.document.addDocumentListener(object : DocumentListener {
            private fun changed() {
                titlePanel.revalidate()
                headerPanel.revalidate()
                requestTabBar.revalidate()
                updateRequestTabBarHeight()
            }

            override fun insertUpdate(e: DocumentEvent?) = changed()
            override fun removeUpdate(e: DocumentEvent?) = changed()
            override fun changedUpdate(e: DocumentEvent?) = changed()
        })

        var finished = false
        var clickAwayListener: java.awt.event.AWTEventListener? = null
        fun restoreLabel(newValue: String?) {
            if (finished) {
                return
            }
            finished = true
            clickAwayListener?.let { listener ->
                java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
                clickAwayListener = null
            }
            if (newValue != null) {
                val trimmed = newValue.trim()
                if (trimmed.isNotEmpty()) {
                    titleLabel.text = trimmed
                    scheduleFuzzerTabsPersist()
                }
            }
            val editorIndex = titlePanel.components.indexOf(editor)
            if (editorIndex != -1) {
                titlePanel.remove(editor)
            }
            if (titlePanel.components.indexOf(titleLabel) == -1) {
                titlePanel.add(titleLabel, BorderLayout.CENTER)
            }
            titlePanel.revalidate()
            titlePanel.repaint()
        }

        editor.addActionListener { restoreLabel(editor.text) }
        editor.registerKeyboardAction(
            { restoreLabel(null) },
            KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_FOCUSED
        )
        editor.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent?) {
                restoreLabel(editor.text)
            }
        })
        clickAwayListener = java.awt.event.AWTEventListener { event ->
            val mouseEvent = event as? java.awt.event.MouseEvent ?: return@AWTEventListener
            if (mouseEvent.id != java.awt.event.MouseEvent.MOUSE_PRESSED) {
                return@AWTEventListener
            }
            val source = mouseEvent.source as? Component ?: run {
                SwingUtilities.invokeLater { restoreLabel(editor.text) }
                return@AWTEventListener
            }
            if (SwingUtilities.isDescendingFrom(source, editor)) {
                return@AWTEventListener
            }
            SwingUtilities.invokeLater { restoreLabel(editor.text) }
        }
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(clickAwayListener, java.awt.AWTEvent.MOUSE_EVENT_MASK)

        val labelIndex = titlePanel.components.indexOf(titleLabel)
        if (labelIndex != -1) {
            titlePanel.remove(labelIndex)
            titlePanel.add(editor, BorderLayout.CENTER)
        } else {
            titlePanel.add(editor, BorderLayout.CENTER)
        }
        titlePanel.revalidate()
        titlePanel.repaint()
        SwingUtilities.invokeLater {
            editor.requestFocusInWindow()
            editor.selectAll()
        }
    }

    val header = object : JPanel(BorderLayout(6, 0)) {
        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            return Dimension(base.width.coerceAtLeast(tabMinWidth), base.height)
        }

        override fun getMinimumSize(): Dimension {
            val base = super.getMinimumSize()
            return Dimension(base.width.coerceAtLeast(tabMinWidth), base.height)
        }

        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fill = (getClientProperty("xproxy.tab.fill") as? Color) ?: tabIdleBg
            val border = (getClientProperty("xproxy.tab.border") as? Color) ?: tabPillBorder
            val selected = (getClientProperty("xproxy.tab.selected") as? Boolean) == true
            val dragging = (getClientProperty("xproxy.tab.dragging") as? Boolean) == true

            if (dragging) {
                g2.color = Color(0, 0, 0, 34)
                g2.fillRoundRect(3, 4, width - 4, height - 3, 12, 12)
            }
            g2.color = if (dragging) blendTabColor(fill, Color.WHITE, 0.18f) else fill
            g2.fillRoundRect(0, if (dragging) 0 else 0, width - 1, height - 1, 12, 12)
            g2.color = if (dragging) tabAccentColor else border
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            if (dragging) {
                g2.color = Color(tabAccentColor.red, tabAccentColor.green, tabAccentColor.blue, 72)
                g2.drawRoundRect(1, 1, width - 3, height - 3, 10, 10)
            }
            if (selected) {
                g2.color = tabAccentColor
                g2.fillRoundRect(0, height - 4, width - 1, 4, 8, 8)
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }
    header.isOpaque = false
    val label = JLabel(title)
    label.horizontalAlignment = SwingConstants.CENTER
    val normalFont = label.font.deriveFont(13f)
    val selectedFont = normalFont
    label.font = normalFont
    val groupIcon = object : JComponent() {
        init {
            preferredSize = Dimension(22, 18)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isOpaque = false
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = (getClientProperty("xproxy.group.icon") as? Color) ?: tabAccentColor
            g2.fillRoundRect(2, 6, 18, 10, 3, 3)
            g2.fillRoundRect(2, 3, 9, 6, 3, 3)
            g2.dispose()
        }
    }
    val groupNameLabel = JLabel()
    groupNameLabel.font = label.font.deriveFont(java.awt.Font.PLAIN, 13f)
    val groupCountLabel = object : JLabel("0", SwingConstants.CENTER) {
        init {
            font = label.font.deriveFont(java.awt.Font.BOLD, 12f)
            border = BorderFactory.createEmptyBorder(1, 7, 1, 7)
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
    val groupToggleLabel = JLabel("‹", SwingConstants.CENTER)
    groupToggleLabel.font = label.font.deriveFont(java.awt.Font.BOLD, 15f)
    groupToggleLabel.border = BorderFactory.createEmptyBorder(0, 8, 0, 2)
    val groupHeader = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val groupName = tabGroups[component].orEmpty()
            val fill = blendTabColor(tabIdleBg, tabGroupColor(groupName), 0.22f)
            val border = blendTabColor(tabPillBorder, tabGroupColor(groupName), 0.55f)
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.color = border
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.dispose()
            super.paintComponent(g)
        }
    }
    groupHeader.layout = BoxLayout(groupHeader, BoxLayout.X_AXIS)
    groupHeader.isOpaque = false
    groupHeader.border = BorderFactory.createEmptyBorder(0, 5, 0, 10)
    groupHeader.toolTipText = I18n.t("tabs.group_toggle_hint")
    groupHeader.add(groupIcon)
    groupHeader.add(Box.createHorizontalStrut(6))
    groupHeader.add(groupNameLabel)
    groupHeader.add(Box.createHorizontalStrut(12))
    groupHeader.add(groupCountLabel)
    groupHeader.add(groupToggleLabel)
    groupHeader.isVisible = false
    val titleContainer = JPanel(BorderLayout())
    titleContainer.isOpaque = false
    titleContainer.add(groupHeader, BorderLayout.WEST)
    // label 的横向边距在 applyTabHeaderStyle 里按选中态设置:选中(LEFT)给左侧 8px 内边距,
    // 未选中(CENTER)用左右对称的 4px,否则不对称边距会让居中文案整体偏右。
    titleContainer.add(label, BorderLayout.CENTER)
    val closeButton = JButton("\u00d7")
    closeButton.isFocusable = false
    closeButton.margin = Insets(0, 0, 0, 0)
    closeButton.border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
    closeButton.isContentAreaFilled = false
    closeButton.isBorderPainted = false
    closeButton.isFocusPainted = false
    closeButton.isOpaque = false
    closeButton.font = closeButton.font.deriveFont(java.awt.Font.PLAIN, 12f)
    val closeSize = Dimension(18, 18)
    closeButton.preferredSize = closeSize
    closeButton.minimumSize = closeSize
    closeButton.maximumSize = closeSize

    val popupMenu = JPopupMenu()
    val renameItem = JMenuItem(I18n.t("tabs.rename"))
    renameItem.addActionListener {
        startInlineTabRename(header, titleContainer, label)
    }
    val duplicateItem = JMenuItem(I18n.t("tabs.duplicate"))
    duplicateItem.addActionListener {
        val sourceState = tabStates[component] ?: return@addActionListener
        val newTab = createRequestTab((tabCounter++).toString(), sourceState.requestEditor.text)
        val newState = tabStates[newTab]
        val duplicateTitle = nextDuplicateTitle(label.text)
        tabHeaderStates[newTab]?.label?.text = duplicateTitle
        if (newState != null) {
            newState.target = sourceState.target.copy()
            newState.responseText = sourceState.responseText
            updateResponseDerived(newState)
            updateTargetDisplay(newState)
        }
        tabSendHistories[newTab] = tabSendHistories[component]?.toMutableList() ?: mutableListOf()
        val sourceGroup = tabGroups[component].orEmpty()
        if (sourceGroup.isNotBlank()) {
            rememberTabGroupColor(sourceGroup, tabGroupColor(sourceGroup))
            moveTabAfterLastInGroup(newTab, sourceGroup)
            tabGroups[newTab] = sourceGroup
            tabHeaderStates[newTab]?.groupName = sourceGroup
        }
        rebuildRequestTabBar(newTab)
        requestTabBar.selectedComponent = if (requestTabBar.indexOfComponent(newTab) != -1) newTab else component
        refreshRequestTabStyles()
        scheduleFuzzerTabsPersist()
    }
    val newGroupItem = JMenuItem(I18n.t("tabs.new_group"))
    newGroupItem.addActionListener {
        val result = promptForGroupInfo(
            I18n.t("tabs.new_group"),
            tabGroups[component].orEmpty(),
            tabGroupColor(tabGroups[component].orEmpty())
        ) ?: return@addActionListener
        setTabGroup(component, result.first, result.second)
    }
    val addToGroupMenu = JMenu(I18n.t("tabs.add_to_group"))
    val removeFromGroupItem = JMenuItem(I18n.t("tabs.remove_from_group"))
    removeFromGroupItem.addActionListener {
        setTabGroup(component, null)
    }
    val toggleGroupItem = JMenuItem(I18n.t("tabs.collapse_group"))
    toggleGroupItem.addActionListener {
        val groupName = tabGroups[component].orEmpty()
        if (groupName.isNotBlank()) {
            toggleTabGroupCollapsed(groupName)
        }
    }
    val editGroupItem = JMenuItem(I18n.t("tabs.edit_group"))
    editGroupItem.addActionListener {
        val currentGroup = tabGroups[component].orEmpty()
        if (currentGroup.isNotBlank()) {
            val result = promptForGroupInfo(I18n.t("tabs.edit_group"), currentGroup, tabGroupColor(currentGroup))
                ?: return@addActionListener
            renameTabGroup(currentGroup, result.first, result.second)
        }
    }

    val closeItem = JMenuItem(I18n.t("common.close"))
    closeItem.addActionListener {
        closeRequestTab(component)
        refreshRequestTabStyles()
    }
    val closeOthersItem = JMenuItem(I18n.t("tabs.close_others"))
    closeOthersItem.addActionListener {
        val keep = component
        val toClose = tabStates.keys.filter { it != keep }
        for (tab in toClose) {
            closeRequestTab(tab)
        }
        requestTabBar.selectedComponent = keep
        val keepState = tabStates[keep]
        if (keepState != null) {
            cardLayout.show(cardPanel, keepState.cardId)
            updateTargetDisplay(keepState)
            applyIntruderVisibility(keepState)
            updateAttackButtonState(keepState)
        }
        refreshRequestTabStyles()
    }
    fun rebuildGroupMenu() {
        addToGroupMenu.removeAll()
        val groups = knownTabGroups().filterNot { it.equals(tabGroups[component].orEmpty(), ignoreCase = false) }
        if (groups.isEmpty()) {
            val emptyItem = JMenuItem(I18n.t("tabs.no_groups"))
            emptyItem.isEnabled = false
            addToGroupMenu.add(emptyItem)
        } else {
            groups.forEach { group ->
                val item = JMenuItem(group)
                item.addActionListener { setTabGroup(component, group) }
                addToGroupMenu.add(item)
            }
        }
        val currentGroup = tabGroups[component].orEmpty()
        removeFromGroupItem.isEnabled = currentGroup.isNotBlank()
        toggleGroupItem.isEnabled = currentGroup.isNotBlank()
        editGroupItem.isEnabled = currentGroup.isNotBlank()
        toggleGroupItem.text = if (currentGroup.isNotBlank() && collapsedTabGroups.contains(currentGroup)) {
            I18n.t("tabs.expand_group")
        } else {
            I18n.t("tabs.collapse_group")
        }
    }

    popupMenu.add(renameItem)
    popupMenu.add(duplicateItem)
    popupMenu.addSeparator()
    popupMenu.add(newGroupItem)
    popupMenu.add(addToGroupMenu)
    popupMenu.add(removeFromGroupItem)
    popupMenu.add(toggleGroupItem)
    popupMenu.add(editGroupItem)
    popupMenu.addSeparator()
    popupMenu.add(closeItem)
    popupMenu.add(closeOthersItem)
    popupMenu.addPopupMenuListener(object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) = rebuildGroupMenu()
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}
        override fun popupMenuCanceled(e: PopupMenuEvent?) {}
    })
    rebuildGroupMenu()
    header.componentPopupMenu = popupMenu
    titleContainer.componentPopupMenu = popupMenu
    label.componentPopupMenu = popupMenu
    groupHeader.componentPopupMenu = popupMenu
    groupIcon.componentPopupMenu = popupMenu
    groupNameLabel.componentPopupMenu = popupMenu
    groupCountLabel.componentPopupMenu = popupMenu
    groupToggleLabel.componentPopupMenu = popupMenu

    val selectTabListener = object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                requestTabBar.selectedComponent = component
                dragTabComponent = component
                dragReordered = false
                dragPendingTargetIndex = -1
                dragPressPoint = e.point
                refreshRequestTabStyles()
            } else if (SwingUtilities.isRightMouseButton(e)) {
                requestTabBar.selectedComponent = component
                refreshRequestTabStyles()
            }
        }

        override fun mouseReleased(e: java.awt.event.MouseEvent?) {
            val dragged = dragTabComponent
            val targetIndex = dragPendingTargetIndex
            endTabDragGhost()
            setTabDragVisual(dragged, false)
            val draggingGroup = dragGroupName
            if (draggingGroup != null && targetIndex >= 0 && reorderTabGroup(draggingGroup, targetIndex)) {
                dragReordered = true
            } else if (dragged != null && targetIndex >= 0 && reorderRequestTab(dragged, targetIndex)) {
                dragReordered = true
            }
            dragGroupName = null
            dragTabComponent = null
            dragPendingTargetIndex = -1
            dragInsertBefore = true
            dragPressPoint = null
            if (dragReordered) {
                dragReordered = false
                scheduleFuzzerTabsPersist()
            }
        }

        override fun mouseEntered(e: java.awt.event.MouseEvent?) {
            tabHeaderStates[component]?.let {
                it.hovered = true
                applyTabHeaderStyle(component, requestTabBar.selectedComponent == component)
            }
        }

        override fun mouseExited(e: java.awt.event.MouseEvent?) {
            tabHeaderStates[component]?.let {
                it.hovered = false
                applyTabHeaderStyle(component, requestTabBar.selectedComponent == component)
            }
        }
    }
    header.addMouseListener(selectTabListener)
    val dragReorderListener = object : MouseMotionAdapter() {
        override fun mouseDragged(e: java.awt.event.MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(e)) {
                return
            }
            val dragged = dragTabComponent ?: dragGroupName?.let { groupMembers(it).firstOrNull() } ?: return
            // 单击抖动不要触发拖动视觉:位移未超过阈值前不进入拖动,避免橙框/拖影一闪而过。
            // (mousePressed 不再立即 setTabDragVisual,真正的拖动视觉从这里开始。)
            val press = dragPressPoint
            if (press != null) {
                val dx = e.x - press.x
                val dy = e.y - press.y
                if (dx * dx + dy * dy < TAB_DRAG_SLOP_PX * TAB_DRAG_SLOP_PX) {
                    return
                }
            }
            setTabDragVisual(dragged, true)
            updateTabDragGhost(dragged, e)
            val pointInTabBar = SwingUtilities.convertPoint(e.component, e.point, requestTabBar)
            val targetIndex = requestTabBar.indexAtLocation(pointInTabBar.x, pointInTabBar.y)
            dragPendingTargetIndex = updateDragInsertionIndicator(pointInTabBar, targetIndex)
        }
    }
    header.addMouseMotionListener(dragReorderListener)
    val groupHeaderMouseListener = object : java.awt.event.MouseAdapter() {
        override fun mouseClicked(e: java.awt.event.MouseEvent) {
            val groupName = tabGroups[component].orEmpty()
            if (SwingUtilities.isLeftMouseButton(e) && groupName.isNotBlank()) {
                toggleTabGroupCollapsed(groupName)
                e.consume()
            }
        }

        override fun mousePressed(e: java.awt.event.MouseEvent) {
            if (SwingUtilities.isRightMouseButton(e)) {
                requestTabBar.selectedComponent = component
                refreshRequestTabStyles()
                popupMenu.show(e.component as Component, e.x, e.y)
                e.consume()
            }
        }
    }
    listOf(groupHeader, groupIcon, groupNameLabel, groupCountLabel, groupToggleLabel).forEach {
        it.addMouseListener(groupHeaderMouseListener)
        it.addMouseMotionListener(dragReorderListener)
    }
    label.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                requestTabBar.selectedComponent = component
                dragTabComponent = component
                dragReordered = false
                dragPendingTargetIndex = -1
                dragPressPoint = e.point
                refreshRequestTabStyles()
                if (e.clickCount >= 2) {
                    startInlineTabRename(header, titleContainer, label)
                }
            } else if (SwingUtilities.isRightMouseButton(e)) {
                requestTabBar.selectedComponent = component
                refreshRequestTabStyles()
                popupMenu.show(label, e.x, e.y)
                e.consume()
            }
        }

        override fun mouseReleased(e: java.awt.event.MouseEvent?) {
            val dragged = dragTabComponent
            val targetIndex = dragPendingTargetIndex
            endTabDragGhost()
            setTabDragVisual(dragged, false)
            val draggingGroup = dragGroupName
            if (draggingGroup != null && targetIndex >= 0 && reorderTabGroup(draggingGroup, targetIndex)) {
                dragReordered = true
            } else if (dragged != null && targetIndex >= 0 && reorderRequestTab(dragged, targetIndex)) {
                dragReordered = true
            }
            dragGroupName = null
            dragTabComponent = null
            dragPendingTargetIndex = -1
            dragInsertBefore = true
            dragPressPoint = null
            if (dragReordered) {
                dragReordered = false
                scheduleFuzzerTabsPersist()
            }
        }
    })
    label.addMouseMotionListener(dragReorderListener)
    titleContainer.addMouseListener(selectTabListener)
    titleContainer.addMouseMotionListener(dragReorderListener)

    closeButton.addActionListener {
        closeRequestTab(component)
        refreshRequestTabStyles()
        scheduleFuzzerTabsPersist()
    }
    closeButton.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseEntered(e: java.awt.event.MouseEvent?) {
            tabHeaderStates[component]?.let {
                it.closeHovered = true
                applyTabHeaderStyle(component, requestTabBar.selectedComponent == component)
            }
        }

        override fun mouseExited(e: java.awt.event.MouseEvent?) {
            tabHeaderStates[component]?.let {
                it.closeHovered = false
                applyTabHeaderStyle(component, requestTabBar.selectedComponent == component)
            }
        }
    })
    header.add(titleContainer, BorderLayout.CENTER)
    header.add(closeButton, BorderLayout.EAST)
    val minHeaderSize = header.preferredSize
    header.minimumSize = Dimension(minHeaderSize.width, minHeaderSize.height)
    tabHeaderStates[component] = TabHeaderUi(
        header,
        titleContainer,
        label,
        groupHeader,
        groupIcon,
        groupNameLabel,
        groupCountLabel,
        groupToggleLabel,
        closeButton,
        normalFont,
        selectedFont
    )
    applyTabHeaderStyle(component, false)
    return header
}
