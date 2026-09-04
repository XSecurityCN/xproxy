package org.jjgroup.xproxy.kits.core

import org.jjgroup.xproxy.kits.model.XappManifest
import org.jjgroup.xproxy.kits.model.XappPlugin
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class XappContextHighlightTest {

    private fun plugin() = XappPlugin(
        manifest = XappManifest(
            id = "highlight-test", name = "highlight-test", version = "1.0.0",
            description = "", entryFile = "xapp.py", author = "test"
        ),
        directory = Path.of("."),
        scriptPath = Path.of("xapp.py"),
        enabled = true,
        loadError = null
    )

    private fun entry(id: Long) = ProxyHistoryEntry(
        id = id, timeMillis = id, method = "GET", host = "example.com", path = "/$id",
        statusCode = 200, length = 0, mimeType = "text", title = "", tls = true, modified = false,
        requestRaw = "GET /$id HTTP/1.1\r\nHost: example.com\r\n\r\n",
        responseRaw = "HTTP/1.1 200 OK\r\n\r\n"
    )

    @Test
    fun `highlight passes current entry id and color to publisher`() {
        val captured = mutableListOf<Pair<Long, String>>()
        val ctx = XappProxyMessageContext(
            plugin = plugin(),
            sourceEntry = entry(42L),
            logSink = {},
            sendAndRecord = { XappHttpResponse.fromRaw("HTTP/1.1 200 OK\r\n\r\n") },
            issuePublisher = {},
            highlightPublisher = { id, color -> captured.add(id to color) }
        )

        assertEquals(42L, ctx.history_id)
        ctx.highlight("red")
        ctx.highlight("green", history_id = 99L)
        assertEquals(listOf(42L to "red", 99L to "green"), captured)
    }

    @Test
    fun `highlight default color is none and forwards to publisher`() {
        val captured = mutableListOf<Pair<Long, String>>()
        val ctx = XappProxyMessageContext(
            plugin = plugin(),
            sourceEntry = entry(7L),
            logSink = {},
            sendAndRecord = { XappHttpResponse.fromRaw("HTTP/1.1 200 OK\r\n\r\n") },
            issuePublisher = {},
            highlightPublisher = { id, color -> captured.add(id to color) }
        )

        ctx.highlight()
        assertEquals(listOf(7L to "none"), captured)
    }

    @Test
    fun `rewrite-path ctx has no history id and highlight is ignored with warning`() {
        val captured = mutableListOf<Pair<Long, String>>()
        val logs = mutableListOf<String>()
        val ctx = XappProxyMessageContext(
            plugin = plugin(),
            requestRaw = "GET /x HTTP/1.1\r\nHost: example.com\r\n\r\n",
            responseRaw = "HTTP/1.1 200 OK\r\n\r\n",
            fallbackHost = "example.com",
            fallbackTls = false,
            logSink = { logs.add(it) },
            sendAndRecord = { XappHttpResponse.fromRaw("HTTP/1.1 200 OK\r\n\r\n") },
            issuePublisher = {},
            highlightPublisher = { id, color -> captured.add(id to color) }
        )

        assertEquals(0L, ctx.history_id)
        ctx.highlight("red")
        assertTrue(captured.isEmpty(), "publisher must not be called without a valid history id")
        assertTrue(logs.any { it.contains("highlight") }, "a warning should be logged")
    }
}
