package org.jjgroup.xproxy.project.core

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

class ProjectRegistry {
    private companion object {
        const val SETTINGS_KEY_PROJECTS_ROOT = "projects_root"
    }

    // 复用单一长连接(与 ProjectDataStore 同模式),避免每次 connection() 都 DriverManager.getConnection
    // (含文件锁/WAL 初始化开销)。close() 置空操作使 `connection().use { }` 不关闭底层连接。
    // 公开方法 @Synchronized 保证共享连接跨线程串行(SQLite 单写)。
    private class ReusableConnection(delegate: Connection) : Connection by delegate {
        override fun close() {
            // 不关闭底层连接,交由 JVM 退出回收
        }
    }

    private val sharedConnection: Connection by lazy {
        ReusableConnection(DriverManager.getConnection("jdbc:sqlite:${ProjectPaths.globalDbPath}"))
    }

    init {
        Files.createDirectories(ProjectPaths.globalRoot)
        connection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode=WAL")
                stmt.execute("PRAGMA synchronous=NORMAL")
                stmt.execute("PRAGMA temp_store=MEMORY")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS projects (
                        id TEXT PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        base_name TEXT NOT NULL,
                        created_date TEXT NOT NULL,
                        project_dir TEXT NOT NULL,
                        db_path TEXT NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        last_opened_ms INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS project_settings (
                        setting_key TEXT PRIMARY KEY,
                        setting_value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
            conn.prepareStatement(
                """
                INSERT INTO project_settings(setting_key, setting_value)
                VALUES(?, ?)
                ON CONFLICT(setting_key) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, SETTINGS_KEY_PROJECTS_ROOT)
                ps.setString(2, ProjectPaths.defaultProjectsRoot.toString())
                ps.executeUpdate()
            }
        }
        Files.createDirectories(projectsRoot())
    }

    @Synchronized
    fun projectsRoot(): Path {
        connection().use { conn ->
            conn.prepareStatement("SELECT setting_value FROM project_settings WHERE setting_key = ? LIMIT 1").use { ps ->
                ps.setString(1, SETTINGS_KEY_PROJECTS_ROOT)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        return normalizeProjectsRoot(rs.getString("setting_value"))
                    }
                }
            }
        }
        return ProjectPaths.defaultProjectsRoot
    }

    @Synchronized
    fun updateProjectsRoot(newRoot: Path): Path {
        val normalizedRoot = newRoot.toAbsolutePath().normalize()
        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO project_settings(setting_key, setting_value)
                VALUES(?, ?)
                ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, SETTINGS_KEY_PROJECTS_ROOT)
                ps.setString(2, normalizedRoot.toString())
                ps.executeUpdate()
            }
        }
        Files.createDirectories(normalizedRoot)
        return normalizedRoot
    }

    @Synchronized
    fun listProjects(): List<ProjectRecord> {
        connection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, display_name, base_name, created_date, project_dir, db_path, created_at_ms, last_opened_ms
                FROM projects
                ORDER BY last_opened_ms DESC, created_at_ms DESC
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<ProjectRecord>()
                    while (rs.next()) {
                        result.add(
                            ProjectRecord(
                                id = rs.getString("id"),
                                displayName = rs.getString("display_name"),
                                baseName = rs.getString("base_name"),
                                createdDate = rs.getString("created_date"),
                                projectDir = rs.getString("project_dir"),
                                dbPath = rs.getString("db_path"),
                                createdAtMillis = rs.getLong("created_at_ms"),
                                lastOpenedMillis = rs.getLong("last_opened_ms")
                            )
                        )
                    }
                    return result
                }
            }
        }
    }

    @Synchronized
    fun createProject(baseNameInput: String, rootDir: Path = projectsRoot()): ProjectRecord {
        val baseName = baseNameInput.trim().ifEmpty { "project" }
        val dateTag = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val candidatePrefix = "${sanitize(baseName)}-$dateTag"
        val normalizedRoot = rootDir.toAbsolutePath().normalize()
        val finalDisplayName = uniqueDisplayName(candidatePrefix, normalizedRoot)
        val projectDir = normalizedRoot.resolve(finalDisplayName)
        Files.createDirectories(projectDir)

        val now = System.currentTimeMillis()
        val record = ProjectRecord(
            id = UUID.randomUUID().toString(),
            displayName = finalDisplayName,
            baseName = baseName,
            createdDate = dateTag,
            projectDir = projectDir.toString(),
            dbPath = projectDir.resolve("project.db").toString(),
            createdAtMillis = now,
            lastOpenedMillis = now
        )

        connection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO projects(id, display_name, base_name, created_date, project_dir, db_path, created_at_ms, last_opened_ms)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, record.id)
                ps.setString(2, record.displayName)
                ps.setString(3, record.baseName)
                ps.setString(4, record.createdDate)
                ps.setString(5, record.projectDir)
                ps.setString(6, record.dbPath)
                ps.setLong(7, record.createdAtMillis)
                ps.setLong(8, record.lastOpenedMillis)
                ps.executeUpdate()
            }
        }
        return record
    }

    @Synchronized
    fun markOpened(projectId: String) {
        connection().use { conn ->
            conn.prepareStatement("UPDATE projects SET last_opened_ms = ? WHERE id = ?").use { ps ->
                ps.setLong(1, System.currentTimeMillis())
                ps.setString(2, projectId)
                ps.executeUpdate()
            }
        }
    }

    @Synchronized
    fun deleteProject(record: ProjectRecord) {
        val projectPath = Path.of(record.projectDir)
        if (Files.exists(projectPath)) {
            Files.walk(projectPath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }

        connection().use { conn ->
            conn.prepareStatement("DELETE FROM projects WHERE id = ?").use { ps ->
                ps.setString(1, record.id)
                ps.executeUpdate()
            }
        }
    }

    private fun uniqueDisplayName(prefix: String, projectsRoot: Path): String {
        val existingNames = listProjects().mapTo(HashSet()) { it.displayName }
        if (!existingNames.contains(prefix) && !Files.exists(projectsRoot.resolve(prefix))) {
            return prefix
        }

        var i = 1
        while (true) {
            val suffix = i.toString().padStart(2, '0')
            val candidate = "$prefix-$suffix"
            if (!existingNames.contains(candidate) && !Files.exists(projectsRoot.resolve(candidate))) {
                return candidate
            }
            i += 1
        }
    }

    private fun sanitize(value: String): String {
        return value
            .replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]+"), "-")
            .trim('-')
            .ifEmpty { "project" }
    }

    private fun normalizeProjectsRoot(raw: String): Path {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ProjectPaths.defaultProjectsRoot
        }
        return Path.of(trimmed).toAbsolutePath().normalize()
    }

    private fun connection(): Connection {
        return sharedConnection
    }
}
