package org.jjgroup.xproxy.proxy.core

import org.jjgroup.xproxy.engine.http.uncompressIfNecessary
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceDebugResult
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceAction
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceMode
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceRule
import org.jjgroup.xproxy.proxy.model.ProxyMatchReplaceScope
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion

class ProxyMatchReplaceEngine {
    // 按 scope 预分区的规则快照:setRules 时一次分区,applyToRequest/Response 热路径直接读对应列表,
    // 避免每请求/响应 5 次 rules.filter 各分配新 ArrayList。整个快照为不可变对象,@Volatile 单引用保证可见性与原子性。
    private data class RulePartitions(
        val requestFirstLine: List<ProxyMatchReplaceRule> = emptyList(),
        val requestHeader: List<ProxyMatchReplaceRule> = emptyList(),
        val requestBody: List<ProxyMatchReplaceRule> = emptyList(),
        val responseHeader: List<ProxyMatchReplaceRule> = emptyList(),
        val responseBody: List<ProxyMatchReplaceRule> = emptyList(),
    ) {
        val isEmpty: Boolean get() =
            requestFirstLine.isEmpty() && requestHeader.isEmpty() && requestBody.isEmpty() &&
                responseHeader.isEmpty() && responseBody.isEmpty()
    }

    @Volatile
    private var partitions: RulePartitions = RulePartitions()

    fun setRules(rules: List<ProxyMatchReplaceRule>) {
        val copied = rules.map { it.copy() }
        partitions = RulePartitions(
            requestFirstLine = copied.filter { it.enabled && it.scope == ProxyMatchReplaceScope.REQUEST_FIRST_LINE },
            requestHeader = copied.filter { it.enabled && it.scope == ProxyMatchReplaceScope.REQUEST_HEADER },
            requestBody = copied.filter { it.enabled && it.scope == ProxyMatchReplaceScope.REQUEST_BODY },
            responseHeader = copied.filter { it.enabled && it.scope == ProxyMatchReplaceScope.RESPONSE_HEADER },
            responseBody = copied.filter { it.enabled && it.scope == ProxyMatchReplaceScope.RESPONSE_BODY },
        )
    }

    fun applyToRequest(request: FullHttpRequest): Boolean {
        val p = partitions
        if (p.isEmpty) {
            return false
        }
        var changed = false
        val requestFirstLineRules = p.requestFirstLine
        val requestHeaderRules = p.requestHeader
        val requestBodyRules = p.requestBody

        if (requestFirstLineRules.isNotEmpty()) {
            val currentFirstLine = "${request.method().name()} ${request.uri()} ${request.protocolVersion().text()}"
            val replacedFirstLine = applyRulesToText(currentFirstLine, requestFirstLineRules)
            if (replacedFirstLine.replacementCount > 0) {
                val candidate = replacedFirstLine.output
                if (candidate.contains('\r') || candidate.contains('\n')) {
                    return changed
                }
                val parts = candidate.trim().split(Regex("\\s+"), limit = 3)
                if (parts.size == 3 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts[2].isNotBlank()) {
                    runCatching {
                        request.setMethod(HttpMethod.valueOf(parts[0]))
                        request.setUri(parts[1])
                        request.setProtocolVersion(HttpVersion.valueOf(parts[2]))
                        changed = true
                    }
                }
            }
        }

        if (requestHeaderRules.isNotEmpty()) {
            val addRules = requestHeaderRules.filter { isHeaderAdditionRule(it) }
            val valueRules = requestHeaderRules.filterNot { isHeaderAdditionRule(it) }

            for (name in request.headers().names().toList()) {
                val values = request.headers().getAll(name)
                var headerChanged = false
                val replacedValues = values.map { value ->
                    val result = applyRulesToText(value, valueRules)
                    if (result.replacementCount > 0) {
                        headerChanged = true
                    }
                    result.output
                }
                if (headerChanged) {
                    request.headers().set(name, replacedValues)
                    changed = true
                }
            }

            addRules.forEach { rule ->
                val definition = parseHeaderDefinition(rule.replaceText)
                if (definition != null) {
                    val headerName = definition.first
                    val headerValue = definition.second
                    if (headerName.equals("Cookie", ignoreCase = true)) {
                        val existing = request.headers().get(headerName)
                        if (existing.isNullOrBlank()) {
                            request.headers().set(headerName, headerValue)
                            changed = true
                        } else if (!existing.contains(headerValue)) {
                            request.headers().set(headerName, "$existing; $headerValue")
                            changed = true
                        }
                    } else if (!request.headers().contains(headerName)) {
                        request.headers().set(headerName, headerValue)
                        changed = true
                    }
                }
            }
        }

        if (requestBodyRules.isNotEmpty()) {
            val bodyBytes = ByteArray(request.content().readableBytes())
            request.content().getBytes(request.content().readerIndex(), bodyBytes)
            val bodyText = String(bodyBytes, Charsets.ISO_8859_1)
            val replaced = applyRulesToText(bodyText, requestBodyRules)
            if (replaced.replacementCount > 0) {
                val newBody = replaced.output.toByteArray(Charsets.ISO_8859_1)
                request.content().clear()
                if (newBody.isNotEmpty()) {
                    request.content().writeBytes(newBody)
                }
                request.headers().set("Content-Length", newBody.size.toString())
                request.headers().remove("Transfer-Encoding")
                changed = true
            }
        }

        return changed
    }

