package org.jjgroup.xproxy.project.core

import java.sql.Connection

/**
 * 流量行高亮标记持久化(`traffic_highlights` 表)。
 *
 * 独立于 `proxy_history`/`ws_history`:高亮是用户/xapp 的事后标注,与流量落库热路径解耦。
 * 键为 (kind, entry_id):kind 取 `http`/`ws`。`http` 同时覆盖代理历史表与 Target 内容表
 * (二者展示同一 `proxy_history` entry id,故共享同一高亮记录)。
 *
 * 语义与 [ReportedIssueDao] 一致:`@Synchronized` + 复用 `connection: () -> Connection`。
 */
class TrafficHighlightDao(private val connection: () -> Connection) {

    @Synchronized
    fun upsertHighlight(kind: String, entryId: Long, color: String) {
        if (entryId <= 0L) return
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO traffic_highlights(kind, entry_id, color)
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, kind)
                ps.setLong(2, entryId)
                ps.setString(3, color)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun deleteHighlight(kind: String, entryId: Long) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                DELETE FROM traffic_highlights
                WHERE kind = ? AND entry_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, kind)
                ps.setLong(2, entryId)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun deleteHighlightsByIds(kind: String, ids: Set<Long>) {
        if (ids.isEmpty()) return
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM traffic_highlights WHERE kind = ? AND entry_id = ?").use { ps ->
                    ps.setString(1, kind)
                    ids.forEach {
                        ps.setLong(2, it)
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

    @Synchronized
    fun loadAllHighlights(kind: String): Map<Long, String> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT entry_id, color
                FROM traffic_highlights
                WHERE kind = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, kind)
                ps.executeQuery().use { rs ->
                    val map = HashMap<Long, String>()
                    while (rs.next()) {
                        map[rs.getLong("entry_id")] = rs.getString("color") ?: "none"
                    }
                    return map
                }
            }
        }
    }
}
