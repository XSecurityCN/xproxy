package org.jjgroup.xproxy.ui.highlight

import javax.swing.text.Segment
import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenMap
import org.fife.ui.rsyntaxtextarea.TokenTypes

class HttpRequestTokenMaker : AbstractTokenMaker() {
    override fun getWordsToHighlight(): TokenMap {
        return TokenMap()
    }

    override fun getTokenList(text: Segment, startTokenType: Int, startOffset: Int): Token {
        resetTokenList()

        val lineOffset = text.offset
        val lineCount = text.count
        if (lineCount == 0) {
            addNullTokenWithType(normalizeState(startTokenType))
            return firstToken
        }

        val line = String(text.array, lineOffset, lineCount)
        val trimmed = line.trim()
        val state = normalizeState(startTokenType)
        if (isBodyState(state)) {
            var effectiveState = state
            if (state == STATE_BODY_OTHER && trimmed.isNotEmpty()) {
                effectiveState = when {
                    looksLikeJson(trimmed) -> STATE_BODY_JSON
                    looksLikeForm(trimmed) -> STATE_BODY_FORM
                    looksLikeHtml(trimmed) -> STATE_BODY_HTML
                    else -> STATE_BODY_OTHER
                }
            }
            if (trimmed.isEmpty()) {
                addNullTokenWithType(effectiveState)
                return firstToken
            }
            when (effectiveState) {
                STATE_BODY_JSON -> tokenizeJsonLine(text, line, lineOffset, startOffset)
                STATE_BODY_FORM -> tokenizeFormLine(text, line, lineOffset, startOffset)
                STATE_BODY_HTML -> tokenizeHtmlLine(text, line, lineOffset, startOffset)
                STATE_BODY_SSE -> tokenizeSseLine(text, line, lineOffset, startOffset)
                else -> addTokenRange(text, lineOffset, startOffset, 0, line.length - 1, TokenTypes.IDENTIFIER)
            }
            addNullTokenWithType(effectiveState)
            return firstToken
        }

        if (trimmed.isEmpty()) {
            addNullTokenWithType(bodyStateFromHeaderState(state))
            return firstToken
        }

        if (isRequestLine(line)) {
            tokenizeRequestLine(text, line, lineOffset, startOffset)
            addNullTokenWithType(state)
            return firstToken
        }

        if (isHeaderLine(line)) {
            tokenizeHeaderLine(text, line, lineOffset, startOffset)
            val nextState = updateHeaderStateFromLine(state, line)
            addNullTokenWithType(nextState)
            return firstToken
        }

        if (looksLikeJson(trimmed)) {
            tokenizeJsonLine(text, line, lineOffset, startOffset)
            addNullTokenWithType(STATE_BODY_JSON)
            return firstToken
        }
        if (looksLikeForm(trimmed)) {
            tokenizeFormLine(text, line, lineOffset, startOffset)
            addNullTokenWithType(STATE_BODY_FORM)
            return firstToken
        }
        if (looksLikeHtml(trimmed)) {
            tokenizeHtmlLine(text, line, lineOffset, startOffset)
            addNullTokenWithType(STATE_BODY_HTML)
            return firstToken
        }

        tokenizeParams(text, line, lineOffset, startOffset, 0, line.length, '&')
        addNullTokenWithType(state)
        return firstToken
    }

    private fun isRequestLine(line: String): Boolean {
        if (line.startsWith("PRI * HTTP/2.0")) {
            return true
        }
        val firstSpace = line.indexOf(' ')
        val lastSpace = line.lastIndexOf(' ')
        if (firstSpace <= 0 || lastSpace <= firstSpace) {
            return false
        }
        val version = line.substring(lastSpace + 1).trim()
        return version.startsWith("HTTP/")
    }

    private fun isHeaderLine(line: String): Boolean {
        val colon = line.indexOf(':')
        if (colon <= 0) {
            return false
        }
        return line.substring(0, colon).all { it.isLetterOrDigit() || it == '-' }
    }

