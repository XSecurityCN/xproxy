package org.jjgroup.xproxy.codec.core

import java.util.Locale

data class CodecTransformResult(
    val output: String,
    val error: String? = null
)

object CodecRecipeEngine {
    val supportedRules: List<String> = CodecOperationCatalog.load().map { it.name }

    private val implementedOps = setOf(
        "jwt decode",
        "to base64",
        "base64 encode",
        "from base64",
        "base64 decode",
        "url encode",
        "url decode",
        "to hex",
        "hex encode",
        "from hex",
        "hex decode",
        "md5",
        "sha1",
        "sha-1",
        "sha256",
        "sha-256",
        "sha512",
        "sha-512",
        "hmac",
        "hmac sha1",
        "hmac sha256",
        "hmac sha512",
        "aes encrypt",
        "aes decrypt",
        "rot13",
        "reverse",
        "reverse string",
        "html encode",
        "html decode",
        "to html entities",
        "from html entities",
        "uppercase",
        "to upper",
        "lowercase",
        "to lower",
        "strip",
        "trim"
    )

    fun isSupportedOperation(name: String): Boolean =
        implementedOps.contains(normalizeRule(name))

    fun apply(input: String, rules: List<String>): CodecTransformResult {
        var current = input
        for (rawRule in rules) {
            val node = parseRuleNode(rawRule)
            if (node.name.isBlank()) {
                continue
            }

            val rule = normalizeRule(node.name)
            val transformed = runCatching {
                when (rule) {
                    "jwt decode" -> CodecOps.jwtDecodePayload(current)
                    "to base64", "base64 encode" -> CodecOps.toBase64(current, node.bool("urlSafe", false))
                    "from base64", "base64 decode" -> CodecOps.fromBase64(current, node.bool("urlSafe", false))
                    "url encode" -> CodecOps.urlEncode(current, node.bool("encodeAll", false))
                    "url decode" -> CodecOps.urlDecode(current)
                    "to hex", "hex encode" -> CodecOps.toHex(current, node.config["delimiter"] ?: "None")
                    "from hex", "hex decode" -> CodecOps.fromHex(current, node.config["delimiter"] ?: "None")
                    "md5" -> CodecOps.md5(current)
                    "sha1", "sha-1" -> CodecOps.sha1(current)
                    "sha256", "sha-256" -> CodecOps.sha256(current)
                    "sha512", "sha-512" -> CodecOps.sha512(current)
                    "hmac" -> CodecOps.hmac(
                        current,
                        node.config["key"] ?: "",
                        node.config["algorithm"] ?: "SHA-256",
                        node.config["output"] ?: "hex"
                    )
                    "hmac sha1" -> CodecOps.hmac(current, node.config["key"] ?: "", "SHA-1", node.config["output"] ?: "hex")
                    "hmac sha256" -> CodecOps.hmac(current, node.config["key"] ?: "", "SHA-256", node.config["output"] ?: "hex")
                    "hmac sha512" -> CodecOps.hmac(current, node.config["key"] ?: "", "SHA-512", node.config["output"] ?: "hex")
                    "aes encrypt" -> CodecOps.aesEncrypt(
                        current,
                        node.config["key"] ?: "",
                        node.config["mode"] ?: "ECB",
                        node.config["iv"] ?: "",
                        node.config["output"] ?: "Base64"
                    )
                    "aes decrypt" -> CodecOps.aesDecrypt(
                        current,
                        node.config["key"] ?: "",
                        node.config["mode"] ?: "ECB",
                        node.config["iv"] ?: "",
                        node.config["input"] ?: "Base64"
                    )
                    "rot13" -> CodecOps.rot13(current)
                    "reverse", "reverse string" -> CodecOps.reverse(current)
                    "html encode", "to html entities" -> CodecOps.htmlEncode(current)
                    "html decode", "from html entities" -> CodecOps.htmlDecode(current)
                    "uppercase", "to upper" -> CodecOps.uppercase(current)
                    "lowercase", "to lower" -> CodecOps.lowercase(current)
                    "strip", "trim" -> CodecOps.strip(current)
                    else -> return CodecTransformResult(current, "Unsupported codec operation in xproxy Codec: ${node.name}")
                }
            }.getOrElse { throwable ->
                return CodecTransformResult(current, "Rule '${node.name}' failed: ${throwable.message ?: throwable.javaClass.simpleName}")
            }
            current = transformed
        }
        return CodecTransformResult(current, null)
    }

    fun normalizeRule(raw: String): String {
        return raw.trim().lowercase(Locale.ROOT).replace('_', ' ').replace(Regex("\\s+"), " ")
    }

    private data class RuleNode(
        val name: String,
        val config: Map<String, String>
    ) {
        fun bool(key: String, default: Boolean): Boolean {
            val raw = config[key] ?: return default
            return raw.equals("true", ignoreCase = true) || raw == "1" || raw.equals("yes", ignoreCase = true)
        }
    }

    private fun parseRuleNode(serialized: String): RuleNode {
        val segments = serialized.split(";;")
        val name = segments.firstOrNull().orEmpty().trim()
        if (segments.size == 1) {
            return RuleNode(name, emptyMap())
        }
        val config = LinkedHashMap<String, String>()
        for (index in 1 until segments.size) {
            val segment = segments[index]
            val equalsIndex = segment.indexOf('=')
            if (equalsIndex <= 0) {
                val keyOnly = segment.trim()
                if (keyOnly.isNotBlank()) {
                    config[keyOnly] = "true"
                }
                continue
            }
            val key = segment.substring(0, equalsIndex).trim()
            val value = segment.substring(equalsIndex + 1).trim()
            if (key.isNotBlank()) {
                config[key] = value
            }
        }
        return RuleNode(name, config)
    }
}
