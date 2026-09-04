package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry
import org.jjgroup.xproxy.proxy.model.WsSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class WsSessionDaoTest {

    private fun newStore(): Pair<ProjectDataStore, String> {
        val dbPath = Files.createTempFile("xproxy-ws-session", ".db")
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
        return ProjectDataStore(record) to "jdbc:sqlite:${dbPath.toAbsolutePath()}"
    }

    @Test
    fun `session is saved loaded and response updated`() {
        val (store, _) = newStore()
        val session = WsSession(
            id = 42,
            timeMillis = 1_700_000_000_000L,
            host = "ws.example.com",
            port = 8443,
            tls = true,
            path = "/ws/chat",
            handshakeRequest = "GET /ws/chat HTTP/1.1\r\nHost: ws.example.com\r\n\r\n",
            handshakeResponse = ""
        )
        store.saveWsSession(session)
        store.updateWsSessionHandshakeResponse(42, "HTTP/1.1 101 Switching Protocols\r\n\r\n")

        val loaded = store.loadWsSession(42)
        assertNotNull(loaded)
        assertEquals("ws.example.com", loaded!!.host)
        assertEquals(8443, loaded.port)
        assertTrue(loaded.tls)
        assertEquals("HTTP/1.1 101 Switching Protocols\r\n\r\n", loaded.handshakeResponse)
        assertEquals(42, store.loadMaxWsSessionId())
    }

    @Test
    fun `ws history entry carries session id and reloads it`() {
        val (store, _) = newStore()
        store.saveWsSession(
            WsSession(7, 1L, "h", 80, false, "/", "GET / HTTP/1.1\r\n\r\n", "HTTP/1.1 101\r\n\r\n")
        )
        val entry = ProxyWsHistoryEntry(
            id = 100,
            timeMillis = 1L,
            host = "h",
            path = "/",
            direction = "C -> S",
            messageType = "Text",
            mimeType = "text",
            length = 5,
            preview = "hello",
            payload = "hello",
            sessionId = 7
        )
        store.saveWsHistory(entry)

        val reloaded = store.loadWsHistoryMetadata().first { it.id == 100L }
        assertEquals(7L, reloaded.sessionId)
        assertNotNull(store.loadWsSession(7))
    }

    @Test
    fun `legacy ws history row without session id loads null session id`() {
        val (store, jdbc) = newStore()
        java.sql.DriverManager.getConnection(jdbc).use { conn ->
            conn.prepareStatement(
                "INSERT INTO ws_history(id, time_millis, host, path, direction, message_type, mime_type, length, preview, payload) " +
                    "VALUES (1, 0, 'h', '/', 'C -> S', 'Text', 'text', 0, '', '')"
            ).use { it.executeUpdate() }
        }
        val row = store.loadWsHistoryMetadata().first { it.id == 1L }
        assertNull(row.sessionId)
    }

    @Test
    fun `deleting ws history cascades to orphaned session`() {
        val (store, _) = newStore()
        store.saveWsSession(WsSession(7, 1L, "h", 80, false, "/", "GET / HTTP/1.1\r\n\r\n", ""))
        store.saveWsHistory(
            ProxyWsHistoryEntry(1, 1L, "h", "/", "C -> S", "Text", "text", 5, "hello", "hello", 7L)
        )
        // 引用存在时不应删除会话:删除前会话可加载。
        assertNotNull(store.loadWsSession(7))
        store.deleteWsHistoryByIds(setOf(1L))
        // 引用被删除后,孤立会话被级联删除。
        assertNull(store.loadWsSession(7))
    }

    @Test
    fun `deleting ws history keeps session referenced by another message`() {
        val (store, _) = newStore()
        store.saveWsSession(WsSession(9, 1L, "h", 80, false, "/", "GET / HTTP/1.1\r\n\r\n", ""))
        store.saveWsHistory(ProxyWsHistoryEntry(1, 1L, "h", "/", "C -> S", "Text", "text", 5, "a", "a", 9L))
        store.saveWsHistory(ProxyWsHistoryEntry(2, 2L, "h", "/", "S -> C", "Text", "text", 5, "b", "b", 9L))
        store.deleteWsHistoryByIds(setOf(1L))
        // 仍被消息 2 引用 -> 会话保留。
        assertNotNull(store.loadWsSession(9))
        assertFalse(store.loadWsHistoryMetadata().none { it.id == 2L })
    }
}
