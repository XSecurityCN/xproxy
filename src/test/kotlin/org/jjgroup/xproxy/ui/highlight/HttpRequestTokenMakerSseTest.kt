package org.jjgroup.xproxy.ui.highlight

import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenTypes
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.text.Segment

class HttpRequestTokenMakerSseTest {

    private data class TokenInfo(val type: Int, val lexeme: String)

    private fun tokenize(message: String): List<List<TokenInfo>> {
        val maker = HttpRequestTokenMaker()
        var state = 0
        val result = ArrayList<List<TokenInfo>>()
        for (raw in message.split("\n")) {
            // 保留 \r:真实文本区按 \n 切行,空行是 "\r"(count=1,trim 后为空)才会触发 header->body 状态切换。
            val line = raw
            val seg = Segment(line.toCharArray(), 0, line.length)
            val first = maker.getTokenList(seg, state, 0)
            val tokens = ArrayList<TokenInfo>()
            var t: Token? = first
            var nextState = state
            while (t != null && t.isPaintable) {
                tokens.add(TokenInfo(t.type, t.lexeme))
                t = t.nextToken
            }
            if (t != null) {
                nextState = t.type
            }
            state = nextState
            result.add(tokens)
        }
        return result
    }

    @Test
    fun `sse body highlights field names as keywords and json data as strings`() {
        val msg = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "\r\n" +
            "id: 1\r\n" +
            "event: item\r\n" +
            "data: {\"index\":1}\r\n" +
            "\r\n"

        val lines = tokenize(msg)
        // 定位 SSE body 行(去 \r 后为空行已剔除内容)
        val idLine = lines.first { it.any { tk -> tk.lexeme == "id" } }
        val eventLine = lines.first { it.any { tk -> tk.lexeme == "event" } }
        val dataLine = lines.first { it.any { tk -> tk.lexeme == "data" } }

        // 字段名 id/event/data 标为关键字
        assertTrue(idLine.any { it.type == TokenTypes.RESERVED_WORD && it.lexeme == "id" }, "id field should be RESERVED_WORD: $idLine")
        assertTrue(eventLine.any { it.type == TokenTypes.RESERVED_WORD && it.lexeme == "event" }, "event field should be RESERVED_WORD: $eventLine")
        assertTrue(dataLine.any { it.type == TokenTypes.RESERVED_WORD && it.lexeme == "data" }, "data field should be RESERVED_WORD: $dataLine")

        // data 值为 JSON 时,JSON 被切分:对象键 "index" 标为关键字、数字 1 标为数字字面量
        assertTrue(
            dataLine.any { it.type == TokenTypes.RESERVED_WORD && it.lexeme.contains("index") },
            "JSON object key should be highlighted: $dataLine"
        )
        assertTrue(dataLine.any { it.type == TokenTypes.LITERAL_NUMBER_DECIMAL_INT }, "JSON number literal should be highlighted: $dataLine")
    }

    @Test
    fun `sse non-json data value highlighted as string literal`() {
        val msg = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "\r\n" +
            "event: time\r\n" +
            "data: 2026-07-11T12:00:00Z\r\n" +
            "\r\n"

        val lines = tokenize(msg)
        val dataLine = lines.first { it.any { tk -> tk.lexeme == "data" } }
        assertTrue(dataLine.any { it.type == TokenTypes.RESERVED_WORD && it.lexeme == "data" })
        assertTrue(
            dataLine.any { it.type == TokenTypes.LITERAL_STRING_DOUBLE_QUOTE && it.lexeme.contains("2026") },
            "non-JSON data value should be a string literal: $dataLine"
        )
    }

    @Test
    fun `non-sse chunked text body is not affected`() {
        val msg = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "hello world\r\n"

        val lines = tokenize(msg)
        val bodyLine = lines.first { it.any { tk -> tk.lexeme.contains("hello") } }
        // text/plain body:整行 IDENTIFIER(无 SSE 字段高亮)
        assertTrue(bodyLine.all { it.type == TokenTypes.IDENTIFIER }, "plain text body should be plain identifier: $bodyLine")
    }
}
