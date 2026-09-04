package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.core.Settings
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.kits.core.IntruderScriptSync
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.fuzzer.ui.ComboBoxRenderer
import org.jjgroup.xproxy.fuzzer.ui.DirectoryItem

import org.fife.rsta.ui.search.FindDialog
import org.fife.rsta.ui.search.SearchEvent
import org.fife.rsta.ui.search.SearchListener
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.Theme
import org.fife.ui.rtextarea.RTextScrollPane
import org.fife.ui.rtextarea.SearchEngine
import java.awt.Desktop
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.TreeMap
import javax.swing.*

data class ScriptEditorBundle(
    val textEditor: RSyntaxTextArea,
    val scrollableTextEditor: RTextScrollPane,
    val codeCombo: JComboBox<Any>,
    val loadDirectoryButton: JButton,
    val saveButton: JButton
)

private fun isVisibleScriptPath(relativePath: Path): Boolean {
    return relativePath.none { part ->
        val name = part.toString()
        name.startsWith(".") || name == "__pycache__"
    }
}

private fun listTopLevelScriptFiles(dir: Path): List<DirectoryItem> {
    if (!Files.isDirectory(dir)) {
        return emptyList()
    }
    return Files.list(dir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().endsWith(".py", ignoreCase = true) }
            .map { path ->
                val relative = dir.relativize(path).toString().replace(File.separatorChar, '/')
                DirectoryItem(path.toString(), relative)
            }
            .sorted(compareBy { it.name.lowercase() })
            .toList()
    }
}

private fun listTopLevelScriptFolders(dir: Path): List<Path> {
    if (!Files.isDirectory(dir)) {
        return emptyList()
    }
    return Files.list(dir).use { stream ->
        stream
            .filter { Files.isDirectory(it) }
            .filter { !it.fileName.toString().startsWith(".") }
            .sorted(compareBy<Path> { it.fileName.toString().lowercase() })
            .toList()
    }
}

private fun listScriptsInFolder(root: Path, folder: Path): List<DirectoryItem> {
    if (!Files.isDirectory(folder)) {
        return emptyList()
    }
    return Files.walk(folder).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().endsWith(".py", ignoreCase = true) }
            .filter { path -> isVisibleScriptPath(root.relativize(path)) }
            .map { path ->
                val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
                DirectoryItem(path.toString(), relative)
            }
            .sorted(compareBy { it.name.lowercase() })
            .toList()
    }
}

private fun selectScriptInCombo(codeCombo: JComboBox<Any>, item: DirectoryItem) {
    val existingIndex = (0 until codeCombo.itemCount).firstOrNull { index ->
        val existing = codeCombo.getItemAt(index)
        existing is DirectoryItem && existing.name == item.name && existing.fullPath == item.fullPath
    }
    if (existingIndex != null) {
        codeCombo.selectedIndex = existingIndex
    } else {
        codeCombo.addItem(item)
        codeCombo.selectedItem = item
    }
}

private fun scriptStateKey(relativePath: String): String {
    return relativePath.replace('\\', '/').lowercase()
}

private fun loadIntruderScriptEnabledStateByKey(projectDataStore: ProjectDataStore?): Map<String, Boolean> {
    return projectDataStore?.loadIntruderAttackScriptStates().orEmpty().associate { it.scriptKey.lowercase() to it.enabled }
}

private fun isScriptEnabled(relativePath: String, enabledStateByKey: Map<String, Boolean>): Boolean {
    return enabledStateByKey[scriptStateKey(relativePath)] != false
}

