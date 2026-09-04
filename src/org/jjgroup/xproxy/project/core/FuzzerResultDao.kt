package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.Request
import java.sql.Connection

class FuzzerResultDao(private val connection: () -> Connection) {

    @Synchronized
    fun saveFuzzerResult(baseRequestHash: String, request: Request) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO fuzzer_results(
                    base_request_hash, payload, request_raw, response_raw,
                    time_millis, arrival_millis, label, queue_id,
                    connection_id, anomaly_rank, created_at_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, baseRequestHash)
                ps.setString(2, request.words.joinToString("/") { it ?: "" })
                ps.setString(3, request.getRequest())
                ps.setString(4, request.response)
                ps.setLong(5, request.time)
                ps.setLong(6, request.arrival)
                ps.setString(7, request.label)
                ps.setInt(8, request.id)
                ps.setInt(9, request.connectionID)
                if (request.anomalyRank == null) {
                    ps.setNull(10, java.sql.Types.INTEGER)
                } else {
                    ps.setInt(10, request.anomalyRank!!)
                }
                ps.setLong(11, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun loadFuzzerResults(baseRequestHash: String): List<Request> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT payload, request_raw, response_raw, time_millis, arrival_millis,
                       label, queue_id, connection_id, anomaly_rank
                FROM fuzzer_results
                WHERE base_request_hash = ?
                ORDER BY id ASC
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, baseRequestHash)
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<Request>()
                    while (rs.next()) {
                        val payload = rs.getString("payload") ?: ""
                        val words: List<String?> = if (payload.isBlank()) emptyList() else payload.split("/")
                        val request = Request(
                            template = rs.getString("request_raw") ?: "",
                            words = words,
                            learnBoring = 0,
                            label = rs.getString("label") ?: ""
                        )
                        request.autoFixContentLength = false
                        request.response = rs.getString("response_raw")
                        request.time = rs.getLong("time_millis")
                        request.arrival = rs.getLong("arrival_millis")
                        request.id = rs.getInt("queue_id")
                        request.connectionID = rs.getInt("connection_id")
                        val anomalyRank = rs.getInt("anomaly_rank")
                        request.anomalyRank = if (rs.wasNull()) null else anomalyRank
                        rows.add(request)
                    }
                    return rows
                }
            }
        }
    }

    @Synchronized
    fun clearFuzzerResults(baseRequestHash: String) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                DELETE FROM fuzzer_results
                WHERE base_request_hash = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, baseRequestHash)
                ps.executeUpdate()
            }
        }
    }
}
