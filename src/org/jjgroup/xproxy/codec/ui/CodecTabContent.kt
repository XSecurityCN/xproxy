package org.jjgroup.xproxy.codec.ui

import org.jjgroup.xproxy.codec.core.CodecOperationDescriptor
import org.jjgroup.xproxy.codec.core.CodecRecipeEngine
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.ListCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeSelectionModel
import java.awt.datatransfer.StringSelection

data class CodecTabContentLayout(
    val root: JPanel,
    val inputArea: JTextArea,
    val recipeList: JList<String>,
    val recipeModel: DefaultListModel<String>,
    val outputArea: JTextArea,
    val statusLabel: JLabel,
    val operationTree: JTree
)

object CodecTabContentFactory {
    fun buildContentPanel(
        operationCatalog: List<CodecOperationDescriptor>,
        input: String,
        rules: List<String>,
        operationTreeTransferHandler: javax.swing.TransferHandler,
        recipeListTransferHandler: javax.swing.TransferHandler
    ): CodecTabContentLayout {
        val panel = JPanel(BorderLayout())
        val operationRoot = DefaultMutableTreeNode(I18n.t("codec.operations"))
        operationCatalog
            .groupBy { it.category }
            .toSortedMap()
            .forEach { (category, operations) ->
                val categoryNode = DefaultMutableTreeNode(category)
                operations
                    .map { it.name }
                    .sorted()
                    .forEach { operationName ->
                        categoryNode.add(DefaultMutableTreeNode(operationName, false))
                    }
                operationRoot.add(categoryNode)
            }
        val operationTree = JTree(operationRoot).apply {
            isRootVisible = false
            showsRootHandles = true
            dragEnabled = true
            transferHandler = operationTreeTransferHandler
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        }
        for (row in 0 until operationTree.rowCount) {
            operationTree.expandRow(row)
        }

        val recipeModel = DefaultListModel<String>()
        rules.forEach { recipeModel.addElement(it) }
        val recipeList = JList(recipeModel).apply {
            visibleRowCount = 10
            selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
            dragEnabled = true
            dropMode = javax.swing.DropMode.INSERT
            transferHandler = recipeListTransferHandler
        }
        recipeList.cellRenderer = buildRecipeCellRenderer()

        val inputArea = JTextArea(input).apply {
            lineWrap = true
            wrapStyleWord = true
        }

        val outputArea = JTextArea().apply {
            isEditable = true
            lineWrap = true
            wrapStyleWord = true
        }
        installOutputContextMenu(outputArea)

        val statusLabel = JLabel(I18n.t("codec.ready"))
        I18nBinder.bindText(statusLabel, "codec.ready")

        val operationsHint = JLabel(I18n.t("codec.operations_hint"))
        I18nBinder.bindText(operationsHint, "codec.operations_hint")
        val operationPane = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("codec.operations")),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            )
            I18nBinder.bind {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(I18n.t("codec.operations")),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)
                )
                repaint()
            }
            add(operationsHint, BorderLayout.NORTH)
            add(JScrollPane(operationTree), BorderLayout.CENTER)
        }

        val recipeHint = JLabel(I18n.t("codec.recipe_hint"))
        I18nBinder.bindText(recipeHint, "codec.recipe_hint")
        val recipePane = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("codec.recipe")),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            )
            I18nBinder.bind {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(I18n.t("codec.recipe")),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)
                )
                repaint()
            }
            add(recipeHint, BorderLayout.NORTH)
            add(JScrollPane(recipeList), BorderLayout.CENTER)
        }

        val ioSplit = JSplitPane(JSplitPane.VERTICAL_SPLIT, JScrollPane(inputArea), JScrollPane(outputArea)).apply {
            resizeWeight = 0.5
            setDividerLocation(0.5)
        }

        val ioPane = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(I18n.t("codec.input_output")),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)
            )
            I18nBinder.bind {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(I18n.t("codec.input_output")),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)
                )
                repaint()
            }
            add(ioSplit, BorderLayout.CENTER)
        }

        val leftSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, operationPane, recipePane).apply {
            resizeWeight = 0.50
            setDividerLocation(0.50)
        }

        val mainSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, ioPane).apply {
            resizeWeight = 0.50
            setDividerLocation(0.50)
        }

        panel.add(mainSplit, BorderLayout.CENTER)
        panel.add(statusLabel, BorderLayout.SOUTH)

        return CodecTabContentLayout(
            root = panel,
            inputArea = inputArea,
            recipeList = recipeList,
            recipeModel = recipeModel,
            outputArea = outputArea,
            statusLabel = statusLabel,
            operationTree = operationTree
        )
    }

    private fun buildRecipeCellRenderer(): ListCellRenderer<String> =
        object : ListCellRenderer<String> {
            private val rowPanel = JPanel(BorderLayout(8, 0))
            private val nameLabel = JLabel()
            private val removeLabel = JLabel("\u2212")

            init {
                rowPanel.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
                rowPanel.add(nameLabel, BorderLayout.CENTER)
                rowPanel.add(removeLabel, BorderLayout.EAST)
                removeLabel.horizontalAlignment = JLabel.CENTER
                removeLabel.preferredSize = Dimension(16, 16)
                removeLabel.foreground = Color(180, 70, 70)
            }

            override fun getListCellRendererComponent(
                list: JList<out String>?,
                value: String?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val token = value.orEmpty()
                val name = tokenName(token)
                val hasConfig = tokenConfig(token).isNotEmpty()
                nameLabel.text = if (hasConfig) "$name \u2699" else name
                val bg = if (isSelected) (list?.selectionBackground ?: Color(210, 230, 255)) else (list?.background ?: Color.WHITE)
                val fg = if (isSelected) (list?.selectionForeground ?: Color.BLACK) else (list?.foreground ?: Color.BLACK)
                rowPanel.background = bg
                rowPanel.isOpaque = true
                nameLabel.foreground = fg
                removeLabel.foreground = if (isSelected) Color(220, 80, 80) else Color(180, 70, 70)
                return rowPanel
            }
        }

    fun installOutputContextMenu(outputArea: JTextArea) {
        val menu = JPopupMenu()
        val copyItem = JMenuItem(I18n.t("menu.copy"))
        copyItem.addActionListener {
            val selected = outputArea.selectedText?.takeIf { it.isNotBlank() }
            val payload = selected ?: outputArea.text
            if (payload.isNotBlank()) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(payload), null)
            }
        }
        menu.add(copyItem)
        menu.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {
                val selected = outputArea.selectedText?.takeIf { it.isNotBlank() }
                copyItem.isEnabled = !(selected ?: outputArea.text).isNullOrBlank()
            }

            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {
            }

            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {
            }
        })
        outputArea.componentPopupMenu = menu
    }

    fun tokenName(token: String): String =
        token.substringBefore(";;").trim()

    fun tokenConfig(token: String): Map<String, String> {
        val segments = token.split(";;")
        if (segments.size <= 1) {
            return emptyMap()
        }
        val config = LinkedHashMap<String, String>()
        for (index in 1 until segments.size) {
            val segment = segments[index]
            val equalsIndex = segment.indexOf('=')
            if (equalsIndex <= 0) {
                continue
            }
            val key = segment.substring(0, equalsIndex).trim()
            val value = segment.substring(equalsIndex + 1).trim()
            if (key.isNotBlank()) {
                config[key] = value
            }
        }
        return config
    }

    fun buildRuleToken(name: String, config: Map<String, String>): String {
        if (config.isEmpty()) {
            return name.trim()
        }
        val payload = config.entries.joinToString(";;") { "${it.key}=${it.value}" }
        return "${name.trim()};;$payload"
    }

    fun parseConfigText(text: String): Map<String, String> {
        val config = LinkedHashMap<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val equalsIndex = line.indexOf('=')
                if (equalsIndex <= 0) {
                    return@forEach
                }
                val key = line.substring(0, equalsIndex).trim()
                val value = line.substring(equalsIndex + 1).trim()
                if (key.isNotBlank()) {
                    config[key] = value
                }
            }
        return config
    }

    fun defaultConfigForOperation(operationName: String): Map<String, String> =
        when (operationName.lowercase()) {
            "to base64", "base64 encode" -> mapOf("urlSafe" to "false")
            "from base64", "base64 decode" -> mapOf("urlSafe" to "false")
            "to hex", "hex encode" -> mapOf("delimiter" to "None")
            "from hex", "hex decode" -> mapOf("delimiter" to "None")
            "url encode" -> mapOf("encodeAll" to "false")
            "hmac", "hmac sha1", "hmac sha256", "hmac sha512" -> mapOf("key" to "", "algorithm" to "SHA-256", "output" to "hex")
            "aes encrypt" -> mapOf("key" to "", "mode" to "ECB", "iv" to "", "output" to "Base64")
            "aes decrypt" -> mapOf("key" to "", "mode" to "ECB", "iv" to "", "input" to "Base64")
            "jwt decode" -> emptyMap()
            else -> emptyMap()
        }

    fun configHintForOperation(operationName: String): String =
        when (operationName.lowercase()) {
            "to base64", "base64 encode", "from base64", "base64 decode" -> "urlSafe=true|false"
            "url encode" -> "encodeAll=true|false"
            "to hex", "hex encode", "from hex", "hex decode" -> "delimiter=None|Space|Comma|Colon|Semi-colon|Line feed|CRLF|0x|\\x"
            "hmac", "hmac sha1", "hmac sha256", "hmac sha512" -> "key=<secret>, algorithm=SHA-1|SHA-256|SHA-512, output=hex|base64"
            "aes encrypt" -> "key=<16|24|32-byte UTF-8>, mode=ECB|CBC, iv=<16-byte UTF-8 for CBC>, output=Base64|Hex"
            "aes decrypt" -> "key=<16|24|32-byte UTF-8>, mode=ECB|CBC, iv=<16-byte UTF-8 for CBC>, input=Base64|Hex"
            else -> "one key=value per line"
        }

    fun operationNameFromLabel(label: String): String {
        val trimmed = label.trim()
        if (!trimmed.startsWith("[")) {
            return tokenName(trimmed)
        }
        val closeIndex = trimmed.indexOf(']')
        if (closeIndex <= 0 || closeIndex + 1 >= trimmed.length) {
            return tokenName(trimmed)
        }
        return trimmed.substring(closeIndex + 1).trim()
    }

    fun modelRules(model: DefaultListModel<String>): List<String> {
        val rules = ArrayList<String>(model.size)
        for (index in 0 until model.size) {
            val value = model.getElementAt(index).trim()
            if (value.isNotBlank()) {
                rules.add(value)
            }
        }
        return rules
    }
}
