package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.model.RequestTabState
import org.jjgroup.xproxy.ui.http.HttpRequestResponseViewer
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JLabel
import javax.swing.JPanel

class IntruderFrameTabsRequestSyncTest {

    @Test
    fun `request sync skips rerender when text unchanged`() {
        val state = newState()
        state.requestEditor.text = "GET / HTTP/1.1\r\nHost: a\r\n\r\n"
        state.requestPretty.text = "GET / HTTP/1.1\nHost: a\n\n"
        state.requestEditor.caretPosition = state.requestEditor.document.length
        state.requestPretty.caretPosition = state.requestPretty.document.length

        val updates = AtomicInteger(0)
        var snapshotCalls = 0
        val changed = syncRequestEditorPreservingView(
            state = state,
            newRequestRaw = "GET / HTTP/1.1\r\nHost: a\r\n\r\n",
            setRequestSync = {},
            updateRequestFromRaw = { _, _ -> updates.incrementAndGet() },
            onTabSnapshotChanged = { _, _ -> snapshotCalls += 1 }
        )

        assertFalse(changed)
        assertEquals(0, updates.get())
        assertEquals(0, snapshotCalls)
    }

    @Test
    fun `request sync preserves caret when content length update rewrites request`() {
        val state = newState()
        state.requestEditor.text = "POST /x HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\n\r\na"
        state.requestPretty.text = state.requestEditor.text
        state.requestEditor.caretPosition = state.requestEditor.document.length
        state.requestPretty.caretPosition = state.requestPretty.document.length
        val rawCaretBefore = state.requestEditor.caretPosition
        val prettyCaretBefore = state.requestPretty.caretPosition

        val changed = syncRequestEditorPreservingView(
            state = state,
            newRequestRaw = "POST /x HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\nabcde",
            setRequestSync = {},
            updateRequestFromRaw = { s, raw -> s.requestPretty.text = raw },
            onTabSnapshotChanged = { _, _ -> }
        )

        assertTrue(changed)
        assertEquals(rawCaretBefore, state.requestEditor.caretPosition)
        assertEquals(prettyCaretBefore, state.requestPretty.caretPosition)
    }

    private fun newState(): RequestTabState {
        val viewer = HttpRequestResponseViewer(requestEditable = true)
        return RequestTabState(
            tabComponent = JPanel(),
            cardId = "c1",
            cardComponent = JPanel(),
            requestEditor = viewer.requestRawArea,
            requestPretty = viewer.requestPrettyArea,
            responseRaw = viewer.responseRawArea,
            responsePretty = viewer.responsePrettyArea,
            responseRender = viewer.responseRenderArea,
            responseViewer = viewer,
            targetLabel = JLabel(),
            target = HttpService("a", 443, "https")
        )
    }
}
