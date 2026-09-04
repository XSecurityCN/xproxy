package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.proxy.model.ProxyInterceptRule
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRuleAction
import org.jjgroup.xproxy.proxy.model.ProxyInterceptRuleMode

// 按 matchText 缓存已编译的拦截规则正则(选项固定为 IGNORE_CASE + DOT_MATCHES_ALL),避免每条请求重新编译。
private val interceptRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

class ProxyInterceptRuleEngine {
    @Volatile
    private var rulesSnapshot: List<ProxyInterceptRule> = emptyList()

    fun setRules(rules: List<ProxyInterceptRule>) {
        rulesSnapshot = rules.map { it.copy() }
    }

    fun decideForRequest(requestRaw: String): ProxyInterceptRuleAction? {
        val parsed = splitHeadersBody(requestRaw)
        return decide(
            requestHeader = parsed.first,
            requestBody = parsed.second,
            responseHeader = "",
            responseBody = ""
        )
    }

    fun decideForResponse(requestRaw: String, responseRaw: String): ProxyInterceptRuleAction? {
        val req = splitHeadersBody(requestRaw)
        val resp = splitHeadersBody(responseRaw)
        return decide(
            requestHeader = req.first,
            requestBody = req.second,
            responseHeader = resp.first,
            responseBody = resp.second
        )
    }

    private fun decide(
        requestHeader: String,
        requestBody: String,
        responseHeader: String,
        responseBody: String
    ): ProxyInterceptRuleAction? =
        rulesSnapshot
            .filter { it.enabled && it.matchText.isNotEmpty() }
            .filter { it.matchRequestHeader || it.matchRequestBody || it.matchResponseHeader || it.matchResponseBody }
            .firstOrNull { matchesRule(it, requestHeader, requestBody, responseHeader, responseBody) }
            ?.action

    private fun matchesRule(
        rule: ProxyInterceptRule,
        requestHeader: String,
        requestBody: String,
        responseHeader: String,
        responseBody: String
    ): Boolean {
        val text = buildString {
            if (rule.matchRequestHeader) append(requestHeader).append('\n')
            if (rule.matchRequestBody) append(requestBody).append('\n')
            if (rule.matchResponseHeader) append(responseHeader).append('\n')
            if (rule.matchResponseBody) append(responseBody)
        }
        return when (rule.mode) {
            ProxyInterceptRuleMode.TEXT -> text.contains(rule.matchText, ignoreCase = true)
            ProxyInterceptRuleMode.REGEX -> {
                runCatching {
                    interceptRegexCache.getOrPut(rule.matchText) {
                        Regex(rule.matchText, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    }.containsMatchIn(text)
                }.getOrDefault(false)
            }
        }
    }

    private fun splitHeadersBody(raw: String): Pair<String, String> {
        val crlf = raw.indexOf("\r\n\r\n")
        if (crlf >= 0) {
            return raw.substring(0, crlf) to raw.substring(crlf + 4)
        }
        val lf = raw.indexOf("\n\n")
        if (lf >= 0) {
            return raw.substring(0, lf) to raw.substring(lf + 2)
        }
        return raw to ""
    }
}
