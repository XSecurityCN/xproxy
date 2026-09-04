package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.issue.model.ReportedIssue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager

class ReportedIssueDaoTest {

    private fun newDao(): Pair<String, ReportedIssueDao> {
        val dbPath = Files.createTempFile("xproxy-reported-issue", ".db")
        val jdbc = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        DriverManager.getConnection(jdbc).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS reported_issues (
                        issue_id TEXT PRIMARY KEY,
                        source TEXT NOT NULL,
                        name TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        confidence TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        remediation TEXT NOT NULL,
                        url TEXT NOT NULL,
                        host TEXT NOT NULL,
                        path TEXT NOT NULL,
                        method TEXT NOT NULL,
                        request_raw TEXT NOT NULL,
                        response_raw TEXT NOT NULL,
                        tags_csv TEXT NOT NULL,
                        evidence TEXT NOT NULL DEFAULT '',
                        created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        return jdbc to ReportedIssueDao { DriverManager.getConnection(jdbc) }
    }

    private fun sampleIssue(id: String, createdAt: Long = 1L) = ReportedIssue(
        issueId = id,
        source = "script",
        name = "missing-security-headers",
        severity = "Medium",
        confidence = "Firm",
        detail = "Response lacks X-Frame-Options",
        remediation = "Add X-Frame-Options header",
        url = "https://example.com/a",
        host = "example.com",
        path = "/a",
        method = "GET",
        requestRaw = "GET /a HTTP/1.1\r\nHost: example.com\r\n\r\n",
        responseRaw = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok",
        tagsCsv = "headers,security",
        createdAtMillis = createdAt
    )

    @Test
    fun `metadata load excludes raw and preserves other fields`() {
        val (_, dao) = newDao()
        dao.saveReportedIssue(sampleIssue("iss-1", 1L))
        dao.saveReportedIssue(sampleIssue("iss-2", 2L))

        val metadata = dao.loadReportedIssueMetadata()
        assertEquals(2, metadata.size)
        // 按 created_at_millis ASC 排序
        assertEquals("iss-1", metadata[0].issueId)
        assertEquals("iss-2", metadata[1].issueId)

        val first = metadata[0]
        // raw 必须为空(启动期不载入,留给懒加载)
        assertEquals("", first.requestRaw)
        assertEquals("", first.responseRaw)
        // 其余元数据完整保留
        assertEquals("missing-security-headers", first.name)
        assertEquals("Medium", first.severity)
        assertEquals("GET", first.method)
        assertEquals("/a", first.path)
        assertEquals("example.com", first.host)
        assertEquals("Response lacks X-Frame-Options", first.detail)
        assertEquals("Add X-Frame-Options header", first.remediation)
        assertEquals("headers,security", first.tagsCsv)
    }

    @Test
    fun `loadReportedIssueRaw returns raw pair by issueId`() {
        val (_, dao) = newDao()
        val issue = sampleIssue("iss-1")
        dao.saveReportedIssue(issue)

        val raw = dao.loadReportedIssueRaw("iss-1")
        assertEquals(issue.requestRaw, raw?.first)
        assertEquals(issue.responseRaw, raw?.second)
        assertTrue(raw!!.first.contains("GET /a HTTP/1.1"))
    }

    @Test
    fun `loadReportedIssueRaw returns null for missing or blank id`() {
        val (_, dao) = newDao()
        dao.saveReportedIssue(sampleIssue("iss-1"))

        assertNull(dao.loadReportedIssueRaw("does-not-exist"))
        assertNull(dao.loadReportedIssueRaw(""))
        assertNull(dao.loadReportedIssueRaw("   "))
    }
}