    fun applyToResponse(response: FullHttpResponse): Boolean {
        val p = partitions
        if (p.isEmpty) {
            return false
        }
        var changed = false
        val responseHeaderRules = p.responseHeader
        val responseBodyRules = p.responseBody

        if (responseHeaderRules.isNotEmpty()) {
            val addRules = responseHeaderRules.filter { isHeaderAdditionRule(it) }
            val valueRules = responseHeaderRules.filterNot { isHeaderAdditionRule(it) }
            for (name in response.headers().names().toList()) {
                val values = response.headers().getAll(name)
                var headerChanged = false
                val replacedValues = values.map { value ->
                    val result = applyRulesToText(value, valueRules)
                    if (result.replacementCount > 0) {
                        headerChanged = true
                    }
                    result.output
                }
                if (headerChanged) {
                    response.headers().set(name, replacedValues)
                    changed = true
                }
            }

            addRules.forEach { rule ->
                val definition = parseHeaderDefinition(rule.replaceText)
                if (definition != null) {
                    val headerName = definition.first
                    val headerValue = definition.second
                    if (!response.headers().contains(headerName)) {
                        response.headers().set(headerName, headerValue)
                        changed = true
                    }
                }
            }
        }

        if (responseBodyRules.isNotEmpty()) {
            val bodyBytes = ByteArray(response.content().readableBytes())
            response.content().getBytes(response.content().readerIndex(), bodyBytes)
            val headersText = response.headers().entries().joinToString("\r\n") { "${it.key}: ${it.value}" }
            val bodyText = uncompressIfNecessary(headersText, String(bodyBytes, Charsets.ISO_8859_1))
            val replaced = applyRulesToText(bodyText, responseBodyRules)
            if (replaced.replacementCount > 0) {
                val newBody = replaced.output.toByteArray(Charsets.ISO_8859_1)
                response.content().clear()
                if (newBody.isNotEmpty()) {
                    response.content().writeBytes(newBody)
                }
                removeBodyTransformHeaders(response)
                response.headers().set("Content-Length", newBody.size.toString())
                changed = true
            }
        }

        return changed
    }

