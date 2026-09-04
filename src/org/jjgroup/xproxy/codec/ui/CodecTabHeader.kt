package org.jjgroup.xproxy.codec.ui

import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

internal fun CodecPanel.buildTabHeader(component: Component): Component {
    val header = object : JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)) {
        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fill = (getClientProperty("xproxy.tab.fill") as? Color) ?: tabIdleBg
            val border = (getClientProperty("xproxy.tab.border") as? Color) ?: tabPillBorder
            val selected = (getClientProperty("xproxy.tab.selected") as? Boolean) == true
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
            g2.color = border
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
            if (selected) {
                g2.color = tabAccentColor
                g2.fillRoundRect(0, height - 4, width - 1, 4, 8, 8)
            }
            g2.dispose()
            super.paintComponent(g)
        }
    }
    header.isOpaque = false
    val label = JLabel("codec")
    val normalFont = label.font.deriveFont(13f)
    val selectedFont = normalFont
    label.font = normalFont

    val closeSize = Dimension(18, 18)
    val closeButton = javax.swing.JButton("\u00d7").apply {
        isFocusable = false
        margin = Insets(0, 0, 0, 0)
        border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        isOpaque = false
        font = font.deriveFont(Font.PLAIN, 12f)
        preferredSize = closeSize
        minimumSize = closeSize
        maximumSize = closeSize
    }

    val popupMenu = JPopupMenu()
    val renameItem = JMenuItem("Rename")
    renameItem.addActionListener { startInlineRename(component, header, label) }
    val duplicateItem = JMenuItem("Duplicate")
    duplicateItem.addActionListener {
        val source = tabByComponent[component] ?: return@addActionListener
        createAndSelectTab(
            title = nextDuplicateTitle(source.title),
            input = source.inputArea.text,
            rules = CodecTabContentFactory.modelRules(source.recipeModel),
            select = true,
            makeDefault = false
        )
        persistState()
    }
    val closeItem = JMenuItem("Close")
    closeItem.addActionListener { closeTab(component) }
    val closeOthersItem = JMenuItem("Close Others")
    closeOthersItem.addActionListener {
        val others = tabByComponent.keys.filter { it != component }
        others.forEach { closeTab(it, persist = false) }
        tabBar.selectedComponent = component
        refreshTabStyles()
        persistState()
    }
    val setDefaultItem = JMenuItem("Set as Default")
    setDefaultItem.addActionListener {
        val tab = tabByComponent[component] ?: return@addActionListener
        defaultTabId = tab.recordId
        refreshTabHeaderTitles()
        persistState()
    }
    popupMenu.add(renameItem)
    popupMenu.add(duplicateItem)
    popupMenu.add(setDefaultItem)
    popupMenu.add(closeItem)
    popupMenu.add(closeOthersItem)
    header.componentPopupMenu = popupMenu

    val selectListener = object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                tabBar.selectedComponent = component
                refreshTabStyles()
            }
        }

        override fun mouseEntered(e: java.awt.event.MouseEvent?) {
            tabHeaderStates[component]?.let {
                it.hovered = true
                applyTabHeaderStyle(component, tabBar.selectedComponent == component)
            }
        }

        override fun mouseExited(e: java.awt.event.MouseEvent?) {
            tabHeaderStates[component]?.let {
                it.hovered = false
                applyTabHeaderStyle(component, tabBar.selectedComponent == component)
            }
        }
    }
    header.addMouseListener(selectListener)
    label.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                tabBar.selectedComponent = component
                refreshTabStyles()
                if (e.clickCount >= 2) {
                    startInlineRename(component, header, label)
                }
            }
        }
    })

    closeButton.addActionListener { closeTab(component) }
    closeButton.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mouseEntered(e: java.awt.event.MouseEvent?) {
            tabHeaderStates[component]?.let {
                it.closeHovered = true
                applyTabHeaderStyle(component, tabBar.selectedComponent == component)
            }
        }

        override fun mouseExited(e: java.awt.event.MouseEvent?) {
            tabHeaderStates[component]?.let {
                it.closeHovered = false
                applyTabHeaderStyle(component, tabBar.selectedComponent == component)
            }
        }
    })

    header.add(label)
    header.add(closeButton)
    val min = header.preferredSize
    header.minimumSize = Dimension(min.width, min.height)
    tabHeaderStates[component] = CodecTabHeaderUi(header, label, closeButton, normalFont, selectedFont)
    applyTabHeaderStyle(component, false)
    return header
}

