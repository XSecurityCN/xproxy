package org.jjgroup.xproxy.project.core

import org.jjgroup.xproxy.Request
import org.jjgroup.xproxy.issue.model.ReportedIssue
import org.jjgroup.xproxy.kits.model.IntruderAttackScriptState
import org.jjgroup.xproxy.kits.model.XappPluginState
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRule
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceRule
import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry
import org.jjgroup.xproxy.proxy.model.WsSession
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

data class FuzzerTabRecord(
    val tabId: String,
    val title: String,
    val requestRaw: String,
    val responseText: String,
    val targetHost: String,
    val targetPort: Int,
    val targetProtocol: String,
    val positionIndex: Int,
    val selected: Boolean,
    val groupName: String = "",
    val groupColor: String = "",
    // WS 重放 tab 持久化:isWsMode 标记该 tab 为 WS 模式;wsOpcode/wsPayload 为重放器当前帧类型与载荷。
    val isWsMode: Boolean = false,
    val wsOpcode: Int = 1,
    val wsPayload: String = ""
)

data class FuzzerTabHistoryRecord(
    val requestRaw: String,
    val responseText: String,
    val fullUrl: String,
    val statusText: String = "Done",
    val responseBytes: Int = 0,
    val elapsedMillis: Long = 0L
)

/**
 * WS 重放 tab 的一帧交换记录(出站 C->S 或入站 S->C),持久化到 fuzzer_tab_ws_frames。
 * payload 对 Text/Continuation 帧为 UTF-8 文本,对二进制/控制帧为十六进制(与重放器展示一致)。
 */
data class FuzzerTabWsFrameRecord(
    val direction: String,
    val opcode: Int,
    val payload: String
)

class ProjectDataStore(private val project: ProjectRecord) {

    private val proxyHistoryDao = ProxyHistoryDao(::connection)
    private val wsHistoryDao = WsHistoryDao(::connection)
    private val wsSessionDao = WsSessionDao(::connection)
    private val proxyRuleDao = ProxyRuleDao(::connection)
    private val fuzzerResultDao = FuzzerResultDao(::connection)
    private val reportedIssueDao = ReportedIssueDao(::connection)
    private val pluginStateDao = PluginStateDao(::connection)
    private val fuzzerTabDao = FuzzerTabDao(::connection)
    private val trafficHighlightDao = TrafficHighlightDao(::connection)

    /**
     * 复用单一长连接,避免每次 DAO 调用都 DriverManager.getConnection(含文件锁/WAL 初始化开销)。
     * 所有 DAO 方法均 @Synchronized,跨线程访问已串行,单连接在 SQLite 下安全。
     * close() 置为空操作,使 DAO 中 `connection().use { ... }` 不会真正关闭底层连接,从而可被下次调用复用。
     * 注意:必须声明在 init 之前,否则 init 中调用 connection() 时 lazy 委托尚未初始化会 NPE;
     * 实际连接在首次访问时惰性创建(晚于 init 内的 createDirectories,保证父目录已存在)。
     */
    private class ReusableConnection(delegate: Connection) : Connection by delegate {
        override fun close() {
            // 不关闭底层连接,交由 JVM 退出或 shutdown 时回收
        }
    }

    private val sharedConnection: Connection by lazy {
        ReusableConnection(DriverManager.getConnection("jdbc:sqlite:${project.dbPath}"))
    }

