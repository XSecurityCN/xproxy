package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TargetContentsTableModelTest {
    @Test
    fun `contents table includes protocol column after tls`() {
        val model = TargetContentsTableModel()
        model.setRows(listOf(history(protocol = "http/2")))

        assertEquals("TLS", model.getColumnName(8))
        assertEquals("Protocol", model.getColumnName(9))
        assertEquals("Modified", model.getColumnName(10))
        assertEquals("http/2", model.getValueAt(0, 9))
    }

    private fun history(protocol: String): ProxyHistoryEntry = ProxyHistoryEntry(
        id = 1,
        timeMillis = 1,
        method = "GET",
        host = "example.com:443",
        path = "/",
        statusCode = 200,
        length = 0,
        mimeType = "text",
        title = "",
        tls = true,
        modified = false,
        requestRaw = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
        responseRaw = "HTTP/1.1 200 OK\r\n\r\n",
        protocol = protocol
    )
}
