package org.jjgroup.xproxy.ui.marking

import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectRecord
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.proxy.ui.ProxyHistoryTableModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.awt.Color
import java.nio.file.Files
import javax.swing.JTable
import javax.swing.SwingUtilities

class HighlightCellRendererTest {

    @AfterEach
    fun reset() {
        TrafficHighlightRegistry.clear()
    }

    private fun newStore(): ProjectDataStore {
        val dbPath = Files.createTempFile("xproxy-highlight-renderer", ".db")
        val record = ProjectRecord(
            id = "test", displayName = "Test", baseName = "test", createdDate = "2026-07-22",
            projectDir = dbPath.parent.toString(), dbPath = dbPath.toAbsolutePath().toString(),
            createdAtMillis = 0L, lastOpenedMillis = 0L
        )
        return ProjectDataStore(record)
    }

    private fun entry(id: Long) = ProxyHistoryEntry(
        id = id, timeMillis = id, method = "GET", host = "example.com", path = "/$id",
        statusCode = 200, length = 0, mimeType = "text", title = "", tls = true, modified = false,
        requestRaw = "GET /$id HTTP/1.1\r\nHost: example.com\r\n\r\n", responseRaw = "HTTP/1.1 200 OK\r\n\r\n"
    )

    private fun <T> edt(block: () -> T): T {
        val holder = arrayOfNulls<Any?>(1)
        SwingUtilities.invokeAndWait { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    @Test
    fun `tinted background applied when highlight set, cleared when none`() {
        TrafficHighlightRegistry.bind(newStore())
        val model = ProxyHistoryTableModel().apply { add(entry(1L)) }
        val renderer = HighlightCellRenderer(TrafficHighlightRegistry.Kind.HTTP) { table, row ->
            (table.model as ProxyHistoryTableModel).getAt(row)?.id
        }

        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 1L, TrafficHighlight.RED)
        val tinted = edt {
            val table = JTable(model)
            val c = renderer.getTableCellRendererComponent(table, 1L, false, false, 0, 0)
            c.background
        }
        val expected = TrafficHighlight.RED.tint()!!.background
        assertEquals(expected, tinted)

        TrafficHighlightRegistry.clearOne(TrafficHighlightRegistry.Kind.HTTP, 1L)
        val plain = edt {
            val table = JTable(model)
            val c = renderer.getTableCellRendererComponent(table, 1L, false, false, 0, 0)
            c.background
        }
        // 无高亮:不再保留 tint 底色(回退表格默认,具体取值由 LAF 决定,此处仅断言已脱离 tint)
        assertNotEquals(expected, plain, "cleared row should not keep the highlight tint")
    }

    @Test
    fun `selected highlighted row uses selected tint`() {
        TrafficHighlightRegistry.bind(newStore())
        val model = ProxyHistoryTableModel().apply { add(entry(2L)) }
        val renderer = HighlightCellRenderer(TrafficHighlightRegistry.Kind.HTTP) { table, row ->
            (table.model as ProxyHistoryTableModel).getAt(row)?.id
        }
        TrafficHighlightRegistry.set(TrafficHighlightRegistry.Kind.HTTP, 2L, TrafficHighlight.GREEN)

        val bg = edt {
            val table = JTable(model)
            val c = renderer.getTableCellRendererComponent(table, 2L, true, false, 0, 0)
            c.background
        }
        val expected = TrafficHighlight.GREEN.tint()!!.backgroundSelected
        assertEquals(expected, bg)
        assertNotNull(bg)
        // 选中 tint 应与未选中 tint 不同(加深)
        assertNotRgb(expected, TrafficHighlight.GREEN.tint()!!.background)
    }

    private fun assertNotRgb(a: Color, b: Color) {
        val same = a.red == b.red && a.green == b.green && a.blue == b.blue
        assertEquals(false, same, "selected tint should differ from plain tint")
    }
}
