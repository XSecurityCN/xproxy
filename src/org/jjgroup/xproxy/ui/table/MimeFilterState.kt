package org.jjgroup.xproxy.ui.table

import org.jjgroup.xproxy.fuzzer.request.splitMessage

/**
 * 不可变的过滤器快照,可在任意线程(含后台扫描线程)上安全使用。
 * 抽离自 [MimeFilterState] 的全部匹配逻辑,使"关键词过滤全表扫描"能脱离 EDT 在后台线程执行,
 * 避免有界缓存启用后 `applyFilters` 退化为 N+1 同步 DB 查询(13 万行 → EDT 冻结数十秒)。
 */
data class MimeFilterSnapshot(
    val allTypes: Set<String>,
    val statusBuckets: Set<String>,
    val selectedTypes: Set<String>,
    val selectedStatusBuckets: Set<String>,
    val keyword: String,
    val keywordRegex: Boolean,
    val keywordCaseSensitive: Boolean,
    val keywordScopeRequestHeader: Boolean,
    val keywordScopeRequestBody: Boolean,
    val keywordScopeResponseHeader: Boolean,
    val keywordScopeResponseBody: Boolean,
    val logicMode: MimeFilterState.LogicMode,
) {
    fun isKeywordActive(): Boolean = keyword.trim().isNotEmpty()

    fun matchesHttp(mimeType: String, statusCode: Int, requestRaw: String, responseRaw: String): Boolean {
        val checks = ArrayList<Boolean>()

        if (selectedTypes.size < allTypes.size) {
            checks.add(selectedTypes.contains(mimeType.lowercase()))
        }

        if (selectedStatusBuckets.size < statusBuckets.size) {
            checks.add(matchesStatus(statusCode))
        }

        if (isKeywordActive()) {
            checks.add(matchesHttpKeywordByScope(requestRaw, responseRaw))
        }

        if (checks.isEmpty()) {
            return true
        }
        return if (logicMode == MimeFilterState.LogicMode.AND) checks.all { it } else checks.any { it }
    }

    fun matchesWs(mimeType: String, searchableText: String): Boolean {
        val checks = ArrayList<Boolean>()

        if (selectedTypes.size < allTypes.size) {
            checks.add(selectedTypes.contains(mimeType.lowercase()))
        }

        if (isKeywordActive()) {
            checks.add(matchesKeyword(searchableText))
        }

        if (checks.isEmpty()) {
            return true
        }
        return if (logicMode == MimeFilterState.LogicMode.AND) checks.all { it } else checks.any { it }
    }

    private fun matchesStatus(statusCode: Int): Boolean {
        val bucket = when (statusCode / 100) {
            1 -> "1xx"
            2 -> "2xx"
            3 -> "3xx"
            4 -> "4xx"
            5 -> "5xx"
            else -> "other"
        }
        return selectedStatusBuckets.contains(bucket)
    }

    private fun matchesKeyword(text: String): Boolean {
        val needle = keyword.trim()
        if (needle.isEmpty()) {
            return true
        }
        return if (keywordRegex) {
            try {
                if (keywordCaseSensitive) {
                    Regex(needle).containsMatchIn(text)
                } else {
                    Regex(needle, RegexOption.IGNORE_CASE).containsMatchIn(text)
                }
            } catch (_: Exception) {
                false
            }
        } else {
            text.contains(needle, ignoreCase = !keywordCaseSensitive)
        }
    }

    private fun matchesHttpKeywordByScope(requestRaw: String, responseRaw: String): Boolean {
        val scopeSelected = keywordScopeRequestHeader || keywordScopeRequestBody || keywordScopeResponseHeader || keywordScopeResponseBody
        if (!scopeSelected) {
            return false
        }

        val requestParsed = splitMessage(requestRaw)
        val responseParsed = splitMessage(responseRaw)
        val scopedText = buildString {
            if (keywordScopeRequestHeader) {
                append(requestParsed.headers)
                append('\n')
            }
            if (keywordScopeRequestBody) {
                append(requestParsed.body)
                append('\n')
            }
            if (keywordScopeResponseHeader) {
                append(responseParsed.headers)
                append('\n')
            }
            if (keywordScopeResponseBody) {
                append(responseParsed.body)
            }
        }
        return matchesKeyword(scopedText)
    }
}

class MimeFilterState {
    enum class LogicMode { AND, OR }

    private val allTypes = listOf("text", "json", "xml", "html", "script", "css", "image", "sse", "bin", "other")
    private val defaultTypes = linkedSetOf("text", "json", "xml", "html", "sse")
    private val selectedTypes = LinkedHashSet<String>(defaultTypes)
    private val statusBuckets = listOf("1xx", "2xx", "3xx", "4xx", "5xx")
    private val selectedStatusBuckets = LinkedHashSet<String>(statusBuckets)
    private var keyword: String = ""
    private var keywordRegex: Boolean = false
    private var keywordCaseSensitive: Boolean = false
    private var keywordScopeRequestHeader: Boolean = true
    private var keywordScopeRequestBody: Boolean = true
    private var keywordScopeResponseHeader: Boolean = true
    private var keywordScopeResponseBody: Boolean = true
    private var logicMode: LogicMode = LogicMode.AND
    private val listeners = ArrayList<() -> Unit>()

