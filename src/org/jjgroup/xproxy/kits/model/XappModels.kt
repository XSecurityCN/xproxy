package org.jjgroup.xproxy.kits.model

import java.nio.file.Path

data class XappManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val entryFile: String,
    val author: String
)

data class XappPlugin(
    val manifest: XappManifest,
    val directory: Path,
    val scriptPath: Path,
    val enabled: Boolean,
    val loadError: String? = null
)

data class XappPluginState(
    val pluginId: String,
    val enabled: Boolean
)

data class StoreXapp(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val files: List<String>
)

data class IntruderAttackScript(
    val key: String,
    val name: String,
    val relativePath: String,
    val category: String,
    val enabled: Boolean,
    val scriptPath: Path,
    val status: String = "Ready"
)

data class IntruderAttackScriptState(
    val scriptKey: String,
    val enabled: Boolean,
    val category: String
)
