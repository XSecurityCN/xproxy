package org.jjgroup.xproxy.kits.core

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

enum class HttpViewerToolContext(val apiName: String) {
    FUZZER("fuzzer"),
    PROXY("proxy"),
    TARGET("target"),
    TABLE("table"),
    UNKNOWN("unknown");

    companion object {
        fun fromApiName(value: String?): HttpViewerToolContext {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return UNKNOWN
            return entries.firstOrNull { it.apiName == normalized } ?: UNKNOWN
        }
    }
}

enum class XappMenuMessagePart(val apiName: String) {
    REQUEST("request"),
    RESPONSE("response"),
    UNKNOWN("unknown");

    companion object {
        fun fromApiName(value: String?): XappMenuMessagePart {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isBlank()) return UNKNOWN
            return entries.firstOrNull { it.apiName == normalized } ?: UNKNOWN
        }
    }
}

data class XappMenuItemDefinition(
    val pluginId: String,
    val pluginName: String,
    val labelPath: List<String>,
    val contexts: Set<XappMenuMessagePart>,
    val tools: Set<HttpViewerToolContext>,
    val requiresEditable: Boolean,
    val handlerName: String,
    val generation: Long = 0L
)

data class XappHttpMenuSnapshot(
    val tool: HttpViewerToolContext,
    val messagePart: XappMenuMessagePart,
    val editable: Boolean,
    val activeText: String,
    val requestRaw: String,
    val responseRaw: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val selectedText: String,
    val requestTextHash: Int,
    val responseTextHash: Int,
    val currentRequestHashProvider: () -> Int,
    val currentResponseHashProvider: () -> Int,
    val requestMutation: ((String) -> Boolean)?,
    val responseMutation: ((String) -> Boolean)?,
    val sendToFuzzer: ((String) -> Unit)?,
    val sendToCodec: ((String, String?) -> Unit)?,
    val clipboardSink: (String) -> Unit = { text ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    },
    val logger: (String) -> Unit = {},
    val promptRunner: XappPromptDialogRunner = SwingXappPromptDialogRunner
)

enum class XappContextMutationGate(val reason: String?) {
    ALLOW(null),
    BLOCK_PLUGIN_DISABLED("plugin disabled"),
    BLOCK_GENERATION_MISMATCH("generation mismatch"),
    BLOCK_EDITOR_STALE("editor stale"),
    BLOCK_NOT_EDITABLE("no longer editable")
}

data class XappPromptFieldDefinition(
    val name: String,
    val label: String,
    val type: XappPromptFieldType,
    val defaultValue: Any?,
    val choices: List<String>
)

enum class XappPromptFieldType {
    TEXT,
    BOOLEAN,
    CHOICE;

    companion object {
        fun fromApiName(raw: String?): XappPromptFieldType? = when (raw?.trim()?.lowercase().orEmpty().ifBlank { "text" }) {
            "text" -> TEXT
            "boolean" -> BOOLEAN
            "choice" -> CHOICE
            else -> null
        }
    }
}

interface XappPromptDialogRunner {
    fun promptText(title: String, message: String, defaultValue: String?): String?
    fun promptChoice(title: String, message: String, choices: List<String>, defaultValue: String?): String?
    fun promptFields(title: String, fields: List<XappPromptFieldDefinition>): Map<String, Any?>?
}

object SwingXappPromptDialogRunner : XappPromptDialogRunner {
    override fun promptText(title: String, message: String, defaultValue: String?): String? {
        return onEdt {
            JOptionPane.showInputDialog(null, message, title, JOptionPane.QUESTION_MESSAGE, null, null, defaultValue) as? String
        }
    }

    override fun promptChoice(title: String, message: String, choices: List<String>, defaultValue: String?): String? {
        return onEdt {
            JOptionPane.showInputDialog(null, message, title, JOptionPane.QUESTION_MESSAGE, null, choices.toTypedArray(), defaultValue) as? String
        }
    }

