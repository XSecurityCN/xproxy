package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.model.BodyKind
import org.jjgroup.xproxy.fuzzer.model.RequestTabState
import org.jjgroup.xproxy.fuzzer.request.applySyntax
import org.jjgroup.xproxy.fuzzer.request.compactJson
import org.jjgroup.xproxy.fuzzer.request.detectBodyKind
import org.jjgroup.xproxy.fuzzer.request.formatBody
import org.jjgroup.xproxy.fuzzer.request.formatTarget
import org.jjgroup.xproxy.fuzzer.request.parseHeaders
import org.jjgroup.xproxy.fuzzer.request.splitMessage
import org.jjgroup.xproxy.settings.core.CharsetPolicy

import java.awt.KeyboardFocusManager
import javax.swing.JPanel
import javax.swing.SwingUtilities

internal fun updateRequestFromRaw(state: RequestTabState, raw: String) {
    val parsed = splitMessage(raw)
    val headerMap = parseHeaders(parsed.headers)
    val decodedBody = if (parsed.body.isNotEmpty()) {
        CharsetPolicy.decodeBodyForDisplay(parsed.headers, parsed.body)
    } else {
        ""
    }
    val kind = detectBodyKind(headerMap, decodedBody)
    val prettyBody = if (kind == BodyKind.JSON || kind == BodyKind.HTML) {
        formatBody(decodedBody, kind, parsed.separator)
    } else {
        decodedBody
    }
    val hasContent = parsed.headers.isNotEmpty() || decodedBody.isNotEmpty()
    val prettyText = if (hasContent) {
        parsed.headers + parsed.separator + parsed.separator + prettyBody
    } else {
        ""
    }
    state.requestPretty.text = prettyText
    applySyntax(state.requestPretty, kind)
    applySyntax(state.requestEditor, kind)
}

internal fun updateRequestFromPretty(state: RequestTabState, pretty: String) {
    val parsed = splitMessage(pretty)
    val headerMap = parseHeaders(parsed.headers)
    val previousParsed = splitMessage(state.requestEditor.text)
    val previousKind = detectBodyKind(parseHeaders(previousParsed.headers), previousParsed.body)
    val keepJsonRawSingleLine = previousKind == BodyKind.JSON &&
        !previousParsed.body.contains('\n') &&
        !previousParsed.body.contains('\r')

    val kind = detectBodyKind(headerMap, parsed.body)
    val bodyForRaw = if (kind == BodyKind.JSON && keepJsonRawSingleLine && parsed.body.isNotBlank()) {
        compactJson(parsed.body)
    } else {
        parsed.body
    }
    state.requestEditor.text = if (parsed.headers.isNotEmpty() || bodyForRaw.isNotEmpty()) {
        parsed.headers + parsed.separator + parsed.separator + bodyForRaw
    } else {
        ""
    }
    applySyntax(state.requestPretty, kind)
    applySyntax(state.requestEditor, kind)
}

internal fun updateResponseDerived(state: RequestTabState) =
    state.responseViewer.showResponse(state.responseText)

internal fun updateTargetDisplay(state: RequestTabState) {
    state.targetLabel.text = formatTarget(state.target)
}

internal fun IntruderUiContext.currentTabState(): RequestTabState? {
    val selected = requestTabBar.selectedComponent
    if (selected == addTabPanel) {
        return null
    }
    return tabStates[selected]
}

internal fun IntruderUiContext.currentRequestEditor(): org.fife.ui.rsyntaxtextarea.RSyntaxTextArea? =
    currentTabState()?.requestEditor

