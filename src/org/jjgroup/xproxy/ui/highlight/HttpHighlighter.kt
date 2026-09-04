package org.jjgroup.xproxy.ui.highlight

import java.awt.Color
import org.fife.ui.rsyntaxtextarea.AbstractTokenMakerFactory
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory
import org.fife.ui.rsyntaxtextarea.TokenTypes
import javax.swing.UIManager

object HttpHighlighter {
    private const val SYNTAX_STYLE = "text/http-request"
    private const val HEADERS_ONLY_STYLE = "text/http-headers-only"
    private var registered = false

    fun attach(textArea: RSyntaxTextArea) {
        registerTokenMaker()
        if (textArea.syntaxEditingStyle == SYNTAX_STYLE) {
            return
        }
        textArea.syntaxEditingStyle = SYNTAX_STYLE
        applyScheme(textArea)
    }

    fun apply(textArea: RSyntaxTextArea) {
        attach(textArea)
    }

    fun attachHeadersOnly(textArea: RSyntaxTextArea) {
        registerTokenMaker()
        if (textArea.syntaxEditingStyle == HEADERS_ONLY_STYLE) {
            return
        }
        textArea.syntaxEditingStyle = HEADERS_ONLY_STYLE
        applyScheme(textArea)
    }

    fun setPlain(textArea: RSyntaxTextArea) {
        if (textArea.syntaxEditingStyle != SyntaxConstants.SYNTAX_STYLE_NONE) {
            textArea.syntaxEditingStyle = SyntaxConstants.SYNTAX_STYLE_NONE
        }
    }

    private fun registerTokenMaker() {
        if (registered) {
            return
        }
        val factory = TokenMakerFactory.getDefaultInstance() as AbstractTokenMakerFactory
        factory.putMapping(SYNTAX_STYLE, "org.jjgroup.xproxy.ui.highlight.HttpRequestTokenMaker")
        factory.putMapping(HEADERS_ONLY_STYLE, "org.jjgroup.xproxy.ui.highlight.HttpHeaderOnlyTokenMaker")
        registered = true
    }

    private fun applyScheme(textArea: RSyntaxTextArea) {
        val isDark = (UIManager.get("laf.dark") as? Boolean) == true
        fun themeColor(dark: Color, light: Color) = if (isDark) dark else light
        val scheme = textArea.syntaxScheme
        val panelBg = UIManager.getColor("TextArea.background") ?: UIManager.getColor("Panel.background")
        val textFg = UIManager.getColor("TextArea.foreground") ?: UIManager.getColor("Label.foreground")
        textArea.background = panelBg
        textArea.foreground = textFg
        textArea.caretColor = textFg
        textArea.currentLineHighlightColor = themeColor(Color(64, 68, 75), Color(230, 230, 230))
        scheme.getStyle(TokenTypes.RESERVED_WORD).foreground = themeColor(Color(137, 180, 250), Color(24, 68, 176))
        scheme.getStyle(TokenTypes.RESERVED_WORD_2).foreground = themeColor(Color(148, 226, 213), Color(0, 113, 98))
        scheme.getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground = themeColor(Color(166, 227, 161), Color(176, 30, 20))
        scheme.getStyle(TokenTypes.LITERAL_NUMBER_DECIMAL_INT).foreground = themeColor(Color(203, 166, 247), Color(111, 66, 193))
        scheme.getStyle(TokenTypes.MARKUP_TAG_NAME).foreground = themeColor(Color(249, 226, 175), Color(173, 102, 0))
        scheme.getStyle(TokenTypes.MARKUP_TAG_DELIMITER).foreground = themeColor(Color(255, 167, 38), Color(224, 108, 0))
        scheme.getStyle(TokenTypes.VARIABLE).foreground = themeColor(Color(255, 140, 0), Color(204, 85, 0))
        scheme.getStyle(TokenTypes.IDENTIFIER).foreground = textFg
        scheme.getStyle(TokenTypes.WHITESPACE).foreground = textFg
        scheme.getStyle(TokenTypes.SEPARATOR).foreground = textFg
        textArea.syntaxScheme = scheme
    }
}