    init {
        val dbParent = Path.of(project.dbPath).parent
        if (dbParent != null) {
            Files.createDirectories(dbParent)
        }
        connection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                // WAL + synchronous=NORMAL: 事务仍 ACID,仅 OS 崩溃时可能丢最后一个未 checkpoint 的 WAL 帧。
                // 默认 FULL 会对每次 commit 执行 fsync,是写入吞吐的主要瓶颈。
                stmt.execute("PRAGMA synchronous=NORMAL")
                stmt.execute("PRAGMA temp_store=MEMORY")
                stmt.execute("PRAGMA wal_autocheckpoint=1000")
                stmt.execute("PRAGMA mmap_size=268435456")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS proxy_history (
                        id INTEGER PRIMARY KEY,
                        time_millis INTEGER NOT NULL,
                        method TEXT NOT NULL,
                        host TEXT NOT NULL,
                        path TEXT NOT NULL,
                        status_code INTEGER NOT NULL,
                        length INTEGER NOT NULL,
                        mime_type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        tls INTEGER NOT NULL,
                        modified INTEGER NOT NULL,
                        tool TEXT NOT NULL DEFAULT 'proxy',
                        request_raw TEXT NOT NULL,
                        response_raw TEXT NOT NULL,
                        original_request_raw TEXT NOT NULL DEFAULT '',
                        original_response_raw TEXT NOT NULL DEFAULT '',
                        protocol TEXT NOT NULL DEFAULT 'http/1.1',
                        stream_id INTEGER,
                        was_downgraded INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS traffic_highlights (
                        kind TEXT NOT NULL,
                        entry_id INTEGER NOT NULL,
                        color TEXT NOT NULL,
                        PRIMARY KEY(kind, entry_id)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS fuzzer_results (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        base_request_hash TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        request_raw TEXT NOT NULL,
                        response_raw TEXT,
                        time_millis INTEGER NOT NULL,
                        arrival_millis INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        queue_id INTEGER NOT NULL,
                        connection_id INTEGER NOT NULL,
                        anomaly_rank INTEGER,
                        created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_fuzzer_results_hash ON fuzzer_results(base_request_hash)")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS reported_issues (
                        issue_id TEXT PRIMARY KEY,
                        source TEXT NOT NULL,
                        name TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        confidence TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        remediation TEXT NOT NULL,
                        url TEXT NOT NULL,
                        host TEXT NOT NULL,
                        path TEXT NOT NULL,
                        method TEXT NOT NULL,
                        request_raw TEXT NOT NULL,
                        response_raw TEXT NOT NULL,
                        tags_csv TEXT NOT NULL,
                        evidence TEXT NOT NULL DEFAULT '',
                        created_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_reported_issues_created_at ON reported_issues(created_at_millis)")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS xapp_plugin_states (
                        plugin_id TEXT PRIMARY KEY,
                        enabled INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS intruder_attack_script_states (
                        script_key TEXT PRIMARY KEY,
                        enabled INTEGER NOT NULL,
                        category TEXT NOT NULL,
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS fuzzer_tabs (
                        tab_id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        request_raw TEXT NOT NULL,
                        response_text TEXT NOT NULL,
                        target_host TEXT NOT NULL,
                        target_port INTEGER NOT NULL,
                        target_protocol TEXT NOT NULL,
                        position_index INTEGER NOT NULL,
                        selected INTEGER NOT NULL,
                        group_name TEXT NOT NULL DEFAULT '',
                        group_color TEXT NOT NULL DEFAULT '',
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS fuzzer_tab_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tab_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        request_raw TEXT NOT NULL,
                        response_text TEXT NOT NULL,
                        full_url TEXT NOT NULL,
                        send_status TEXT NOT NULL DEFAULT 'Done',
                        response_bytes INTEGER NOT NULL DEFAULT 0,
                        elapsed_millis INTEGER NOT NULL DEFAULT 0,
                        created_at_millis INTEGER NOT NULL,
                        UNIQUE(tab_id, seq)
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS fuzzer_tab_ws_frames (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tab_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        direction TEXT NOT NULL,
                        opcode INTEGER NOT NULL,
                        payload TEXT NOT NULL,
                        UNIQUE(tab_id, seq)
                    )
                    """.trimIndent()
                )
                ensureColumn(conn, "fuzzer_tabs", "group_name", "TEXT NOT NULL DEFAULT ''")
                ensureColumn(conn, "fuzzer_tabs", "group_color", "TEXT NOT NULL DEFAULT ''")
                // WS 重放 tab 持久化:模式标记 + 帧类型 + 载荷。
                ensureColumn(conn, "fuzzer_tabs", "is_ws_mode", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(conn, "fuzzer_tabs", "ws_opcode", "INTEGER NOT NULL DEFAULT 1")
                ensureColumn(conn, "fuzzer_tabs", "ws_payload", "TEXT NOT NULL DEFAULT ''")
                // issue 证据高亮片段(多个 \n 分隔);老库无此列时补建。
                ensureColumn(conn, "reported_issues", "evidence", "TEXT NOT NULL DEFAULT ''")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_fuzzer_tabs_position ON fuzzer_tabs(position_index)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_fuzzer_tab_history_tab ON fuzzer_tab_history(tab_id)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_fuzzer_tab_ws_frames_tab ON fuzzer_tab_ws_frames(tab_id)")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ws_history (
                        id INTEGER PRIMARY KEY,
                        time_millis INTEGER NOT NULL,
                        host TEXT NOT NULL,
                        path TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        message_type TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        length INTEGER NOT NULL,
                        preview TEXT NOT NULL,
                        payload TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS ws_session (
                        id INTEGER PRIMARY KEY,
                        time_millis INTEGER NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        tls INTEGER NOT NULL,
                        path TEXT NOT NULL,
                        handshake_request TEXT NOT NULL,
                        handshake_response TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS proxy_match_replace_rules (
                        rule_id TEXT PRIMARY KEY,
                        enabled INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        action TEXT NOT NULL DEFAULT 'REPLACE',
                        match_text TEXT NOT NULL,
                        replace_text TEXT NOT NULL,
                        position_index INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS proxy_intercept_rules (
                        rule_id TEXT PRIMARY KEY,
                        enabled INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        match_text TEXT NOT NULL,
                        action TEXT NOT NULL,
                        match_request_header INTEGER NOT NULL,
                        match_request_body INTEGER NOT NULL,
                        match_response_header INTEGER NOT NULL,
                        match_response_body INTEGER NOT NULL,
                        position_index INTEGER NOT NULL,
                        updated_at_millis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                try {
                    stmt.execute("ALTER TABLE proxy_match_replace_rules ADD COLUMN action TEXT NOT NULL DEFAULT 'REPLACE'")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE ws_history ADD COLUMN mime_type TEXT NOT NULL DEFAULT 'other'")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE ws_history ADD COLUMN session_id INTEGER")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE proxy_history ADD COLUMN original_request_raw TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE proxy_history ADD COLUMN original_response_raw TEXT NOT NULL DEFAULT ''")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE proxy_history ADD COLUMN tool TEXT NOT NULL DEFAULT 'proxy'")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE proxy_history ADD COLUMN protocol TEXT NOT NULL DEFAULT 'http/1.1'")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE proxy_history ADD COLUMN stream_id INTEGER")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE proxy_history ADD COLUMN was_downgraded INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE fuzzer_tab_history ADD COLUMN send_status TEXT NOT NULL DEFAULT 'Done'")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE fuzzer_tab_history ADD COLUMN response_bytes INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {
                }
                try {
                    stmt.execute("ALTER TABLE fuzzer_tab_history ADD COLUMN elapsed_millis INTEGER NOT NULL DEFAULT 0")
                } catch (_: Exception) {
                }
            }
        }
    }

    // --- ProxyHistory delegation ---

    @Synchronized
    fun saveHistory(entry: ProxyHistoryEntry) = proxyHistoryDao.saveHistory(entry)

    @Synchronized
    fun loadHistoryMetadata(): List<ProxyHistoryEntry> = proxyHistoryDao.loadHistoryMetadata()

    @Synchronized
    fun loadHistoryById(id: Long): ProxyHistoryEntry? = proxyHistoryDao.loadHistoryById(id)

    /**
     * 后台关键词过滤的全表流式扫描(不经 store/DAO 的 @Synchronized,不持共享连接 monitor)。
     * 开独立读连接:WAL 允许并发读,扫描期间 `persistExecutor` 的 saveHistory 写入不受阻塞。
     */
    fun scanHistoryDetails(action: (id: Long, mimeType: String, statusCode: Int, requestRaw: String, responseRaw: String) -> Boolean): Long {
        DriverManager.getConnection("jdbc:sqlite:${project.dbPath}").use { conn ->
            return proxyHistoryDao.scanHistoryDetails(conn, action)
        }
    }

    @Synchronized
    fun deleteHistoryByIds(ids: Set<Long>) = proxyHistoryDao.deleteHistoryByIds(ids)

    // --- WsHistory delegation ---

    @Synchronized
    fun saveWsHistory(entry: ProxyWsHistoryEntry) = wsHistoryDao.saveWsHistory(entry)

    @Synchronized
    fun loadWsHistoryMetadata(): List<ProxyWsHistoryEntry> = wsHistoryDao.loadWsHistoryMetadata()

    @Synchronized
    fun loadWsPayloadById(id: Long): String? = wsHistoryDao.loadWsPayloadById(id)

    /** 后台关键词过滤的 WS 全表流式扫描,语义同 [scanHistoryDetails](独立读连接,不阻塞写入)。 */
    fun scanWsDetails(
        action: (id: Long, host: String, path: String, direction: String, messageType: String, mimeType: String, preview: String, payload: String) -> Boolean
    ): Long {
        DriverManager.getConnection("jdbc:sqlite:${project.dbPath}").use { conn ->
            return wsHistoryDao.scanWsDetails(conn, action)
        }
    }

    @Synchronized
    fun deleteWsHistoryByIds(ids: Set<Long>) {
        // 先取出被删 ws_history 行对应的 session_id 集合,再级联删除无引用的 ws_session,
        // 避免会话表无限增长(握手请求文本可能较大)。
        val sessionIds = ids.mapNotNull { id -> wsHistoryDao.findSessionIdByHistoryId(id) }.toSet()
        wsHistoryDao.deleteWsHistoryByIds(ids)
        val remaining = sessionIds.filterNot { sid -> wsHistoryDao.sessionHasReferences(sid) }.toSet()
        if (remaining.isNotEmpty()) {
            wsSessionDao.deleteWsSessionByIds(remaining)
        }
    }

    // --- WsSession delegation ---

    @Synchronized
    fun saveWsSession(session: WsSession) = wsSessionDao.saveWsSession(session)

    @Synchronized
    fun updateWsSessionHandshakeResponse(id: Long, handshakeResponse: String) =
        wsSessionDao.updateHandshakeResponse(id, handshakeResponse)

    @Synchronized
    fun loadWsSession(id: Long): WsSession? = wsSessionDao.loadWsSession(id)

    @Synchronized
    fun loadMaxWsSessionId(): Long = wsSessionDao.loadMaxWsSessionId()

    // --- ProxyRule delegation ---

    @Synchronized
    fun replaceProxyMatchReplaceRules(rules: List<ProxyMatchReplaceRule>) =
        proxyRuleDao.replaceProxyMatchReplaceRules(rules)

    @Synchronized
    fun loadProxyMatchReplaceRules(): List<ProxyMatchReplaceRule> =
        proxyRuleDao.loadProxyMatchReplaceRules()

    @Synchronized
    fun replaceProxyInterceptRules(rules: List<ProxyInterceptRule>) =
        proxyRuleDao.replaceProxyInterceptRules(rules)

    @Synchronized
    fun loadProxyInterceptRules(): List<ProxyInterceptRule> =
        proxyRuleDao.loadProxyInterceptRules()

    // --- FuzzerResult delegation ---

    @Synchronized
    fun saveFuzzerResult(baseRequestHash: String, request: Request) =
        fuzzerResultDao.saveFuzzerResult(baseRequestHash, request)

    @Synchronized
    fun loadFuzzerResults(baseRequestHash: String): List<Request> =
        fuzzerResultDao.loadFuzzerResults(baseRequestHash)

    @Synchronized
    fun clearFuzzerResults(baseRequestHash: String) =
        fuzzerResultDao.clearFuzzerResults(baseRequestHash)

    // --- ReportedIssue delegation ---

    @Synchronized
    fun saveReportedIssue(issue: ReportedIssue) = reportedIssueDao.saveReportedIssue(issue)

    @Synchronized
    fun loadReportedIssues(): List<ReportedIssue> = reportedIssueDao.loadReportedIssues()

    /** 启动期批量载入 issue 元数据(不含 raw),见 [ReportedIssueDao.loadReportedIssueMetadata]。 */
    @Synchronized
    fun loadReportedIssueMetadata(): List<ReportedIssue> = reportedIssueDao.loadReportedIssueMetadata()

    /** 按 issueId 懒加载单条 issue 的 (requestRaw, responseRaw),见 [ReportedIssueDao.loadReportedIssueRaw]。 */
    @Synchronized
    fun loadReportedIssueRaw(issueId: String): Pair<String, String>? = reportedIssueDao.loadReportedIssueRaw(issueId)

    @Synchronized
    fun deleteReportedIssue(issueId: String) = reportedIssueDao.deleteReportedIssue(issueId)

    // --- PluginState delegation ---

    @Synchronized
    fun upsertXappPluginState(state: XappPluginState) =
        pluginStateDao.upsertXappPluginState(state)

    @Synchronized
    fun loadXappPluginStates(): List<XappPluginState> =
        pluginStateDao.loadXappPluginStates()

    @Synchronized
    fun upsertIntruderAttackScriptState(state: IntruderAttackScriptState) =
        pluginStateDao.upsertIntruderAttackScriptState(state)

    @Synchronized
    fun loadIntruderAttackScriptStates(): List<IntruderAttackScriptState> =
        pluginStateDao.loadIntruderAttackScriptStates()

    @Synchronized
    fun deleteIntruderAttackScriptState(scriptKey: String) =
        pluginStateDao.deleteIntruderAttackScriptState(scriptKey)

    // --- FuzzerTab delegation ---

    @Synchronized
    fun upsertFuzzerTab(record: FuzzerTabRecord) = fuzzerTabDao.upsertFuzzerTab(record)

    @Synchronized
    fun loadFuzzerTabs(): List<FuzzerTabRecord> = fuzzerTabDao.loadFuzzerTabs()

    @Synchronized
    fun replaceFuzzerTabHistory(tabId: String, history: List<FuzzerTabHistoryRecord>) =
        fuzzerTabDao.replaceFuzzerTabHistory(tabId, history)

    @Synchronized
    fun loadFuzzerTabHistory(tabId: String): List<FuzzerTabHistoryRecord> =
        fuzzerTabDao.loadFuzzerTabHistory(tabId)

    @Synchronized
    fun deleteFuzzerTab(tabId: String) = fuzzerTabDao.deleteFuzzerTab(tabId)

    @Synchronized
    fun deleteFuzzerTabsNotIn(tabIds: Set<String>) = fuzzerTabDao.deleteFuzzerTabsNotIn(tabIds)

    @Synchronized
    fun replaceFuzzerTabWsFrames(tabId: String, frames: List<FuzzerTabWsFrameRecord>) =
        fuzzerTabDao.replaceFuzzerTabWsFrames(tabId, frames)

    @Synchronized
    fun loadFuzzerTabWsFrames(tabId: String): List<FuzzerTabWsFrameRecord> =
        fuzzerTabDao.loadFuzzerTabWsFrames(tabId)

    // --- TrafficHighlight delegation ---
    // kind 取 "http"/"ws"。"http" 同时覆盖代理历史表与 Target 内容表(同一 entry id)。

    @Synchronized
    fun upsertHighlight(kind: String, entryId: Long, color: String) =
        trafficHighlightDao.upsertHighlight(kind, entryId, color)

    @Synchronized
    fun deleteHighlight(kind: String, entryId: Long) =
        trafficHighlightDao.deleteHighlight(kind, entryId)

    @Synchronized
    fun deleteHighlightsByIds(kind: String, ids: Set<Long>) =
        trafficHighlightDao.deleteHighlightsByIds(kind, ids)

    @Synchronized
    fun loadAllHighlights(kind: String): Map<Long, String> =
        trafficHighlightDao.loadAllHighlights(kind)

    private fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name").equals(column, ignoreCase = true)) {
                        return
                    }
                }
            }
            stmt.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    private fun connection(): Connection {
        return sharedConnection
    }
}
