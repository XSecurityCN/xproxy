package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager

class ProxyHistoryDaoTest {

    @Test
    fun `save and load preserves protocol stream and downgrade fields`() {
        val dbPath = Files.createTempFile("xproxy-proxy-history", ".db")
        val jdbc = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        DriverManager.getConnection(jdbc).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS proxy_history (
                        id INTEGER PRIMARY KEY,
                        time_millis INTEGER NOT NULL,
                        method TEXT NOT NULL,
                        host TEXT NOT NULL,
                        path TEXT NOT NULL,
                        status_code INTEGER NOT NULL,
                        length INTEGER NOT NULL,
                        mime_type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        tls INTEGER NOT NULL,
                        modified INTEGER NOT NULL,
                        tool TEXT NOT NULL DEFAULT 'proxy',
                        request_raw TEXT NOT NULL,
                        response_raw TEXT NOT NULL,
                        original_request_raw TEXT NOT NULL DEFAULT '',
                        original_response_raw TEXT NOT NULL DEFAULT '',
                        protocol TEXT NOT NULL DEFAULT 'http/1.1',
                        stream_id INTEGER,
                        was_downgraded INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        val dao = ProxyHistoryDao { DriverManager.getConnection(jdbc) }
        val input = ProxyHistoryEntry(
            id = 1,
            timeMillis = 1,
            method = "GET",
            host = "example.com",
            path = "/",
            statusCode = 200,
            length = 0,
            mimeType = "text",
            title = "",
            tls = true,
            modified = false,
            requestRaw = "GET / HTTP/2\r\nHost: example.com\r\n\r\n",
            responseRaw = "HTTP/2 200\r\n\r\n",
            protocol = "http/2",
            streamId = 3,
            wasDowngraded = true
        )

        dao.saveHistory(input)
        val loaded = dao.loadHistoryById(1)

        assertNotNull(loaded)
        assertEquals("http/2", loaded?.protocol)
        assertEquals(3, loaded?.streamId)
        assertEquals(true, loaded?.wasDowngraded)
    }

    @Test
    fun `metadata load keeps protocol defaults when not provided`() {
        val dbPath = Files.createTempFile("xproxy-proxy-history-meta", ".db")
        val jdbc = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        DriverManager.getConnection(jdbc).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS proxy_history (
                        id INTEGER PRIMARY KEY,
                        time_millis INTEGER NOT NULL,
                        method TEXT NOT NULL,
                        host TEXT NOT NULL,
                        path TEXT NOT NULL,
                        status_code INTEGER NOT NULL,
                        length INTEGER NOT NULL,
                        mime_type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        tls INTEGER NOT NULL,
                        modified INTEGER NOT NULL,
                        tool TEXT NOT NULL DEFAULT 'proxy',
                        request_raw TEXT NOT NULL,
                        response_raw TEXT NOT NULL,
                        original_request_raw TEXT NOT NULL DEFAULT '',
                        original_response_raw TEXT NOT NULL DEFAULT '',
                        protocol TEXT NOT NULL DEFAULT 'http/1.1',
                        stream_id INTEGER,
                        was_downgraded INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    INSERT INTO proxy_history(
                        id, time_millis, method, host, path, status_code, length, mime_type, title,
                        tls, modified, tool, request_raw, response_raw, original_request_raw, original_response_raw,
                        protocol, stream_id, was_downgraded
                    ) VALUES (2, 2, 'GET', 'example.com', '/', 200, 0, 'text', '', 0, 0, 'proxy', '', '', '', '', 'http/1.1', NULL, 0)
                    """.trimIndent()
                )
            }
        }

        val dao = ProxyHistoryDao { DriverManager.getConnection(jdbc) }
        val rows = dao.loadHistoryMetadata()
        assertEquals(1, rows.size)
        assertEquals("http/1.1", rows.first().protocol)
        assertEquals(null, rows.first().streamId)
        assertFalse(rows.first().wasDowngraded)
    }

    @Test
    fun `scanHistoryDetails streams all rows and reports max id`() {
        val dbPath = Files.createTempFile("xproxy-proxy-history-scan", ".db")
        val jdbc = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        DriverManager.getConnection(jdbc).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE proxy_history (
                        id INTEGER PRIMARY KEY,
                        time_millis INTEGER NOT NULL,
                        method TEXT NOT NULL,
                        host TEXT NOT NULL,
                        path TEXT NOT NULL,
                        status_code INTEGER NOT NULL,
                        length INTEGER NOT NULL,
                        mime_type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        tls INTEGER NOT NULL,
                        modified INTEGER NOT NULL,
                        tool TEXT NOT NULL DEFAULT 'proxy',
                        request_raw TEXT NOT NULL,
                        response_raw TEXT NOT NULL,
                        original_request_raw TEXT NOT NULL DEFAULT '',
                        original_response_raw TEXT NOT NULL DEFAULT '',
                        protocol TEXT NOT NULL DEFAULT 'http/1.1',
                        stream_id INTEGER,
                        was_downgraded INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }
        val dao = ProxyHistoryDao { DriverManager.getConnection(jdbc) }
        dao.saveHistory(entry(1, "json", 200, "secret-token"))
        dao.saveHistory(entry(2, "html", 404, "<html>nope</html>"))
        dao.saveHistory(entry(3, "json", 500, "another-secret"))

        DriverManager.getConnection(jdbc).use { conn ->
            val seen = mutableListOf<Pair<Long, String>>()
            val maxId = dao.scanHistoryDetails(conn) { id, mime, status, req, resp ->
                seen.add(id to resp)
                true
            }
            assertEquals(3L, maxId)
            assertEquals(listOf(1L to "secret-token", 2L to "<html>nope</html>", 3L to "another-secret"), seen)
        }

        // 返回 false 应提前终止扫描
        DriverManager.getConnection(jdbc).use { conn ->
            var count = 0
            dao.scanHistoryDetails(conn) { _, _, _, _, _ ->
                count++
                false
            }
            assertEquals(1, count)
        }
    }

    @Test
    fun `scanHistoryDetails is empty safe on fresh table`() {
        val dbPath = Files.createTempFile("xproxy-proxy-history-scan-empty", ".db")
        val jdbc = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        DriverManager.getConnection(jdbc).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE proxy_history (
                        id INTEGER PRIMARY KEY, time_millis INTEGER NOT NULL, method TEXT NOT NULL, host TEXT NOT NULL,
                        path TEXT NOT NULL, status_code INTEGER NOT NULL, length INTEGER NOT NULL, mime_type TEXT NOT NULL,
                        title TEXT NOT NULL, tls INTEGER NOT NULL, modified INTEGER NOT NULL, request_raw TEXT NOT NULL,
                        response_raw TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        val dao = ProxyHistoryDao { DriverManager.getConnection(jdbc) }
        DriverManager.getConnection(jdbc).use { conn ->
            var hits = 0
            val maxId = dao.scanHistoryDetails(conn) { _, _, _, _, _ -> hits++; true }
            assertEquals(0, hits)
            assertEquals(0L, maxId)
        }
    }

    private fun entry(id: Long, mime: String, status: Int, responseRaw: String) = ProxyHistoryEntry(
        id = id,
        timeMillis = id,
        method = "GET",
        host = "example.com",
        path = "/",
        statusCode = status,
        length = 0,
        mimeType = mime,
        title = "",
        tls = false,
        modified = false,
        requestRaw = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
        responseRaw = responseRaw
    )
}
