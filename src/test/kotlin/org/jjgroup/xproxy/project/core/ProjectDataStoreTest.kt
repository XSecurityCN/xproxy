package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.issue.model.ReportedIssue
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager

class ProjectDataStoreTest {

    private fun newStore(): ProjectDataStore {
        val dbPath = Files.createTempFile("xproxy-datastore", ".db")
        val record = ProjectRecord(
            id = "test",
            displayName = "Test",
            baseName = "test",
            createdDate = "2026-07-08",
            projectDir = dbPath.parent.toString(),
            dbPath = dbPath.toAbsolutePath().toString(),
            createdAtMillis = 0L,
            lastOpenedMillis = 0L
        )
        return ProjectDataStore(record)
    }

    private fun history(id: Long): ProxyHistoryEntry = ProxyHistoryEntry(
        id = id,
        timeMillis = id,
        method = "GET",
        host = "example.com",
        path = "/$id",
        statusCode = 200,
        length = 0,
        mimeType = "text",
        title = "",
        tls = true,
        modified = false,
        requestRaw = "GET /$id HTTP/1.1\r\nHost: example.com\r\n\r\n",
        responseRaw = "HTTP/1.1 200 OK\r\n\r\nok"
    )

    @Test
    fun `constructing the store initializes schema without NPE and allows save and load`() {
        // 回归:sharedConnection(by lazy) 必须声明在 init 之前,否则 init 中调用 connection()
        // 时 lazy 委托尚未初始化,会抛 "Cannot invoke Lazy.getValue() because ... is null"。
        val store = newStore()

        store.saveHistory(history(1))
        val loaded = store.loadHistoryById(1)

        assertNotNull(loaded)
        assertEquals("GET", loaded?.method)
        assertEquals("/1", loaded?.path)
    }

    @Test
    fun `reused connection persists across multiple saves and loads`() {
        val store = newStore()
        repeat(3) { i -> store.saveHistory(history((i + 1).toLong())) }

        val meta = store.loadHistoryMetadata()
        assertEquals(3, meta.size)

        // 再次按 id 读取,验证复用连接后续调用仍正常
        val second = store.loadHistoryById(2)
        assertNotNull(second)
        assertEquals("/2", second?.path)
    }

    @Test
    fun `old reported_issues without evidence column migrates on open`() {
        val dbPath = Files.createTempFile("xproxy-migrate", ".db")
        val jdbc = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        // 建老库 reported_issues(无 evidence 列)+ 插一条无 evidence 的行。
        DriverManager.getConnection(jdbc).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE reported_issues (
                        issue_id TEXT PRIMARY KEY, source TEXT NOT NULL, name TEXT NOT NULL,
                        severity TEXT NOT NULL, confidence TEXT NOT NULL, detail TEXT NOT NULL,
                        remediation TEXT NOT NULL, url TEXT NOT NULL, host TEXT NOT NULL, path TEXT NOT NULL,
                        method TEXT NOT NULL, request_raw TEXT NOT NULL, response_raw TEXT NOT NULL,
                        tags_csv TEXT NOT NULL, created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    "INSERT INTO reported_issues VALUES(" +
                        "'i1','mcp','old','Information','Tentative','d','r','u','h','/','GET','req','resp','',1)"
                )
            }
        }
        val record = ProjectRecord(
            id = "test", displayName = "T", baseName = "t", createdDate = "2026-07-19",
            projectDir = dbPath.parent.toString(), dbPath = dbPath.toAbsolutePath().toString(),
            createdAtMillis = 0L, lastOpenedMillis = 0L
        )
        val store = ProjectDataStore(record) // 触发 ensureColumn 加 evidence 列

        val issues = store.loadReportedIssues()
        assertEquals(1, issues.size)
        assertEquals("", issues.first().evidenceCsv, "migrated old row should default evidence to empty")
        // 迁移后可写 evidence 并读回。
        store.saveReportedIssue(issues.first().copy(evidenceCsv = "secret1\nsecret2"))
        val reloaded = store.loadReportedIssues().first()
        assertEquals("secret1\nsecret2", reloaded.evidenceCsv)
    }
}
