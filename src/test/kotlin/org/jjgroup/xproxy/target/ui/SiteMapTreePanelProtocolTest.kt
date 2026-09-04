package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nLocaleOption
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.target.core.SiteMapService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SiteMapTreePanelProtocolTest {
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
    fun `http2 history maps to https host key and fuzzer service protocol`() {
        val panel = SiteMapTreePanel(
            service = SiteMapService(),
            onSelectionChanged = {},
            onSendToFuzzer = { _, _ -> },
            onSendToCodec = null,
            onDeleteHistoryIds = null
        )

        val entry = ProxyHistoryEntry(
            id = 1,
            timeMillis = 1,
            method = "GET",
            host = "api.example.com:443",
            path = "/v1/items",
            statusCode = 200,
            length = 0,
            mimeType = "json",
            title = "",
            tls = true,
            modified = false,
            requestRaw = "GET /v1/items HTTP/2\r\nHost: api.example.com\r\n\r\n",
            responseRaw = "HTTP/2 200\r\n\r\n",
            protocol = "http/2"
        )

        val record = panel.indexHistory(entry)
        val service = panel.toHttpService(entry)

        assertTrue(record.hostKey.startsWith("https://"))
        assertEquals("https", service.protocol)
        assertEquals(443, service.port)
    }

}
