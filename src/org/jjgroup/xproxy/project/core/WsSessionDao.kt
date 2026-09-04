package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.proxy.model.WsSession
import java.sql.Connection

class WsSessionDao(private val connection: () -> Connection) {

    @Synchronized
    fun saveWsSession(session: WsSession) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO ws_session(
                    id, time_millis, host, port, tls, path, handshake_request, handshake_response
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, session.id)
                ps.setLong(2, session.timeMillis)
                ps.setString(3, session.host)
                ps.setInt(4, session.port)
                ps.setInt(5, if (session.tls) 1 else 0)
                ps.setString(6, session.path)
                ps.setString(7, session.handshakeRequest)
                ps.setString(8, session.handshakeResponse)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun updateHandshakeResponse(id: Long, handshakeResponse: String) {
        connection().use { conn ->
            conn.prepareStatement(
                "UPDATE ws_session SET handshake_response = ? WHERE id = ?"
            ).use { ps ->
                ps.setString(1, handshakeResponse)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun loadWsSession(id: Long): WsSession? {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, time_millis, host, port, tls, path, handshake_request, handshake_response
                FROM ws_session
                WHERE id = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        return null
                    }
                    return WsSession(
                        id = rs.getLong("id"),
                        timeMillis = rs.getLong("time_millis"),
                        host = rs.getString("host"),
                        port = rs.getInt("port"),
                        tls = rs.getInt("tls") != 0,
                        path = rs.getString("path"),
                        handshakeRequest = rs.getString("handshake_request") ?: "",
                        handshakeResponse = rs.getString("handshake_response") ?: ""
                    )
                }
            }
        }
    }

    @Synchronized
    fun loadMaxWsSessionId(): Long {
        connection().use { conn ->
            conn.prepareStatement("SELECT COALESCE(MAX(id), 0) AS max_id FROM ws_session").use { ps ->
                ps.executeQuery().use { rs -> return if (rs.next()) rs.getLong("max_id") else 0L }
            }
        }
    }

    @Synchronized
    fun deleteWsSessionByIds(ids: Set<Long>) {
        if (ids.isEmpty()) {
            return
        }
        connection().use { conn ->
            conn.prepareStatement(
                "DELETE FROM ws_session WHERE id = ?"
            ).use { ps ->
                ids.forEach {
                    ps.setLong(1, it)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }
}
