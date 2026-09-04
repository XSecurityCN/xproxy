package org.jjgroup.xproxy.mcp.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * MCP 层共享的 Jackson [ObjectMapper] 与 JSON Schema 构建辅助。
 *
 * 单独抽出:(1) 全局复用一个 mapper(线程安全)避免重复构造;(2) 给工具的 `inputSchema` 一个简短 DSL,
 * 否则每个工具手写 ObjectNode 会非常啰嗦。
 */
object McpJson {
    val mapper: ObjectMapper = ObjectMapper()
        .disable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)

    fun obj(): ObjectNode = mapper.createObjectNode()
    fun arr(): ArrayNode = mapper.createArrayNode()

    /** 把任意对象序列化为紧凑 JSON 字符串(用于工具结果文本)。 */
    fun stringify(value: Any?): String = mapper.writeValueAsString(value)

    /** 解析 JSON 字符串为 [JsonNode];失败返回 null(用于解析请求体容错)。 */
    fun parseOrNull(text: String): JsonNode? = runCatching { mapper.readTree(text) }.getOrNull()
}

/**
 * JSON Schema 构建器:用 Kotlin 代码生成 MCP 工具的 `inputSchema`。
 * 仅覆盖工具用到的子集(object/array/string/integer/number/boolean + enum + required + description)。
 */
class SchemaBuilder {
    private val node: ObjectNode = McpJson.obj()
    private val props: ObjectNode = node.putObject("properties")

    init {
        node.put("type", "object")
    }

    fun prop(name: String, type: String, description: String? = null, block: (ObjectNode.() -> Unit)? = null): SchemaBuilder {
        val p = McpJson.obj()
        p.put("type", type)
        if (description != null) p.put("description", description)
        block?.let { p.it() }
        props.set<JsonNode>(name, p)
        return this
    }

    fun stringProp(name: String, description: String? = null, enum: List<String>? = null, default: String? = null): SchemaBuilder =
        prop(name, "string", description) {
            if (enum != null) {
                val arr = McpJson.arr()
                enum.forEach { arr.add(it) }
                set<JsonNode>("enum", arr)
            }
            if (default != null) put("default", default)
        }

    fun intProp(name: String, description: String? = null, default: Int? = null, min: Int? = null): SchemaBuilder =
        prop(name, "integer", description) {
            if (default != null) put("default", default)
            if (min != null) put("minimum", min)
        }

    fun boolProp(name: String, description: String? = null, default: Boolean? = null): SchemaBuilder =
        prop(name, "boolean", description) {
            if (default != null) put("default", default)
        }

    fun arrayProp(name: String, itemType: String, description: String? = null, itemEnum: List<String>? = null): SchemaBuilder =
        prop(name, "array", description) {
            val items = McpJson.obj()
            items.put("type", itemType)
            if (itemEnum != null) {
                val arr = McpJson.arr()
                itemEnum.forEach { arr.add(it) }
                items.set<JsonNode>("enum", arr)
            }
            set<JsonNode>("items", items)
        }

    fun required(vararg names: String): SchemaBuilder {
        val arr = McpJson.arr()
        names.forEach { arr.add(it) }
        node.set<JsonNode>("required", arr)
        return this
    }

    fun build(): ObjectNode = node
}

fun mcpSchema(block: SchemaBuilder.() -> Unit): ObjectNode = SchemaBuilder().apply(block).build()
