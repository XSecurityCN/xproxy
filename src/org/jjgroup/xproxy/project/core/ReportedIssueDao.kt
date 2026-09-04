package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.issue.model.ReportedIssue
import java.sql.Connection

class ReportedIssueDao(private val connection: () -> Connection) {

    @Synchronized
    fun saveReportedIssue(issue: ReportedIssue) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO reported_issues(
                    issue_id, source, name, severity, confidence, detail,
                    remediation, url, host, path, method, request_raw,
                    response_raw, tags_csv, evidence, created_at_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, issue.issueId)
                ps.setString(2, issue.source)
                ps.setString(3, issue.name)
                ps.setString(4, issue.severity)
                ps.setString(5, issue.confidence)
                ps.setString(6, issue.detail)
                ps.setString(7, issue.remediation)
                ps.setString(8, issue.url)
                ps.setString(9, issue.host)
                ps.setString(10, issue.path)
                ps.setString(11, issue.method)
                ps.setString(12, issue.requestRaw)
                ps.setString(13, issue.responseRaw)
                ps.setString(14, issue.tagsCsv)
                ps.setString(15, issue.evidenceCsv)
                ps.setLong(16, issue.createdAtMillis)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun loadReportedIssues(): List<ReportedIssue> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT issue_id, source, name, severity, confidence, detail,
                       remediation, url, host, path, method, request_raw,
                       response_raw, tags_csv, evidence, created_at_millis
                FROM reported_issues
                ORDER BY created_at_millis ASC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<ReportedIssue>()
                    while (rs.next()) {
                        rows.add(
                            ReportedIssue(
                                issueId = rs.getString("issue_id"),
                                source = rs.getString("source"),
                                name = rs.getString("name"),
                                severity = rs.getString("severity"),
                                confidence = rs.getString("confidence"),
                                detail = rs.getString("detail"),
                                remediation = rs.getString("remediation"),
                                url = rs.getString("url"),
                                host = rs.getString("host"),
                                path = rs.getString("path"),
                                method = rs.getString("method"),
                                requestRaw = rs.getString("request_raw"),
                                responseRaw = rs.getString("response_raw"),
                                tagsCsv = rs.getString("tags_csv"),
                                evidenceCsv = rs.getString("evidence") ?: "",
                                createdAtMillis = rs.getLong("created_at_millis")
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    /**
     * 仅加载 issues 的展示元数据(**不**含 request_raw / response_raw),供启动时批量载入列表。
     *
     * 历史项目可能积累数万条 issue(实测 5.8 万条),其 request_raw+response_raw 合计可达数 GB
     * (实测单列 response_raw 即 2.3GB,占整个 issue 表近全部体积)。列表/树展示只需元数据
     * (name/severity/host/path/method/detail/remediation/url/tags 等,合计仅数十 MB),
     * raw 仅在选中某条 issue 查看请求/响应时需要。故启动只载元数据,raw 按需由
     * [loadReportedIssueRaw] 懒加载,与 proxy_history 的 loadHistoryMetadata/loadHistoryById 同构。
     * 实测可将启动期 issue 内存占用从 ~2.5GB 降至 ~数十 MB。
     */
    @Synchronized
    fun loadReportedIssueMetadata(): List<ReportedIssue> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT issue_id, source, name, severity, confidence, detail,
                       remediation, url, host, path, method, tags_csv, evidence, created_at_millis
                FROM reported_issues
                ORDER BY created_at_millis ASC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<ReportedIssue>()
                    while (rs.next()) {
                        rows.add(
                            ReportedIssue(
                                issueId = rs.getString("issue_id"),
                                source = rs.getString("source"),
                                name = rs.getString("name"),
                                severity = rs.getString("severity"),
                                confidence = rs.getString("confidence"),
                                detail = rs.getString("detail"),
                                remediation = rs.getString("remediation"),
                                url = rs.getString("url"),
                                host = rs.getString("host"),
                                path = rs.getString("path"),
                                method = rs.getString("method"),
                                requestRaw = "",
                                responseRaw = "",
                                tagsCsv = rs.getString("tags_csv"),
                                evidenceCsv = rs.getString("evidence") ?: "",
                                createdAtMillis = rs.getLong("created_at_millis")
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    /**
     * 按 issue_id 懒加载单条 issue 的 request_raw / response_raw(选中查看时调用)。
     * issue_id 为主键,单行快查;返回 (requestRaw, responseRaw),不存在时返回 null。
     * 调用方应配 LRU 缓存(TargetPanel.issueRawCache)避免重复查库。
     */
    @Synchronized
    fun loadReportedIssueRaw(issueId: String): Pair<String, String>? {
        if (issueId.isBlank()) return null
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT request_raw, response_raw
                FROM reported_issues
                WHERE issue_id = ?
                LIMIT 1
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, issueId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return (rs.getString("request_raw") ?: "") to (rs.getString("response_raw") ?: "")
                }
            }
        }
    }

    @Synchronized
    fun deleteReportedIssue(issueId: String) {
        if (issueId.isBlank()) {
            return
        }
        connection().use { conn ->
            conn.prepareStatement(
                """
                DELETE FROM reported_issues
                WHERE issue_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, issueId)
                ps.executeUpdate()
            }
        }
    }
}
