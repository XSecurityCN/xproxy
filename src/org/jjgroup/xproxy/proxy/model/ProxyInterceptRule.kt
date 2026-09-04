package org.jjgroup.xproxy.proxy.model

import java.util.UUID

enum class ProxyInterceptRuleMode(val label: String) {
    TEXT("Text"),
    REGEX("Regex");

    override fun toString(): String = label
}

enum class ProxyInterceptRuleAction(val label: String) {
    FORWARD("Auto forward"),
    DROP("Auto drop");

    override fun toString(): String = label
}

data class ProxyInterceptRule(
    val ruleId: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var name: String = "Rule",
    var mode: ProxyInterceptRuleMode = ProxyInterceptRuleMode.TEXT,
    var matchText: String = "",
    var action: ProxyInterceptRuleAction = ProxyInterceptRuleAction.FORWARD,
    var matchRequestHeader: Boolean = true,
    var matchRequestBody: Boolean = true,
    var matchResponseHeader: Boolean = true,
    var matchResponseBody: Boolean = true
) {
    fun scopeSummary(): String {
        val selected = ArrayList<String>()
        if (matchRequestHeader) selected.add("Req H")
        if (matchRequestBody) selected.add("Req B")
        if (matchResponseHeader) selected.add("Resp H")
        if (matchResponseBody) selected.add("Resp B")
        return if (selected.isEmpty()) "None" else selected.joinToString(" + ")
    }
}