internal fun CodecPanel.applyTabHeaderStyle(component: Component, selected: Boolean) {
    val ui = tabHeaderStates[component] ?: return
    val hasHover = ui.hovered || ui.closeHovered
    val fill = when {
        selected -> tabSelectedBg
        hasHover -> tabHoverBg
        else -> tabIdleBg
    }
    val border = if (selected || hasHover) tabPillBorder else tabIdleBg
    ui.root.putClientProperty("xproxy.tab.fill", fill)
    ui.root.putClientProperty("xproxy.tab.border", border)
    ui.root.putClientProperty("xproxy.tab.selected", selected)
    ui.root.border = BorderFactory.createEmptyBorder(4, 7, 4, 6)

    if (selected) {
        ui.label.foreground = tabSelectedText
        ui.label.font = ui.selectedFont
        ui.close.text = "\u00d7"
        ui.close.foreground = if (ui.closeHovered) tabCloseHoverFg else Color(84, 84, 90)
        ui.close.isVisible = true
        ui.close.isEnabled = true
    } else {
        ui.label.foreground = tabNormalText
        ui.label.font = ui.normalFont
        ui.close.text = ""
        ui.close.foreground = Color(0, 0, 0, 0)
        ui.close.isVisible = true
        ui.close.isEnabled = false
    }
    ui.close.isOpaque = false
    ui.close.background = Color(0, 0, 0, 0)
    ui.close.border = BorderFactory.createEmptyBorder(0, 3, 0, 3)
    ui.root.repaint()
}

internal fun CodecPanel.refreshTabStyles() {
    val selected = tabBar.selectedComponent
    tabHeaderStates.keys.forEach { component ->
        applyTabHeaderStyle(component, component == selected)
    }
    val plusIndex = tabBar.indexOfComponent(addTabPanel)
    if (plusIndex != -1) {
        tabBar.setForegroundAt(plusIndex, tabNormalText)
    }
    updateTabBarHeight()
}

internal fun CodecPanel.updateTabBarHeight() {
    SwingUtilities.invokeLater {
        var maxBottom = 0
        var fallbackTabHeight = tabHeaderStates.values.firstOrNull()?.root?.preferredSize?.height ?: 0
        for (index in 0 until tabBar.tabCount) {
            try {
                val bounds = tabBar.getBoundsAt(index)
                if (bounds.height > 0) {
                    maxBottom = maxOf(maxBottom, bounds.y + bounds.height)
                    if (fallbackTabHeight <= 0) {
                        fallbackTabHeight = bounds.height
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (fallbackTabHeight <= 0) {
            fallbackTabHeight = tabBar.font.size + 14
        }
        if (maxBottom <= 0) {
            maxBottom = fallbackTabHeight
        }
        val targetHeight = maxBottom + 6
        val current = tabBar.preferredSize
        if (current.height != targetHeight) {
            tabBar.preferredSize = Dimension(current.width, targetHeight)
            tabBar.minimumSize = Dimension(0, targetHeight)
            tabBar.maximumSize = Dimension(Int.MAX_VALUE, targetHeight)
            tabBar.revalidate()
            tabBarContainerRef?.revalidate()
        }
    }
}

internal fun CodecPanel.refreshTabHeaderTitles() {
    tabByComponent.values.forEach { tab ->
        val header = tabHeaderStates[tab.tabComponent] ?: return@forEach
        header.label.text = if (tab.recordId == defaultTabId) {
            "${tab.title} \u2605"
        } else {
            tab.title
        }
    }
}
