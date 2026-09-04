package org.jjgroup.xproxy.kits.core

import org.jjgroup.xproxy.kits.model.XappManifest
import org.jjgroup.xproxy.kits.model.XappPlugin
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class XappContextMenuLifecycleTest {

    @BeforeEach
    fun clearHub() {
        XappContextMenuHub.clearAllForTests()
    }

    @Test
    fun `discovers context menu definitions from register_context_menu`() {
        val manager = XappManager(projectDataStore = null)
        val plugin = plugin(
            id = "menu-plugin",
            script = """
                def register_context_menu(api):
                    api.add_menu_item(label=["Header", "Set custom header"], contexts=["request"], tools=["fuzzer"], requires_editable=True, handler="set_header")

                def set_header(ctx):
                    pass
            """.trimIndent()
        )
        manager.plugins = listOf(plugin)

        manager.refreshContextMenuDefinitions(plugin)

        val definitions = XappContextMenuHub.matchingDefinitions(snapshot(editable = true))
        assertEquals(1, definitions.size)
        assertEquals(listOf("Header", "Set custom header"), definitions.single().labelPath)
        assertEquals("set_header", definitions.single().handlerName)
        manager.shutdown()
    }

    @Test
    fun `invalid definitions are skipped and logged`() {
        val logs = mutableListOf<String>()
        val manager = XappManager(projectDataStore = null)
        manager.addLogListener { _, line -> logs.add(line) }
        val plugin = plugin(
            id = "invalid-menu-plugin",
            script = """
                def register_context_menu(api):
                    api.add_menu_item(label="A/B/C/D/E", contexts=["request"], tools=["fuzzer"], handler="ok")
                    api.add_menu_item(label="Valid", contexts=["request"], tools=["fuzzer"], handler="bad-name!")
            """.trimIndent()
        )
        manager.plugins = listOf(plugin)

        manager.refreshContextMenuDefinitions(plugin)

        assertTrue(XappContextMenuHub.matchingDefinitions(snapshot()).isEmpty())
        assertTrue(logs.any { it.contains("[context-menu-warning]") })
        manager.shutdown()
    }

    @Test
    fun `executes context menu handler and applies request mutation`() {
        val manager = XappManager(projectDataStore = null)
        val plugin = plugin(
            id = "action-plugin",
            script = """
                def register_context_menu(api):
                    api.add_menu_item(label="Add header", contexts=["request"], tools=["fuzzer"], requires_editable=True, handler="add_header")

                def add_header(ctx):
                    ctx.request.headers["X-Menu"] = "1"
                    ctx.apply_request()
            """.trimIndent()
        )
        manager.plugins = listOf(plugin)
        manager.refreshContextMenuDefinitions(plugin)
        val definition = XappContextMenuHub.matchingDefinitions(snapshot(editable = true)).single()
        val latch = CountDownLatch(1)
        var applied = ""

        manager.executeContextMenuAction(
            definition,
            snapshot(
                editable = true,
                requestMutation = { raw -> applied = raw; latch.countDown(); true }
            )
        )

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(applied.contains("X-Menu: 1"))
        manager.shutdown()
    }

    @Test
    fun `missing handler logs exact error`() {
        val logs = mutableListOf<String>()
        val manager = XappManager(projectDataStore = null)
        manager.addLogListener { _, line -> logs.add(line) }
        val plugin = plugin(
            id = "missing-handler",
            script = """
                def register_context_menu(api):
                    api.add_menu_item(label="Missing", contexts=["request"], tools=["fuzzer"], handler="not_there")
            """.trimIndent()
        )
        manager.plugins = listOf(plugin)
        manager.refreshContextMenuDefinitions(plugin)
        val definition = XappContextMenuHub.matchingDefinitions(snapshot()).single()

        manager.executeContextMenuAction(definition, snapshot())

        Thread.sleep(500)
        assertTrue(logs.any { it == "[context-menu-error] handler not found: not_there" })
        manager.shutdown()
    }

    private fun plugin(id: String, script: String): XappPlugin {
        val dir = Files.createTempDirectory("xapp-context-menu-$id")
        val scriptPath = dir.resolve("xapp.py")
        Files.writeString(scriptPath, script)
        return XappPlugin(
            manifest = XappManifest(
                id = id,
                name = id,
                version = "1.0.0",
                description = "",
                entryFile = "xapp.py",
                author = "test"
            ),
            directory = dir,
            scriptPath = scriptPath,
            enabled = true,
            loadError = null
        )
    }

    private fun snapshot(
        editable: Boolean = true,
        requestMutation: ((String) -> Boolean)? = { true }
    ): XappHttpMenuSnapshot = XappHttpMenuSnapshot(
        tool = HttpViewerToolContext.FUZZER,
        messagePart = XappMenuMessagePart.REQUEST,
        editable = editable,
        activeText = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
        requestRaw = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
        responseRaw = "",
        selectionStart = 0,
        selectionEnd = 0,
        selectedText = "",
        requestTextHash = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".hashCode(),
        responseTextHash = "".hashCode(),
        currentRequestHashProvider = { "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".hashCode() },
        currentResponseHashProvider = { "".hashCode() },
        requestMutation = requestMutation,
        responseMutation = null,
        sendToFuzzer = null,
        sendToCodec = null,
        clipboardSink = {},
        logger = {},
        promptRunner = object : XappPromptDialogRunner {
            override fun promptText(title: String, message: String, defaultValue: String?): String? = null
            override fun promptChoice(title: String, message: String, choices: List<String>, defaultValue: String?): String? = null
            override fun promptFields(title: String, fields: List<XappPromptFieldDefinition>): Map<String, Any?>? = null
        }
    )
}
