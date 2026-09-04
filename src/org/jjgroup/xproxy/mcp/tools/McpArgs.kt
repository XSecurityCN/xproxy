package org.jjgroup.xproxy.mcp.tools

import com.fasterxml.jackson.databind.JsonNode

/**
 * 从工具入参 [JsonNode] 安全读取可选字段的顶层扩展函数。
 *
 * 放在 `mcp.tools` 包顶层(而非 object 内),使同包的工具类可直接 `args.str("host")` 调用,无需 import。
 */
fun JsonNode.str(field: String, default: String = ""): String =
    get(field)?.takeIf { !it.isNull && it.isTextual }?.asText()?.takeIf { it.isNotEmpty() } ?: default

fun JsonNode.strOpt(field: String): String? =
    get(field)?.takeIf { !it.isNull && it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

fun JsonNode.intOr(field: String, default: Int): Int =
    get(field)?.takeIf { !it.isNull && it.isNumber }?.asInt() ?: default

fun JsonNode.intOpt(field: String): Int? =
    get(field)?.takeIf { !it.isNull && it.isNumber }?.asInt()

fun JsonNode.longOr(field: String, default: Long): Long =
    get(field)?.takeIf { !it.isNull && it.isNumber }?.asLong() ?: default

fun JsonNode.boolOr(field: String, default: Boolean): Boolean =
    get(field)?.takeIf { !it.isNull && it.isBoolean }?.asBoolean() ?: default

fun JsonNode.strList(field: String): List<String> {
    val node = get(field) ?: return emptyList()
    return when {
        node.isArray -> node.mapNotNull { it?.asText()?.takeIf { s -> s.isNotBlank() } }
        node.isTextual -> listOf(node.asText())
        else -> emptyList()
    }
}
