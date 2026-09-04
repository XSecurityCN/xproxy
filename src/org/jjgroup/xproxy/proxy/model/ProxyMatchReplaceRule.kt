package org.jjgroup.xproxy.proxy.model

import java.util.UUID

enum class ProxyMatchReplaceScope(val label: String) {
    REQUEST_FIRST_LINE("Request First Line"),
    REQUEST_HEADER("Request Header"),
    REQUEST_BODY("Request Body"),
    RESPONSE_HEADER("Response Header"),
    RESPONSE_BODY("Response Body");

    override fun toString(): String = label
}

enum class ProxyMatchReplaceMode(val label: String) {
    TEXT("Text"),
    REGEX("Regex");

    override fun toString(): String = label
}

enum class ProxyMatchReplaceAction(val label: String) {
    REPLACE("Replace"),
    ADD("Add"),
    REMOVE("Remove");

    override fun toString(): String = label
}

data class ProxyMatchReplaceRule(
    val ruleId: String = UUID.randomUUID().toString(),
    var enabled: Boolean = true,
    var name: String = "Rule",
    var scope: ProxyMatchReplaceScope = ProxyMatchReplaceScope.REQUEST_BODY,
    var mode: ProxyMatchReplaceMode = ProxyMatchReplaceMode.TEXT,
    var action: ProxyMatchReplaceAction = ProxyMatchReplaceAction.REPLACE,
    var matchText: String = "",
    var replaceText: String = ""
)

data class ProxyMatchReplaceDebugResult(
    val output: String,
    val replacementCount: Int,
    val matchedRuleCount: Int
)