    private fun tokenizeRequestLine(text: Segment, line: String, lineOffset: Int, startOffset: Int) {
        val firstSpace = line.indexOf(' ')
        val lastSpace = line.lastIndexOf(' ')
        if (firstSpace <= 0 || lastSpace <= firstSpace) {
            addTokenRange(text, lineOffset, startOffset, 0, line.length - 1, TokenTypes.IDENTIFIER)
            return
        }

        addTokenRange(text, lineOffset, startOffset, 0, firstSpace - 1, TokenTypes.IDENTIFIER)
        addTokenRange(text, lineOffset, startOffset, firstSpace, firstSpace, TokenTypes.WHITESPACE)

        val uriStart = firstSpace + 1
        val uriEnd = lastSpace - 1
        val queryIndex = line.indexOf('?', uriStart)
        if (queryIndex != -1 && queryIndex <= uriEnd) {
            addTokenRange(text, lineOffset, startOffset, uriStart, queryIndex - 1, TokenTypes.IDENTIFIER)
            addTokenRange(text, lineOffset, startOffset, queryIndex, queryIndex, TokenTypes.SEPARATOR)
            val queryStart = queryIndex + 1
            tokenizeParams(text, line, lineOffset, startOffset, queryStart, uriEnd + 1, '&')
        } else {
            addTokenRange(text, lineOffset, startOffset, uriStart, uriEnd, TokenTypes.IDENTIFIER)
        }

        addTokenRange(text, lineOffset, startOffset, lastSpace, lastSpace, TokenTypes.WHITESPACE)
        addTokenRange(text, lineOffset, startOffset, lastSpace + 1, line.length - 1, TokenTypes.IDENTIFIER)
    }

    private fun tokenizeHeaderLine(text: Segment, line: String, lineOffset: Int, startOffset: Int) {
        val colon = line.indexOf(':')
        if (colon <= 0) {
            addTokenRange(text, lineOffset, startOffset, 0, line.length - 1, TokenTypes.IDENTIFIER)
            return
        }

        val headerName = line.substring(0, colon).trim()
        addTokenRange(text, lineOffset, startOffset, 0, colon - 1, TokenTypes.RESERVED_WORD)
        addTokenRange(text, lineOffset, startOffset, colon, colon, TokenTypes.SEPARATOR)

        var valueStart = colon + 1
        while (valueStart < line.length && line[valueStart] == ' ') {
            valueStart++
        }

        if (valueStart > colon + 1) {
            addTokenRange(text, lineOffset, startOffset, colon + 1, valueStart - 1, TokenTypes.WHITESPACE)
        }

        if (valueStart < line.length) {
            if (headerName.equals("Cookie", ignoreCase = true) || headerName.equals("Content-Disposition", ignoreCase = true)) {
                tokenizeParams(text, line, lineOffset, startOffset, valueStart, line.length, ';')
            } else {
                addTokenRange(text, lineOffset, startOffset, valueStart, line.length - 1, TokenTypes.IDENTIFIER)
            }
        }
    }

    private fun tokenizeParams(
        text: Segment,
        line: String,
        lineOffset: Int,
        startOffset: Int,
        start: Int,
        end: Int,
        separator: Char,
    ) {
        var i = start
        while (i < end) {
            val nameStart = i
            while (i < end && line[i] != '=' && line[i] != separator) {
                i++
            }

            val nameEnd = i - 1
            if (nameEnd >= nameStart) {
                addTokenRange(text, lineOffset, startOffset, nameStart, nameEnd, TokenTypes.RESERVED_WORD)
            }

            if (i < end && line[i] == '=') {
                addTokenRange(text, lineOffset, startOffset, i, i, TokenTypes.SEPARATOR)
                i++
                val valueStart = i
                while (i < end && line[i] != separator) {
                    i++
                }
                val valueEnd = i - 1
                if (valueEnd >= valueStart) {
                    addTokenRange(text, lineOffset, startOffset, valueStart, valueEnd, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)
                }
            }

            if (i < end && line[i] == separator) {
                addTokenRange(text, lineOffset, startOffset, i, i, TokenTypes.SEPARATOR)
                i++
                while (i < end && line[i] == ' ') {
                    addTokenRange(text, lineOffset, startOffset, i, i, TokenTypes.WHITESPACE)
                    i++
                }
            }
        }
    }

    private fun addTokenRange(text: Segment, lineOffset: Int, startOffset: Int, start: Int, end: Int, tokenType: Int) {
        if (end < start) {
            return
        }
        if (tokenType != TokenTypes.WHITESPACE && tokenType != TokenTypes.SEPARATOR) {
            addTokenRangeWithPlaceholders(text, lineOffset, startOffset, start, end, tokenType)
            return
        }
        addToken(text, lineOffset + start, lineOffset + end, tokenType, startOffset + start)
    }

