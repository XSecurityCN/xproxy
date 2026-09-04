package org.jjgroup.xproxy.ui.highlight

import javax.swing.text.Segment
import org.fife.ui.rsyntaxtextarea.AbstractTokenMaker
import org.fife.ui.rsyntaxtextarea.Token
import org.fife.ui.rsyntaxtextarea.TokenMap
import org.fife.ui.rsyntaxtextarea.TokenTypes

class HttpHeaderOnlyTokenMaker : AbstractTokenMaker() {
    override fun getWordsToHighlight(): TokenMap = TokenMap()

    override fun getTokenList(text: Segment, startTokenType: Int, startOffset: Int): Token {
        resetTokenList()
        val lineOffset = text.offset
        val lineCount = text.count
        val state = if (startTokenType == TokenTypes.NULL) STATE_HEADER else startTokenType

        if (lineCount == 0) {
            addNullTokenWithType(if (state == STATE_BODY) STATE_BODY else STATE_HEADER)
            return firstToken
        }

        // Critical performance path: after the blank header/body separator, body lines are always
        // plain text. Do not allocate String/trim/scan header syntax while scrolling large bodies.
        if (state == STATE_BODY) {
            addToken(text, lineOffset, lineOffset + lineCount - 1, TokenTypes.IDENTIFIER, startOffset)
            addNullTokenWithType(STATE_BODY)
            return firstToken
        }

        if (isBlankLine(text, lineOffset, lineCount)) {
            addNullTokenWithType(STATE_BODY)
            return firstToken
        }

        val colon = headerColonIndex(text, lineOffset, lineCount)
        if (colon > lineOffset) {
            tokenizeHeaderLine(text, lineOffset, lineCount, startOffset, colon)
        } else {
            addToken(text, lineOffset, lineOffset + lineCount - 1, TokenTypes.IDENTIFIER, startOffset)
        }
        addNullTokenWithType(STATE_HEADER)
        return firstToken
    }

    private fun isBlankLine(text: Segment, lineOffset: Int, lineCount: Int): Boolean {
        val end = lineOffset + lineCount
        for (i in lineOffset until end) {
            if (!text.array[i].isWhitespace()) return false
        }
        return true
    }

    private fun headerColonIndex(text: Segment, lineOffset: Int, lineCount: Int): Int {
        val end = lineOffset + lineCount
        var i = lineOffset
        while (i < end) {
            val ch = text.array[i]
            if (ch == ':') {
                return if (i == lineOffset) -1 else i
            }
            if (!(ch.isLetterOrDigit() || ch == '-')) {
                return -1
            }
            i++
        }
        return -1
    }

    private fun tokenizeHeaderLine(text: Segment, lineOffset: Int, lineCount: Int, startOffset: Int, colon: Int) {
        val lineEnd = lineOffset + lineCount - 1
        addToken(text, lineOffset, colon - 1, TokenTypes.RESERVED_WORD, startOffset)
        addToken(text, colon, colon, TokenTypes.SEPARATOR, startOffset + (colon - lineOffset))
        if (colon + 1 <= lineEnd) {
            addToken(text, colon + 1, lineEnd, TokenTypes.IDENTIFIER, startOffset + (colon + 1 - lineOffset))
        }
    }

    private fun addNullTokenWithType(type: Int) {
        addNullToken()
        currentToken.type = type
    }

    companion object {
        private const val STATE_HEADER = -200
        private const val STATE_BODY = -201
    }
}
