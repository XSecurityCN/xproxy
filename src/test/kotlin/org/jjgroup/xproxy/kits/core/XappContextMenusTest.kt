package org.jjgroup.xproxy.kits.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

class XappContextMenusTest {

    @Test
    fun `normalizes tool context and message part api names`() {
        assertEquals(HttpViewerToolContext.FUZZER, HttpViewerToolContext.fromApiName(" FUZZER "))
        assertEquals(HttpViewerToolContext.UNKNOWN, HttpViewerToolContext.fromApiName(""))
        assertEquals(HttpViewerToolContext.UNKNOWN, HttpViewerToolContext.fromApiName("missing"))
        assertEquals(XappMenuMessagePart.REQUEST, XappMenuMessagePart.fromApiName("request"))
        assertEquals(XappMenuMessagePart.RESPONSE, XappMenuMessagePart.fromApiName(" RESPONSE "))
        assertEquals(XappMenuMessagePart.UNKNOWN, XappMenuMessagePart.fromApiName("other"))
    }

    @Test
    fun `normalizes slash and list label paths with max depth`() {
        assertEquals(listOf("JWT", "Decode"), normalizeXappMenuLabelPath(" JWT / Decode ", mutableListOf()))
        assertEquals(listOf("Header", "Set"), normalizeXappMenuLabelPath(listOf(" Header ", "Set"), mutableListOf()))
        assertNull(normalizeXappMenuLabelPath("JWT//Decode", mutableListOf()))
        assertNotNull(normalizeXappMenuLabelPath("A/B/C/D", mutableListOf()))
        val warnings = mutableListOf<String>()
        assertNull(normalizeXappMenuLabelPath("A/B/C/D/E", warnings))
        assertTrue(warnings.any { it.contains("label depth") })
    }

    @Test
    fun `hub filters definitions by context tool and editability`() {
        XappContextMenuHub.clearAllForTests()
        val definitions = listOf(
            definition(labelPath = listOf("Request"), contexts = setOf(XappMenuMessagePart.REQUEST)),
            definition(labelPath = listOf("Response"), contexts = setOf(XappMenuMessagePart.RESPONSE), tools = emptySet()),
            definition(labelPath = listOf("Editable"), contexts = emptySet(), tools = emptySet(), requiresEditable = true),
            definition(labelPath = listOf("Proxy"), contexts = emptySet(), tools = setOf(HttpViewerToolContext.PROXY)),
            definition(labelPath = listOf("All"), contexts = emptySet(), tools = emptySet())
        )
        XappContextMenuHub.replacePluginDefinitions("p1", definitions)

        val fuzzerRequest = snapshot(tool = HttpViewerToolContext.FUZZER, part = XappMenuMessagePart.REQUEST, editable = false)
        assertEquals(listOf("All", "Request"), XappContextMenuHub.matchingDefinitions(fuzzerRequest).map { it.labelPath.joinToString("/") })

        val proxyResponseEditable = snapshot(tool = HttpViewerToolContext.PROXY, part = XappMenuMessagePart.RESPONSE, editable = true)
        assertEquals(
            listOf("All", "Editable", "Proxy", "Response"),
            XappContextMenuHub.matchingDefinitions(proxyResponseEditable).map { it.labelPath.joinToString("/") }
        )

        XappContextMenuHub.removePluginDefinitions("p1")
        assertTrue(XappContextMenuHub.matchingDefinitions(proxyResponseEditable).isEmpty())
    }

    @Test
    fun `builds nested menu tree and deterministic collision groups`() {
        val defs = listOf(
            definition(labelPath = listOf("Tools"), pluginName = "Alpha"),
            definition(labelPath = listOf("Tools", "Encode"), pluginName = "Alpha"),
            definition(labelPath = listOf("Tools", "Actions", "Real"), pluginName = "Alpha"),
            definition(labelPath = listOf("Tools", "Plugin Actions", "Real"), pluginName = "Alpha"),
            definition(labelPath = listOf("Tools", "Plugin Actions (2)", "Real"), pluginName = "Alpha")
        )

        val tree = buildXappMenuTree(defs)
        val tools = tree.children.single { it.label == "Tools" }
        assertTrue(tools.children.any { it.label == "Encode" && it.action != null })
        val reserved = tools.children.single { it.label == "Plugin Actions (3)" }
        assertTrue(reserved.children.any { it.label == "Tools" && it.action != null })
    }

    @Test
    fun `context applies request mutation and blocks readonly mutation`() {
        var applied: String? = null
        val logs = mutableListOf<String>()
        val ctx = XappHttpMenuContext(
            definition = definition(),
            snapshot = snapshot(
                editable = true,
                requestRaw = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
                requestMutation = { raw -> applied = raw; true },
                logger = { logs.add(it) }
            ),
            generationChecker = { _, _ -> XappContextMutationGate.ALLOW }
        )
        ctx.request.headers["X-Test"] = "1"
        assertTrue(ctx.apply_request())
        assertTrue(applied!!.contains("X-Test: 1"))

        val readonly = XappHttpMenuContext(
            definition = definition(),
            snapshot = snapshot(editable = false, requestMutation = null, logger = { logs.add(it) }),
            generationChecker = { _, _ -> XappContextMutationGate.ALLOW }
        )
        assertFalse(readonly.apply_request("GET /x HTTP/1.1\r\nHost: a\r\n\r\n"))
        assertTrue(logs.any { it.contains("no longer editable") })
    }