    private fun addTokenRangeWithPlaceholders(
        text: Segment,
        lineOffset: Int,
        startOffset: Int,
        start: Int,
        end: Int,
        baseTokenType: Int,
    ) {
        var cursor = start
        while (cursor <= end) {
            val open = findSequence(text, lineOffset, cursor, end, '{', '{')
            if (open == -1) {
                addToken(text, lineOffset + cursor, lineOffset + end, baseTokenType, startOffset + cursor)
                return
            }

            if (open > cursor) {
                addToken(text, lineOffset + cursor, lineOffset + open - 1, baseTokenType, startOffset + cursor)
            }

            val close = findSequence(text, lineOffset, open + 2, end, '}', '}')
            if (close == -1) {
                addToken(text, lineOffset + open, lineOffset + end, baseTokenType, startOffset + open)
                return
            }

            addToken(text, lineOffset + open, lineOffset + open + 1, TokenTypes.MARKUP_TAG_DELIMITER, startOffset + open)
            if (close > open + 2) {
                addToken(text, lineOffset + open + 2, lineOffset + close - 1, TokenTypes.VARIABLE, startOffset + open + 2)
            }
            addToken(text, lineOffset + close, lineOffset + close + 1, TokenTypes.MARKUP_TAG_DELIMITER, startOffset + close)
            cursor = close + 2
        }
    }

    private fun findSequence(text: Segment, lineOffset: Int, from: Int, end: Int, first: Char, second: Char): Int {
        for (i in from until end) {
            val c1 = text.array[lineOffset + i]
            val c2 = text.array[lineOffset + i + 1]
            if (c1 == first && c2 == second) {
                return i
            }
        }
        return -1
    }

    private fun addNullTokenWithType(type: Int) {
        addNullToken()
        currentToken.type = type
    }

    private fun normalizeState(startTokenType: Int): Int {
        return when (startTokenType) {
            STATE_HEADER_JSON,
            STATE_HEADER_HTML,
            STATE_HEADER_FORM,
            STATE_HEADER_SSE,
            STATE_HEADER_OTHER,
            STATE_BODY_JSON,
            STATE_BODY_HTML,
            STATE_BODY_FORM,
            STATE_BODY_SSE,
            STATE_BODY_OTHER,
            -> startTokenType

            else -> STATE_HEADER_OTHER
        }
    }

    private fun isBodyState(state: Int): Boolean {
        return state <= STATE_BODY_BASE
    }

    private fun bodyStateFromHeaderState(headerState: Int): Int {
        return when (headerState) {
            STATE_HEADER_JSON -> STATE_BODY_JSON
            STATE_HEADER_HTML -> STATE_BODY_HTML
            STATE_HEADER_FORM -> STATE_BODY_FORM
            STATE_HEADER_SSE -> STATE_BODY_SSE
            else -> STATE_BODY_OTHER
        }
    }

    private fun updateHeaderStateFromLine(headerState: Int, line: String): Int {
        val colon = line.indexOf(':')
        if (colon <= 0) {
            return headerState
        }
        val name = line.substring(0, colon).trim()
        if (!name.equals("Content-Type", ignoreCase = true)) {
            return headerState
        }
        val value = line.substring(colon + 1).trim().lowercase()
        if (value.contains("multipart/form-data")) {
            return STATE_HEADER_FORM
        }
        if (value.contains("application/x-www-form-urlencoded") || value.contains("application/www-form-urlencoded")) {
            return STATE_HEADER_FORM
        }
        if (value.contains("application/json") || value.contains("text/json") || value.contains("+json")) {
            return STATE_HEADER_JSON
        }
        if (value.contains("text/html") || value.contains("application/xhtml+xml")) {
            return STATE_HEADER_HTML
        }
        if (value.contains("text/event-stream")) {
            return STATE_HEADER_SSE
        }
        return STATE_HEADER_OTHER
    }

    private fun looksLikeJson(trimmed: String): Boolean {
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }

    private fun looksLikeHtml(trimmed: String): Boolean {
        return trimmed.startsWith("<")
    }

    private fun looksLikeForm(trimmed: String): Boolean {
        return trimmed.indexOf('=') > 0 && !trimmed.startsWith("{") && !trimmed.startsWith("[") && !trimmed.startsWith("<")
    }

