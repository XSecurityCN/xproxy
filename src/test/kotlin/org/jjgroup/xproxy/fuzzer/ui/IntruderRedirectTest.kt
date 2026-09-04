package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IntruderRedirectTest {
    @Test
    fun `follow redirect to https default port updates target to 443`() {
        val request = "GET / HTTP/1.1\r\nHost: echo.websocket.org\r\n\r\n"
        val response = "HTTP/1.1 301 Moved Permanently\r\nlocation: https://echo.websocket.org/\r\n\r\n"
        val originalTarget = HttpService("echo.websocket.org", 80, "http")

        val redirectUri = resolveRedirectUri(request, response, originalTarget) ?: error("redirect URI should resolve")
        val updatedRequest = applyUrlToRequest(request, redirectUri) ?: error("request should update from redirect URI")
        val updatedTarget = targetFromUri(redirectUri, originalTarget)

        assertEquals(HttpService("echo.websocket.org", 443, "https"), updatedTarget)
        assertEquals("GET / HTTP/1.1\r\nHost: echo.websocket.org\r\n\r\n", updatedRequest)
    }
}
