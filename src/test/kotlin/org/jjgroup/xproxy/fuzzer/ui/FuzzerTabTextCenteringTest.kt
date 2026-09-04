package org.jjgroup.xproxy.fuzzer.ui

import com.formdev.flatlaf.FlatLightLaf
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.core.SeedRequest
import org.jjgroup.xproxy.fuzzer.model.RequestTabState
import org.jjgroup.xproxy.ui.http.HttpRequestResponseViewer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import kotlin.math.abs

/**
 * 回归:未选中 tab 的文案应整体居中,而不是偏右。
 *
 * 根因:label 永远带 `EmptyBorder(0,8,0,0)`(8px 左、0px 右),未选中时是 CENTER 对齐,
 * 不对称边距让居中文案整体右移约 (8-0)/2 = 4px。
 * 修复:边距改为按选中态设置——选中(LEFT)保留 8px 左内边距;未选中(CENTER)用左右对称的 4px。
 */
class FuzzerTabTextCenteringTest {

    @Test
    fun `non-selected tab label has symmetric horizontal border`() {
        val ctx = newContext()
        val (component, _) = addRealTab(ctx, "example request")
        val label = ctx.tabHeaderStates[component]!!.label

        ctx.applyTabHeaderStyle(component, selected = false)
        val insets = label.border.getBorderInsets(label)
        assertEquals(insets.left, insets.right) {
            "non-selected (CENTER) label border must be symmetric, got left=${insets.left} right=${insets.right}"
        }
    }

    @Test
    fun `selected tab label keeps left padding`() {
        val ctx = newContext()
        val (component, _) = addRealTab(ctx, "example request")
        val label = ctx.tabHeaderStates[component]!!.label

        ctx.applyTabHeaderStyle(component, selected = true)
        val insets = label.border.getBorderInsets(label)
        assertEquals(8, insets.left)
        assertEquals(0, insets.right)
    }

    @Test
    fun `non-selected tab text is centered within the header`() {
        val ctx = newContext()
        val (component, header) = addRealTab(ctx, "example request")
        val headerUi = ctx.tabHeaderStates[component]!!
        val label = headerUi.label
        val title = "example request"

        ctx.applyTabHeaderStyle(component, selected = false)
        val width = 160
        header.size = Dimension(width, 30)
        header.doLayout()
        headerUi.titleContainer.doLayout()

        val insets = label.border.getBorderInsets(label)
        val contentWidth = label.width - insets.left - insets.right
        val fm = label.getFontMetrics(label.font)
        val textWidth = fm.stringWidth(title)
        assertTrue(textWidth < contentWidth) { "test setup: title must fit for centering math" }
        // CENTER 对齐:文案中心 = label 内容区中心(label 坐标系),换算到 header 坐标系后应 ≈ header 中心。
        val textCenterInLabel = insets.left + contentWidth / 2
        val textCenterInHeader = SwingUtilities.convertPoint(label, textCenterInLabel, 0, header).x
        val headerCenterX = width / 2
        // 根边框 (4,7,4,6) 自身有 ~0.5px 的左右不对称,容差 1px。
        assertTrue(abs(textCenterInHeader - headerCenterX) <= 1) {
            "text center $textCenterInHeader should be ~header center $headerCenterX (diff=${textCenterInHeader - headerCenterX})"
        }
    }

    private fun addRealTab(ctx: IntruderUiContext, title: String): Pair<Component, JPanel> {
        val tabComponent = JPanel()
        val viewer = HttpRequestResponseViewer()
        ctx.tabStates[tabComponent] = RequestTabState(
            tabComponent = tabComponent,
            cardId = "card-${ctx.cardCounter++}",
            cardComponent = JPanel(),
            requestEditor = viewer.requestRawArea,
            requestPretty = viewer.requestPrettyArea,
            responseRaw = viewer.responseRawArea,
            responsePretty = viewer.responsePrettyArea,
            responseRender = viewer.responseRenderArea,
            responseViewer = viewer,
            targetLabel = JLabel(),
            target = HttpService("host", 80, "http")
        )
        ctx.tabOrder.add(tabComponent)
        val header = ctx.buildTabHeader(title, tabComponent) as JPanel
        val insertIndex = (ctx.requestTabBar.tabCount - 1).coerceAtLeast(0)
        ctx.requestTabBar.insertTab(title, null, tabComponent, null, insertIndex)
        val tabIndex = ctx.requestTabBar.indexOfComponent(tabComponent)
        if (tabIndex != -1) {
            ctx.requestTabBar.setTabComponentAt(tabIndex, header)
        }
        return tabComponent to header
    }

    private fun newContext(): IntruderUiContext {
        val requestTabBar = RequestTabBar().apply { applyChrome() }
        val addTabPanel = JPanel()
        requestTabBar.putClientProperty("xproxy.applyAddTabHeader", Runnable {})
        val cardLayout = java.awt.CardLayout()
        val cardPanel = JPanel(cardLayout)
        val frame = IntruderFrame(
            SeedRequest(ByteArray(0), HttpService("host", 80, "http")),
            null, null, null, null
        )
        val ctx = IntruderUiContext(
            frame = frame,
            initialService = HttpService("host", 80, "http"),
            initialRequestText = "",
            projectDataStore = null,
            pane = JSplitPane(),
            intruderPanel = JPanel(),
            defaultPaneDividerSize = 0,
            intruderPlaceholder = JPanel(),
            requestTabBar = requestTabBar,
            cardLayout = cardLayout,
            cardPanel = cardPanel,
            emptyCardId = "empty-card",
            addTabPanel = addTabPanel,
            addPlaceholderButton = JButton(),
            placeholderRegex = Regex("x")
        )
        requestTabBar.putClientProperty("xproxy.ctx", ctx)
        return ctx
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun setupLaf() {
            FlatLightLaf.setup()
        }
    }
}
