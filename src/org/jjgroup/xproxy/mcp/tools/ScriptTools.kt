package org.jjgroup.xproxy.mcp.tools

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jjgroup.xproxy.kits.core.loadPlugins
import org.jjgroup.xproxy.kits.core.updateEnabled
import org.jjgroup.xproxy.kits.model.XappPlugin
import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.mcp.server.McpJson
import org.jjgroup.xproxy.mcp.server.mcpSchema
import java.nio.file.Files

/* ============================ Area 2: 脚本 / xapp 管理工具 ============================ */

internal class ListXappsTool : BaseTool() {
    override val name = "list_xapps"
    override val description = "List installed xapp plugins (Jython) with enable state and declared lifecycle handlers."
    override val inputSchema: ObjectNode = mcpSchema {}

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().xappManager
        val list = mgr.plugins.map { pluginSummary(it) }
        return McpToolResult.ok(mapOf("xapps" to list, "directory" to mgr.xappDirectory().toString()))
    }
}

internal class GetXappTool : BaseTool() {
    override val name = "get_xapp"
    override val description = "Read an xapp plugin's manifest and script source."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("id", "Plugin id (manifest id).")
        required("id")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().xappManager
        val id = args.str("id").takeIf { it.isNotBlank() } ?: return McpToolResult.error("id is required.")
        val plugin = mgr.plugins.firstOrNull { it.manifest.id == id }
            ?: return McpToolResult.error("xapp not found: $id")
        val source = runCatching { Files.readString(plugin.scriptPath) }.getOrDefault("")
        return McpToolResult.ok(mapOf(
            "manifest" to mapOf(
                "id" to plugin.manifest.id, "name" to plugin.manifest.name,
                "version" to plugin.manifest.version, "author" to plugin.manifest.author,
                "description" to plugin.manifest.description, "entry" to plugin.manifest.entryFile
            ),
            "enabled" to plugin.enabled,
            "loadError" to plugin.loadError,
            "directory" to plugin.directory.toString(),
            "scriptPath" to plugin.scriptPath.toString(),
            "source" to source
        ))
    }
}

internal class CreateXappTool : BaseTool() {
    override val name = "create_xapp"
    override val description = "Create a new xapp plugin: writes xapp.json + xapp.py under the xapp directory and reloads plugins. Returns the created plugin id."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("id", "Plugin id (also directory name).")
        stringProp("name", "Display name (defaults to id).")
        stringProp("description", "Plugin description.")
        stringProp("author", "Author (default xproxy).")
        stringProp("script", "Python source for xapp.py. If empty, a template is written.")
        required("id")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().xappManager
        val rawId = args.str("id").takeIf { it.isNotBlank() } ?: return McpToolResult.error("id is required.")
        val safeId = rawId.replace(Regex("[^A-Za-z0-9_.-]"), "-").trim('-', '.').ifBlank { "xapp" }
        val dir = mgr.xappDirectory().resolve(safeId)
        if (Files.exists(dir)) return McpToolResult.error("xapp already exists: $safeId")
        Files.createDirectories(dir)
        val name = args.str("name").ifBlank { rawId }
        val manifest = McpJson.obj().apply {
            put("id", safeId)
            put("name", name)
            put("version", "0.1.0")
            put("author", args.str("author").ifBlank { "xproxy" })
            put("description", args.str("description"))
            put("entry", "xapp.py")
        }
        Files.writeString(dir.resolve("xapp.json"), McpJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest))
        val script = args.strOpt("script") ?: XAPP_TEMPLATE
        Files.writeString(dir.resolve("xapp.py"), script)
        mgr.loadPlugins()
        return McpToolResult.ok(mapOf("id" to safeId, "directory" to dir.toString(), "created" to true))
    }
}

internal class UpdateXappScriptTool : BaseTool() {
    override val name = "update_xapp_script"
    override val description = "Overwrite an xapp plugin's xapp.py source and reload plugins."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("id", "Plugin id.")
        stringProp("script", "New Python source for xapp.py.")
        required("id", "script")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().xappManager
        val id = args.str("id").takeIf { it.isNotBlank() } ?: return McpToolResult.error("id is required.")
        val script = args.strOpt("script") ?: return McpToolResult.error("script is required.")
        val plugin = mgr.plugins.firstOrNull { it.manifest.id == id }
            ?: return McpToolResult.error("xapp not found: $id")
        Files.writeString(plugin.scriptPath, script)
        mgr.loadPlugins()
        return McpToolResult.ok(mapOf("id" to id, "updated" to true))
    }
}

