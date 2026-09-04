package org.jjgroup.xproxy.project.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager

class TrafficHighlightDaoTest {

    private fun newDao(): TrafficHighlightDao {
        val dbPath = Files.createTempFile("xproxy-traffic-highlight", ".db")
        val jdbc = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        DriverManager.getConnection(jdbc).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS traffic_highlights (
                        kind TEXT NOT NULL,
                        entry_id INTEGER NOT NULL,
                        color TEXT NOT NULL,
                        PRIMARY KEY(kind, entry_id)
                    )
                    """.trimIndent()
                )
            }
        }
        return TrafficHighlightDao { DriverManager.getConnection(jdbc) }
    }

    @Test
    fun `upsert then load returns color by kind`() {
        val dao = newDao()
        dao.upsertHighlight("http", 10L, "red")
        dao.upsertHighlight("ws", 10L, "blue")

        val http = dao.loadAllHighlights("http")
        val ws = dao.loadAllHighlights("ws")
        assertEquals("red", http[10L])
        assertEquals("blue", ws[10L])
        // 同 id 不同 kind 互不干扰
        assertEquals(1, http.size)
        assertEquals(1, ws.size)
    }

    @Test
    fun `upsert replaces existing color`() {
        val dao = newDao()
        dao.upsertHighlight("http", 5L, "red")
        dao.upsertHighlight("http", 5L, "green")
        assertEquals("green", dao.loadAllHighlights("http")[5L])
    }

    @Test
    fun `upsert ignores non-positive id`() {
        val dao = newDao()
        dao.upsertHighlight("http", 0L, "red")
        dao.upsertHighlight("http", -1L, "red")
        assertTrue(dao.loadAllHighlights("http").isEmpty())
    }

    @Test
    fun `deleteHighlight removes single entry`() {
        val dao = newDao()
        dao.upsertHighlight("http", 1L, "red")
        dao.upsertHighlight("http", 2L, "green")
        dao.deleteHighlight("http", 1L)
        val remaining = dao.loadAllHighlights("http")
        assertEquals(1, remaining.size)
        assertEquals("green", remaining[2L])
    }

    @Test
    fun `deleteHighlightsByIds removes batch and keeps others`() {
        val dao = newDao()
        dao.upsertHighlight("http", 1L, "red")
        dao.upsertHighlight("http", 2L, "green")
        dao.upsertHighlight("http", 3L, "blue")
        dao.deleteHighlightsByIds("http", setOf(1L, 3L))
        val remaining = dao.loadAllHighlights("http")
        assertEquals(1, remaining.size)
        assertEquals("green", remaining[2L])
    }
}
