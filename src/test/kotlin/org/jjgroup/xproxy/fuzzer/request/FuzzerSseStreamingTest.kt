package org.jjgroup.xproxy.fuzzer.request

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.proxy.core.MockSseServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 验证 fuzzer 的 sendSingleRequest 对 SSE(text/event-stream)的实时刷新支持:
 *  - 干净关闭的 SSE(/sse/3):onProgress 多次触发,返回完整响应。
 *  - 持续 SSE(/sse/events):onProgress 实时触发,直到 cancel 返回部分响应(不走 10s 超时/502)。
 *
 * 上游用本地自包含的 [MockSseServer](plain HTTP/1.1,临时端口),不再依赖外部 xhttp 服务。
 */
class FuzzerSseStreamingTest {

    private lateinit var server: MockSseServer

    @BeforeEach
    fun setUp() {
        server = MockSseServer.start(tls = false)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    private fun sseRequest(path: String, host: String, port: Int, connection: String = "close"): String =
        "GET $path HTTP/1.1\r\nHost: $host:$port\r\nAccept: text/event-stream\r\nConnection: $connection\r\n\r\n"

    @Test
    fun `sse clean-close streams live via onProgress over http`() {
        val service = HttpService(server.host, server.port, "http")
        val request = sseRequest("/sse/3", server.host, server.port)
        val progress = CopyOnWriteArrayList<String>()
        val response = sendSingleRequest(service, request, shouldCancel = { false }) { partial ->
            progress.add(partial)
        }

        assertTrue(response.contains("text/event-stream", ignoreCase = true), "not SSE: ${response.take(120)}")
        assertTrue(progress.size >= 2, "expected live progress callbacks, got ${progress.size}")
        // 去分块:body 不含 chunk-size 行(如 `5a\r\n`),直接以 SSE 事件开头
        val body = response.substringAfter("\r\n\r\n")
        assertTrue(body.startsWith("id: 1"), "body should be de-chunked (start with event, not chunk size): ${body.take(80)}")
        assertFalse(
            Regex("(?m)^[0-9a-fA-F]{1,8}\r$").containsMatchIn(body),
            "body should not contain chunk-size framing lines: ${body.take(200)}"
        )
        assertTrue(response.contains("event: item"), "response should contain SSE events")
        assertTrue(response.contains("event: end"), "SSE should close cleanly")
    }

    @Test
    fun `continuous sse streams until cancel without timeout`() {
        val service = HttpService(server.host, server.port, "http")
        val request = sseRequest("/sse/events?interval=1s", server.host, server.port, connection = "keep-alive")
        val progress = CopyOnWriteArrayList<String>()
        val cancelAt = System.currentTimeMillis() + 3500

        val response = sendSingleRequest(service, request, shouldCancel = { System.currentTimeMillis() > cancelAt }) { partial ->
            progress.add(partial)
        }

        assertTrue(progress.isNotEmpty(), "no progress callbacks fired")
        assertTrue(progress.size >= 2, "expected live progress before cancel, got ${progress.size}")
        val last = progress.last()
        assertTrue(last.contains("event:", ignoreCase = true) || last.contains("data:", ignoreCase = true), "captured body should contain SSE events: ${last.take(200)}")
        // 去分块:progress body 不含 chunk-size 行
        val lastBody = last.substringAfter("\r\n\r\n")
        assertFalse(
            Regex("(?m)^[0-9a-fA-F]{1,8}\r$").containsMatchIn(lastBody),
            "streaming body should not contain chunk-size framing: ${lastBody.take(200)}"
        )
        // 取消后应正常返回(部分响应),不抛超时异常
        assertTrue(response.contains("text/event-stream", ignoreCase = true), "partial response should still be SSE")
    }
}
