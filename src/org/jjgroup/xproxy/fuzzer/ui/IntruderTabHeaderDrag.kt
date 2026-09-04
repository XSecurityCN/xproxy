package org.jjgroup.xproxy.fuzzer.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import javax.swing.*

internal fun IntruderUiContext.setTabDragVisual(component: Component?, dragging: Boolean) {
    val header = component?.let { tabHeaderStates[it] } ?: return
    header.root.putClientProperty("xproxy.tab.dragging", dragging)
    val cursor = if (dragging) Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) else Cursor.getDefaultCursor()
    header.root.cursor = cursor
    header.titleContainer.cursor = cursor
    header.label.cursor = cursor
    requestTabBar.cursor = cursor
    header.root.repaint()
}

internal fun IntruderUiContext.updateTabDragGhost(component: Component, event: java.awt.event.MouseEvent) {
    val now = System.currentTimeMillis()
    if (now - lastDragGhostUpdateMillis < 16) {
        return
    }
    lastDragGhostUpdateMillis = now
    val header = tabHeaderStates[component] ?: return
    val title = header.label.text
    val group = tabGroups[component].orEmpty()
    val accent = tabGroupColor(group) ?: tabAccentColor
    val ghost = dragGhostWindow ?: run {
        val content = object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(8, 12, 10, 12)
                add(JLabel(title).apply {
                    font = header.label.font.deriveFont(java.awt.Font.BOLD, 14f)
                    foreground = tabSelectedText
                    border = BorderFactory.createEmptyBorder(2, 14, 2, 14)
                }, BorderLayout.CENTER)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(0, 0, 0, 70)
                g2.fillRoundRect(7, 8, width - 10, height - 10, 16, 16)
                g2.color = blendTabColor(tabIdleBg, accent, 0.38f)
                g2.fillRoundRect(2, 2, width - 12, height - 12, 16, 16)
                g2.color = tabAccentColor
                g2.drawRoundRect(2, 2, width - 12, height - 12, 16, 16)
                g2.color = Color(tabAccentColor.red, tabAccentColor.green, tabAccentColor.blue, 90)
                g2.drawRoundRect(4, 4, width - 16, height - 16, 14, 14)
                g2.dispose()
                super.paintComponent(g)
            }
        }
        JWindow(frame).apply {
            background = Color(0, 0, 0, 0)
            isAlwaysOnTop = true
            contentPane.add(content)
            pack()
            dragGhostWindow = this
        }
    }
    val location = runCatching { event.locationOnScreen }.getOrElse { Point(frame.locationOnScreen.x, frame.locationOnScreen.y) }
    ghost.setLocation(location.x - ghost.width / 2, location.y + 10)
    if (!ghost.isVisible) {
        ghost.isVisible = true
    }
}

internal fun IntruderUiContext.updateDragInsertionIndicator(pointInTabBar: Point, rawTargetIndex: Int): Int {
    fun visibleDropIndexes(): List<Int> = (0 until requestTabBar.tabCount)
        .filter { requestTabBar.getComponentAt(it) != addTabPanel }

    var targetIndex = rawTargetIndex
    if (targetIndex < 0 || targetIndex >= requestTabBar.tabCount || requestTabBar.getComponentAt(targetIndex) == addTabPanel) {
        val indexes = visibleDropIndexes()
        if (indexes.isEmpty()) {
            dragIndicatorX = -1
            dragInsertBefore = true
            requestTabBar.repaint()
            return -1
        }
        val bounds = indexes.mapNotNull { index ->
            runCatching { requestTabBar.getBoundsAt(index) }.getOrNull()?.let { index to it }
        }
        val beforeFirst = bounds.firstOrNull { pointInTabBar.x <= it.second.x }
        val afterLast = bounds.lastOrNull { pointInTabBar.x >= it.second.x + it.second.width }
        val between = bounds.zipWithNext().firstOrNull { (left, right) ->
            pointInTabBar.x in (left.second.x + left.second.width)..right.second.x
        }
        when {
            beforeFirst != null -> {
                targetIndex = beforeFirst.first
                dragInsertBefore = true
                dragIndicatorX = beforeFirst.second.x
            }
            afterLast != null -> {
                targetIndex = afterLast.first
                dragInsertBefore = false
                dragIndicatorX = afterLast.second.x + afterLast.second.width
            }
            between != null -> {
                targetIndex = between.second.first
                dragInsertBefore = true
                dragIndicatorX = between.second.second.x
            }
            else -> {
                dragIndicatorX = -1
                dragInsertBefore = true
                requestTabBar.repaint()
                return -1
            }
        }
        requestTabBar.repaint()
        return targetIndex
    }

    val bounds = runCatching { requestTabBar.getBoundsAt(targetIndex) }.getOrNull()
    if (bounds == null) {
        dragIndicatorX = -1
        requestTabBar.repaint()
        return -1
    }
    dragInsertBefore = pointInTabBar.x < bounds.x + bounds.width / 2
    dragIndicatorX = if (dragInsertBefore) bounds.x else bounds.x + bounds.width
    requestTabBar.repaint()
    return targetIndex
}

internal fun IntruderUiContext.endTabDragGhost() {
    lastDragGhostUpdateMillis = 0L
    dragGhostWindow?.isVisible = false
    dragGhostWindow?.dispose()
    dragGhostWindow = null
    dragIndicatorX = -1
    requestTabBar.repaint()
    requestTabBar.cursor = Cursor.getDefaultCursor()
}