    private fun tokenizeJsonLine(text: Segment, line: String, lineOffset: Int, startOffset: Int, from: Int = 0) {
        var i = from
        while (i < line.length) {
            val c = line[i]
            if (c.isWhitespace()) {
                val start = i
                while (i < line.length && line[i].isWhitespace()) {
                    i++
                }
                addTokenRange(text, lineOffset, startOffset, start, i - 1, TokenTypes.WHITESPACE)
                continue
            }
            if (c == '"') {
                val start = i
                i++
                var escaped = false
                while (i < line.length) {
                    val ch = line[i]
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        i++
                        break
                    }
                    i++
                }
                val tokenType = if (isJsonObjectKey(line, i)) TokenTypes.RESERVED_WORD else TokenTypes.LITERAL_STRING_DOUBLE_QUOTE
                addTokenRange(text, lineOffset, startOffset, start, i - 1, tokenType)
                continue
            }
            if (c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',') {
                addTokenRange(text, lineOffset, startOffset, i, i, TokenTypes.SEPARATOR)
                i++
                continue
            }
            if (c == '-' || c.isDigit()) {
                val start = i
                i++
                while (i < line.length) {
                    val ch = line[i]
                    if (!ch.isDigit() && ch != '.' && ch != 'e' && ch != 'E' && ch != '+' && ch != '-') {
                        break
                    }
                    i++
                }
                addTokenRange(text, lineOffset, startOffset, start, i - 1, TokenTypes.LITERAL_NUMBER_DECIMAL_INT)
                continue
            }
            if (line.startsWith("true", i) || line.startsWith("false", i) || line.startsWith("null", i)) {
                val len = if (line.startsWith("true", i)) 4 else if (line.startsWith("false", i)) 5 else 4
                addTokenRange(text, lineOffset, startOffset, i, i + len - 1, TokenTypes.RESERVED_WORD)
                i += len
                continue
            }
            addTokenRange(text, lineOffset, startOffset, i, i, TokenTypes.IDENTIFIER)
            i++
        }
    }

    private fun isJsonObjectKey(line: String, indexAfterString: Int): Boolean {
        var j = indexAfterString
        while (j < line.length && line[j].isWhitespace()) {
            j++
        }
        return j < line.length && line[j] == ':'
    }

    /**
     * SSE(text/event-stream)行高亮:`field: value` 的字段名(event/data/id/retry/comment)标为关键字,
     * `:` 标为分隔符;`data:` 值若为 JSON 则按 JSON 高亮,否则按字符串字面量;注释行(`: ...`)按注释。
     */
    private fun tokenizeSseLine(text: Segment, line: String, lineOffset: Int, startOffset: Int) {
        if (line.startsWith(":")) {
            addTokenRange(text, lineOffset, startOffset, 0, line.length - 1, TokenTypes.COMMENT_EOL)
            return
        }
        val colon = line.indexOf(':')
        if (colon <= 0) {
            addTokenRange(text, lineOffset, startOffset, 0, line.length - 1, TokenTypes.IDENTIFIER)
            return
        }
        addTokenRange(text, lineOffset, startOffset, 0, colon - 1, TokenTypes.RESERVED_WORD)
        addTokenRange(text, lineOffset, startOffset, colon, colon, TokenTypes.SEPARATOR)
        var valueStart = colon + 1
        while (valueStart < line.length && line[valueStart] == ' ') {
            valueStart++
        }
        if (valueStart > colon + 1) {
            addTokenRange(text, lineOffset, startOffset, colon + 1, valueStart - 1, TokenTypes.WHITESPACE)
        }
        if (valueStart >= line.length) {
            return
        }
        val ch = line[valueStart]
        if (ch == '{' || ch == '[') {
            tokenizeJsonLine(text, line, lineOffset, startOffset, valueStart)
        } else {
            addTokenRange(text, lineOffset, startOffset, valueStart, line.length - 1, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)
        }
    }

    private fun tokenizeFormLine(text: Segment, line: String, lineOffset: Int, startOffset: Int) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            addTokenRange(text, lineOffset, startOffset, 0, line.length - 1, TokenTypes.WHITESPACE)
            return
        }

        if (isHeaderLine(line)) {
            tokenizeHeaderLine(text, line, lineOffset, startOffset)
            return
        }

        if (line.indexOf('=') >= 0) {
            tokenizeParams(text, line, lineOffset, startOffset, 0, line.length, '&')
            return
        }

        addTokenRange(text, lineOffset, startOffset, 0, line.length - 1, TokenTypes.IDENTIFIER)
    }

    private fun tokenizeHtmlLine(text: Segment, line: String, lineOffset: Int, startOffset: Int) {
        var i = 0
        while (i < line.length) {
            val lt = line.indexOf('<', i)
            if (lt == -1) {
                addTokenRange(text, lineOffset, startOffset, i, line.length - 1, TokenTypes.IDENTIFIER)
                return
            }
            if (lt > i) {
                addTokenRange(text, lineOffset, startOffset, i, lt - 1, TokenTypes.IDENTIFIER)
            }
            val gt = line.indexOf('>', lt + 1)
            if (gt == -1) {
                addTokenRange(text, lineOffset, startOffset, lt, line.length - 1, TokenTypes.IDENTIFIER)
                return
            }
            addTokenRange(text, lineOffset, startOffset, lt, lt, TokenTypes.SEPARATOR)
            var j = lt + 1
            if (j < gt && line[j] == '/') {
                addTokenRange(text, lineOffset, startOffset, j, j, TokenTypes.SEPARATOR)
                j++
            }
            val nameStart = j
            while (j < gt && !line[j].isWhitespace() && line[j] != '/' && line[j] != '>') {
                j++
            }
            if (j > nameStart) {
                addTokenRange(text, lineOffset, startOffset, nameStart, j - 1, TokenTypes.RESERVED_WORD)
            }
            while (j < gt) {
                while (j < gt && line[j].isWhitespace()) {
                    addTokenRange(text, lineOffset, startOffset, j, j, TokenTypes.WHITESPACE)
                    j++
                }
                if (j >= gt) {
                    break
                }
                if (line[j] == '/') {
                    addTokenRange(text, lineOffset, startOffset, j, j, TokenTypes.SEPARATOR)
                    j++
                    continue
                }
                val attrStart = j
                while (j < gt && line[j] != '=' && !line[j].isWhitespace() && line[j] != '>') {
                    j++
                }
                if (j > attrStart) {
                    addTokenRange(text, lineOffset, startOffset, attrStart, j - 1, TokenTypes.IDENTIFIER)
                }
                while (j < gt && line[j].isWhitespace()) {
                    addTokenRange(text, lineOffset, startOffset, j, j, TokenTypes.WHITESPACE)
                    j++
                }
                if (j < gt && line[j] == '=') {
                    addTokenRange(text, lineOffset, startOffset, j, j, TokenTypes.SEPARATOR)
                    j++
                }
                while (j < gt && line[j].isWhitespace()) {
                    addTokenRange(text, lineOffset, startOffset, j, j, TokenTypes.WHITESPACE)
                    j++
                }
                if (j < gt && (line[j] == '"' || line[j] == '\'')) {
                    val quote = line[j]
                    val valueStart = j
                    j++
                    while (j < gt && line[j] != quote) {
                        if (line[j] == '\\' && j + 1 < gt) {
                            j += 2
                        } else {
                            j++
                        }
                    }
                    if (j < gt) {
                        j++
                    }
                    addTokenRange(text, lineOffset, startOffset, valueStart, j - 1, TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)
                }
            }
            addTokenRange(text, lineOffset, startOffset, gt, gt, TokenTypes.SEPARATOR)
            i = gt + 1
        }
    }

    companion object {
        private const val STATE_HEADER_BASE = -100
        private const val STATE_HEADER_JSON = STATE_HEADER_BASE - 1
        private const val STATE_HEADER_HTML = STATE_HEADER_BASE - 2
        private const val STATE_HEADER_FORM = STATE_HEADER_BASE - 3
        private const val STATE_HEADER_SSE = STATE_HEADER_BASE - 4
        private const val STATE_HEADER_OTHER = STATE_HEADER_BASE - 5

        private const val STATE_BODY_BASE = -120
        private const val STATE_BODY_JSON = STATE_BODY_BASE - 1
        private const val STATE_BODY_HTML = STATE_BODY_BASE - 2
        private const val STATE_BODY_FORM = STATE_BODY_BASE - 3
        private const val STATE_BODY_SSE = STATE_BODY_BASE - 4
        private const val STATE_BODY_OTHER = STATE_BODY_BASE - 5
    }
}
