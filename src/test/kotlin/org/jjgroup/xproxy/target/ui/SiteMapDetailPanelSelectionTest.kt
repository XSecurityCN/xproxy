package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nLocaleOption
import org.jjgroup.xproxy.issue.model.ReportedIssue
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class SiteMapDetailPanelSelectionTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun setupLanguage() {
        I18n.configureForTests(temp.resolve("bundled"), temp.resolve("user"), persistedLanguage = I18nLocaleOption.SYSTEM.key)
        I18n.registerSettings()
        I18n.syncUserBundles()
        I18n.setLocaleOption(I18nLocaleOption.EN)
    }

    @Test
    fun `refresh keeps user-selected contents row when new traffic arrives`() {
        val panel = newPanel()

        val h1 = history(1, "/a")
        val h2 = history(2, "/b")
        panel.refreshContents(recordsOf(h1, h2), recordsByHostOf(h1, h2), recordsByKeyOf(h1, h2), null)

        selectContentsByHistoryId(panel, 1)
        assertEquals(1L, selectedContentsHistoryId(panel))

        val h3 = history(3, "/c")
        panel.refreshContents(recordsOf(h1, h2, h3), recordsByHostOf(h1, h2, h3), recordsByKeyOf(h1, h2, h3), null)

        assertEquals(1L, selectedContentsHistoryId(panel))
        assertTrue(panel.detailViewer.requestRawArea.text.contains("GET /a HTTP/1.1"))
    }

    @Test
    fun `refresh keeps selected issue detail when new traffic arrives`() {
        val panel = newPanel()

        val h1 = history(1, "/a")
        val h2 = history(2, "/b")
        panel.refreshContents(recordsOf(h1, h2), recordsByHostOf(h1, h2), recordsByKeyOf(h1, h2), null)

        val issue = ReportedIssue(
            issueId = "iss-1",
            source = "script",
            name = "Custom issue",
            severity = "High",
            confidence = "Firm",
            detail = "detail",
            remediation = "fix",
            url = "https://example.com/a",
            host = "example.com",
            path = "/a",
            method = "GET",
            requestRaw = "GET /issue HTTP/1.1\r\nHost: example.com\r\n\r\n",
            responseRaw = "HTTP/1.1 200 OK\r\n\r\nissue",
            tagsCsv = "",
            createdAtMillis = 1L
        )
        panel.reportedIssuesById[issue.issueId] = issue
        panel.refreshIssuesTree(panel.contentsModelCurrentRows(), null)

        selectIssueByKey(panel, "script:iss-1")
        assertEquals("script:iss-1", panel.selectedIssueKey())
        assertTrue(panel.detailViewer.requestRawArea.text.contains("GET /issue HTTP/1.1"))

        val h3 = history(3, "/c")
        panel.refreshContents(recordsOf(h1, h2, h3), recordsByHostOf(h1, h2, h3), recordsByKeyOf(h1, h2, h3), null)

        assertEquals("script:iss-1", panel.selectedIssueKey())
        assertTrue(panel.detailViewer.requestRawArea.text.contains("GET /issue HTTP/1.1"))
    }

    @Test
    fun `metadata-only reported issue lazy loads raw on selection`() {
        // 启动期批量载入的 issue 仅含元数据(requestRaw/responseRaw 为空),选中查看时按需懒加载。
        val loadedIssueIds = mutableListOf<String>()
        val panel = newPanel(resolveIssueRaw = { issueId ->
            loadedIssueIds.add(issueId)
            "GET /issue HTTP/1.1\r\nHost: example.com\r\n\r\n" to "HTTP/1.1 200 OK\r\n\r\nissue"
        })

        val h1 = history(1, "/a")
        panel.refreshContents(recordsOf(h1), recordsByHostOf(h1), recordsByKeyOf(h1), null)

        val issue = ReportedIssue(
            issueId = "iss-lazy",
            source = "script",
            name = "Custom issue",
            severity = "High",
            confidence = "Firm",
            detail = "detail",
            remediation = "fix",
            url = "https://example.com/a",
            host = "example.com",
            path = "/a",
            method = "GET",
            requestRaw = "",
            responseRaw = "",
            tagsCsv = "",
            createdAtMillis = 1L
        )
        panel.reportedIssuesById[issue.issueId] = issue
        panel.refreshIssuesTree(panel.contentsModelCurrentRows(), null)

        selectIssueByKey(panel, "script:iss-lazy")
        assertEquals("script:iss-lazy", panel.selectedIssueKey())
        assertTrue(panel.detailViewer.requestRawArea.text.contains("GET /issue HTTP/1.1"))
        assertEquals(listOf("iss-lazy"), loadedIssueIds)
    }

    @Test
    fun `refresh does not reset caret for unchanged selected request`() {
        val panel = newPanel()

        val h1 = history(1, "/a")
        val h2 = history(2, "/b")
        panel.refreshContents(recordsOf(h1, h2), recordsByHostOf(h1, h2), recordsByKeyOf(h1, h2), null)

        selectContentsByHistoryId(panel, 1)
        panel.detailViewer.requestRawArea.caretPosition = panel.detailViewer.requestRawArea.document.length
        val beforeCaret = panel.detailViewer.requestRawArea.caretPosition

        val h3 = history(3, "/c")
        panel.refreshContents(recordsOf(h1, h2, h3), recordsByHostOf(h1, h2, h3), recordsByKeyOf(h1, h2, h3), null)

        assertEquals(1L, selectedContentsHistoryId(panel))
        assertEquals(beforeCaret, panel.detailViewer.requestRawArea.caretPosition)
    }

    private fun newPanel(resolveIssueRaw: (String) -> Pair<String, String>? = { null }): SiteMapDetailPanel = SiteMapDetailPanel(
        onSendToFuzzer = { _: String, _: HttpService? -> },
        onSendToCodec = null,
        onDeleteHistoryIds = null,
        onDeleteReportedIssueId = null,
        resolveDetail = { it },
        resolveDetailById = { null },
        resolveIssueRaw = resolveIssueRaw,
        toHttpService = { HttpService(it.host.substringBefore(':'), 443, if (it.tls) "https" else "http") },
        normalizePath = { path -> path.substringBefore('?').ifBlank { "/" } },
        isPathUnder = { path, prefix -> prefix == null || prefix == "/" || path == prefix || path.startsWith("$prefix/") }
    )

    private fun history(id: Long, path: String): ProxyHistoryEntry = ProxyHistoryEntry(
        id = id,
        timeMillis = id,
        method = "GET",
        host = "example.com:443",
        path = path,
        statusCode = 200,
        length = 2,
        mimeType = "text",
        title = "",
        tls = true,
        modified = false,
        requestRaw = "GET $path HTTP/1.1\r\nHost: example.com\r\n\r\n",
        responseRaw = "HTTP/1.1 200 OK\r\n\r\nok"
    )

    private fun recordsOf(vararg entries: ProxyHistoryEntry): List<HistoryRecord> =
        entries.map { entry ->
            val normalized = entry.path.substringBefore('?').ifBlank { "/" }
            HistoryRecord(entry, "https://example.com:443", normalized, "https://example.com:443|$normalized")
        }

    private fun recordsByHostOf(vararg entries: ProxyHistoryEntry): Map<String, List<HistoryRecord>> {
        val records = recordsOf(*entries)
        return records.groupBy { it.hostKey }
    }

    private fun recordsByKeyOf(vararg entries: ProxyHistoryEntry): Map<String, List<HistoryRecord>> {
        val records = recordsOf(*entries)
        return records.groupBy { it.key }
    }

    private fun selectContentsByHistoryId(panel: SiteMapDetailPanel, id: Long) {
        val modelRow = panel.contentsModelCurrentRows().indexOfFirst { it.id == id }
        assertTrue(modelRow >= 0)
        val viewRow = panel.contentsTable.convertRowIndexToView(modelRow)
        assertTrue(viewRow >= 0)
        panel.contentsTable.selectionModel.setSelectionInterval(viewRow, viewRow)
    }

    private fun selectedContentsHistoryId(panel: SiteMapDetailPanel): Long? {
        val modelRow = panel.selectedModelRow(panel.contentsTable)
        return panel.contentsModelGetAt(modelRow)?.id
    }

    private fun selectIssueByKey(panel: SiteMapDetailPanel, key: String) {
        val root = panel.issuesTree.model.root as DefaultMutableTreeNode
        val path = findIssuePath(root, key)
        assertNotNull(path)
        panel.issuesTree.selectionPath = path
    }

    private fun findIssuePath(root: DefaultMutableTreeNode, key: String): TreePath? {
        for (i in 0 until root.childCount) {
            val categoryNode = root.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            for (j in 0 until categoryNode.childCount) {
                val issueNode = categoryNode.getChildAt(j) as? DefaultMutableTreeNode ?: continue
                val data = issueNode.userObject as? IssueTreeNode ?: continue
                if (data.issue?.key == key) {
                    return TreePath(arrayOf(root, categoryNode, issueNode))
                }
            }
        }
        return null
    }
}
