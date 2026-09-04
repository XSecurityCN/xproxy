package org.jjgroup.xproxy.proxy.core

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceAction
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceMode
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceRule
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class ProxyMatchReplaceEngineTest {

    @Test
    fun `response body replacement removes stale content encoding`() {
        val engine = ProxyMatchReplaceEngine()
        engine.setRules(
            listOf(
                ProxyMatchReplaceRule(
                    scope = ProxyMatchReplaceScope.RESPONSE_BODY,
                    mode = ProxyMatchReplaceMode.TEXT,
                    action = ProxyMatchReplaceAction.REPLACE,
                    matchText = "ORIGINAL_TOKEN",
                    replaceText = "REPLACED_TOKEN"
                )
            )
        )
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer("<!DOCTYPE html>ORIGINAL_TOKEN".toByteArray(Charsets.ISO_8859_1))
        )
        response.headers().set("Content-Type", "text/html;charset=utf-8")
        response.headers().set("Content-Encoding", "br")

        val changed = engine.applyToResponse(response)

        assertTrue(changed)
        assertEquals(null, response.headers().get("Content-Encoding"))
        assertEquals("<!DOCTYPE html>REPLACED_TOKEN", response.content().toString(Charsets.ISO_8859_1))
        assertEquals(response.content().readableBytes().toString(), response.headers().get("Content-Length"))
    }

    @Test
    fun `response body replacement matches gzip encoded browser response`() {
        val engine = ProxyMatchReplaceEngine()
        engine.setRules(
            listOf(
                ProxyMatchReplaceRule(
                    scope = ProxyMatchReplaceScope.RESPONSE_BODY,
                    mode = ProxyMatchReplaceMode.TEXT,
                    action = ProxyMatchReplaceAction.REPLACE,
                    matchText = "ORIGINAL_TOKEN",
                    replaceText = "REPLACED_TOKEN"
                )
            )
        )
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(gzip("<!DOCTYPE html>ORIGINAL_TOKEN"))
        )
        response.headers().set("Content-Type", "text/html;charset=utf-8")
        response.headers().set("Content-Encoding", "gzip")

        val changed = engine.applyToResponse(response)

        assertTrue(changed)
        assertEquals(null, response.headers().get("Content-Encoding"))
        assertEquals("<!DOCTYPE html>REPLACED_TOKEN", response.content().toString(Charsets.ISO_8859_1))
        assertEquals(response.content().readableBytes().toString(), response.headers().get("Content-Length"))
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.ISO_8859_1)) }
        return out.toByteArray()
    }
}
