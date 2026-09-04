package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nLocaleOption
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProxyHistoryTableModelTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun setupLanguage() {
        I18n.configureForTests(temp.resolve("bundled"), temp.resolve("user"), persistedLanguage = I18nLocaleOption.SYSTEM.key)
        I18n.registerSettings()
        I18n.syncUserBundles()
        I18n.setLocaleOption(I18nLocaleOption.EN)
    }

    @Test
    fun `history table includes protocol column after tls`() {
        val model = ProxyHistoryTableModel()
        model.add(history(protocol = "http/2"))

        assertEquals("TLS", model.getColumnName(8))
        assertEquals("Protocol", model.getColumnName(9))
        assertEquals("Modified", model.getColumnName(10))
        assertEquals("http/2", model.getValueAt(0, 9))
    }

    @Test
    fun `update replaces row by id without changing row count`() {
        val model = ProxyHistoryTableModel()
        model.add(history(protocol = "http/1.1").copy(id = 10, length = 0, mimeType = "sse"))
        assertEquals(1, model.rowCount)
        assertEquals(0, model.getAt(0)!!.length)

        model.update(
            history(protocol = "http/1.1").copy(
                id = 10,
                length = 42,
                mimeType = "sse",
                responseRaw = "HTTP/1.1 200 OK\r\n\r\ndata: hi\n\n"
            )
        )

        assertEquals(1, model.rowCount)
        assertEquals(42, model.getAt(0)!!.length)
        assertEquals("sse", model.getAt(0)!!.mimeType)
        assertEquals("HTTP/1.1 200 OK\r\n\r\ndata: hi\n\n", model.getAt(0)!!.responseRaw)
    }

    @Test
    fun `update ignores unknown id`() {
        val model = ProxyHistoryTableModel()
        model.add(history(protocol = "http/1.1").copy(id = 1, length = 0))

        model.update(history(protocol = "http/1.1").copy(id = 999, length = 5))

        assertEquals(1, model.rowCount)
        assertEquals(0, model.getAt(0)!!.length)
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
