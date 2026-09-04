package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SiteMapIssueDetectorBuiltinsTest {

    @Test
    fun `detectIssues returns no built-in issues`() {
        val panel = newPanel()
        val rows = listOf(
            ProxyHistoryEntry(
                id = 1,
                timeMillis = 1,
                method = "GET",
                host = "example.com:80",
                path = "/admin",
                statusCode = 200,
                length = 128,
                mimeType = "html",
                title = "Index",
                tls = false,
                modified = false,
                requestRaw = "GET /admin HTTP/1.1\r\nHost: example.com\r\n\r\n",
                responseRaw = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=1\r\nServer: nginx/1.22.0\r\n\r\nIndex of /"
            )
        )

        val issues = panel.detectIssues(rows)
        assertEquals(0, issues.size)
    }

    private fun newPanel(): SiteMapDetailPanel = SiteMapDetailPanel(
        onSendToFuzzer = { _: String, _: HttpService? -> },
        onSendToCodec = null,
        onDeleteHistoryIds = null,
        onDeleteReportedIssueId = null,
        resolveDetail = { it },
        resolveDetailById = { null },
        toHttpService = { HttpService(it.host.substringBefore(':'), 443, if (it.tls) "https" else "http") },
        normalizePath = { path -> path.substringBefore('?').ifBlank { "/" } },
        isPathUnder = { path, prefix -> prefix == null || prefix == "/" || path == prefix || path.startsWith("$prefix/") }
    )
}
