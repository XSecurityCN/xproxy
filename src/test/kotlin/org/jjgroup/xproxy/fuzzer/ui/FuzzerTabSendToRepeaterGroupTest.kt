package org.jjgroup.xproxy.fuzzer.ui

import com.formdev.flatlaf.FlatLightLaf
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.core.SeedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

/**
 * 回归:在 fuzzer tab 分组中的子 tab 上右键"发送到 重放器"时,新建的 tab 应留在同一分组。
 *
 * 根因:onSendToFuzzer 闭包只调用 createAndSelectRequestTabWithRequest 新建 tab,不携带源 tab 的分组,
 * 故新 tab 落到分组之外。
 * 修复:新建后若源 tab 处于某分组,用 setTabGroup 把新 tab 加入同一分组。
 */
class FuzzerTabSendToRepeaterGroupTest {

    @Test
    fun `send to repeater from a grouped tab keeps the new tab in the same group`() {
        val ctx = newContext()
        val tabA = ctx.createAndSelectRequestTabWithRequest(
            "GET / HTTP/1.1\r\nHost: h\r\n\r\n", triggerNotification = false
        )
        ctx.setTabGroup(tabA, "G")
        assertEquals("G", ctx.tabGroups[tabA])

        val stateA = ctx.tabStates[tabA]!!
        val before = ctx.tabStates.size
        // 模拟在 tabA 的请求视图右键"发送到 重放器"。
        stateA.responseViewer.onSendToFuzzer?.invoke("POST /x HTTP/1.1\r\nHost: h\r\n\r\n")
        SwingUtilities.invokeAndWait { }

        assertEquals(before + 1, ctx.tabStates.size) { "a new tab should be created" }
        val newTab = ctx.tabStates.keys.firstOrNull { it != tabA } ?: error("new tab not found")
        assertEquals("G", ctx.tabGroups[newTab]) {
            "new tab should inherit the source tab's group"
        }
        // 新 tab 应聚合到分组成员之后(在 tabOrder 中位于 tabA 之后,且都在分组 G 内)。
        val gMembers = ctx.tabOrder.filter { ctx.tabGroups[it] == "G" }
        assertTrue(gMembers.size == 2 && gMembers.first() == tabA && gMembers.last() == newTab) {
            "group G members in tabOrder should be [tabA, newTab], got positions: ${gMembers.map { ctx.tabHeaderStates[it]?.label?.text }}"
        }
    }

    @Test
    fun `send to repeater from an ungrouped tab leaves the new tab ungrouped`() {
        val ctx = newContext()
        val tabA = ctx.createAndSelectRequestTabWithRequest(
            "GET / HTTP/1.1\r\nHost: h\r\n\r\n", triggerNotification = false
        )
        assertFalse(ctx.tabGroups.containsKey(tabA))

        val stateA = ctx.tabStates[tabA]!!
        stateA.responseViewer.onSendToFuzzer?.invoke("POST /x HTTP/1.1\r\nHost: h\r\n\r\n")
        SwingUtilities.invokeAndWait { }

        val newTab = ctx.tabStates.keys.first { it != tabA }
        assertFalse(ctx.tabGroups.containsKey(newTab)) {
            "new tab from an ungrouped source should not be assigned to any group"
        }
    }

    private fun newContext(): IntruderUiContext {
        val requestTabBar = RequestTabBar().apply { applyChrome() }
        val addTabPanel = JPanel()
        requestTabBar.putClientProperty("xproxy.applyAddTabHeader", Runnable {})
        requestTabBar.addTab("+", addTabPanel)
        val cardLayout = java.awt.CardLayout()
        val cardPanel = JPanel(cardLayout)
        val frame = IntruderFrame(
            SeedRequest(ByteArray(0), HttpService("host", 80, "http")),
            null, null, null, null
        )
        val ctx = IntruderUiContext(
            frame = frame,
            initialService = HttpService("host", 80, "http"),
            initialRequestText = "GET / HTTP/1.1\r\nHost: host\r\n\r\n",
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
        // createAndSelectRequestTabWithRequest 经由 createRequestTab 建表;createRequestTab 是
        // lateinit,在 buildIntruderUI 里才赋值,这里手动接上 createRequestTabWithState。
        ctx.createRequestTab = { title, requestText -> ctx.createRequestTabWithState(title = title, requestText = requestText) }
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