private fun installHierarchicalScriptMenu(codeCombo: JComboBox<Any>, projectDataStore: ProjectDataStore?): () -> Unit {
    var showing = false

    fun showScriptPopup() {
        if (showing) {
            return
        }
        showing = true
        SwingUtilities.invokeLater {
            val root = IntruderScriptSync.ensurePersistentDir()
            val enabledStateByKey = loadIntruderScriptEnabledStateByKey(projectDataStore)
            val popup = JPopupMenu()
            popup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {}
                override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {
                    showing = false
                }
                override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {
                    showing = false
                }
            })

    val lastCodeItem = JMenuItem(I18n.t("fuzzer.last_code_used"))
            lastCodeItem.addActionListener {
                codeCombo.selectedIndex = 0
            }
            popup.add(lastCodeItem)
            popup.addSeparator()

            val topFiles = listTopLevelScriptFiles(root)
            val visibleTopFiles = topFiles.filter { isScriptEnabled(it.name, enabledStateByKey) }
            for (item in visibleTopFiles) {
                val menuItem = JMenuItem(item.name)
                menuItem.addActionListener {
                    selectScriptInCombo(codeCombo, item)
                }
                popup.add(menuItem)
            }

            val topFolders = listTopLevelScriptFolders(root)
            for (folder in topFolders) {
                val folderMenu = JMenu(folder.fileName.toString())
                val scripts = listScriptsInFolder(root, folder)
                val visibleScripts = scripts.filter { isScriptEnabled(it.name, enabledStateByKey) }
                if (visibleScripts.isEmpty()) {
                val emptyItem = JMenuItem(I18n.t("fuzzer.empty_scripts"))
                    emptyItem.isEnabled = false
                    folderMenu.add(emptyItem)
                } else {
                    for (script in visibleScripts) {
                        val displayName = script.name.removePrefix(folder.fileName.toString() + "/")
                        val scriptItem = JMenuItem(displayName)
                        scriptItem.addActionListener {
                            selectScriptInCombo(codeCombo, script)
                        }
                        folderMenu.add(scriptItem)
                    }
                }
                popup.add(folderMenu)
            }

            if (popup.componentCount <= 2) {
        val empty = JMenuItem(I18n.t("fuzzer.no_scripts"))
                empty.isEnabled = false
                popup.add(empty)
            }
            popup.show(codeCombo, 0, codeCombo.height)
        }
    }

    codeCombo.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_DOWN) {
                showScriptPopup()
                e.consume()
            }
        }
    })

    return ::showScriptPopup
}

private fun listDirectoryItems(dir: Path): List<DirectoryItem> {
    if (!Files.isDirectory(dir)) {
        return emptyList()
    }
    return Files.walk(dir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().endsWith(".py", ignoreCase = true) }
            .filter { path -> isVisibleScriptPath(dir.relativize(path)) }
            .map { path ->
                val relative = dir.relativize(path).toString().replace(File.separatorChar, '/')
                DirectoryItem(path.toString(), relative)
            }
            .sorted(compareBy { it.name.lowercase() })
            .toList()
    }
}

