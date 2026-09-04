package org.jjgroup.xproxy.ui.table

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.SearchContext
import org.fife.ui.rtextarea.SearchEngine

internal class SearchGlyphIcon : javax.swing.Icon {
    override fun getIconWidth(): Int = 12
    override fun getIconHeight(): Int = 12

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color(120, 120, 126)
        g2.drawOval(x + 1, y + 1, 7, 7)
        g2.drawLine(x + 7, y + 7, x + 11, y + 11)
        g2.dispose()
    }
}

internal class PlaceholderTextField(
    columns: Int,
    internal var placeholder: String
) : JTextField(columns) {
    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (text.isNotEmpty()) {
            return
        }
        (g.create() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = Color(150, 150, 156)
            font = this@PlaceholderTextField.font
            val fm = fontMetrics
            val x = insets.left + 2
            val y = (height - fm.height) / 2 + fm.ascent
            drawString(placeholder, x, y)
            dispose()
        }
    }
}

internal class PaneSearchController(
    private val area: RSyntaxTextArea
) {
    val panel = JPanel(BorderLayout())
    private val queryField = PlaceholderTextField(1, "Search current pane")
    private val statusLabel = JLabel("0 highlights")
    private val settingsButton = JButton("\u2699")
    private val previousButton = JButton("\u2190")
    private val nextButton = JButton("\u2192")
    private var regexMode = false
    private var matchCase = false

    init {
        settingsButton.margin = Insets(0, 5, 0, 5)
        previousButton.margin = Insets(0, 6, 0, 6)
        nextButton.margin = Insets(0, 6, 0, 6)
        statusLabel.horizontalAlignment = JLabel.RIGHT

        val optionsMenu = JPopupMenu()
        val textModeItem = JRadioButtonMenuItem("Text", true)
        val regexModeItem = JRadioButtonMenuItem("Regex", false)
        val modeGroup = ButtonGroup()
        modeGroup.add(textModeItem)
        modeGroup.add(regexModeItem)
        optionsMenu.add(textModeItem)
        optionsMenu.add(regexModeItem)
        optionsMenu.addSeparator()
        val matchCaseItem = JCheckBoxMenuItem("Match case", false)
        optionsMenu.add(matchCaseItem)

        textModeItem.addActionListener {
            regexMode = false
            refreshHighlights()
        }
        regexModeItem.addActionListener {
            regexMode = true
            refreshHighlights()
        }
        matchCaseItem.addActionListener {
            matchCase = matchCaseItem.isSelected
            refreshHighlights()
        }

        settingsButton.addActionListener {
            optionsMenu.show(settingsButton, 0, settingsButton.height)
        }
        previousButton.addActionListener { jumpToMatch(false) }
        nextButton.addActionListener { jumpToMatch(true) }

        val fieldShell = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createLineBorder(Color(198, 198, 204), 1)
        }
        queryField.border = BorderFactory.createEmptyBorder(2, 8, 2, 6)
        queryField.addActionListener { jumpToMatch(true) }
        queryField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshHighlights()
            override fun removeUpdate(e: DocumentEvent?) = refreshHighlights()
            override fun changedUpdate(e: DocumentEvent?) = refreshHighlights()
        })
        val searchIcon = JLabel(SearchGlyphIcon()).apply {
            border = BorderFactory.createEmptyBorder(2, 2, 2, 8)
        }
        fieldShell.add(queryField, BorderLayout.CENTER)
        fieldShell.add(searchIcon, BorderLayout.EAST)

        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshHighlights()
            override fun removeUpdate(e: DocumentEvent?) = refreshHighlights()
            override fun changedUpdate(e: DocumentEvent?) = refreshHighlights()
        })

        val row = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }
        val c = GridBagConstraints().apply {
            gridy = 0
            insets = Insets(2, 6, 2, 6)
        }

        c.gridx = 0
        c.weightx = 0.0
        c.fill = GridBagConstraints.NONE
        row.add(settingsButton, c)

        c.gridx = 1
        c.weightx = 1.0
        c.fill = GridBagConstraints.HORIZONTAL
        row.add(fieldShell, c)

        c.gridx = 2
        c.weightx = 0.0
        c.fill = GridBagConstraints.NONE
        row.add(previousButton, c)

        c.gridx = 3
        row.add(nextButton, c)

        c.gridx = 4
        c.anchor = GridBagConstraints.EAST
        row.add(statusLabel, c)

        panel.border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(214, 214, 218))
        panel.add(row, BorderLayout.CENTER)
        refreshHighlights()
    }

    fun refreshHighlights() {
        val keyword = queryField.text ?: ""
        if (keyword.isBlank()) {
            clearHighlights()
            updateState("0 highlights", hasQuery = false, hasMatches = false)
            return
        }
        val result = runCatching {
            SearchEngine.markAll(area, buildContext(keyword, searchForward = true, markAll = true))
        }.getOrNull()

        if (result == null) {
            clearHighlights()
            updateState("Invalid pattern", hasQuery = true, hasMatches = false)
            return
        }
        val count = result.markedCount
        updateState("$count highlights", hasQuery = true, hasMatches = count > 0)
    }

    fun jumpToMatch(forward: Boolean) {
        val keyword = queryField.text ?: ""
        if (keyword.isBlank()) {
            Toolkit.getDefaultToolkit().beep()
            return
        }
        val result = runCatching {
            SearchEngine.find(area, buildContext(keyword, searchForward = forward, markAll = false))
        }.getOrNull()

        if (result == null || !result.wasFound()) {
            Toolkit.getDefaultToolkit().beep()
        }
        refreshHighlights()
        if (result?.wasFound() == true) {
            val progress = computeMatchProgress(keyword)
            if (progress != null) {
                val (current, total) = progress
                updateState("$current/$total highlights", hasQuery = true, hasMatches = total > 0)
            }
        }
    }

    private fun buildContext(keyword: String, searchForward: Boolean, markAll: Boolean): SearchContext {
        val context = SearchContext()
        context.searchFor = keyword
        context.searchForward = searchForward
        context.searchWrap = true
        context.isRegularExpression = regexMode
        context.matchCase = matchCase
        context.markAll = markAll
        return context
    }

    private fun clearHighlights() {
        val clearContext = SearchContext()
        clearContext.searchFor = ""
        clearContext.markAll = true
        SearchEngine.markAll(area, clearContext)
    }

    private fun updateState(statusText: String, hasQuery: Boolean, hasMatches: Boolean) {
        statusLabel.text = statusText
        previousButton.isEnabled = hasQuery && hasMatches
        nextButton.isEnabled = hasQuery && hasMatches
    }

    private fun computeMatchProgress(keyword: String): Pair<Int, Int>? {
        val text = area.text ?: return null
        val starts = if (regexMode) {
            val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            runCatching { Regex(keyword, options).findAll(text).map { it.range.first }.toList() }.getOrNull()
                ?: return null
        } else {
            val haystack = if (matchCase) text else text.lowercase()
            val needle = if (matchCase) keyword else keyword.lowercase()
            if (needle.isEmpty()) {
                emptyList()
            } else {
                val result = mutableListOf<Int>()
                var from = 0
                while (true) {
                    val idx = haystack.indexOf(needle, from)
                    if (idx < 0) break
                    result.add(idx)
                    from = idx + 1
                }
                result
            }
        }

        if (starts.isEmpty()) {
            return null
        }
        val selectedStart = area.selectionStart
        val current = starts.indexOf(selectedStart).let { if (it >= 0) it + 1 else 1 }
        return current to starts.size
    }
}
