package org.jjgroup.xproxy.kits.core

import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.ui.ReadFromJar
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * intruder 攻击脚本的路径解析与内置脚本同步,统一供 Kits 面板([IntruderScriptManager])与
 * Fuzzer 编辑器([org.jjgroup.xproxy.fuzzer.ui.IntruderFrameEditor])共用,避免两处各维护一份
 * 同步逻辑导致行为漂移。
 */
object IntruderScriptSync {
    fun persistentDir(): Path =
        Paths.get(System.getProperty("user.home"), ".xproxy", "intruder").toAbsolutePath().normalize()

    fun bundledDir(): Path =
        Paths.get(System.getProperty("user.dir"), "resources", "intruder").toAbsolutePath().normalize()

    fun ensurePersistentDir(): Path {
        val dir = persistentDir()
        Files.createDirectories(dir)
        return dir
    }

    /**
     * 把 resources/intruder 下的内置脚本同步到 ~/.xproxy/intruder(仅当目标不存在时复制,不覆盖用户改动)。
     * 文件系统与 jar 两种打包来源都覆盖;逐文件 runCatching,单个失败不影响其余。
     */
    fun syncBundledToPersistent() {
        val persistentRoot = ensurePersistentDir()

        val bundledRoot = bundledDir()
        if (Files.isDirectory(bundledRoot)) {
            Files.walk(bundledRoot).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".py", ignoreCase = true) }
                    .filter { path ->
                        val relativePath = bundledRoot.relativize(path)
                        relativePath.none { part ->
                            val name = part.toString()
                            name.startsWith(".") || name == "__pycache__"
                        }
                    }
                    .forEach { sourcePath ->
                        runCatching {
                            val relativePath = bundledRoot.relativize(sourcePath)
                            val targetPath = persistentRoot.resolve(relativePath).normalize()
                            if (!Files.exists(targetPath)) {
                                Files.createDirectories(targetPath.parent)
                                Files.copy(sourcePath, targetPath)
                            }
                        }.onFailure { copyError ->
                            Utils.err("Failed to sync bundled intruder script from filesystem: $copyError")
                        }
                    }
            }
        }

        runCatching {
            val readJar = ReadFromJar()
            val jarEntries = readJar.getFiles("intruder")
            for (entry in jarEntries) {
                if (!entry.endsWith(".py", ignoreCase = true)) {
                    continue
                }
                if (entry.contains("/__pycache__/") || entry.contains("/.") || entry.endsWith("/__init__.py")) {
                    continue
                }
                val relative = entry.removePrefix("intruder/")
                if (relative.isBlank()) {
                    continue
                }
                val targetPath = persistentRoot.resolve(relative.replace('/', File.separatorChar)).normalize()
                if (Files.exists(targetPath)) {
                    continue
                }
                val stream = ReadFromJar::class.java.getResourceAsStream("/$entry") ?: continue
                stream.use { input ->
                    Files.createDirectories(targetPath.parent)
                    Files.copy(input, targetPath)
                }
            }
        }.onFailure { jarError ->
            Utils.err("Failed to sync bundled intruder scripts from jar: $jarError")
        }
    }
}
