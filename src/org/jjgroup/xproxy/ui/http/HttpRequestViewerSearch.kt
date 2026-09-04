package org.jjgroup.xproxy.ui.http

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.settings.core.ResponsePrettySettings
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.SearchContext
import org.fife.ui.rtextarea.SearchEngine
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
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
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class SearchGlyphIcon : javax.swing.Icon {
    override fun getIconWidth(): Int = 12
    override fun getIconHeight(): Int = 12

    override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
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
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.color = Color(150, 150, 156)
        g2.font = font
        val fm = g2.fontMetrics
        val x = insets.left + 2
        val y = (height - fm.height) / 2 + fm.ascent
        g2.drawString(placeholder, x, y)
        g2.dispose()
    }
}

internal class SideSearchController(
    private val areas: List<RSyntaxTextArea>,
    private val activeAreaProvider: () -> RSyntaxTextArea
) {
    val panel = JPanel(BorderLayout())
    private val queryField = PlaceholderTextField(1, I18n.t("search.current_pane"))
    private val statusLabel = JLabel(I18n.t("search.highlights", "count" to 0))
    private val settingsButton = JButton("\u2699")
    private val previousButton = JButton("\u2190")
    private val nextButton = JButton("\u2192")
    private var regexMode = false
    private var matchCase = false
    @Volatile private var pendingRefresh = false

    init {
        settingsButton.margin = java.awt.Insets(0, 5, 0, 5)
        previousButton.margin = java.awt.Insets(0, 6, 0, 6)
        nextButton.margin = java.awt.Insets(0, 6, 0, 6)
        statusLabel.horizontalAlignment = JLabel.RIGHT
        I18nBinder.bind { queryField.placeholder = I18n.t("search.current_pane"); queryField.repaint() }

        val optionsMenu = JPopupMenu().apply {
            val textModeItem = JRadioButtonMenuItem(I18n.t("search.text"), true)
            val regexModeItem = JRadioButtonMenuItem(I18n.t("search.regex"), false)
            val modeGroup = ButtonGroup()
            modeGroup.add(textModeItem)
            modeGroup.add(regexModeItem)
            add(textModeItem)
            add(regexModeItem)
            addSeparator()
            val matchCaseItem = JCheckBoxMenuItem(I18n.t("search.match_case"), false)
            add(matchCaseItem)

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
        queryField.toolTipText = I18n.t("search.current_pane")
        I18nBinder.bind { queryField.toolTipText = I18n.t("search.current_pane") }
        queryField.addActionListener { jumpToMatch(true) }
        queryField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleRefresh()
            override fun removeUpdate(e: DocumentEvent?) = scheduleRefresh()
            override fun changedUpdate(e: DocumentEvent?) = scheduleRefresh()
        })
        val searchIcon = JLabel(SearchGlyphIcon()).apply {
            border = BorderFactory.createEmptyBorder(2, 2, 2, 8)
        }
        fieldShell.add(queryField, BorderLayout.CENTER)
        fieldShell.add(searchIcon, BorderLayout.EAST)

        val row = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }
        val c = GridBagConstraints().apply {
            gridy = 0
            insets = java.awt.Insets(2, 6, 2, 6)
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
        val active = activeAreaProvider.invoke()
        areas.filter { it !== active }.forEach { clearHighlights(it) }
        if (keyword.isBlank()) {
            clearHighlights(active)
            updateState(I18n.t("search.highlights", "count" to 0), hasQuery = false, hasMatches = false)
            return
        }

        if (isLargeViewerArea(active)) {
            clearHighlights(active)
            updateState("Search ready (highlight-all disabled for large view)", hasQuery = true, hasMatches = true)
            return
        }

        val result = runCatching {
            SearchEngine.markAll(active, buildContext(keyword, searchForward = true, markAll = true))
        }.getOrNull()

        if (result == null) {
            clearHighlights(active)
            updateState("Invalid pattern", hasQuery = true, hasMatches = false)
            return
        }

        val count = result.markedCount
        updateState(I18n.t("search.highlights", "count" to count), hasQuery = true, hasMatches = count > 0)
    }

    // 每次按键都触发 DocumentListener;dirty-flag + invokeLater 合并同一 EDT 周期内的多次按键为一次 markAll(非 Timer)。
    private fun scheduleRefresh() {
        if (pendingRefresh) return
        pendingRefresh = true
        SwingUtilities.invokeLater {
            if (pendingRefresh) {
                pendingRefresh = false
                refreshHighlights()
            }
        }
    }

    fun jumpToMatch(forward: Boolean) {
        val keyword = queryField.text ?: ""
        if (keyword.isBlank()) {
            Toolkit.getDefaultToolkit().beep()
            return
        }
        val area = activeAreaProvider.invoke()
        val result = runCatching {
            SearchEngine.find(area, buildContext(keyword, searchForward = forward, markAll = false))
        }.getOrNull()

        if (result == null) {
            updateState("Invalid pattern", hasQuery = true, hasMatches = false)
            Toolkit.getDefaultToolkit().beep()
            return
        }

        if (!result.wasFound()) {
            Toolkit.getDefaultToolkit().beep()
        }
        refreshHighlights()
        if (result.wasFound()) {
            val progress = computeMatchProgress(area, keyword)
            if (progress != null) {
                val (current, total) = progress
        updateState(I18n.t("search.highlights_position", "current" to current, "total" to total), hasQuery = true, hasMatches = total > 0)
            }
        }
    }

    private fun isLargeViewerArea(area: RSyntaxTextArea): Boolean {
        if (area.getClientProperty("xproxy.large-viewer-mode") == true) {
            return true
        }
        val hint = area.getClientProperty("xproxy.highlight-size-hint") as? Int
        val threshold = ResponsePrettySettings.getAutoHighlightMaxBytes().coerceAtLeast(1024)
        return hint != null && hint > threshold
    }

    private fun buildContext(keyword: String, searchForward: Boolean, markAll: Boolean): SearchContext =
        SearchContext().apply {
            searchFor = keyword
            this.searchForward = searchForward
            searchWrap = true
            isRegularExpression = regexMode
            this.matchCase = this@SideSearchController.matchCase
            this.markAll = markAll
        }

    private fun clearHighlights(area: RSyntaxTextArea) {
        val clearContext = SearchContext().apply {
            searchFor = ""
            markAll = true
        }
        SearchEngine.markAll(area, clearContext)
    }

    private fun updateState(statusText: String, hasQuery: Boolean, hasMatches: Boolean) {
        statusLabel.text = statusText
        previousButton.isEnabled = hasQuery && hasMatches
        nextButton.isEnabled = hasQuery && hasMatches
    }

    private fun computeMatchProgress(area: RSyntaxTextArea, keyword: String): Pair<Int, Int>? {
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
