package org.jjgroup.xproxy.kits.core

import org.jjgroup.xproxy.kits.model.IntruderAttackScript
import org.jjgroup.xproxy.kits.model.IntruderAttackScriptState
import org.jjgroup.xproxy.project.core.ProjectDataStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class IntruderScriptManager(
    private val projectDataStore: ProjectDataStore?
) {
    private val intruderRoot: Path = IntruderScriptSync.persistentDir()

    fun scriptDirectory(): Path = intruderRoot

    fun loadScripts(): List<IntruderAttackScript> {
        IntruderScriptSync.syncBundledToPersistent()
        Files.createDirectories(intruderRoot)
        val stateByKey = projectDataStore?.loadIntruderAttackScriptStates().orEmpty().associateBy { it.scriptKey }

        val scripts = Files.walk(intruderRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".py", ignoreCase = true) }
                .filter { path ->
                    val relativePath = intruderRoot.relativize(path)
                    relativePath.none { part ->
                        val name = part.toString()
                        name.startsWith(".") || name == "__pycache__"
                    }
                }
                .map { path ->
                    val relative = intruderRoot.relativize(path).toString().replace(File.separatorChar, '/')
                    val key = relative.lowercase(Locale.getDefault())
                    val state = stateByKey[key]
                    val category = state?.category?.ifBlank { deriveCategory(relative) } ?: deriveCategory(relative)
                    val enabled = state?.enabled ?: true
                    if (state == null) {
                        projectDataStore?.upsertIntruderAttackScriptState(
                            IntruderAttackScriptState(scriptKey = key, enabled = enabled, category = category)
                        )
                    }
                    IntruderAttackScript(
                        key = key,
                        name = path.fileName.toString(),
                        relativePath = relative,
                        category = category,
                        enabled = enabled,
                        scriptPath = path,
                        status = "Ready"
                    )
                }
                .sorted(
                    compareBy<IntruderAttackScript> { it.category.lowercase(Locale.getDefault()) }
                        .thenBy { it.relativePath.lowercase(Locale.getDefault()) }
                )
                .toList()
        }
        return scripts
    }

    fun updateEnabled(scriptKey: String, enabled: Boolean) {
        val current = projectDataStore?.loadIntruderAttackScriptStates().orEmpty().firstOrNull { it.scriptKey == scriptKey }
        val category = current?.category?.ifBlank { "General" } ?: "General"
        projectDataStore?.upsertIntruderAttackScriptState(
            IntruderAttackScriptState(scriptKey = scriptKey, enabled = enabled, category = category)
        )
    }

    fun updateCategory(scriptKey: String, category: String) {
        val normalized = category.trim().ifBlank { "General" }
        val current = projectDataStore?.loadIntruderAttackScriptStates().orEmpty().firstOrNull { it.scriptKey == scriptKey }
        val enabled = current?.enabled ?: true
        projectDataStore?.upsertIntruderAttackScriptState(
            IntruderAttackScriptState(scriptKey = scriptKey, enabled = enabled, category = normalized)
        )
    }

    fun saveScript(scriptPath: Path, content: String) {
        if (!scriptPath.startsWith(intruderRoot)) {
            return
        }
        Files.createDirectories(scriptPath.parent)
        Files.writeString(scriptPath, content, Charsets.UTF_8)
    }

    fun createScript(category: String, name: String): Path {
        val normalizedName = sanitizeName(name)
        val categoryName = sanitizeName(category).ifBlank { "General" }
        val targetDir = intruderRoot.resolve(categoryName)
        Files.createDirectories(targetDir)
        val candidate = generateSequence(0) { it + 1 }
            .map { suffix -> if (suffix == 0) targetDir.resolve("$normalizedName.py") else targetDir.resolve("${normalizedName}_$suffix.py") }
            .first { !Files.exists(it) }
        Files.writeString(
            candidate,
            defaultTemplate(candidate.fileName.toString()),
            Charsets.UTF_8
        )
        val relative = intruderRoot.relativize(candidate).toString().replace(File.separatorChar, '/')
        val key = relative.lowercase(Locale.getDefault())
        projectDataStore?.upsertIntruderAttackScriptState(
            IntruderAttackScriptState(scriptKey = key, enabled = true, category = categoryName)
        )
        return candidate
    }

    fun deleteScript(scriptPath: Path) {
        if (!scriptPath.startsWith(intruderRoot) || !Files.exists(scriptPath)) {
            return
        }
        val relative = intruderRoot.relativize(scriptPath).toString().replace(File.separatorChar, '/')
        val key = relative.lowercase(Locale.getDefault())
        runCatching { Files.deleteIfExists(scriptPath) }
        cleanupEmptyParents(scriptPath.parent)
        projectDataStore?.deleteIntruderAttackScriptState(key)
    }

    private fun cleanupEmptyParents(start: Path?) {
        var current = start
        while (current != null && current.startsWith(intruderRoot) && current != intruderRoot) {
            val hasChildren = Files.list(current).use { it.findAny().isPresent }
            if (hasChildren) {
                return
            }
            runCatching { Files.deleteIfExists(current) }
            current = current.parent
        }
    }

    private fun deriveCategory(relativePath: String): String {
        val first = relativePath.substringBefore('/')
        if (first.equals(relativePath, ignoreCase = true)) {
            return "General"
        }
        return first.ifBlank { "General" }
    }

    private fun sanitizeName(name: String): String {
        return name.trim()
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .trim('_', '.')
            .ifBlank { "script" }
    }

    private fun defaultTemplate(fileName: String): String {
        return """
            # $fileName
            # pyright: reportUndefinedVariable=false

            def queue_requests(target, wordlists):
                # Define your payload strategy here.
                pass


            def handle_response(req, interesting):
                # Process responses and report issues if needed.
                pass
        """.trimIndent() + "\n"
    }
}
