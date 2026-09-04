package org.jjgroup.xproxy.fuzzer.ui

import com.formdev.flatlaf.FlatLightLaf
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.core.SeedRequest
import org.jjgroup.xproxy.fuzzer.model.RequestTabState
import org.jjgroup.xproxy.ui.http.HttpRequestResponseViewer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.event.FocusEvent
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * 回归:单击/双击(重命名)tab 不应残留拖动视觉(橙色描边/拖影)。
 *
 * 根因:`mousePressed` 原本在按下瞬间就 `setTabDragVisual(component, true)`,导致:
 *  - 单击时按下→释放之间一闪而过的橙框(Bug 1);
 *  - 双击重命名时编辑器接管随后的 mouseReleased(落点在编辑器),label 自身的 release 不会清理,
 *    dragging=true 残留到编辑后(Bug 2)。
 * 修复:按下不再立即置 dragging,改为在 mouseDragged 超过位移阈值后才置;重命名入口额外兜底清理。
 */
class FuzzerTabDragVisualTest {

    @Test
    fun `single press does not set the drag visual`() {
        val ctx = newContext()
        val (component, _) = addRealTab(ctx, "A")
        val root = ctx.tabHeaderStates[component]!!.root
        val label = ctx.tabHeaderStates[component]!!.label

        mousePress(label, 50, 5)
        try {
            assertFalse(isDragging(root)) {
                "plain press must not trigger the drag visual (orange ring), dragging=${root.getClientProperty("xproxy.tab.dragging")}"
            }
        } finally {
            mouseRelease(label, 50, 5)
            SwingUtilities.invokeAndWait { }
        }
    }

    @Test
    fun `small drag below slop does not set the drag visual`() {
        val ctx = newContext()
        val (component, _) = addRealTab(ctx, "A")
        val root = ctx.tabHeaderStates[component]!!.root
        val label = ctx.tabHeaderStates[component]!!.label

        mousePress(label, 50, 5)
        try {
            // 2px 位移:未过阈值,不应进入拖动视觉。
            mouseDrag(label, 52, 5)
            assertFalse(isDragging(root)) {
                "sub-threshold jitter must not trigger the drag visual, dragging=${root.getClientProperty("xproxy.tab.dragging")}"
            }
        } finally {
            mouseRelease(label, 52, 5)
            SwingUtilities.invokeAndWait { }
        }
    }

    @Test
    fun `starting rename clears any residual drag visual`() {
        val ctx = newContext()
        val (component, _) = addRealTab(ctx, "A")
        val root = ctx.tabHeaderStates[component]!!.root
        val label = ctx.tabHeaderStates[component]!!.label
        val titleContainer = ctx.tabHeaderStates[component]!!.titleContainer

        // 模拟拖动视觉已被置上(例如双击过程中抖动越过阈值)。
        root.putClientProperty("xproxy.tab.dragging", true)
        assertTrue(isDragging(root))

        // 双击触发重命名:startInlineTabRename 应兜底清理 dragging。
        mousePress(label, 50, 5, clickCount = 2)
        try {
            assertFalse(isDragging(root)) {
                "rename must clear residual drag visual, dragging=${root.getClientProperty("xproxy.tab.dragging")}"
            }
            // 编辑器确实打开了。
            val editor = titleContainer.components.firstOrNull { it is JTextField } as JTextField?
            assertNotNull(editor, "inline rename editor should be open after double-click")
        } finally {
            // 确认重命名以移除全局 clickAway AWTEventListener,保证测试隔离。
            (titleContainer.components.firstOrNull { it is JTextField } as JTextField?)?.let { editor ->
                editor.dispatchEvent(FocusEvent(editor, FocusEvent.FOCUS_LOST))
            }
            SwingUtilities.invokeAndWait { }
        }
    }

    private fun isDragging(root: JComponent): Boolean =
        root.getClientProperty("xproxy.tab.dragging") == true

    private fun mousePress(comp: Component, x: Int, y: Int, clickCount: Int = 1) {
        comp.dispatchEvent(
            MouseEvent(comp, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK, x, y, clickCount, false, MouseEvent.BUTTON1)
        )
    }

    private fun mouseDrag(comp: Component, x: Int, y: Int) {
        comp.dispatchEvent(
            MouseEvent(comp, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK, x, y, 0, false, MouseEvent.BUTTON1)
        )
    }

    private fun mouseRelease(comp: Component, x: Int, y: Int, clickCount: Int = 1) {
        comp.dispatchEvent(
            MouseEvent(comp, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK, x, y, clickCount, false, MouseEvent.BUTTON1)
        )
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
        // 把 tab 加入标签条(插到 "+" 之前),使 setSelectedComponent 等操作合法。
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
