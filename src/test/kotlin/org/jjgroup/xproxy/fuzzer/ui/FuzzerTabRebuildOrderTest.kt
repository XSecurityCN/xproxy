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
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * 回归:[rebuildRequestTabBar] 必须按 [IntruderUiContext.tabOrder] 的顺序重建标签条,
 * 而不是把首个 tab(如 "example request")推到末尾。
 *
 * 根因:rebuild 时先 removeAll() 再逐个 insertTab,而 [addTabComponentToBar] 的默认插入点
 * 是 `tabCount - 1`(意在插到 "+" 之前)。但 "+" 是在循环之后才 addTab 的,循环期间并不存在,
 * 于是每次都插到"最后一个真实 tab"之前,把第一个 tab 不断向右挤,最终首个 tab 落到末尾。
 */
class FuzzerTabRebuildOrderTest {

    @Test
    fun `rebuild preserves tabOrder with first tab staying first`() {
        val (ctx, requestTabBar, addTabPanel) = newContext()
        // 模拟一个有多个请求 tab 的会话:example 在首位,其后是 2..5。
        val titles = listOf("example request", "2", "3", "4", "5")
        val tabs = titles.map { addTab(ctx, it) }
        val expected = tabs.toList()

        ctx.rebuildRequestTabBar(tabs.first())

        val barOrder = (0 until requestTabBar.tabCount)
            .map { requestTabBar.getComponentAt(it) }
            .filter { it != addTabPanel }
        assertEquals(expected, barOrder)
        // 显式断言:首个 tab(example request)仍在标签条的第一位。
        assertTrue(requestTabBar.getComponentAt(0) === expected.first()) {
            "first tab should stay first after rebuild, but order was: ${barOrder.map { ctx.tabHeaderStates[it]?.label?.text }}"
        }
    }

    @Test
    fun `rebuild preserves order for a single tab`() {
        val (ctx, requestTabBar, addTabPanel) = newContext()
        val only = addTab(ctx, "example request")
        ctx.rebuildRequestTabBar(only)
        assertEquals(only, requestTabBar.getComponentAt(0))
        assertEquals(addTabPanel, requestTabBar.getComponentAt(1))
    }

    @Test
    fun `new group keeps the tab in its original position`() {
        val (ctx, requestTabBar, addTabPanel) = newContext()
        val a = addTab(ctx, "A")
        val b = addTab(ctx, "B")
        val c = addTab(ctx, "C")
        val d = addTab(ctx, "D")
        val e = addTab(ctx, "E")
        // 为中间的 C 新建分组 g1。
        ctx.setTabGroup(c, "g1", null)

        // tabOrder 保持不变:C 仍在第 2 位,没有被挪到末尾。
        assertEquals(listOf(a, b, c, d, e), ctx.tabOrder)

        val barComponents = (0 until requestTabBar.tabCount)
            .map { requestTabBar.getComponentAt(it) }
            .filter { it != addTabPanel }
        val cIndex = barComponents.indexOf(c)
        val eIndex = barComponents.indexOf(e)
        assertTrue(cIndex in 0 until eIndex) {
            "C should stay before E after grouping, but bar order was: ${barComponents.map { describe(ctx, it) }}"
        }
        // 分组头应紧贴在 C 之前(原位),而不是出现在末尾。
        assertEquals("g1", ctx.groupHeaderTabGroups[barComponents[cIndex - 1]])
    }

    @Test
    fun `adding to an existing group still coalesces next to the group`() {
        val (ctx, requestTabBar, addTabPanel) = newContext()
        val a = addTab(ctx, "A")
        val b = addTab(ctx, "B")
        val c = addTab(ctx, "C")
        val d = addTab(ctx, "D")
        val e = addTab(ctx, "E")
        // 先让 B 新建分组 g1(留在原位)。
        ctx.setTabGroup(b, "g1", null)
        // 再把末尾的 E 加入同一分组 g1:应挪到 B 之后,与分组聚合,而不是留在末尾。
        ctx.setTabGroup(e, "g1", null)

        // tabOrder:E 被挪到 B 之后,g1 成员连续;A/C/D 仍在外、相对顺序不变。
        assertEquals(listOf(a, b, e, c, d), ctx.tabOrder)

        val barComponents = (0 until requestTabBar.tabCount)
            .map { requestTabBar.getComponentAt(it) }
            .filter { it != addTabPanel }
        val bIndex = barComponents.indexOf(b)
        val eIndex = barComponents.indexOf(e)
        val cIndex = barComponents.indexOf(c)
        // B 与 E(同组)相邻,且都在 C 之前。
        assertTrue(eIndex == bIndex + 1) {
            "E should sit right after B (same group), but bar order was: ${barComponents.map { describe(ctx, it) }}"
        }
        assertTrue(eIndex < cIndex)
    }

    private fun describe(ctx: IntruderUiContext, component: Component): String {
        ctx.groupHeaderTabGroups[component]?.let { return "[group:$it]" }
        return ctx.tabHeaderStates[component]?.label?.text ?: component.javaClass.simpleName
    }

    private fun addTab(ctx: IntruderUiContext, title: String): Component {
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
        val baseFont = JLabel().font
        ctx.tabHeaderStates[tabComponent] = TabHeaderUi(
            root = JPanel(),
            titleContainer = JPanel(),
            label = JLabel(title),
            groupHeader = JPanel(),
            groupIcon = JPanel(),
            groupNameLabel = JLabel(),
            groupCountLabel = JLabel(),
            groupToggleLabel = JLabel(),
            close = JButton(),
            normalFont = baseFont,
            selectedFont = baseFont.deriveFont(java.awt.Font.BOLD)
        )
        ctx.tabOrder.add(tabComponent)
        return tabComponent
    }

    private fun newContext(): Triple<IntruderUiContext, RequestTabBar, JPanel> {
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
        return Triple(ctx, requestTabBar, addTabPanel)
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun setupLaf() {
            FlatLightLaf.setup()
        }
    }
}