fun createScriptEditorBundle(frame: IntruderFrame, projectDataStore: ProjectDataStore?): ScriptEditorBundle {
    var showScriptPopup: (() -> Unit)? = null
    val codeCombo = object : JComboBox<Any>() {
        override fun setPopupVisible(v: Boolean) {
            if (!v) {
                super.setPopupVisible(false)
            }
        }

        override fun processMouseEvent(e: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                when (e.id) {
                    MouseEvent.MOUSE_PRESSED,
                    MouseEvent.MOUSE_CLICKED -> {
                        e.consume()
                        return
                    }

                    MouseEvent.MOUSE_RELEASED -> {
                        e.consume()
                        showScriptPopup?.invoke()
                        return
                    }
                }
            }
            super.processMouseEvent(e)
        }
    }
    codeCombo.renderer = ComboBoxRenderer(5)
    showScriptPopup = installHierarchicalScriptMenu(codeCombo, projectDataStore)

    val loadDirectoryButton = JButton(I18n.t("fuzzer.open_script_dir"))
    I18nBinder.bindText(loadDirectoryButton, "fuzzer.open_script_dir")
    loadDirectoryButton.addActionListener {
        try {
            val scriptDir = IntruderScriptSync.ensurePersistentDir().toFile()
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(scriptDir)
            }
        } catch (e: Exception) {
            System.err.println("Failed to open script directory: $e")
        }
    }

    javax.swing.text.JTextComponent.removeKeymap("RTextAreaKeymap")
    javax.swing.UIManager.put("RTextAreaUI.inputMap", null)
    javax.swing.UIManager.put("RTextAreaUI.actionMap", null)
    javax.swing.UIManager.put("RSyntaxTextAreaUI.inputMap", null)
    javax.swing.UIManager.put("RSyntaxTextAreaUI.actionMap", null)

    val textEditor = RSyntaxTextArea(20, 60)
    textEditor.isEditable = true
    textEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_PYTHON)
    textEditor.antiAliasingEnabled = true
    textEditor.isAutoIndentEnabled = true
    textEditor.paintTabLines = false
    textEditor.tabSize = 4
    textEditor.tabsEmulated = true
    textEditor.eolMarkersVisible = Settings.getBoolean("show-eol", false)
    textEditor.isWhitespaceVisible = Settings.getBoolean("visible-whitespace", false)

    when {
        UIManager.getLookAndFeel().id.contains("Dar") -> {
            val inputStream = frame.javaClass.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml")
            try {
                val theme = Theme.load(inputStream)
                theme.apply(textEditor)
            } catch (ioe: IOException) {
                Utils.out(ioe.toString())
                ioe.printStackTrace()
            }
        }
        else -> textEditor.highlightCurrentLine = false
    }

    textEditor.font = textEditor.font.deriveFont(Settings.getInt("font-size", 14).toFloat())
    val scrollableTextEditor = RTextScrollPane(textEditor)
    scrollableTextEditor.lineNumbersEnabled = Settings.getBoolean("line-numbers", true)

    val searchListener = object : SearchListener {
        override fun searchEvent(e: SearchEvent) {
            val context = e.searchContext
            val result = SearchEngine.find(textEditor, context)
            if (!result.wasFound() && context.searchForward) {
                textEditor.caretPosition = 0
                SearchEngine.find(textEditor, context)
            } else if (!result.wasFound() && !context.searchForward) {
                textEditor.caretPosition = textEditor.text.length
                SearchEngine.find(textEditor, context)
            }
        }

        override fun getSelectedText(): String {
            return textEditor.selectedText ?: ""
        }
    }

    val findDialog = FindDialog(frame, searchListener)
    textEditor.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
        KeyStroke.getKeyStroke("control F"), "find"
    )
    textEditor.actionMap.put("find", object : AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent) {
            if (!findDialog.isVisible) {
                findDialog.isVisible = true
            } else {
                findDialog.toFront()
            }
        }
    })

    val saveButton = JButton(I18n.t("common.save"))
    I18nBinder.bindText(saveButton, "common.save")
    saveButton.isEnabled = false
    saveButton.addActionListener {
        val comboItem = codeCombo.selectedItem
        if (comboItem is DirectoryItem) {
            try {
                val persistentRoot = IntruderScriptSync.ensurePersistentDir()
                val relativePath = Paths.get(comboItem.name.replace('/', File.separatorChar)).normalize()
                val destinationPath = persistentRoot.resolve(relativePath).normalize()
                Files.createDirectories(destinationPath.parent)
                Files.write(destinationPath, textEditor.text.toByteArray())
            } catch (e: IOException) {
                System.err.println("Failed to write file:$e")
            }
        }
    }

    return ScriptEditorBundle(textEditor, scrollableTextEditor, codeCombo, loadDirectoryButton, saveButton)
}

fun readScriptDirectoriesIntoCombo(codeCombo: JComboBox<Any>, projectDataStore: ProjectDataStore?) {
    codeCombo.removeAllItems()
    codeCombo.addItem(I18n.t("fuzzer.last_attack_script"))
    try {
        IntruderScriptSync.syncBundledToPersistent()
        val enabledStateByKey = loadIntruderScriptEnabledStateByKey(projectDataStore)
        val merged = TreeMap<String, DirectoryItem>(String.CASE_INSENSITIVE_ORDER)
        val persistentItems = listDirectoryItems(IntruderScriptSync.ensurePersistentDir())
        persistentItems.filter { isScriptEnabled(it.name, enabledStateByKey) }.forEach { item ->
            merged[item.name] = item
        }

        for (item in merged.values) {
            codeCombo.addItem(item)
        }

        val scriptsPath = Settings.getString("scriptsPath", "")
        if (scriptsPath.isNotEmpty()) {
            val customDir = File(scriptsPath).toPath().toAbsolutePath().normalize()
            val bundledDir = IntruderScriptSync.bundledDir()
            val persistentDir = IntruderScriptSync.persistentDir()
            if (customDir != bundledDir && customDir != persistentDir) {
                val customItems = listDirectoryItems(customDir)
                if (customItems.isNotEmpty()) {
                    codeCombo.addItem(JSeparator(JSeparator.HORIZONTAL))
                    for (item in customItems) {
                        codeCombo.addItem(item)
                    }
                }
            }
        }
    } catch (e: IOException) {
        System.err.println("Error:$e")
    }
}
