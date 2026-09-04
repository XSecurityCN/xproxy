package org.jjgroup.xproxy.fuzzer.ui

import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import javax.swing.*

internal fun IntruderUiContext.applyTabHeaderStyle(component: Component, selected: Boolean) {
    val header = tabHeaderStates[component] ?: return
    val hasHover = header.hovered || header.closeHovered
    val groupColor = tabGroupColor(header.groupName)
    val baseFillColor = when {
        selected -> tabSelectedBg
        hasHover -> tabHoverBg
        else -> tabIdleBg
    }
    val fillColor = blendTabColor(baseFillColor, groupColor, if (selected) 0.28f else 0.22f)
    val borderColor = if (groupColor != null) blendTabColor(tabPillBorder, groupColor, 0.55f)
        else if (selected || hasHover) tabPillBorder else tabIdleBg
    header.root.putClientProperty("xproxy.tab.fill", fillColor)
    header.root.putClientProperty("xproxy.tab.border", borderColor)
    header.root.putClientProperty("xproxy.tab.selected", selected)
    header.root.putClientProperty("xproxy.tab.groupColor", groupColor)
    header.root.border = BorderFactory.createEmptyBorder(4, 7, 4, 6)

    if (selected) {
        header.label.foreground = tabSelectedText
        header.label.font = header.selectedFont
        header.label.horizontalAlignment = SwingConstants.LEFT
        header.label.border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
        header.close.text = "\u00d7"
        header.close.foreground = if (header.closeHovered) tabCloseHoverFg else Color(84, 84, 90)
        header.close.isVisible = true
        header.close.isEnabled = true
    } else {
        header.label.foreground = tabNormalText
        header.label.font = header.normalFont
        header.label.horizontalAlignment = SwingConstants.CENTER
        header.label.border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
        header.closeHovered = false
        header.close.foreground = Color(0, 0, 0, 0)
        header.close.text = "\u00d7"
        header.close.isVisible = false
        header.close.isEnabled = false
    }
    header.close.isOpaque = false
    header.close.background = Color(0, 0, 0, 0)
    header.close.border = BorderFactory.createEmptyBorder(0, 3, 0, 3)
    val groupName = header.groupName.trim()
    val collapsed = groupName.isNotEmpty() && collapsedTabGroups.contains(groupName)
    val showGroupHeader = false
    header.groupHeader.isVisible = false
    header.label.isVisible = true
    if (showGroupHeader) {
        val accent = tabGroupColor(groupName) ?: tabAccentColor
        header.groupNameLabel.text = groupName
        header.groupNameLabel.foreground = tabSelectedText
        header.groupCountLabel.text = groupTabCount(groupName).toString()
        header.groupToggleLabel.text = if (collapsed) "›" else "‹"
        header.groupToggleLabel.foreground = tabSelectedText
        header.groupIcon.putClientProperty("xproxy.group.icon", accent)
        header.groupIcon.repaint()
        header.groupCountLabel.repaint()
    }
    header.root.repaint()
}

internal fun IntruderUiContext.updateRequestTabBarHeight() {
    SwingUtilities.invokeLater {
        val availableWidth = (requestTabBar.width.takeIf { it > 0 }
            ?: requestTabBarContainerRef?.width
            ?: 0).let { (it - 12).coerceAtLeast(1) }
        var rows = 1
        var rowWidth = 0
        var rowHeight = 0
        for (i in 0 until requestTabBar.tabCount) {
            val component = requestTabBar.getComponentAt(i)
            val custom = requestTabBar.getTabComponentAt(i)
            val size = when {
                component == addTabPanel -> Dimension(44, 34)
                custom != null -> custom.preferredSize
                else -> Dimension(
                    requestTabBar.getFontMetrics(requestTabBar.font).stringWidth(requestTabBar.getTitleAt(i)) + 26,
                    requestTabBar.font.size + 18
                )
            }
            val tabWidth = (size.width + 2).coerceAtLeast(1)
            val tabHeight = (size.height + 2).coerceAtLeast(requestTabBar.font.size + 14)
            if (rowWidth > 0 && rowWidth + tabWidth > availableWidth) {
                rows += 1
                rowWidth = 0
            }
            rowWidth += tabWidth
            rowHeight = maxOf(rowHeight, tabHeight)
        }
        if (rowHeight <= 0) {
            rowHeight = tabHeaderStates.values.firstOrNull()?.root?.preferredSize?.height
                ?: (requestTabBar.font.size + 18)
        }
        val simulatedHeight = rows * rowHeight + 10

        var boundsHeight = 0
        for (i in 0 until requestTabBar.tabCount) {
            try {
                val bounds = requestTabBar.getBoundsAt(i)
                if (bounds.height > 0) {
                    boundsHeight = maxOf(boundsHeight, bounds.y + bounds.height + 6)
                }
            } catch (_: Exception) {
            }
        }
        val targetHeight = maxOf(simulatedHeight, boundsHeight)
        val current = requestTabBar.preferredSize
        if (current.height != targetHeight) {
            requestTabBar.preferredSize = Dimension(current.width, targetHeight)
            requestTabBar.minimumSize = Dimension(0, targetHeight)
            requestTabBar.maximumSize = Dimension(Int.MAX_VALUE, targetHeight)
            requestTabBar.revalidate()
            requestTabBarContainerRef?.revalidate()
            requestTabBar.repaint()
        }
    }
}

internal fun IntruderUiContext.refreshRequestTabStyles() {
    val selectedComponent = requestTabBar.selectedComponent
    tabHeaderStates.keys.forEach { component ->
        applyTabHeaderStyle(component, component == selectedComponent)
    }
    val plusIndex = requestTabBar.indexOfComponent(addTabPanel)
    if (plusIndex != -1) {
        // "+" 是自定义 JLabel（applyAddTabHeader 注入），主题切换后需重新刷新其前景色，
        // 否则 dark 模式下沿用旧的深灰色导致 "+" 不可见。
        requestTabBar.setForegroundAt(plusIndex, tabNormalText)
        requestTabBar.getTabComponentAt(plusIndex)?.foreground = tabNormalText
    }
    updateRequestTabBarHeight()
}