    override fun promptFields(title: String, fields: List<XappPromptFieldDefinition>): Map<String, Any?>? {
        return onEdt {
            val panel = JPanel(java.awt.GridLayout(fields.size, 2, 6, 6))
            val components = LinkedHashMap<String, Any>()
            fields.forEach { field ->
                panel.add(JLabel(field.label))
                val component: Any = when (field.type) {
                    XappPromptFieldType.TEXT -> JTextField(field.defaultValue?.toString().orEmpty(), 24)
                    XappPromptFieldType.BOOLEAN -> JCheckBox().apply { isSelected = field.defaultValue as? Boolean ?: false }
                    XappPromptFieldType.CHOICE -> JComboBox(field.choices.toTypedArray()).apply {
                        selectedItem = field.defaultValue?.toString()?.takeIf { it in field.choices } ?: field.choices.firstOrNull()
                    }
                }
                components[field.name] = component
                panel.add(component as java.awt.Component)
            }
            val result = JOptionPane.showConfirmDialog(null, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
            if (result != JOptionPane.OK_OPTION) {
                null
            } else {
                fields.associate { field ->
                    val value = when (val component = components[field.name]) {
                        is JTextField -> component.text
                        is JCheckBox -> component.isSelected
                        is JComboBox<*> -> component.selectedItem?.toString().orEmpty()
                        else -> null
                    }
                    field.name to value
                }
            }
        }
    }

    private fun <T> onEdt(block: () -> T): T? {
        if (SwingUtilities.isEventDispatchThread()) {
            return block()
        }
        val value = AtomicReference<T?>()
        val error = AtomicReference<Throwable?>()
        try {
            SwingUtilities.invokeAndWait {
                try {
                    value.set(block())
                } catch (ex: Throwable) {
                    error.set(ex)
                }
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        }
        error.get()?.let { throw it }
        return value.get()
    }
}

fun normalizeXappMenuLabelPath(raw: Any?, warnings: MutableList<String>): List<String>? {
    val parts = when (raw) {
        is String -> raw.split('/')
        is Iterable<*> -> raw.map { it?.toString().orEmpty() }
        is Array<*> -> raw.map { it?.toString().orEmpty() }
        null -> emptyList()
        else -> listOf(raw.toString())
    }.map { it.trim() }
    if (parts.isEmpty() || parts.any { it.isBlank() }) {
        warnings.add("invalid label path")
        return null
    }
    if (parts.size > 4) {
        warnings.add("label depth exceeds 4")
        return null
    }
    return parts
}

fun normalizeXappMenuHandlerName(raw: Any?, warnings: MutableList<String>): String? {
    val name = raw?.toString()?.trim().orEmpty()
    if (!Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(name)) {
        warnings.add("invalid handler name")
        return null
    }
    return name
}

object XappContextMenuHub {
    private val definitionsByPlugin = LinkedHashMap<String, List<XappMenuItemDefinition>>()

    @Synchronized
    fun replacePluginDefinitions(pluginId: String, definitions: List<XappMenuItemDefinition>) {
        definitionsByPlugin[pluginId] = definitions.toList()
    }

    @Synchronized
    fun removePluginDefinitions(pluginId: String) {
        definitionsByPlugin.remove(pluginId)
    }

    @Synchronized
    fun matchingDefinitions(snapshot: XappHttpMenuSnapshot): List<XappMenuItemDefinition> {
        return definitionsByPlugin.values.flatten()
            .filter { definition -> definition.matches(snapshot) }
            .sortedWith(compareBy<XappMenuItemDefinition> { it.pluginName.lowercase() }
                .thenBy { it.pluginId.lowercase() }
                .thenBy { it.labelPath.joinToString("/").lowercase() })
    }

    @Synchronized
    fun clearAllForTests() {
        definitionsByPlugin.clear()
    }

    private fun XappMenuItemDefinition.matches(snapshot: XappHttpMenuSnapshot): Boolean {
        if (requiresEditable && !snapshot.editable) return false
        if (contexts.isNotEmpty() && snapshot.messagePart !in contexts) return false
        if (tools.isNotEmpty() && snapshot.tool !in tools) return false
        return true
    }
}

object XappContextMenuInvoker {
    @Volatile
    private var handler: ((XappMenuItemDefinition, XappHttpMenuSnapshot) -> Unit)? = null

    fun setHandler(next: ((XappMenuItemDefinition, XappHttpMenuSnapshot) -> Unit)?) {
        handler = next
    }

    fun invoke(definition: XappMenuItemDefinition, snapshot: XappHttpMenuSnapshot) {
        handler?.invoke(definition, snapshot)
            ?: snapshot.logger("[context-menu-warning] no xapp context-menu handler registered")
    }
}

data class XappMenuTreeNode(
    val label: String,
    val action: XappMenuItemDefinition? = null,
    val children: List<XappMenuTreeNode> = emptyList()
)

private class MutableMenuNode(val label: String) {
    val children = LinkedHashMap<String, MutableMenuNode>()
    val actions = mutableListOf<XappMenuItemDefinition>()
}

fun buildXappMenuTree(definitions: List<XappMenuItemDefinition>): XappMenuTreeNode {
    val root = MutableMenuNode("Xapp")
    val sorted = definitions.sortedWith(compareBy<XappMenuItemDefinition> { it.pluginName.lowercase() }
        .thenBy { it.pluginId.lowercase() }
        .thenBy { it.labelPath.joinToString("/").lowercase() })
    sorted.forEach { definition ->
        var node = root
        definition.labelPath.dropLast(1).forEach { segment ->
            node = node.children.getOrPut(segment) { MutableMenuNode(segment) }
        }
        val leaf = definition.labelPath.last()
        if (node.children.containsKey(leaf)) {
            val submenu = node.children.getValue(leaf)
            val reserved = reservedActionsNode(submenu)
            reserved.actions.add(definition.copy(labelPath = listOf(leaf)))
        } else {
            node.actions.add(definition)
        }
    }
    moveCollidingActions(root)
    return root.toImmutable()
}

private fun moveCollidingActions(node: MutableMenuNode) {
    val iterator = node.actions.iterator()
    val moved = mutableListOf<XappMenuItemDefinition>()
    while (iterator.hasNext()) {
        val action = iterator.next()
        val leaf = action.labelPath.last()
        val child = node.children[leaf]
        if (child != null) {
            reservedActionsNode(child).actions.add(action.copy(labelPath = listOf(leaf)))
            moved.add(action)
            iterator.remove()
        }
    }
    node.children.values.forEach { moveCollidingActions(it) }
}

private fun reservedActionsNode(node: MutableMenuNode): MutableMenuNode {
    val candidates = sequence {
        yield("Actions")
        yield("Plugin Actions")
        var idx = 2
        while (true) {
            yield("Plugin Actions ($idx)")
            idx += 1
        }
    }
    val label = candidates.first { candidate -> candidate !in node.children }
    return node.children.getOrPut(label) { MutableMenuNode(label) }
}

private fun MutableMenuNode.toImmutable(): XappMenuTreeNode {
    val childNodes = children.values.map { it.toImmutable() }.toMutableList()
    val siblingActionLabels = actions.groupingBy { it.labelPath.last() }.eachCount()
    actions.forEach { action ->
        val base = action.labelPath.last()
        val label = if ((siblingActionLabels[base] ?: 0) > 1) "$base (${action.pluginName})" else base
        childNodes.add(XappMenuTreeNode(label = label, action = action))
    }
    return XappMenuTreeNode(label = label, children = childNodes.sortedBy { it.label.lowercase() })
}

class XappHttpMenuContext(
    private val definition: XappMenuItemDefinition,
    private val snapshot: XappHttpMenuSnapshot,
    private val generationChecker: (String, Long) -> XappContextMutationGate
) {
    val codec: XappCodecHelper = XappCodecHelper()
    val tool: String get() = snapshot.tool.apiName
    val message_part: String get() = snapshot.messagePart.apiName
    val editable: Boolean get() = snapshot.editable
    val selection_start: Int get() = snapshot.selectionStart
    val selection_end: Int get() = snapshot.selectionEnd
    val selected_text: String get() = snapshot.selectedText
    val request_raw: String get() = snapshot.requestRaw
    val response_raw: String get() = snapshot.responseRaw
    val plugin_id: String get() = definition.pluginId
    val plugin_name: String get() = definition.pluginName
    val request: XappHttpRequest = XappHttpRequest.fromRaw(snapshot.requestRaw)
    val response: XappHttpResponse = XappHttpResponse.fromRaw(snapshot.responseRaw)

    fun log(message: String) = snapshot.logger(message)

    fun copy_to_clipboard(text: String) = snapshot.clipboardSink(text)

    @JvmOverloads
    fun send_to_fuzzer(requestRaw: String? = null) {
        snapshot.sendToFuzzer?.invoke(requestRaw ?: request.toRaw())
    }

    @JvmOverloads
    fun send_to_codec(text: String, tabTitle: String? = null) {
        snapshot.sendToCodec?.invoke(text, tabTitle)
    }

    @JvmOverloads
    fun apply_request(raw: String? = null): Boolean {
        val next = raw ?: return runCatching { request.toRaw() }.getOrElse {
            snapshot.logger("[context-menu-error] request serialization failed: ${it.message ?: it.javaClass.simpleName}")
            return false
        }.let { applyRequestRaw(it) }
        return applyRequestRaw(next)
    }

    @JvmOverloads
    fun apply_response(raw: String? = null): Boolean {
        val next = raw ?: return runCatching { response.toRaw() }.getOrElse {
            snapshot.logger("[context-menu-error] response serialization failed: ${it.message ?: it.javaClass.simpleName}")
            return false
        }.let { applyResponseRaw(it) }
        return applyResponseRaw(next)
    }

    fun replace_selection(text: String): Boolean {
        if (snapshot.selectionEnd <= snapshot.selectionStart || snapshot.selectionStart < 0 || snapshot.selectionEnd > snapshot.activeText.length) {
            snapshot.logger("[context-menu-warning] no selection to replace")
            return false
        }
        val updated = snapshot.activeText.substring(0, snapshot.selectionStart) + text + snapshot.activeText.substring(snapshot.selectionEnd)
        return when (snapshot.messagePart) {
            XappMenuMessagePart.REQUEST -> applyRequestRaw(updated)
            XappMenuMessagePart.RESPONSE -> applyResponseRaw(updated)
            XappMenuMessagePart.UNKNOWN -> false
        }
    }

    @JvmOverloads
    fun prompt_text(title: String, message: String, default: String? = null): String? {
        return promptSafely { snapshot.promptRunner.promptText(title, message, default) }
    }

    @JvmOverloads
    fun prompt_choice(title: String, message: String, choices: List<String>, default: String? = null): String? {
        if (choices.isEmpty() || (default != null && default !in choices)) {
            snapshot.logger("[context-menu-warning] invalid prompt schema")
            return null
        }
        return promptSafely { snapshot.promptRunner.promptChoice(title, message, choices, default) }
    }

    fun prompt_fields(title: String, fields: List<Map<String, Any?>>): Map<String, Any?>? {
        val normalized = normalizePromptFields(fields) ?: return null
        return promptSafely { snapshot.promptRunner.promptFields(title, normalized) }
    }

    private fun applyRequestRaw(raw: String): Boolean {
        val gate = mutationGate(request = true)
        if (gate != XappContextMutationGate.ALLOW) return blockMutation(gate)
        return snapshot.requestMutation?.invoke(raw) ?: blockMutation(XappContextMutationGate.BLOCK_NOT_EDITABLE)
    }

    private fun applyResponseRaw(raw: String): Boolean {
        val gate = mutationGate(request = false)
        if (gate != XappContextMutationGate.ALLOW) return blockMutation(gate)
        return snapshot.responseMutation?.invoke(raw) ?: blockMutation(XappContextMutationGate.BLOCK_NOT_EDITABLE)
    }

    private fun mutationGate(request: Boolean): XappContextMutationGate {
        val generationGate = generationChecker(definition.pluginId, definition.generation)
        if (generationGate != XappContextMutationGate.ALLOW) return generationGate
        if (!snapshot.editable) return XappContextMutationGate.BLOCK_NOT_EDITABLE
        val stale = if (request) snapshot.currentRequestHashProvider() != snapshot.requestTextHash else snapshot.currentResponseHashProvider() != snapshot.responseTextHash
        if (stale) return XappContextMutationGate.BLOCK_EDITOR_STALE
        return XappContextMutationGate.ALLOW
    }

    private fun blockMutation(gate: XappContextMutationGate): Boolean {
        snapshot.logger("[context-menu-warning] skipped stale context-menu mutation: ${gate.reason ?: "unknown"}")
        return false
    }

    private fun <T> promptSafely(block: () -> T?): T? {
        return try {
            block()
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            snapshot.logger("[context-menu-error] prompt failed: ${ex.message ?: ex.javaClass.simpleName}")
            null
        } catch (ex: Throwable) {
            snapshot.logger("[context-menu-error] prompt failed: ${ex.message ?: ex.javaClass.simpleName}")
            null
        }
    }

    private fun normalizePromptFields(fields: List<Map<String, Any?>>): List<XappPromptFieldDefinition>? {
        if (fields.isEmpty() || fields.size > 8) {
            snapshot.logger("[context-menu-warning] invalid prompt schema")
            return null
        }
        val result = fields.mapNotNull { field ->
            val name = field["name"]?.toString()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val type = XappPromptFieldType.fromApiName(field["type"]?.toString()) ?: return@mapNotNull null
            val choices = when (val rawChoices = field["choices"]) {
                is Iterable<*> -> rawChoices.map { it?.toString().orEmpty() }.filter { it.isNotBlank() }
                is Array<*> -> rawChoices.map { it?.toString().orEmpty() }.filter { it.isNotBlank() }
                null -> emptyList()
                else -> emptyList()
            }
            if (type == XappPromptFieldType.CHOICE && choices.isEmpty()) return@mapNotNull null
            val defaultValue = field["default"]
            if (type == XappPromptFieldType.CHOICE && defaultValue != null && defaultValue.toString() !in choices) return@mapNotNull null
            XappPromptFieldDefinition(
                name = name,
                label = field["label"]?.toString()?.takeIf { it.isNotBlank() } ?: name,
                type = type,
                defaultValue = defaultValue,
                choices = choices
            )
        }
        if (result.size != fields.size) {
            snapshot.logger("[context-menu-warning] invalid prompt schema")
            return null
        }
        return result
    }
}

class XappContextMenuRegistrationApi(
    private val pluginId: String,
    private val pluginName: String,
    private val generation: Long,
    private val warnings: MutableList<String>
) {
    private val collected = mutableListOf<XappMenuItemDefinition>()
    private val seen = LinkedHashSet<String>()

    val definitions: List<XappMenuItemDefinition> get() = collected.toList()

    @JvmOverloads
    fun add_menu_item(
        label: Any?,
        contexts: Any? = null,
        tools: Any? = null,
        requires_editable: Boolean = false,
        handler: Any? = null
    ) {
        val labelPath = normalizeXappMenuLabelPath(label, warnings) ?: return
        val handlerName = normalizeXappMenuHandlerName(handler, warnings) ?: return
        val contextSet = normalizeMessageParts(contexts)
        val toolSet = normalizeTools(tools)
        val dedupeKey = listOf(pluginId, labelPath.joinToString("/"), handlerName).joinToString("\u0000")
        if (!seen.add(dedupeKey)) return
        collected.add(
            XappMenuItemDefinition(
                pluginId = pluginId,
                pluginName = pluginName,
                labelPath = labelPath,
                contexts = contextSet,
                tools = toolSet,
                requiresEditable = requires_editable,
                handlerName = handlerName,
                generation = generation
            )
        )
    }

    private fun normalizeMessageParts(raw: Any?): Set<XappMenuMessagePart> {
        return normalizeStrings(raw)
            .map { XappMenuMessagePart.fromApiName(it) }
            .filter { it != XappMenuMessagePart.UNKNOWN }
            .toSet()
    }

    private fun normalizeTools(raw: Any?): Set<HttpViewerToolContext> {
        return normalizeStrings(raw)
            .map { HttpViewerToolContext.fromApiName(it) }
            .filter { it != HttpViewerToolContext.UNKNOWN }
            .toSet()
    }

    private fun normalizeStrings(raw: Any?): List<String> {
        return when (raw) {
            null -> emptyList()
            is String -> listOf(raw)
            is Iterable<*> -> raw.map { it?.toString().orEmpty() }
            is Array<*> -> raw.map { it?.toString().orEmpty() }
            else -> listOf(raw.toString())
        }.map { it.trim().lowercase(Locale.getDefault()) }.filter { it.isNotBlank() }
    }
}
