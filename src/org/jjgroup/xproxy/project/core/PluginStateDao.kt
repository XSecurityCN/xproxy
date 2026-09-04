package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.kits.model.IntruderAttackScriptState
import org.jjgroup.xproxy.kits.model.XappPluginState
import java.sql.Connection

class PluginStateDao(private val connection: () -> Connection) {

    @Synchronized
    fun upsertXappPluginState(state: XappPluginState) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO xapp_plugin_states(plugin_id, enabled, updated_at_millis)
                VALUES (?, ?, ?)
                ON CONFLICT(plugin_id) DO UPDATE SET
                    enabled = excluded.enabled,
                    updated_at_millis = excluded.updated_at_millis
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, state.pluginId)
                ps.setInt(2, if (state.enabled) 1 else 0)
                ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun loadXappPluginStates(): List<XappPluginState> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT plugin_id, enabled
                FROM xapp_plugin_states
                ORDER BY updated_at_millis ASC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<XappPluginState>()
                    while (rs.next()) {
                        rows.add(
                            XappPluginState(
                                pluginId = rs.getString("plugin_id"),
                                enabled = rs.getInt("enabled") == 1
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    @Synchronized
    fun upsertIntruderAttackScriptState(state: IntruderAttackScriptState) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO intruder_attack_script_states(script_key, enabled, category, updated_at_millis)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(script_key) DO UPDATE SET
                    enabled = excluded.enabled,
                    category = excluded.category,
                    updated_at_millis = excluded.updated_at_millis
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, state.scriptKey)
                ps.setInt(2, if (state.enabled) 1 else 0)
                ps.setString(3, state.category)
                ps.setLong(4, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun loadIntruderAttackScriptStates(): List<IntruderAttackScriptState> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT script_key, enabled, category
                FROM intruder_attack_script_states
                ORDER BY updated_at_millis ASC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = ArrayList<IntruderAttackScriptState>()
                    while (rs.next()) {
                        rows.add(
                            IntruderAttackScriptState(
                                scriptKey = rs.getString("script_key"),
                                enabled = rs.getInt("enabled") == 1,
                                category = rs.getString("category") ?: "General"
                            )
                        )
                    }
                    return rows
                }
            }
        }
    }

    @Synchronized
    fun deleteIntruderAttackScriptState(scriptKey: String) {
        connection().use { conn ->
            conn.prepareStatement(
                """
                DELETE FROM intruder_attack_script_states
                WHERE script_key = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, scriptKey)
                ps.executeUpdate()
            }
        }
    }
}