    companion object {
        // 按 matchText 缓存已编译的 Regex,避免每条请求都重新 Pattern.compile。
        private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

        fun debugApply(
            input: String,
            scope: ProxyMatchReplaceScope,
            rules: List<ProxyMatchReplaceRule>
        ): ProxyMatchReplaceDebugResult {
            val scopedRules = rules.filter { it.enabled && it.scope == scope }
            if (scopedRules.isEmpty()) {
                return ProxyMatchReplaceDebugResult(input, 0, 0)
            }

            if (scope == ProxyMatchReplaceScope.REQUEST_FIRST_LINE) {
                val firstLineRange = firstLineRange(input)
                    ?: return applyRulesToText(input, scopedRules)
                val firstLine = input.substring(firstLineRange.first, firstLineRange.second)
                val rest = input.substring(firstLineRange.second)
                val replaced = applyRulesToText(firstLine, scopedRules)
                return ProxyMatchReplaceDebugResult(
                    output = replaced.output + rest,
                    replacementCount = replaced.replacementCount,
                    matchedRuleCount = replaced.matchedRuleCount
                )
            }

            if (scope == ProxyMatchReplaceScope.REQUEST_HEADER || scope == ProxyMatchReplaceScope.RESPONSE_HEADER) {
                val additions = scopedRules
                    .filter { isHeaderAdditionRule(it) }
                    .mapNotNull { parseHeaderDefinition(it.replaceText) }
                    .distinctBy { it.first.lowercase() + "\u0000" + it.second }
                val valueRules = scopedRules.filterNot { isHeaderAdditionRule(it) }
                val separator = when {
                    input.contains("\r\n") -> "\r\n"
                    input.contains("\n") -> "\n"
                    else -> "\n"
                }
                val headerSplit = "$separator$separator"
                val idx = input.indexOf(headerSplit)
                val headerPart = if (idx >= 0) input.substring(0, idx) else input
                val bodyPart = if (idx >= 0) input.substring(idx + headerSplit.length) else ""
                val lines = headerPart.split(separator)
                if (lines.isEmpty()) {
                    return ProxyMatchReplaceDebugResult(input, 0, 0)
                }

                val firstLine = lines.first()
                val headerLines = lines.drop(1).toMutableList()
                var replacements = 0
                var matchedRules = 0

                headerLines.forEachIndexed { i, line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val name = line.substring(0, colon)
                        val value = line.substring(colon + 1).trimStart()
                        val result = applyRulesToText(value, valueRules)
                        if (result.replacementCount > 0) {
                            headerLines[i] = "$name: ${result.output}"
                            replacements += result.replacementCount
                            matchedRules += result.matchedRuleCount
                        }
                    }
                }

                additions.forEach { (name, value) ->
                    val headerIndex = headerLines.indexOfFirst { it.startsWith("$name:", ignoreCase = true) }
                    if (name.equals("Cookie", ignoreCase = true)) {
                        if (headerIndex < 0) {
                            headerLines.add("$name: $value")
                            replacements += 1
                            matchedRules += 1
                        } else {
                            val existingValue = headerLines[headerIndex].substringAfter(':').trim()
                            if (!existingValue.contains(value)) {
                                headerLines[headerIndex] = "$name: $existingValue; $value"
                                replacements += 1
                                matchedRules += 1
                            }
                        }
                    } else if (headerIndex < 0) {
                        headerLines.add("$name: $value")
                        replacements += 1
                        matchedRules += 1
                    }
                }

                val rebuiltHeaders = buildString {
                    append(firstLine)
                    if (headerLines.isNotEmpty()) {
                        append(separator)
                        append(headerLines.joinToString(separator))
                    }
                }
                val rebuiltWithAdditions = if (idx >= 0) "$rebuiltHeaders$headerSplit$bodyPart" else rebuiltHeaders
                return ProxyMatchReplaceDebugResult(
                    output = rebuiltWithAdditions,
                    replacementCount = replacements,
                    matchedRuleCount = matchedRules
                )
            }

            return applyRulesToText(input, scopedRules)
        }

        private fun applyRulesToText(input: String, rules: List<ProxyMatchReplaceRule>): ProxyMatchReplaceDebugResult {
            if (rules.isEmpty()) {
                return ProxyMatchReplaceDebugResult(input, 0, 0)
            }

            var text = input
            var totalReplacements = 0
            var matchedRuleCount = 0

            for (rule in rules) {
                if (rule.matchText.isEmpty() && rule.action != ProxyMatchReplaceAction.ADD) {
                    continue
                }
                val result = applyRule(text, rule)
                if (result.replacements > 0) {
                    totalReplacements += result.replacements
                    matchedRuleCount += 1
                }
                text = result.output
            }
            return ProxyMatchReplaceDebugResult(text, totalReplacements, matchedRuleCount)
        }

