package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.settings.core.UiThemePalette

import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.*
import javax.swing.plaf.basic.BasicTabbedPaneUI

/**
 * JTabbedPane 子类：自定义 [BasicTabbedPaneUI] 控制 tab 的间距、换行与绘制（含拖拽指示线）。
 *
 * 主题切换时 [com.formdev.flatlaf.FlatLaf] 的 updateUI() 会用默认 TabbedPaneUI 覆盖该自定义委托，
 * 导致 tab 间距与背景色异常。这里重写 [updateUI]，在每次 Swing 重装 UI 后立即装回自定义委托，
 * 并刷新容器/表头的主题色（这些颜色在构造期被捕获，主题切换后会过期）。
 */
internal class RequestTabBar : JTabbedPane() {
    override fun paint(g: java.awt.Graphics) {
        super.paint(g)
        val ctx = getClientProperty("xproxy.ctx") as? IntruderUiContext ?: return
        val x = ctx.dragIndicatorX
        if (x < 0) return
        val targetIndex = ctx.dragPendingTargetIndex
        val targetBounds = if (targetIndex in 0 until tabCount) {
            runCatching { getBoundsAt(targetIndex) }.getOrNull()
        } else {
            null
        }
        val lineY = (targetBounds?.y ?: 7) + 4
        val lineHeight = ((targetBounds?.height ?: (font.size + 14)) - 8).coerceAtLeast(12)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = UiThemePalette.accent
        g2.fillRoundRect(x - 2, lineY, 4, lineHeight, 4, 4)
        g2.color = UiThemePalette.accentRgba(70)
        g2.fillRoundRect(x - 5, lineY + lineHeight - 2, 10, 3, 5, 5)
        g2.dispose()
    }

    override fun updateUI() {
        super.updateUI()
        // 重新装回自定义委托并刷新主题色。xproxy.ctx 在装配完成前为 null，据此跳过
        // 构造期 JTabbedPane 自动触发的 updateUI（此时容器/表头尚未建立）。
        applyChrome()
        val ctxRef = getClientProperty("xproxy.ctx") as? IntruderUiContext
        ctxRef?.requestTabBarContainerRef?.let { applyRequestTabBarContainerTheme(it) }
        ctxRef?.refreshRequestTabStyles()
    }

    fun applyChrome() {
        tabPlacement = JTabbedPane.TOP
        tabLayoutPolicy = JTabbedPane.WRAP_TAB_LAYOUT
        font = font.deriveFont(13f)
        isOpaque = false
        border = BorderFactory.createEmptyBorder(3, 3, 3, 3)
        // 显式调用 setUI 而非 `ui = ...`：在子类方法中 Kotlin 会把 `ui =` 解析为对受保护字段
        // JComponent.ui 的直接赋值（putfield），从而绕过 setUI 的 uninstallUI/installUI 流程，
        // 导致自定义委托的 tabPane 保持为 null，paint 时抛 NPE。setUI 才会真正安装委托。
        setUI(customUi())
    }

    private fun customUi(): javax.swing.plaf.TabbedPaneUI = object : BasicTabbedPaneUI() {
        override fun createLayoutManager(): java.awt.LayoutManager {
            return object : TabbedPaneLayout() {
                override fun normalizeTabRuns(tabPlacement: Int, tabCount: Int, start: Int, max: Int) {
                    // Do not stretch tabs to fill each row; keep natural widths so rows fill predictably.
                }

                override fun rotateTabRuns(tabPlacement: Int, selectedRun: Int) {
                    // BasicTabbedPaneUI normally rotates tab runs to keep the selected tab adjacent to
                    // the content border. That makes wrapped TOP tabs appear in reverse row order
                    // (for example: 13 14 + / 9..12 / 5..8 / 1..4). We keep the model run order stable
                    // and fix the row y-coordinates after Swing's layout pass instead.
                }

                override fun calculateTabRects(tabPlacement: Int, tabCount: Int) {
                    super.calculateTabRects(tabPlacement, tabCount)
                    if (tabPlacement != JTabbedPane.TOP || runCount <= 1 || tabCount <= 0) {
                        return
                    }

                    // Swing lays out TOP WRAP_TAB_LAYOUT runs from bottom to top so the last run is
                    // visually first. Reassign only the run y positions in ascending model order; x/width
                    // remain as calculated, preserving wrapping, drag hit testing, and custom widths.
                    val orderedRunStarts = (0 until runCount).map { tabRuns[it] }.sorted()
                    val topY = tabPane.insets.top + getTabAreaInsets(tabPlacement).top
                    val rowStep = (maxTabHeight - getTabRunOverlay(tabPlacement)).coerceAtLeast(1)
                    orderedRunStarts.forEachIndexed { row, startIndex ->
                        val endExclusive = orderedRunStarts.getOrNull(row + 1) ?: tabCount
                        val rowY = topY + row * rowStep
                        for (tabIndex in startIndex until endExclusive) {
                            rects[tabIndex].y = rowY
                        }
                    }
                    padSelectedTab(tabPlacement, tabPane.selectedIndex)
                }
            }
        }

        override fun paintContentBorder(g: java.awt.Graphics?, tabPlacement: Int, selectedIndex: Int) {
        }

        override fun paintTabBackground(
            g: java.awt.Graphics?,
            tabPlacement: Int,
            tabIndex: Int,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            isSelected: Boolean
        ) {
        }

        override fun paintTabBorder(
            g: java.awt.Graphics?,
            tabPlacement: Int,
            tabIndex: Int,
            x: Int,
            y: Int,
            w: Int,
            h: Int,
            isSelected: Boolean
        ) {
        }

        override fun paintFocusIndicator(
            g: java.awt.Graphics?,
            tabPlacement: Int,
            rects: Array<out java.awt.Rectangle>?,
            tabIndex: Int,
            iconRect: java.awt.Rectangle?,
            textRect: java.awt.Rectangle?,
            isSelected: Boolean
        ) {
        }

        override fun calculateTabWidth(tabPlacement: Int, tabIndex: Int, metrics: java.awt.FontMetrics): Int {
            if (tabPane.getTitleAt(tabIndex) == "+") {
                return 44
            }
            val custom = tabPane.getTabComponentAt(tabIndex)
            return if (custom != null) {
                custom.preferredSize.width + 2
            } else {
                super.calculateTabWidth(tabPlacement, tabIndex, metrics)
            }
        }

        override fun calculateTabHeight(tabPlacement: Int, tabIndex: Int, fontHeight: Int): Int {
            if (tabPane.getTitleAt(tabIndex) == "+") {
                return 34
            }
            val custom = tabPane.getTabComponentAt(tabIndex)
            return if (custom != null) {
                custom.preferredSize.height + 2
            } else {
                super.calculateTabHeight(tabPlacement, tabIndex, fontHeight)
            }
        }

        override fun shouldPadTabRun(tabPlacement: Int, run: Int): Boolean = false

        override fun getTabRunOverlay(tabPlacement: Int): Int = 0

        override fun shouldRotateTabRuns(tabPlacement: Int): Boolean = false
    }
}

internal fun applyRequestTabBarContainerTheme(container: JPanel) {
    // 标签条容器透明，直接透出父面板背景，避免 dark 模式下出现比面板更深的突兀色块。
    container.isOpaque = false
    container.border = BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(0, 0, 1, 0, UiThemePalette.tabBarBorder),
        BorderFactory.createEmptyBorder(4, 0, 2, 0)
    )
}