internal class DeleteXappTool : BaseTool() {
    override val name = "delete_xapp"
    override val description = "Delete a user-created xapp plugin directory. Note: bundled plugins will be re-synced on reload."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("id", "Plugin id.")
        required("id")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().xappManager
        val id = args.str("id").takeIf { it.isNotBlank() } ?: return McpToolResult.error("id is required.")
        val plugin = mgr.plugins.firstOrNull { it.manifest.id == id }
            ?: return McpToolResult.error("xapp not found: $id")
        val dir = plugin.directory
        if (!dir.startsWith(mgr.xappDirectory())) return McpToolResult.error("Refused: directory outside xapp root.")
        dir.toFile().deleteRecursively()
        mgr.loadPlugins()
        return McpToolResult.ok(mapOf("id" to id, "deleted" to true))
    }
}

internal class SetXappEnabledTool : BaseTool() {
    override val name = "set_xapp_enabled"
    override val description = "Enable or disable an xapp plugin (takes effect on the live proxy immediately)."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("id", "Plugin id.")
        boolProp("enabled", "true to enable, false to disable.")
        required("id", "enabled")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().xappManager
        val id = args.str("id").takeIf { it.isNotBlank() } ?: return McpToolResult.error("id is required.")
        if (mgr.plugins.none { it.manifest.id == id }) return McpToolResult.error("xapp not found: $id")
        val enabled = args.boolOr("enabled", true)
        mgr.updateEnabled(id, enabled)
        return McpToolResult.ok(mapOf("id" to id, "enabled" to enabled))
    }
}

internal class ReloadXappsTool : BaseTool() {
    override val name = "reload_xapps"
    override val description = "Reload all xapp plugins from disk (pick up manual file changes)."
    override val inputSchema: ObjectNode = mcpSchema {}

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().xappManager
        val loaded = mgr.loadPlugins()
        return McpToolResult.ok(mapOf("reloaded" to loaded.size, "xapps" to loaded.map { pluginSummary(it) }))
    }
}

internal class ListAttackScriptsTool : BaseTool() {
    override val name = "list_attack_scripts"
    override val description = "List intruder/attack scripts (Jython) with enable state and category."
    override val inputSchema: ObjectNode = mcpSchema {}

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().intruderScriptManager
        val scripts = mgr.loadScripts().map {
            mapOf(
                "key" to it.key, "name" to it.name, "category" to it.category,
                "enabled" to it.enabled, "relativePath" to it.relativePath
            )
        }
        return McpToolResult.ok(mapOf("scripts" to scripts, "directory" to mgr.scriptDirectory().toString()))
    }
}

internal class GetAttackScriptTool : BaseTool() {
    override val name = "get_attack_script"
    override val description = "Read an intruder/attack script's source by key."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("key", "Script key (lowercase relative path, e.g. bruteforce/sniper.py).")
        required("key")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().intruderScriptManager
        val key = args.str("key").takeIf { it.isNotBlank() } ?: return McpToolResult.error("key is required.")
        val script = mgr.loadScripts().firstOrNull { it.key == key }
            ?: return McpToolResult.error("attack script not found: $key")
        val source = runCatching { Files.readString(script.scriptPath) }.getOrDefault("")
        return McpToolResult.ok(mapOf("key" to key, "source" to source, "path" to script.scriptPath.toString()))
    }
}

internal class CreateAttackScriptTool : BaseTool() {
    override val name = "create_attack_script"
    override val description = "Create a new intruder/attack script. If content is omitted, a queue_requests/handle_response template is written."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("name", "Script file name (without extension, e.g. my-fuzz).")
        stringProp("category", "Category/subdirectory (default General).")
        stringProp("content", "Python source. If empty, a template is used.")
        required("name")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().intruderScriptManager
        val name = args.str("name").takeIf { it.isNotBlank() } ?: return McpToolResult.error("name is required.")
        val category = args.str("category").ifBlank { "General" }
        val path = mgr.createScript(category, name)
        val content = args.strOpt("content")
        if (content != null) mgr.saveScript(path, content)
        val key = mgr.scriptDirectory().relativize(path).toString().replace(java.io.File.separatorChar, '/').lowercase()
        return McpToolResult.ok(mapOf("key" to key, "path" to path.toString(), "created" to true))
    }
}