    @Test
    fun `replace selection uses active text and mutation callback`() {
        var applied: String? = null
        val ctx = XappHttpMenuContext(
            definition = definition(),
            snapshot = snapshot(
                editable = true,
                activeText = "GET /old HTTP/1.1\r\nHost: a\r\n\r\n",
                requestRaw = "GET /old HTTP/1.1\r\nHost: a\r\n\r\n",
                selectionStart = 4,
                selectionEnd = 8,
                requestMutation = { raw -> applied = raw; true }
            ),
            generationChecker = { _, _ -> XappContextMutationGate.ALLOW }
        )

        assertTrue(ctx.replace_selection("/new"))
        assertEquals("GET /new HTTP/1.1\r\nHost: a\r\n\r\n", applied)
    }

    @Test
    fun `prompt helpers return typed values and cancellation`() {
        val runner = FakePromptDialogRunner(
            textResult = "hello",
            choiceResult = "safe",
            fieldsResult = mapOf("header" to "X-Debug", "enabled" to true)
        )
        val ctx = XappHttpMenuContext(
            definition = definition(),
            snapshot = snapshot(promptRunner = runner),
            generationChecker = { _, _ -> XappContextMutationGate.ALLOW }
        )

        assertEquals("hello", ctx.prompt_text("T", "M"))
        assertEquals("safe", ctx.prompt_choice("T", "M", listOf("fast", "safe")))
        assertEquals(mapOf("header" to "X-Debug", "enabled" to true), ctx.prompt_fields("T", listOf(mapOf("name" to "header"), mapOf("name" to "enabled", "type" to "boolean"))))

        val cancel = XappHttpMenuContext(
            definition = definition(),
            snapshot = snapshot(promptRunner = FakePromptDialogRunner()),
            generationChecker = { _, _ -> XappContextMutationGate.ALLOW }
        )
        assertNull(cancel.prompt_text("T", "M"))
    }

    @Test
    fun `mutation gate skips stale changes with exact warning prefix`() {
        val logs = mutableListOf<String>()
        var applied = false
        val ctx = XappHttpMenuContext(
            definition = definition(),
            snapshot = snapshot(editable = true, requestMutation = { applied = true; true }, logger = { logs.add(it) }),
            generationChecker = { _, _ -> XappContextMutationGate.BLOCK_PLUGIN_DISABLED }
        )

        assertFalse(ctx.apply_request("GET / HTTP/1.1\r\nHost: a\r\n\r\n"))
        assertFalse(applied)
        assertTrue(logs.any { it == "[context-menu-warning] skipped stale context-menu mutation: plugin disabled" })
    }

    private fun definition(
        pluginId: String = "p1",
        pluginName: String = "Plugin",
        labelPath: List<String> = listOf("Action"),
        contexts: Set<XappMenuMessagePart> = setOf(XappMenuMessagePart.REQUEST),
        tools: Set<HttpViewerToolContext> = setOf(HttpViewerToolContext.FUZZER),
        requiresEditable: Boolean = false,
        handlerName: String = "handle",
        generation: Long = 1L
    ): XappMenuItemDefinition = XappMenuItemDefinition(
        pluginId = pluginId,
        pluginName = pluginName,
        labelPath = labelPath,
        contexts = contexts,
        tools = tools,
        requiresEditable = requiresEditable,
        handlerName = handlerName,
        generation = generation
    )

    private fun snapshot(
        tool: HttpViewerToolContext = HttpViewerToolContext.FUZZER,
        part: XappMenuMessagePart = XappMenuMessagePart.REQUEST,
        editable: Boolean = true,
        activeText: String = "GET / HTTP/1.1\r\nHost: a\r\n\r\n",
        requestRaw: String = activeText,
        responseRaw: String = "",
        selectionStart: Int = 0,
        selectionEnd: Int = 0,
        requestMutation: ((String) -> Boolean)? = { true },
        responseMutation: ((String) -> Boolean)? = null,
        logger: (String) -> Unit = {},
        promptRunner: XappPromptDialogRunner = FakePromptDialogRunner(),
    ): XappHttpMenuSnapshot = XappHttpMenuSnapshot(
        tool = tool,
        messagePart = part,
        editable = editable,
        activeText = activeText,
        requestRaw = requestRaw,
        responseRaw = responseRaw,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        selectedText = if (selectionEnd > selectionStart) activeText.substring(selectionStart, selectionEnd) else "",
        requestTextHash = requestRaw.hashCode(),
        responseTextHash = responseRaw.hashCode(),
        currentRequestHashProvider = { requestRaw.hashCode() },
        currentResponseHashProvider = { responseRaw.hashCode() },
        requestMutation = requestMutation,
        responseMutation = responseMutation,
        sendToFuzzer = null,
        sendToCodec = null,
        clipboardSink = {},
        logger = logger,
        promptRunner = promptRunner
    )

    private class FakePromptDialogRunner(
        private val textResult: String? = null,
        private val choiceResult: String? = null,
        private val fieldsResult: Map<String, Any?>? = null,
        private val failure: RuntimeException? = null
    ) : XappPromptDialogRunner {
        override fun promptText(title: String, message: String, defaultValue: String?): String? {
            failure?.let { throw it }
            return textResult
        }

        override fun promptChoice(title: String, message: String, choices: List<String>, defaultValue: String?): String? {
            failure?.let { throw it }
            return choiceResult
        }

        override fun promptFields(title: String, fields: List<XappPromptFieldDefinition>): Map<String, Any?>? {
            failure?.let { throw it }
            return fieldsResult
        }
    }
}
