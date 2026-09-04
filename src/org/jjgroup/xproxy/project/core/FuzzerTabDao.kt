package org.jjgroup.xproxy.project.core

import java.sql.Connection

class FuzzerTabDao(private val connection: () -> Connection) {

    @Synchronized
    fun upsertFuzzerTab(record: FuzzerTabRecord) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO fuzzer_tabs(
                    tab_id, title, request_raw, response_text, target_host, target_port,
                    target_protocol, position_index, selected, group_name, group_color,
                    is_ws_mode, ws_opcode, ws_payload, updated_at_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(tab_id) DO UPDATE SET
                    title = excluded.title,
                    request_raw = excluded.request_raw,
                    response_text = excluded.response_text,
                    target_host = excluded.target_host,
                    target_port = excluded.target_port,
                    target_protocol = excluded.target_protocol,
                    position_index = excluded.position_index,
                    selected = excluded.selected,
                    group_name = excluded.group_name,
                    group_color = excluded.group_color,
                    is_ws_mode = excluded.is_ws_mode,
                    ws_opcode = excluded.ws_opcode,
                    ws_payload = excluded.ws_payload,
                    updated_at_millis = excluded.updated_at_millis
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, record.tabId)
                ps.setString(2, record.title)
                ps.setString(3, record.requestRaw)
                ps.setString(4, record.responseText)
                ps.setString(5, record.targetHost)
                ps.setInt(6, record.targetPort)
                ps.setString(7, record.targetProtocol)
                ps.setInt(8, record.positionIndex)
                ps.setInt(9, if (record.selected) 1 else 0)
                ps.setString(10, record.groupName)
                ps.setString(11, record.groupColor)
                ps.setInt(12, if (record.isWsMode) 1 else 0)
                ps.setInt(13, record.wsOpcode)
                ps.setString(14, record.wsPayload)
                ps.setLong(15, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun loadFuzzerTabs(): List<FuzzerTabRecord> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT tab_id, title, request_raw, response_text, target_host, target_port,
                       target_protocol, position_index, selected, group_name, group_color,
                       is_ws_mode, ws_opcode, ws_payload
                FROM fuzzer_tabs
                ORDER BY position_index ASC, updated_at_millis ASC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<FuzzerTabRecord>()
                    while (rs.next()) {
                        rows.add(
                            FuzzerTabRecord(
                                tabId = rs.getString("tab_id"),
                                title = rs.getString("title"),
                                requestRaw = rs.getString("request_raw"),
                                responseText = rs.getString("response_text"),
                                targetHost = rs.getString("target_host"),
                                targetPort = rs.getInt("target_port"),
                                targetProtocol = rs.getString("target_protocol"),
                                positionIndex = rs.getInt("position_index"),
                                selected = rs.getInt("selected") == 1,
                                groupName = rs.getString("group_name") ?: "",
                                groupColor = rs.getString("group_color") ?: "",
                                isWsMode = rs.getInt("is_ws_mode") == 1,
                                wsOpcode = rs.getInt("ws_opcode"),
                                wsPayload = rs.getString("ws_payload") ?: ""
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    @Synchronized
    fun replaceFuzzerTabHistory(tabId: String, history: List<FuzzerTabHistoryRecord>) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM fuzzer_tab_history WHERE tab_id = ?").use { deletePs ->
                    deletePs.setString(1, tabId)
                    deletePs.executeUpdate()
                }

                conn.prepareStatement(
                    """
                    INSERT INTO fuzzer_tab_history(
                        tab_id, seq, request_raw, response_text, full_url,
                        send_status, response_bytes, elapsed_millis, created_at_millis
                    )
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insertPs ->
                    history.forEachIndexed { index, row ->
                        insertPs.setString(1, tabId)
                        insertPs.setInt(2, index)
                        insertPs.setString(3, row.requestRaw)
                        insertPs.setString(4, row.responseText)
                        insertPs.setString(5, row.fullUrl)
                        insertPs.setString(6, row.statusText)
                        insertPs.setInt(7, row.responseBytes)
                        insertPs.setLong(8, row.elapsedMillis)
                        insertPs.setLong(9, System.currentTimeMillis())
                        insertPs.addBatch()
                    }
                    insertPs.executeBatch()
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
    fun loadFuzzerTabHistory(tabId: String): List<FuzzerTabHistoryRecord> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT request_raw, response_text, full_url, send_status, response_bytes, elapsed_millis
                FROM fuzzer_tab_history
                WHERE tab_id = ?
                ORDER BY seq ASC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, tabId)
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<FuzzerTabHistoryRecord>()
                    while (rs.next()) {
                        rows.add(
                            FuzzerTabHistoryRecord(
                                requestRaw = rs.getString("request_raw"),
                                responseText = rs.getString("response_text"),
                                fullUrl = rs.getString("full_url"),
                                statusText = rs.getString("send_status") ?: "Done",
                                responseBytes = rs.getInt("response_bytes"),
                                elapsedMillis = rs.getLong("elapsed_millis")
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    @Synchronized
    fun deleteFuzzerTab(tabId: String) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM fuzzer_tab_history WHERE tab_id = ?").use { ps ->
                    ps.setString(1, tabId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM fuzzer_tab_ws_frames WHERE tab_id = ?").use { ps ->
                    ps.setString(1, tabId)
                    ps.executeUpdate()
                }
                conn.prepareStatement("DELETE FROM fuzzer_tabs WHERE tab_id = ?").use { ps ->
                    ps.setString(1, tabId)
                    ps.executeUpdate()
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
    fun deleteFuzzerTabsNotIn(tabIds: Set<String>) {
        if (tabIds.isEmpty()) {
            connection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate("DELETE FROM fuzzer_tab_history")
                        stmt.executeUpdate("DELETE FROM fuzzer_tab_ws_frames")
                        stmt.executeUpdate("DELETE FROM fuzzer_tabs")
                    }
                    conn.commit()
                } catch (ex: Exception) {
                    conn.rollback()
                    throw ex
                } finally {
                    conn.autoCommit = true
                }
            }
            return
        }

        connection().use { conn ->
            conn.autoCommit = false
            try {
                val placeholders = List(tabIds.size) { "?" }.joinToString(",")
                val deleteHistorySql = "DELETE FROM fuzzer_tab_history WHERE tab_id NOT IN ($placeholders)"
                val deleteWsFramesSql = "DELETE FROM fuzzer_tab_ws_frames WHERE tab_id NOT IN ($placeholders)"
                val deleteTabsSql = "DELETE FROM fuzzer_tabs WHERE tab_id NOT IN ($placeholders)"

                conn.prepareStatement(deleteHistorySql).use { ps ->
                    tabIds.forEachIndexed { index, tabId -> ps.setString(index + 1, tabId) }
                    ps.executeUpdate()
                }
                conn.prepareStatement(deleteWsFramesSql).use { ps ->
                    tabIds.forEachIndexed { index, tabId -> ps.setString(index + 1, tabId) }
                    ps.executeUpdate()
                }
                conn.prepareStatement(deleteTabsSql).use { ps ->
                    tabIds.forEachIndexed { index, tabId -> ps.setString(index + 1, tabId) }
                    ps.executeUpdate()
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
    fun replaceFuzzerTabWsFrames(tabId: String, frames: List<FuzzerTabWsFrameRecord>) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement("DELETE FROM fuzzer_tab_ws_frames WHERE tab_id = ?").use { deletePs ->
                    deletePs.setString(1, tabId)
                    deletePs.executeUpdate()
                }
                conn.prepareStatement(
                    """
                    INSERT INTO fuzzer_tab_ws_frames(tab_id, seq, direction, opcode, payload)
                    VALUES(?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { insertPs ->
                    frames.forEachIndexed { index, row ->
                        insertPs.setString(1, tabId)
                        insertPs.setInt(2, index)
                        insertPs.setString(3, row.direction)
                        insertPs.setInt(4, row.opcode)
                        insertPs.setString(5, row.payload)
                        insertPs.addBatch()
                    }
                    insertPs.executeBatch()
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
    fun loadFuzzerTabWsFrames(tabId: String): List<FuzzerTabWsFrameRecord> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT direction, opcode, payload
                FROM fuzzer_tab_ws_frames
                WHERE tab_id = ?
                ORDER BY seq ASC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, tabId)
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<FuzzerTabWsFrameRecord>()
                    while (rs.next()) {
                        rows.add(
                            FuzzerTabWsFrameRecord(
                                direction = rs.getString("direction"),
                                opcode = rs.getInt("opcode"),
                                payload = rs.getString("payload") ?: ""
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }
}
