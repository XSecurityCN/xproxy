package org.jjgroup.xproxy.fuzzer.ui

import com.formdev.flatlaf.FlatLightLaf
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.core.SeedRequest
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities

/**
 * 复现 MCP confirm_vuln 经 [recordMcpVuln] 建立的 `* <漏洞名>` 子 tab 是否被正确持久化
 * (title + group_name)。参考库 003-20260720 中确认漏洞后,落库的 tab 标题为计数 "3"、分组为空,
 * 与内存态不一致 —— 此测试用于定位该回归。
 */
class RecordMcpVulnPersistTest {

    @Test
    fun `recordMcpVuln persists starred title and host group`() {
        val store = newStore()
        val ctx = newContext(store)
        val target = HttpService("demo.testfire.net", 80, "http")
        val requestRaw = "GET /index.jsp?content=../WEB-INF/web.xml HTTP/1.1\r\nHost: demo.testfire.net\r\n\r\n"
        val responseRaw = "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nxml"

        SwingUtilities.invokeAndWait {
            ctx.recordMcpVuln(
                requestRaw = requestRaw,
                responseText = responseRaw,
                target = target,
                vulnName = "LFI web.xml",
                statusText = "HTTP/1.1 200 OK",
                responseBytes = responseRaw.length,
                elapsedMillis = 10L,
                evidence = listOf("xml")
            )
            // 不再显式 await 落库:recordMcpVuln 末尾已 persistAllFuzzerTabs(await=true) 同步落库,
            // 返回前 `* <漏洞名>` 标题 + host 分组必须已落库(防 hard-kill 留下计数标题/无分组的预变更快照)。
        }

        val tabs = store.loadFuzzerTabs()
        assertEquals(1, tabs.size, "exactly one vuln sub-tab should be persisted")
        val tab = tabs.first()
        assertEquals("* LFI web.xml", tab.title, "persisted title must be '* <vulnName>'")
        assertEquals("demo.testfire.net", tab.groupName, "persisted group must be the host group")
        assertEquals(responseRaw, tab.responseText)
    }

    @Test
    fun `multiple rapid recordMcpVuln calls all persist group and star title`() {
        val store = newStore()
        val ctx = newContext(store)
        val target = HttpService("demo.testfire.net", 80, "http")

        SwingUtilities.invokeAndWait {
            for (i in 1..6) {
                ctx.recordMcpVuln(
                    requestRaw = "GET /p$i HTTP/1.1\r\nHost: demo.testfire.net\r\n\r\n",
                    responseText = "HTTP/1.1 200 OK\r\n\r\n$i",
                    target = target,
                    vulnName = "vuln $i",
                    statusText = "HTTP/1.1 200 OK",
                    responseBytes = 3,
                    elapsedMillis = 1L
                )
            }
        }

        val tabs = store.loadFuzzerTabs()
        assertEquals(6, tabs.size, "all six vuln sub-tabs should be persisted")
        for (tab in tabs) {
            assertTrue(tab.title.startsWith("* vuln "), "title should be '* vuln N', got ${tab.title}")
            assertEquals("demo.testfire.net", tab.groupName, "group should be the host group, got '${tab.groupName}'")
        }
    }

    private fun newStore(): ProjectDataStore {
        val dbPath = Files.createTempFile("xproxy-vuln-persist", ".db")
        val record = ProjectRecord(
            id = "test", displayName = "Test", baseName = "test",
            createdDate = "2026-07-20", projectDir = dbPath.parent.toString(),
            dbPath = dbPath.toAbsolutePath().toString(),
            createdAtMillis = 0L, lastOpenedMillis = 0L
        )
        return ProjectDataStore(record)
    }

    private fun newContext(store: ProjectDataStore): IntruderUiContext {
        val requestTabBar = RequestTabBar().apply { applyChrome() }
        val addTabPanel = JPanel()
        requestTabBar.putClientProperty("xproxy.applyAddTabHeader", Runnable {})
        requestTabBar.addTab("+", addTabPanel)
        val cardLayout = java.awt.CardLayout()
        val cardPanel = JPanel(cardLayout)
        val frame = IntruderFrame(SeedRequest(ByteArray(0), HttpService("host", 80, "http")), null, null, null, null)
        val ctx = IntruderUiContext(
            frame = frame,
            initialService = HttpService("host", 80, "http"),
            initialRequestText = "GET / HTTP/1.1\r\nHost: host\r\n\r\n",
            projectDataStore = store,
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
        ctx.createRequestTab = { title, requestText -> ctx.createRequestTabWithState(title = title, requestText = requestText) }
        return ctx
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun setupLaf() { FlatLightLaf.setup() }
    }
}