internal class UpdateAttackScriptTool : BaseTool() {
    override val name = "update_attack_script"
    override val description = "Overwrite an intruder/attack script's source by key."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("key", "Script key.")
        stringProp("content", "New Python source.")
        required("key", "content")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().intruderScriptManager
        val key = args.str("key").takeIf { it.isNotBlank() } ?: return McpToolResult.error("key is required.")
        val content = args.strOpt("content") ?: return McpToolResult.error("content is required.")
        val script = mgr.loadScripts().firstOrNull { it.key == key }
            ?: return McpToolResult.error("attack script not found: $key")
        mgr.saveScript(script.scriptPath, content)
        return McpToolResult.ok(mapOf("key" to key, "updated" to true))
    }
}

internal class DeleteAttackScriptTool : BaseTool() {
    override val name = "delete_attack_script"
    override val description = "Delete an intruder/attack script by key."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("key", "Script key.")
        required("key")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().intruderScriptManager
        val key = args.str("key").takeIf { it.isNotBlank() } ?: return McpToolResult.error("key is required.")
        val script = mgr.loadScripts().firstOrNull { it.key == key }
            ?: return McpToolResult.error("attack script not found: $key")
        mgr.deleteScript(script.scriptPath)
        return McpToolResult.ok(mapOf("key" to key, "deleted" to true))
    }
}

internal class SetAttackScriptEnabledTool : BaseTool() {
    override val name = "set_attack_script_enabled"
    override val description = "Enable or disable an intruder/attack script (controls visibility in the Fuzzer dropdown)."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("key", "Script key.")
        boolProp("enabled", "true to enable, false to disable.")
        required("key", "enabled")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val mgr = XproxyAppContext.requireKitsPanel().intruderScriptManager
        val key = args.str("key").takeIf { it.isNotBlank() } ?: return McpToolResult.error("key is required.")
        val enabled = args.boolOr("enabled", true)
        mgr.updateEnabled(key, enabled)
        return McpToolResult.ok(mapOf("key" to key, "enabled" to enabled))
    }
}

internal class GetScriptApiDocsTool : BaseTool() {
    override val name = "get_script_api_docs"
    override val description = "Return reference documentation for the xapp and intruder scripting APIs (ctx, hooks, RequestEngine, report_issue, codec). Use when writing attack scripts or xapp plugins."
    override val inputSchema: ObjectNode = mcpSchema {
        stringProp("kind", "Which docs: xapp, intruder, or both (default both).", enum = listOf("xapp", "intruder", "both"), default = "both")
    }

    override fun run(args: JsonNode, ctx: McpToolContext): McpToolResult {
        val kind = args.str("kind", "both")
        val out = LinkedHashMap<String, String>()
        if (kind == "xapp" || kind == "both") out["xapp"] = XAPP_API_DOCS
        if (kind == "intruder" || kind == "both") out["intruder"] = ATTACK_SCRIPT_API_DOCS
        return McpToolResult.ok(out)
    }
}

/* ------------------------------ helpers ------------------------------ */

private fun pluginSummary(p: XappPlugin): Map<String, Any?> = mapOf(
    "id" to p.manifest.id,
    "name" to p.manifest.name,
    "version" to p.manifest.version,
    "description" to p.manifest.description,
    "enabled" to p.enabled,
    "loadError" to p.loadError,
    "directory" to p.directory.fileName.toString()
)

private val XAPP_TEMPLATE = """
# xapp plugin template
# pyright: reportUndefinedVariable=false

# Lifecycle hooks (define any that apply):
#   on_proxy_http_message(ctx)       - every proxied HTTP message
#   on_http_message(ctx)             - deduplicated per method+host+port+path
#   on_before_request(ctx) -> str    - rewrite request (return new raw)
#   on_after_request(ctx) -> str     - rewrite response (return new raw)
#   register_context_menu(ctx)       - declare right-click menu items

def on_http_message(ctx):
    if ctx.response_contains("private") or ctx.response_contains("secret"):
        ctx.report_issue(
            req=None, resp=None,
            name="Sensitive keyword in response",
            detail=f"Response on {ctx.url} contained a sensitive keyword.",
            severity="Low",
            confidence="Tentative",
            url=ctx.url, host=ctx.host, path=ctx.path, method=ctx.method,
        )
""".trimIndent()

