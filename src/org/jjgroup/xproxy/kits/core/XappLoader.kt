package org.jjgroup.xproxy.kits.core

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.kits.model.XappManifest
import org.jjgroup.xproxy.kits.model.XappPlugin
import org.jjgroup.xproxy.kits.model.XappPluginState
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

internal fun XappManager.loadPlugins(): List<XappPlugin> {
    ensureBundledXappsSynced()
    Files.createDirectories(xappRoot)
    val persistedStates = projectDataStore?.loadXappPluginStates().orEmpty().associateBy { it.pluginId }
    val loaded = Files.list(xappRoot).use { pathStream ->
        pathStream
            .filter { Files.isDirectory(it) }
            .sorted(compareBy<Path> { it.fileName.toString().lowercase(Locale.getDefault()) })
            .map { directory -> loadPlugin(directory, persistedStates) }
            .toList()
    }
    plugins = loaded
    refreshAllContextMenuDefinitions()
    notifyListeners(loaded)
    return loaded
}

private fun XappManager.loadPlugin(directory: Path, persistedStateByPluginId: Map<String, XappPluginState>): XappPlugin {
    val fallbackId = directory.fileName.toString()
    val manifestPath = directory.resolve("xapp.json")
    val manifest = if (Files.exists(manifestPath)) {
        readManifest(manifestPath, fallbackId)
    } else {
        XappManifest(
            id = fallbackId,
            name = fallbackId,
            version = "0.1.0",
            description = "",
            entryFile = "xapp.py",
            author = ""
        )
    }
    val persistedState = persistedStateByPluginId[manifest.id] ?: persistedStateByPluginId[fallbackId]
    val scriptPath = directory.resolve(manifest.entryFile)
    val loadError = if (Files.exists(scriptPath)) null else "Missing entry script: ${manifest.entryFile}"
    val enabled = persistedState?.enabled ?: false
    if (persistedState == null) {
        projectDataStore?.upsertXappPluginState(XappPluginState(pluginId = manifest.id, enabled = enabled))
    }
    return XappPlugin(
        manifest = manifest,
        directory = directory,
        scriptPath = scriptPath,
        enabled = enabled,
        loadError = loadError
    )
}

private fun XappManager.readManifest(path: Path, fallbackId: String): XappManifest {
    return try {
        val root = objectMapper.readTree(Files.newBufferedReader(path, Charsets.UTF_8))
        val id = root.path("id").asText(fallbackId).ifBlank { fallbackId }
        XappManifest(
            id = id,
            name = root.path("name").asText(id).ifBlank { id },
            version = root.path("version").asText("0.1.0"),
            description = root.path("description").asText(""),
            entryFile = root.path("entry").asText("xapp.py").ifBlank { "xapp.py" },
            author = root.path("author").asText("")
        )
    } catch (ex: Exception) {
        Utils.err("Failed to parse xapp manifest at $path: ${ex.message}")
        XappManifest(
            id = fallbackId,
            name = fallbackId,
            version = "0.1.0",
            description = "Invalid xapp.json manifest",
            entryFile = "xapp.py",
            author = ""
        )
    }
}

private fun XappManager.ensureBundledXappsSynced() {
    Files.createDirectories(xappRoot)
    runCatching { copyBundledXapps() }
        .onFailure { ex -> Utils.err("Failed to sync bundled xapps: ${ex.message}") }
}

private fun XappManager.copyBundledXapps() {
    val url = javaClass.classLoader.getResource("xapp") ?: return
    when (url.protocol) {
        "file" -> copyTree(Path.of(url.toURI()))
        "jar" -> {
            val uri = url.toURI()
            val raw = uri.toString()
            val separator = raw.indexOf("!/")
            if (separator < 0) return
            val jarUri = URI(raw.substring(0, separator))
            val internalRoot = raw.substring(separator + 1)
            val existingFs = runCatching { FileSystems.getFileSystem(jarUri) }.getOrNull()
            if (existingFs != null) {
                copyTree(existingFs.getPath(internalRoot))
            } else {
                FileSystems.newFileSystem(jarUri, emptyMap<String, Any>()).use { fs ->
                    copyTree(fs.getPath(internalRoot))
                }
            }
        }
        else -> Utils.err("Unsupported xapp resource protocol: ${url.protocol}")
    }
}

private fun XappManager.copyTree(sourceRoot: Path) {
    Files.walk(sourceRoot).use { stream ->
        stream.forEach { source ->
            val relative = sourceRoot.relativize(source)
            if (relative.toString().isBlank()) return@forEach
            val target = xappRoot.resolve(relative.toString())
            if (Files.isDirectory(source)) {
                val shouldCreate = shouldSyncBundledPath(sourceRoot, source, target)
                if (shouldCreate) {
                    Files.createDirectories(target)
                }
            } else {
                if (shouldSyncBundledPath(sourceRoot, source, target)) {
                    Files.createDirectories(target.parent)
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

private fun XappManager.shouldSyncBundledPath(sourceRoot: Path, source: Path, target: Path): Boolean {
    val relative = sourceRoot.relativize(source)
    if (relative.nameCount <= 0) {
        return false
    }
    val pluginRootRelative = relative.getName(0)
    val sourcePluginRoot = sourceRoot.resolve(pluginRootRelative)
    val targetPluginRoot = xappRoot.resolve(pluginRootRelative.toString())

    if (!Files.exists(targetPluginRoot)) {
        return true
    }

    val sourceManifest = sourcePluginRoot.resolve("xapp.json")
    val targetManifest = targetPluginRoot.resolve("xapp.json")
    if (!Files.exists(sourceManifest)) {
        return false
    }
    if (!Files.exists(targetManifest)) {
        return true
    }

    return runCatching {
        val src = objectMapper.readTree(Files.newBufferedReader(sourceManifest, Charsets.UTF_8))
        val dst = objectMapper.readTree(Files.newBufferedReader(targetManifest, Charsets.UTF_8))
        val srcAuthor = src.path("author").asText("").trim()
        val dstAuthor = dst.path("author").asText("").trim()
        val srcId = src.path("id").asText("").trim()
        val dstId = dst.path("id").asText("").trim()
        if (srcId.isBlank() || srcId != dstId) {
            return@runCatching false
        }
        if (dstAuthor.isBlank()) {
            return@runCatching true
        }
        srcAuthor.equals(dstAuthor, ignoreCase = true) || dstAuthor.equals("xproxy", ignoreCase = true)
    }.getOrDefault(false)
}
