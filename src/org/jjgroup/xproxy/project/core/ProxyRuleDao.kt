package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.proxy.model.ProxyInterceptRule
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRuleAction
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRuleMode
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceAction
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceMode
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceRule
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceScope
import java.sql.Connection

class ProxyRuleDao(private val connection: () -> Connection) {

    @Synchronized
    fun replaceProxyMatchReplaceRules(rules: List<ProxyMatchReplaceRule>) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM proxy_match_replace_rules")
                }
                conn.prepareStatement(
                    """
                    INSERT INTO proxy_match_replace_rules(
                        rule_id, enabled, name, scope, mode, action, match_text, replace_text, position_index, updated_at_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    rules.forEachIndexed { index, rule ->
                        ps.setString(1, rule.ruleId)
                        ps.setInt(2, if (rule.enabled) 1 else 0)
                        ps.setString(3, rule.name)
                        ps.setString(4, rule.scope.name)
                        ps.setString(5, rule.mode.name)
                        ps.setString(6, rule.action.name)
                        ps.setString(7, rule.matchText)
                        ps.setString(8, rule.replaceText)
                        ps.setInt(9, index)
                        ps.setLong(10, System.currentTimeMillis())
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
    fun loadProxyMatchReplaceRules(): List<ProxyMatchReplaceRule> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT rule_id, enabled, name, scope, mode, action, match_text, replace_text
                FROM proxy_match_replace_rules
                ORDER BY position_index ASC, updated_at_millis ASC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<ProxyMatchReplaceRule>()
                    while (rs.next()) {
                        val rawScope = rs.getString("scope") ?: ProxyMatchReplaceScope.REQUEST_BODY.name
                        val scope = runCatching {
                            if (rawScope == "REQUEST_HEADER_ADD") {
                                ProxyMatchReplaceScope.REQUEST_HEADER
                            } else {
                                ProxyMatchReplaceScope.valueOf(rawScope)
                            }
                        }
                            .getOrDefault(ProxyMatchReplaceScope.REQUEST_BODY)
                        val mode = runCatching { ProxyMatchReplaceMode.valueOf(rs.getString("mode")) }
                            .getOrDefault(ProxyMatchReplaceMode.TEXT)
                        val action = runCatching {
                            val rawAction = rs.getString("action")
                            if (!rawAction.isNullOrBlank()) {
                                ProxyMatchReplaceAction.valueOf(rawAction)
                            } else if (rawScope == "REQUEST_HEADER_ADD") {
                                ProxyMatchReplaceAction.ADD
                            } else {
                                ProxyMatchReplaceAction.REPLACE
                            }
                        }.getOrDefault(
                            if (rawScope == "REQUEST_HEADER_ADD") ProxyMatchReplaceAction.ADD else ProxyMatchReplaceAction.REPLACE
                        )
                        rows.add(
                            ProxyMatchReplaceRule(
                                ruleId = rs.getString("rule_id"),
                                enabled = rs.getInt("enabled") == 1,
                                name = rs.getString("name"),
                                scope = scope,
                                mode = mode,
                                action = action,
                                matchText = rs.getString("match_text") ?: "",
                                replaceText = rs.getString("replace_text") ?: ""
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    @Synchronized
    fun replaceProxyInterceptRules(rules: List<ProxyInterceptRule>) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM proxy_intercept_rules")
                }
                conn.prepareStatement(
                    """
                    INSERT INTO proxy_intercept_rules(
                        rule_id, enabled, name, mode, match_text, action,
                        match_request_header, match_request_body, match_response_header, match_response_body,
                        position_index, updated_at_millis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { ps ->
                    rules.forEachIndexed { index, rule ->
                        ps.setString(1, rule.ruleId)
                        ps.setInt(2, if (rule.enabled) 1 else 0)
                        ps.setString(3, rule.name)
                        ps.setString(4, rule.mode.name)
                        ps.setString(5, rule.matchText)
                        ps.setString(6, rule.action.name)
                        ps.setInt(7, if (rule.matchRequestHeader) 1 else 0)
                        ps.setInt(8, if (rule.matchRequestBody) 1 else 0)
                        ps.setInt(9, if (rule.matchResponseHeader) 1 else 0)
                        ps.setInt(10, if (rule.matchResponseBody) 1 else 0)
                        ps.setInt(11, index)
                        ps.setLong(12, System.currentTimeMillis())
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
    fun loadProxyInterceptRules(): List<ProxyInterceptRule> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT rule_id, enabled, name, mode, match_text, action,
                       match_request_header, match_request_body, match_response_header, match_response_body
                FROM proxy_intercept_rules
                ORDER BY position_index ASC, updated_at_millis ASC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<ProxyInterceptRule>()
                    while (rs.next()) {
                        val mode = runCatching { ProxyInterceptRuleMode.valueOf(rs.getString("mode")) }
                            .getOrDefault(ProxyInterceptRuleMode.TEXT)
                        val action = runCatching { ProxyInterceptRuleAction.valueOf(rs.getString("action")) }
                            .getOrDefault(ProxyInterceptRuleAction.FORWARD)
                        rows.add(
                            ProxyInterceptRule(
                                ruleId = rs.getString("rule_id"),
                                enabled = rs.getInt("enabled") == 1,
                                name = rs.getString("name"),
                                mode = mode,
                                matchText = rs.getString("match_text") ?: "",
                                action = action,
                                matchRequestHeader = rs.getInt("match_request_header") == 1,
                                matchRequestBody = rs.getInt("match_request_body") == 1,
                                matchResponseHeader = rs.getInt("match_response_header") == 1,
                                matchResponseBody = rs.getInt("match_response_body") == 1
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }
}
