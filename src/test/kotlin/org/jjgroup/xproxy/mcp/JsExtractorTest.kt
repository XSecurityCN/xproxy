package org.jjgroup.xproxy.mcp

import org.jjgroup.xproxy.mcp.tools.JsExtractor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsExtractorTest {
    private val sample = """
        HTTP/1.1 200 OK
        Content-Type: text/html

        <html>
        <head>
          <script src="/static/app.js"></script>
          <script src="https://cdn.example.com/lib.js?v=2"></script>
          <script>
            var API = "/api/v2/items";
            fetch("/api/v2/search?q=" + q);
            var base = "https://api.example.com/v3";
          </script>
        </head>
        <body><img src="/img/logo.png"><link rel="stylesheet" href="/x.css"></body>
        </html>
    """.trimIndent()

    @Test
    fun `extracts external script srcs`() {
        val ext = JsExtractor.extract(sample)
        assertTrue(ext.scriptSrcs.contains("/static/app.js"))
        assertTrue(ext.scriptSrcs.contains("https://cdn.example.com/lib.js?v=2"))
    }

    @Test
    fun `extracts inline script content`() {
        val ext = JsExtractor.extract(sample)
        assertTrue(ext.inlineScripts.any { it.contains("API") && it.contains("/api/v2/items") })
    }

    @Test
    fun `extracts suspected endpoints and skips static assets`() {
        val ext = JsExtractor.extract(sample)
        assertTrue(ext.endpoints.contains("/api/v2/items"))
        assertTrue(ext.endpoints.contains("/api/v2/search?q="))
        assertTrue(ext.endpoints.contains("https://api.example.com/v3"))
        // 静态资源被跳过
        assertTrue(ext.endpoints.none { it.contains(".css") })
        assertTrue(ext.endpoints.none { it.contains(".png") })
    }

    @Test
    fun `handles response with no scripts gracefully`() {
        val ext = JsExtractor.extract("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}")
        assertTrue(ext.scriptSrcs.isEmpty())
        assertTrue(ext.inlineScripts.isEmpty())
    }
}