private val XAPP_API_DOCS = """
xapp plugin API (Jython). Plugin = xapp.json manifest + xapp.py entry.
ctx (XappProxyMessageContext) fields/methods:
  ctx.plugin_id, ctx.plugin_name
  ctx.method, ctx.host, ctx.path, ctx.status_code, ctx.mime_type, ctx.url
  ctx.request_raw, ctx.response_raw
  ctx.request, ctx.response  (mutable request/response objects)
  ctx.send(req=None) -> response   # send a (possibly modified) request and record it
  ctx.request_contains(token) -> bool
  ctx.response_contains(token) -> bool
  ctx.response_regex(pattern) -> bool
  ctx.report_issue(req, resp, name, detail, severity=, confidence=, remediation=, url=, host=, path=, method=, tags=, source=)
     # severity: High/Medium/Low/Information; req/resp may be None
  ctx.highlight(color="red", history_id=None)  # mark traffic row by color (red/orange/yellow/green/cyan/blue/pink/gray/none); history_id defaults to current entry
  ctx.history_id  # current proxy history entry id (0 in rewrite hooks)
  ctx.log(message)
  ctx.codec.*  # to_base64, from_base64, url_encode, url_decode, to_hex, from_hex,
               # html_encode, html_decode, jwt_decode_payload, md5, sha1, sha256, sha512, hmac, aes_*, rot13, ...
Lifecycle hooks (define functions with these names):
  on_proxy_http_message(ctx)           # every proxied message (high volume)
  on_http_message(ctx)                 # deduplicated per method+host+port+path
  on_before_request(ctx) -> str|None   # return rewritten raw request to modify
  on_after_request(ctx) -> str|None    # return rewritten raw response to modify
  register_context_menu(ctx)           # declare right-click menu entries
Decorators: @Match(...), @Filter(...) (see XappRuntime.py).
Issues reported via ctx.report_issue flow into the Target panel + ReportedIssue store (visible to MCP list_issues).
""".trimIndent()

private val ATTACK_SCRIPT_API_DOCS = """
intruder/attack script API (Jython). Entrypoints:
  def queue_requests(target, wordlists): ...   # REQUIRED: build & queue requests
  def handle_response(req, interesting): ...    # called per response; call table.add(req) to keep
  def completed(outputHandler): ...             # OPTIONAL: runs after all workers finish
Globals injected:
  target           # target.req (template str), target.rawreq (bytes), target.endpoint, target.base_input
  wordlists        # wordlists.bruteforce.generate(seed,count,batch), wordlists.observed_words, wordlists.clipboard
  handler          # AttackHandler: handler.report_issue(...), .abort(), .pause(), .resume()
  table / outputHandler  # table.add(req) to store a result; outputHandler.getAllRquests()
  ctx, codec       # same codec helpers as xapp
  host             # target hostname
RequestEngine (Python class, created in queue_requests):
  engine = RequestEngine(endpoint=target.endpoint, engine=Engine.HTTP,
                         concurrent_connections=5, requests_per_connection=100,
                         pipeline=False, max_queue_size=100, timeout=10,
                         max_retries_per_request=3, idle_timeout=0, auto_start=True)
  Engine.HTTP=1, Engine.HTTP2=2, Engine.SPIKE=3
  engine.queue(template, payloads=None, learn=0, gate=None, label="", delay=0, endpoint=None, fix_content_length=True)
     # template uses {{placeholder}} markers; payloads list substitutes them (count must match markers)
  engine.open_gate(name)      # release a race gate
  engine.complete(timeout=-1) # block until all queued requests finish
  engine.cancel()
  engine.apply_setting(name, value)  # e.g. ignoreLength
  engine.report_issue(...)
req (Request) fields in handle_response:
  req.code/req.status (int), req.length, req.wordcount, req.time (micros),
  req.words (payload list), req.label, req.response (raw), req.anomalyRank
Use MCP run_attack to launch a script headlessly; results are queryable via get_attack_results.
""".trimIndent()

/** Area 2 工具集。 */
fun scriptTools(): List<McpTool> = listOf(
    ListXappsTool(),
    GetXappTool(),
    CreateXappTool(),
    UpdateXappScriptTool(),
    DeleteXappTool(),
    SetXappEnabledTool(),
    ReloadXappsTool(),
    ListAttackScriptsTool(),
    GetAttackScriptTool(),
    CreateAttackScriptTool(),
    UpdateAttackScriptTool(),
    DeleteAttackScriptTool(),
    SetAttackScriptEnabledTool(),
    GetScriptApiDocsTool()
)
