package org.jjgroup.xproxy.project.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class FuzzerTabDaoTest {

    private fun newStore(): Pair<ProjectDataStore, String> {
        val dbPath = Files.createTempFile("xproxy-fuzzer-tab", ".db")
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
    fun `ws mode tab is persisted and reloaded with opcode and payload`() {
        val (store, _) = newStore()
        store.upsertFuzzerTab(
            FuzzerTabRecord(
                tabId = "ws-tab-1",
                title = "wss://host/ws",
                requestRaw = "GET /ws HTTP/1.1\r\nHost: host\r\n\r\n",
                responseText = "HTTP/1.1 101 Switching Protocols\r\n\r\n",
                targetHost = "host",
                targetPort = 443,
                targetProtocol = "https",
                positionIndex = 0,
                selected = true,
                isWsMode = true,
                wsOpcode = 2,
                wsPayload = "{\"hello\":\"world\"}"
            )
        )
        store.replaceFuzzerTabWsFrames(
            "ws-tab-1",
            listOf(
                FuzzerTabWsFrameRecord("C -> S", 1, "{\"hi\":1}"),
                FuzzerTabWsFrameRecord("S -> C", 1, "{\"hi\":1,\"ok\":true}"),
                FuzzerTabWsFrameRecord("S -> C", 2, "deadbeef")
            )
        )
        store.upsertFuzzerTab(
            FuzzerTabRecord(
                tabId = "http-tab-1",
                title = "GET /",
                requestRaw = "GET / HTTP/1.1\r\nHost: host\r\n\r\n",
                responseText = "HTTP/1.1 200 OK\r\n\r\n",
                targetHost = "host",
                targetPort = 443,
                targetProtocol = "https",
                positionIndex = 1,
                selected = false
            )
        )

        val tabs = store.loadFuzzerTabs()
        val ws = tabs.first { it.tabId == "ws-tab-1" }
        val http = tabs.first { it.tabId == "http-tab-1" }

        assertTrue(ws.isWsMode)
        assertEquals(2, ws.wsOpcode)
        assertEquals("{\"hello\":\"world\"}", ws.wsPayload)
        assertEquals("GET /ws HTTP/1.1\r\nHost: host\r\n\r\n", ws.requestRaw)

        // WS 帧持久化:顺序、方向、opcode、payload 均原样回读。
        val frames = store.loadFuzzerTabWsFrames("ws-tab-1")
        assertEquals(3, frames.size)
        assertEquals("C -> S", frames[0].direction)
        assertEquals(1, frames[0].opcode)
        assertEquals("{\"hi\":1}", frames[0].payload)
        assertEquals("S -> C", frames[2].direction)
        assertEquals(2, frames[2].opcode)
        assertEquals("deadbeef", frames[2].payload)
        // HTTP tab 无 WS 帧。
        assertTrue(store.loadFuzzerTabWsFrames("http-tab-1").isEmpty())

        assertFalse(http.isWsMode)
        assertEquals(1, http.wsOpcode) // 默认 TEXT
        assertEquals("", http.wsPayload)
    }

    @Test
    fun `ws frames replace overwrites and delete cascades`() {
        val (store, _) = newStore()
        store.upsertFuzzerTab(
            FuzzerTabRecord(
                tabId = "ws-2", title = "t", requestRaw = "r", responseText = "",
                targetHost = "h", targetPort = 443, targetProtocol = "https",
                positionIndex = 0, selected = true, isWsMode = true, wsOpcode = 1, wsPayload = "p"
            )
        )
        store.replaceFuzzerTabWsFrames("ws-2", listOf(FuzzerTabWsFrameRecord("C -> S", 1, "a"), FuzzerTabWsFrameRecord("S -> C", 1, "b")))
        assertEquals(2, store.loadFuzzerTabWsFrames("ws-2").size)

        // 再次 replace 为更短列表,应整体覆盖而非追加。
        store.replaceFuzzerTabWsFrames("ws-2", listOf(FuzzerTabWsFrameRecord("C -> S", 1, "only")))
        val after = store.loadFuzzerTabWsFrames("ws-2")
        assertEquals(1, after.size)
        assertEquals("only", after[0].payload)

        // 删除 tab 级联清理 ws 帧。
        store.deleteFuzzerTab("ws-2")
        assertTrue(store.loadFuzzerTabWsFrames("ws-2").isEmpty())
        assertTrue(store.loadFuzzerTabs().none { it.tabId == "ws-2" })
    }

    @Test
    fun `legacy fuzzer tab without ws columns loads as http mode`() {
        val (store, jdbc) = newStore()
        java.sql.DriverManager.getConnection(jdbc).use { conn ->
            conn.prepareStatement(
                "INSERT INTO fuzzer_tabs(tab_id, title, request_raw, response_text, target_host, target_port, " +
                    "target_protocol, position_index, selected, group_name, group_color, updated_at_millis) " +
                    "VALUES ('legacy', 't', 'r', 'r', 'h', 80, 'http', 0, 0, '', '', 0)"
            ).use { it.executeUpdate() }
        }
        val legacy = store.loadFuzzerTabs().first { it.tabId == "legacy" }
        assertFalse(legacy.isWsMode)
        assertEquals(1, legacy.wsOpcode)
        assertEquals("", legacy.wsPayload)
    }
}