    /** 捕获当前状态的不可变快照(应在 EDT 调用以保证字段一致性),供后台线程扫描过滤使用。 */
    fun snapshot(): MimeFilterSnapshot = MimeFilterSnapshot(
        allTypes = allTypes.toSet(),
        statusBuckets = statusBuckets.toSet(),
        selectedTypes = LinkedHashSet(selectedTypes),
        selectedStatusBuckets = LinkedHashSet(selectedStatusBuckets),
        keyword = keyword,
        keywordRegex = keywordRegex,
        keywordCaseSensitive = keywordCaseSensitive,
        keywordScopeRequestHeader = keywordScopeRequestHeader,
        keywordScopeRequestBody = keywordScopeRequestBody,
        keywordScopeResponseHeader = keywordScopeResponseHeader,
        keywordScopeResponseBody = keywordScopeResponseBody,
        logicMode = logicMode,
    )

    fun types(): List<String> = allTypes

    fun selectedTypes(): Set<String> = LinkedHashSet(selectedTypes)

    fun defaultTypes(): Set<String> = LinkedHashSet(defaultTypes)

    fun statusBuckets(): List<String> = statusBuckets

    fun selectedStatusBuckets(): Set<String> = LinkedHashSet(selectedStatusBuckets)

    fun keyword(): String = keyword

    fun keywordRegex(): Boolean = keywordRegex

    fun keywordCaseSensitive(): Boolean = keywordCaseSensitive

    fun keywordScopeRequestHeader(): Boolean = keywordScopeRequestHeader

    fun keywordScopeRequestBody(): Boolean = keywordScopeRequestBody

    fun keywordScopeResponseHeader(): Boolean = keywordScopeResponseHeader

    fun keywordScopeResponseBody(): Boolean = keywordScopeResponseBody

    fun logicMode(): LogicMode = logicMode

    fun isAllowed(type: String): Boolean {
        return selectedTypes.contains(type.lowercase())
    }

    fun setAllowed(type: String, allowed: Boolean) {
        val key = type.lowercase()
        if (allowed) {
            selectedTypes.add(key)
        } else {
            selectedTypes.remove(key)
        }
        emitChange()
    }

    fun setStatusBucketAllowed(bucket: String, allowed: Boolean) {
        val key = bucket.lowercase()
        if (allowed) {
            selectedStatusBuckets.add(key)
        } else {
            selectedStatusBuckets.remove(key)
        }
        emitChange()
    }

    fun setKeyword(value: String) {
        keyword = value
        emitChange()
    }

    fun setKeywordRegex(enabled: Boolean) {
        keywordRegex = enabled
        emitChange()
    }

    fun setKeywordCaseSensitive(enabled: Boolean) {
        keywordCaseSensitive = enabled
        emitChange()
    }

    fun setKeywordScopeRequestHeader(enabled: Boolean) {
        keywordScopeRequestHeader = enabled
        emitChange()
    }

    fun setKeywordScopeRequestBody(enabled: Boolean) {
        keywordScopeRequestBody = enabled
        emitChange()
    }

    fun setKeywordScopeResponseHeader(enabled: Boolean) {
        keywordScopeResponseHeader = enabled
        emitChange()
    }

    fun setKeywordScopeResponseBody(enabled: Boolean) {
        keywordScopeResponseBody = enabled
        emitChange()
    }

    fun setLogicMode(mode: LogicMode) {
        logicMode = mode
        emitChange()
    }

    fun replaceSelectedTypes(types: Set<String>) {
        selectedTypes.clear()
        selectedTypes.addAll(types.map { it.lowercase() }.filter { allTypes.contains(it) })
        emitChange()
    }

    fun resetDefault() {
        replaceSelectedTypes(defaultTypes)
    }

    fun showAll() {
        replaceSelectedTypes(allTypes.toSet())
    }

    fun hideAll() {
        replaceSelectedTypes(emptySet())
    }

    fun matchesHttp(mimeType: String, statusCode: Int, requestRaw: String, responseRaw: String): Boolean =
        snapshot().matchesHttp(mimeType, statusCode, requestRaw, responseRaw)

    fun matchesWs(mimeType: String, searchableText: String): Boolean =
        snapshot().matchesWs(mimeType, searchableText)

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private var inBatch = false
    private var batchDirty = false

    /** 批量提交多个 setter 变更,期间抑制 emitChange 通知,结束时仅通知一次(用于 Apply 的 14 个 setter)。 */
    fun batch(block: MimeFilterState.() -> Unit) {
        inBatch = true
        try {
            block()
        } finally {
            inBatch = false
            if (batchDirty) {
                batchDirty = false
                listeners.forEach { it.invoke() }
            }
        }
    }

    private fun emitChange() {
        if (inBatch) {
            batchDirty = true
            return
        }
        listeners.forEach { it.invoke() }
    }
}