internal fun IntruderUiContext.currentPlaceholderEditor(requireSelection: Boolean = false): org.fife.ui.rsyntaxtextarea.RSyntaxTextArea? {
    val state = currentTabState() ?: return null
    val rawEditor = state.requestEditor
    val prettyEditor = state.requestPretty

    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    val focusedEditor = when {
        focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, rawEditor) -> rawEditor
        focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, prettyEditor) -> prettyEditor
        else -> null
    }

    if (!requireSelection) {
        return focusedEditor ?: rawEditor
    }

    if (focusedEditor != null && focusedEditor.selectionStart != focusedEditor.selectionEnd) {
        return focusedEditor
    }

    if (rawEditor.selectionStart != rawEditor.selectionEnd) {
        return rawEditor
    }

    if (prettyEditor.selectionStart != prettyEditor.selectionEnd) {
        return prettyEditor
    }

    return null
}

internal fun IntruderUiContext.refreshPlaceholderButtons() {
    val editorWithSelection = currentPlaceholderEditor(requireSelection = true)
    addPlaceholderButton.isEnabled = editorWithSelection != null
}

internal fun IntruderUiContext.addPlaceholderAroundSelection() {
    val editor = currentPlaceholderEditor(requireSelection = true) ?: return
    val selectionStart = editor.selectionStart
    val selectionEnd = editor.selectionEnd
    if (selectionStart < 0 || selectionEnd <= selectionStart) {
        refreshPlaceholderButtons()
        return
    }
    val selected = editor.text.substring(selectionStart, selectionEnd)
    val replacement = "{{$selected}}"
    editor.select(selectionStart, selectionEnd)
    editor.replaceSelection(replacement)
    editor.select(selectionStart, selectionStart + replacement.length)
    refreshPlaceholderButtons()
}

internal fun IntruderUiContext.clearPlaceholderInSelectionOrAll() {
    val editor = currentPlaceholderEditor(requireSelection = false) ?: return
    val selectionStart = editor.selectionStart
    val selectionEnd = editor.selectionEnd

    if (selectionStart >= 0 && selectionEnd > selectionStart) {
        val selected = editor.text.substring(selectionStart, selectionEnd)
        val selectedMatch = placeholderRegex.matchEntire(selected)
        if (selectedMatch != null) {
            val replacement = selectedMatch.groupValues[1]
            editor.select(selectionStart, selectionEnd)
            editor.replaceSelection(replacement)
            editor.select(selectionStart, selectionStart + replacement.length)
            refreshPlaceholderButtons()
            return
        }

        val requestText = editor.text
        if (selectionStart >= 2 && selectionEnd + 2 <= requestText.length) {
            val prefix = requestText.substring(selectionStart - 2, selectionStart)
            val suffix = requestText.substring(selectionEnd, selectionEnd + 2)
            if (prefix == "{{" && suffix == "}}") {
                val replacement = selected
                val wrappedStart = selectionStart - 2
                val wrappedEnd = selectionEnd + 2
                editor.select(wrappedStart, wrappedEnd)
                editor.replaceSelection(replacement)
                editor.select(wrappedStart, wrappedStart + replacement.length)
                refreshPlaceholderButtons()
                return
            }
        }
    }

    val cleared = placeholderRegex.replace(editor.text) { match -> match.groupValues[1] }
    if (cleared != editor.text) {
        editor.text = cleared
    }
    refreshPlaceholderButtons()
}

internal fun IntruderUiContext.showIntruderDrawer() {
    pane.bottomComponent = intruderPanel
    pane.dividerSize = defaultPaneDividerSize
    SwingUtilities.invokeLater {
        pane.setDividerLocation(0.4)
        pane.revalidate()
        pane.repaint()
    }
}

internal fun IntruderUiContext.showResultsPanel(resultsPanel: JPanel) {
    pane.bottomComponent = resultsPanel
    pane.dividerSize = defaultPaneDividerSize
    SwingUtilities.invokeLater {
        pane.setDividerLocation(0.25)
        pane.revalidate()
        pane.repaint()
    }
}

internal fun IntruderUiContext.hideIntruderDrawer() {
    pane.bottomComponent = intruderPlaceholder
    pane.dividerSize = 0
    SwingUtilities.invokeLater {
        pane.setDividerLocation(pane.maximumDividerLocation)
        pane.revalidate()
        pane.repaint()
    }
}