        private data class RuleApplyResult(val output: String, val replacements: Int)

        private fun applyRule(input: String, rule: ProxyMatchReplaceRule): RuleApplyResult {
            return when (rule.mode) {
                ProxyMatchReplaceMode.TEXT -> {
                    when (rule.action) {
                        ProxyMatchReplaceAction.REPLACE -> {
                            val count = countOccurrences(input, rule.matchText)
                            if (count == 0) {
                                RuleApplyResult(input, 0)
                            } else {
                                RuleApplyResult(input.replace(rule.matchText, rule.replaceText), count)
                            }
                        }

                        ProxyMatchReplaceAction.REMOVE -> {
                            val count = countOccurrences(input, rule.matchText)
                            if (count == 0) {
                                RuleApplyResult(input, 0)
                            } else {
                                RuleApplyResult(input.replace(rule.matchText, ""), count)
                            }
                        }

                        ProxyMatchReplaceAction.ADD -> {
                            if (rule.matchText.isEmpty()) {
                                if (rule.replaceText.isEmpty()) {
                                    RuleApplyResult(input, 0)
                                } else {
                                    RuleApplyResult(input + rule.replaceText, 1)
                                }
                            } else {
                                val count = countOccurrences(input, rule.matchText)
                                if (count == 0) {
                                    RuleApplyResult(input, 0)
                                } else {
                                    RuleApplyResult(input.replace(rule.matchText, rule.matchText + rule.replaceText), count)
                                }
                            }
                        }
                    }
                }

                ProxyMatchReplaceMode.REGEX -> {
                    try {
                        if (rule.matchText.isEmpty()) {
                            return RuleApplyResult(input, 0)
                        }
                        // 正则编译开销远大于匹配,按 matchText 缓存编译结果;非法正则编译失败时不会写入缓存(与原逻辑一致,返回原输入)。
                        val regex = regexCache.getOrPut(rule.matchText) { Regex(rule.matchText) }
                        val count = regex.findAll(input).count()
                        if (count == 0) {
                            RuleApplyResult(input, 0)
                        } else {
                            when (rule.action) {
                                ProxyMatchReplaceAction.REPLACE -> RuleApplyResult(regex.replace(input, rule.replaceText), count)
                                ProxyMatchReplaceAction.REMOVE -> RuleApplyResult(regex.replace(input, ""), count)
                                ProxyMatchReplaceAction.ADD -> RuleApplyResult(
                                    regex.replace(input) { mr -> mr.value + rule.replaceText },
                                    count
                                )
                            }
                        }
                    } catch (_: Exception) {
                        RuleApplyResult(input, 0)
                    }
                }
            }
        }

        private fun isHeaderAdditionRule(rule: ProxyMatchReplaceRule): Boolean {
            return rule.action == ProxyMatchReplaceAction.ADD && parseHeaderDefinition(rule.replaceText) != null
        }

        private fun countOccurrences(input: String, needle: String): Int =
            if (needle.isEmpty()) 0
            else generateSequence(input.indexOf(needle).takeIf { it >= 0 }) { prev ->
                input.indexOf(needle, prev + needle.length).takeIf { it >= 0 }
            }.count()

        private fun parseHeaderDefinition(raw: String): Pair<String, String>? {
            val line = raw.trim()
            if (line.isEmpty()) {
                return null
            }
            val idx = line.indexOf(':')
            if (idx <= 0 || idx >= line.length - 1) {
                return null
            }
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (name.isEmpty() || value.isEmpty()) {
                return null
            }
            return name to value
        }

        private fun firstLineRange(input: String): Pair<Int, Int>? {
            if (input.isEmpty()) {
                return null
            }
            val end = input.indexOfAny(charArrayOf('\r', '\n'))
            return if (end < 0) {
                0 to input.length
            } else {
                0 to end
            }
        }
    }
}
