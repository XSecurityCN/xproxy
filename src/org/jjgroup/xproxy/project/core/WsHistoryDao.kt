package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry
import java.sql.Connection

class WsHistoryDao(private val connection: () -> Connection) {

    @Synchronized
    fun saveWsHistory(entry: ProxyWsHistoryEntry) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO ws_history(
                    id, time_millis, host, path, direction, message_type, mime_type, length, preview, payload, session_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, entry.id)
                ps.setLong(2, entry.timeMillis)
                ps.setString(3, entry.host)
                ps.setString(4, entry.path)
                ps.setString(5, entry.direction)
                ps.setString(6, entry.messageType)
                ps.setString(7, entry.mimeType)
                ps.setInt(8, entry.length)
                ps.setString(9, entry.preview)
                ps.setString(10, entry.payload)
                if (entry.sessionId != null) {
                    ps.setLong(11, entry.sessionId)
                } else {
                    ps.setNull(11, java.sql.Types.INTEGER)
                }
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun loadWsHistoryMetadata(): List<ProxyWsHistoryEntry> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, time_millis, host, path, direction, message_type, mime_type, length, preview, session_id
                FROM ws_history
                ORDER BY id ASC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<ProxyWsHistoryEntry>()
                    while (rs.next()) {
                        val sessionId = (rs.getObject("session_id") as? Number)?.toLong()
                        rows.add(
                            ProxyWsHistoryEntry(
                                id = rs.getLong("id"),
                                timeMillis = rs.getLong("time_millis"),
                                host = rs.getString("host"),
                                path = rs.getString("path"),
                                direction = rs.getString("direction"),
                                messageType = rs.getString("message_type"),
                                mimeType = rs.getString("mime_type") ?: "other",
                                length = rs.getInt("length"),
                                preview = rs.getString("preview"),
                                payload = "",
                                sessionId = sessionId
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    @Synchronized
    fun loadWsPayloadById(id: Long): String? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT payload
                FROM ws_history
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    return rs.getString("payload")
                }
            }
        }
    }

    /**
     * 流式扫描全表 WS 历史,对每行回调 [action](过滤所需字段)。语义同 [ProxyHistoryDao.scanHistoryDetails]:
     * 后台关键词扫描用,O(1) 内存,替代有界缓存后的 N+1 逐行 `loadWsPayloadById`。不加 `@Synchronized`、
     * 使用独立读连接 [conn],不阻塞持久化写入。[action] 返回 false 提前终止。
     */
    fun scanWsDetails(
        conn: Connection,
        action: (id: Long, host: String, path: String, direction: String, messageType: String, mimeType: String, preview: String, payload: String) -> Boolean
    ): Long {
        var maxId = 0L
        conn.prepareStatement(
            """
            SELECT id, host, path, direction, message_type, mime_type, preview, payload
            FROM ws_history
            ORDER BY id ASC
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val id = rs.getLong("id")
                    if (id > maxId) {
                        maxId = id
                    }
                    val continueScan = action(
                        id,
                        rs.getString("host") ?: "",
                        rs.getString("path") ?: "",
                        rs.getString("direction") ?: "",
                        rs.getString("message_type") ?: "",
                        rs.getString("mime_type") ?: "other",
                        rs.getString("preview") ?: "",
                        rs.getString("payload") ?: ""
                    )
                    if (!continueScan) {
                        break
                    }
                }
            }
        }
        return maxId
    }

    @Synchronized
    fun deleteWsHistoryByIds(ids: Set<Long>) {
        if (ids.isEmpty()) {
            return
        }
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM ws_history WHERE id = ?").use { ps ->
                    ids.forEach {
                        ps.setLong(1, it)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /** 取某条 ws_history 行的 session_id(供级联删除 ws_session 判定引用关系)。 */
    @Synchronized
    fun findSessionIdByHistoryId(id: Long): Long? {
        connection().use { conn ->
            conn.prepareStatement("SELECT session_id FROM ws_history WHERE id = ? LIMIT 1").use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    return (rs.getObject("session_id") as? Number)?.toLong()
                }
            }
        }
    }

    /** 该 session_id 是否仍被任意 ws_history 行引用(供级联删除 ws_session 判定)。 */
    @Synchronized
    fun sessionHasReferences(sessionId: Long): Boolean {
        connection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM ws_history WHERE session_id = ? LIMIT 1").use { ps ->
                ps.setLong(1, sessionId)
                ps.executeQuery().use { rs -> return rs.next() }
            }
        }
    }
}
