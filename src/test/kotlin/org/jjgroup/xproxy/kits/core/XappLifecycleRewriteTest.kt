package org.jjgroup.xproxy.kits.core

import org.jjgroup.xproxy.kits.model.XappManifest
import org.jjgroup.xproxy.kits.model.XappPlugin
import com.github.luben.zstd.ZstdOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class XappLifecycleRewriteTest {

    @Test
    fun `plugin without on_before_request must not rewrite http2 request`() {
        val manager = XappManager(projectDataStore = null)
        val pluginDir = Files.createTempDirectory("xapp-no-before")
        val script = pluginDir.resolve("xapp.py")
        Files.writeString(
            script,
            """
            def on_proxy_http_message(ctx):
                pass
            """.trimIndent()
        )
        manager.plugins = listOf(
            XappPlugin(
                manifest = XappManifest(
                    id = "debug-http-observer",
                    name = "debug-http-observer",
                    version = "1.0.0",
                    description = "",
                    entryFile = "xapp.py",
                    author = "test"
                ),
                directory = pluginDir,
                scriptPath = script,
                enabled = true,
                loadError = null
            )
        )

        val rawHttp2 = "GET /chat HTTP/2\r\n:authority: chat.baidu.com\r\n:user-agent: Chrome\r\n\r\n"
        val rewritten = manager.rewriteBeforeRequest(rawHttp2, "chat.baidu.com:443", true)

        assertEquals(rawHttp2, rewritten)
    }

    @Test
    fun `no-op on_after_request must not rewrite binary-like response raw`() {
        val manager = XappManager(projectDataStore = null)
        val pluginDir = Files.createTempDirectory("xapp-noop-after")
        val script = pluginDir.resolve("xapp.py")
        Files.writeString(
            script,
            """
            def on_after_request(ctx):
                ctx.log("observe only")
            """.trimIndent()
        )
        manager.plugins = listOf(
            XappPlugin(
                manifest = XappManifest(
                    id = "noop-after",
                    name = "noop-after",
                    version = "1.0.0",
                    description = "",
                    entryFile = "xapp.py",
                    author = "test"
                ),
                directory = pluginDir,
                scriptPath = script,
                enabled = true,
                loadError = null
            )
        )

        val binaryBody = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x11, 0x22, 0x33).toString(Charsets.ISO_8859_1)
        val responseRaw = "HTTP/2 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 7\r\n\r\n$binaryBody"
        val rewritten = manager.rewriteAfterRequest(
            requestRaw = "GET /img.png HTTP/2\r\n:authority: cdn.example.com\r\n\r\n",
            responseRaw = responseRaw,
            host = "cdn.example.com:443",
            tls = true
        )

        assertEquals(responseRaw, rewritten)
    }

    @Test
    fun `no-op on_before_request must preserve request raw exactly`() {
        val manager = XappManager(projectDataStore = null)
        val pluginDir = Files.createTempDirectory("xapp-noop-before")
        val script = pluginDir.resolve("xapp.py")
        Files.writeString(
            script,
            """
            def on_before_request(ctx):
                ctx.log("before observe")
            """.trimIndent()
        )
        manager.plugins = listOf(
            XappPlugin(
                manifest = XappManifest(
                    id = "noop-before",
                    name = "noop-before",
                    version = "1.0.0",
                    description = "",
                    entryFile = "xapp.py",
                    author = "test"
                ),
                directory = pluginDir,
                scriptPath = script,
                enabled = true,
                loadError = null
            )
        )

        val body = byteArrayOf(0x00, 0x10, 0x7f, 0xff.toByte(), 0x41).toString(Charsets.ISO_8859_1)
        val requestRaw = "POST /upload HTTP/2\r\n:authority: img.example.com\r\ncontent-type: application/octet-stream\r\ncontent-length: 5\r\n\r\n$body"

        val rewritten = manager.rewriteBeforeRequest(requestRaw, "img.example.com:443", true)
        assertEquals(requestRaw, rewritten)
    }

    @Test
    fun `after-request header rewrite preserves binary response body bytes`() {
        val manager = XappManager(projectDataStore = null)
        val pluginDir = Files.createTempDirectory("xapp-rewrite-binary")
        val script = pluginDir.resolve("xapp.py")
        Files.writeString(
            script,
            """
            def on_after_request(ctx):
                ctx.response.headers["X-Rewrite"] = "1"
            """.trimIndent()
        )
        manager.plugins = listOf(
            XappPlugin(
                manifest = XappManifest(
                    id = "rewrite-binary",
                    name = "rewrite-binary",
                    version = "1.0.0",
                    description = "",
                    entryFile = "xapp.py",
                    author = "test"
                ),
                directory = pluginDir,
                scriptPath = script,
                enabled = true,
                loadError = null
            )
        )

        val binaryBody = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a).toString(Charsets.ISO_8859_1)
        val responseRaw = "HTTP/2 200 OK\r\nContent-Type: image/png\r\nContent-Encoding: gzip\r\nContent-Length: 6\r\n\r\n$binaryBody"
        val rewritten = manager.rewriteAfterRequest(
            requestRaw = "GET /logo.png HTTP/2\r\n:authority: static.example.com\r\n\r\n",
            responseRaw = responseRaw,
            host = "static.example.com:443",
            tls = true
        )

        val originalBody = responseRaw.substringAfter("\r\n\r\n")
        val rewrittenBody = rewritten.substringAfter("\r\n\r\n")
        assertEquals(originalBody, rewrittenBody)
        assertEquals(true, rewritten.contains("X-Rewrite: 1", ignoreCase = true))
        assertEquals(true, rewritten.contains("Content-Encoding: gzip", ignoreCase = true))
    }

    @Test
    fun `after-request body rewrite removes stale content encoding`() {
        val manager = XappManager(projectDataStore = null)
        val pluginDir = Files.createTempDirectory("xapp-rewrite-encoded-body")
        val script = pluginDir.resolve("xapp.py")
        Files.writeString(
            script,
            """
            def on_after_request(ctx):
                ctx.response.body = ctx.response.body.replace("ORIGINAL_TOKEN", "REPLACED_TOKEN")
            """.trimIndent()
        )
        manager.plugins = listOf(
            XappPlugin(
                manifest = XappManifest(
                    id = "rewrite-encoded-body",
                    name = "rewrite-encoded-body",
                    version = "1.0.0",
                    description = "",
                    entryFile = "xapp.py",
                    author = "test"
                ),
                directory = pluginDir,
                scriptPath = script,
                enabled = true,
                loadError = null
            )
        )

        val responseRaw = "HTTP/1.1 200 OK\r\nContent-Type: text/html;charset=utf-8\r\nContent-Encoding: br\r\nContent-Length: 28\r\n\r\n<!DOCTYPE html>ORIGINAL_TOKEN"
        val rewritten = manager.rewriteAfterRequest(
            requestRaw = "GET / HTTP/1.1\r\nHost: www.baidu.com\r\n\r\n",
            responseRaw = responseRaw,
            host = "www.baidu.com:443",
            tls = true
        )

        assertEquals(false, rewritten.contains("Content-Encoding:", ignoreCase = true))
        assertEquals(true, rewritten.contains("<!DOCTYPE html>REPLACED_TOKEN"))
    }

    @Test
    fun `after-request header rewrite decodes zstd text response before sending to browser`() {
        val manager = XappManager(projectDataStore = null)
        val pluginDir = Files.createTempDirectory("xapp-rewrite-zstd-text")
        val script = pluginDir.resolve("xapp.py")
        Files.writeString(
            script,
            """
            def on_after_request(ctx):
                ctx.response.headers["X-Xproxy-Rewrite-Response"] = "1"
            """.trimIndent()
        )
        manager.plugins = listOf(
            XappPlugin(
                manifest = XappManifest(
                    id = "rewrite-zstd-text",
                    name = "rewrite-zstd-text",
                    version = "1.0.0",
                    description = "",
                    entryFile = "xapp.py",
                    author = "test"
                ),
                directory = pluginDir,
                scriptPath = script,
                enabled = true,
                loadError = null
            )
        )

        val plainBody = "<html>\n  <head><title>websocket</title></head>\n</html>"
        val encodedBody = zstd(plainBody).toString(Charsets.ISO_8859_1)
        val responseRaw = "HTTP/2 200 OK\r\ncontent-encoding: zstd\r\ncontent-type: text/html\r\ncontent-length: ${encodedBody.toByteArray(Charsets.ISO_8859_1).size}\r\n\r\n$encodedBody"
        val rewritten = manager.rewriteAfterRequest(
            requestRaw = "GET / HTTP/2\r\n:authority: demo.example\r\n\r\n",
            responseRaw = responseRaw,
            host = "demo.example:443",
            tls = true
        )

        assertEquals(false, rewritten.contains("content-encoding:", ignoreCase = true))
        assertEquals(true, rewritten.contains("X-Xproxy-Rewrite-Response: 1"))
        assertEquals(true, rewritten.endsWith(plainBody))
        assertEquals(
            plainBody.toByteArray(Charsets.ISO_8859_1).size.toString(),
            Regex("(?im)^content-length: (\\d+)$").find(rewritten)?.groupValues?.get(1)
        )
    }

    @Test
    fun `edited plugin script is recompiled on next dispatch via mtime invalidation`() {
        val manager = XappManager(projectDataStore = null)
        val pluginDir = Files.createTempDirectory("xapp-recompile")
        val script = pluginDir.resolve("xapp.py")
        // 初始脚本:on_before_request 仅访问 ctx,不改写请求(应原样返回)。
        Files.writeString(
            script,
            """
            def on_before_request(ctx):
                _ = ctx.response
            """.trimIndent()
        )
        manager.plugins = listOf(
            XappPlugin(
                manifest = XappManifest(
                    id = "recompile-test",
                    name = "recompile-test",
                    version = "1.0.0",
                    description = "",
                    entryFile = "xapp.py",
                    author = "test"
                ),
                directory = pluginDir,
                scriptPath = script,
                enabled = true,
                loadError = null
            )
        )

        val requestRaw = "GET / HTTP/1.1\r\nHost: a.example\r\n\r\n"
        // 第一次派发:编译并缓存 PyCode(mtime = 写入时刻)。
        val firstRewritten = manager.rewriteBeforeRequest(requestRaw, "a.example:80", false)
        assertEquals(requestRaw, firstRewritten)

        // 编辑脚本:改为实际加一个头。显式把 mtime 推到 2s 后,确保与缓存 mtime 严格不等(免分辨率抖动)。
        Files.writeString(
            script,
            """
            def on_before_request(ctx):
                ctx.request.headers["X-Edited"] = "1"
            """.trimIndent()
        )
        Files.setLastModifiedTime(script, FileTime.fromMillis(System.currentTimeMillis() + 2000L))

        // 第二次派发:mtime 变化应触发重编译,使用新脚本逻辑。
        val secondRewritten = manager.rewriteBeforeRequest(requestRaw, "a.example:80", false)
        assertEquals(true, secondRewritten.contains("X-Edited: 1", ignoreCase = true))
    }

    private fun zstd(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZstdOutputStream(out).use { it.write(text.toByteArray(Charsets.ISO_8859_1)) }
        return out.toByteArray()
    }
}
