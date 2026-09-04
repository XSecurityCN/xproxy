package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import java.sql.Connection

class ProxyHistoryDao(private val connection: () -> Connection) {

    @Synchronized
    fun saveHistory(entry: ProxyHistoryEntry) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO proxy_history(
                    id, time_millis, method, host, path, status_code, length, mime_type, title,
                    tls, modified, tool, request_raw, response_raw, original_request_raw, original_response_raw,
                    protocol, stream_id, was_downgraded
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, entry.id)
                ps.setLong(2, entry.timeMillis)
                ps.setString(3, entry.method)
                ps.setString(4, entry.host)
                ps.setString(5, entry.path)
                ps.setInt(6, entry.statusCode)
                ps.setInt(7, entry.length)
                ps.setString(8, entry.mimeType)
                ps.setString(9, entry.title)
                ps.setInt(10, if (entry.tls) 1 else 0)
                ps.setInt(11, if (entry.modified) 1 else 0)
                ps.setString(12, entry.tool)
                ps.setString(13, entry.requestRaw)
                ps.setString(14, entry.responseRaw)
                ps.setString(15, entry.originalRequestRaw)
                ps.setString(16, entry.originalResponseRaw)
                ps.setString(17, entry.protocol)
                if (entry.streamId == null) {
                    ps.setObject(18, null)
                } else {
                    ps.setInt(18, entry.streamId)
                }
                ps.setInt(19, if (entry.wasDowngraded) 1 else 0)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun loadHistoryMetadata(): List<ProxyHistoryEntry> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, time_millis, method, host, path, status_code, length, mime_type, title,
                       tls, modified, tool, protocol, stream_id, was_downgraded
                FROM proxy_history
                ORDER BY id ASC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<ProxyHistoryEntry>()
                    while (rs.next()) {
                        rows.add(
                            ProxyHistoryEntry(
                                id = rs.getLong("id"),
                                timeMillis = rs.getLong("time_millis"),
                                method = rs.getString("method"),
                                host = rs.getString("host"),
                                path = rs.getString("path"),
                                statusCode = rs.getInt("status_code"),
                                length = rs.getInt("length"),
                                mimeType = rs.getString("mime_type"),
                                title = rs.getString("title"),
                                tls = rs.getInt("tls") == 1,
                                modified = rs.getInt("modified") == 1,
                                tool = rs.getString("tool") ?: "proxy",
                                requestRaw = "",
                                responseRaw = "",
                                originalRequestRaw = "",
                                originalResponseRaw = "",
                                protocol = rs.getString("protocol") ?: "http/1.1",
                                streamId = rs.getObject("stream_id")?.let { rs.getInt("stream_id") },
                                wasDowngraded = rs.getInt("was_downgraded") == 1
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    @Synchronized
    fun loadHistoryById(id: Long): ProxyHistoryEntry? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, time_millis, method, host, path, status_code, length, mime_type, title,
                       tls, modified, tool, request_raw, response_raw, original_request_raw, original_response_raw,
                       protocol, stream_id, was_downgraded
                FROM proxy_history
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    return ProxyHistoryEntry(
                        id = rs.getLong("id"),
                        timeMillis = rs.getLong("time_millis"),
                        method = rs.getString("method"),
                        host = rs.getString("host"),
                        path = rs.getString("path"),
                        statusCode = rs.getInt("status_code"),
                        length = rs.getInt("length"),
                        mimeType = rs.getString("mime_type"),
                        title = rs.getString("title"),
                        tls = rs.getInt("tls") == 1,
                        modified = rs.getInt("modified") == 1,
                        tool = rs.getString("tool") ?: "proxy",
                        requestRaw = rs.getString("request_raw"),
                        responseRaw = rs.getString("response_raw"),
                        originalRequestRaw = rs.getString("original_request_raw") ?: "",
                        originalResponseRaw = rs.getString("original_response_raw") ?: "",
                        protocol = rs.getString("protocol") ?: "http/1.1",
                        streamId = rs.getObject("stream_id")?.let { rs.getInt("stream_id") },
                        wasDowngraded = rs.getInt("was_downgraded") == 1
                    )
                }
            }
        }
    }

    /**
     * 流式扫描全表历史详情,对每行回调 [action] 并传入过滤所需字段(id/mime/status/request_raw/response_raw)。
     *
     * 用于关键词过滤的后台全表扫描:ResultSet 按 SQLite 页逐行流式读取,**不把全量 raw body 物化进内存**(O(1) 峰值),
     * 替代有界缓存启用后 `applyFilters` 的 N+1 逐行 `loadHistoryById`(13 万行 -> EDT 冻结)。
     *
     * 不加 `@Synchronized` 且使用调用方传入的独立读连接 [conn]:WAL 模式允许并发读,扫描期间不持 store/DAO monitor,
     * 不阻塞 `persistExecutor` 的 `saveHistory` 写入(否则持久化队列会堆积,反而损及内存修复)。
     * [action] 返回 false 提前终止(代际取消);返回本扫描覆盖到的最大 id(供 RowFilter 对新到流量做 live 回退)。
     */
    fun scanHistoryDetails(
        conn: Connection,
        action: (id: Long, mimeType: String, statusCode: Int, requestRaw: String, responseRaw: String) -> Boolean
    ): Long {
        var maxId = 0L
        conn.prepareStatement(
            """
            SELECT id, mime_type, status_code, request_raw, response_raw
            FROM proxy_history
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
                        rs.getString("mime_type") ?: "other",
                        rs.getInt("status_code"),
                        rs.getString("request_raw") ?: "",
                        rs.getString("response_raw") ?: ""
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
    fun deleteHistoryByIds(ids: Set<Long>) {
        if (ids.isEmpty()) {
            return
        }
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM proxy_history WHERE id = ?").use { ps ->
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
}
