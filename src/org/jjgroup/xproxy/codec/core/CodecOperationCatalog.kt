package org.jjgroup.xproxy.codec.core

import java.io.File

data class CodecOperationDescriptor(
    val name: String,
    val category: String
)

object CodecOperationCatalog {
    private val builtinFallback = listOf(
        CodecOperationDescriptor("JWT Decode", "Signature"),
        CodecOperationDescriptor("To Base64", "Data format"),
        CodecOperationDescriptor("From Base64", "Data format"),
        CodecOperationDescriptor("Url Encode", "Data format"),
        CodecOperationDescriptor("Url Decode", "Data format"),
        CodecOperationDescriptor("To Hex", "Data format"),
        CodecOperationDescriptor("From Hex", "Data format"),
        CodecOperationDescriptor("MD5", "Hashing"),
        CodecOperationDescriptor("SHA1", "Hashing"),
        CodecOperationDescriptor("SHA256", "Hashing"),
        CodecOperationDescriptor("SHA512", "Hashing"),
        CodecOperationDescriptor("HMAC", "Hashing"),
        CodecOperationDescriptor("AES Encrypt", "Encryption / Encoding"),
        CodecOperationDescriptor("AES Decrypt", "Encryption / Encoding"),
        CodecOperationDescriptor("ROT13", "String"),
        CodecOperationDescriptor("Reverse", "String"),
        CodecOperationDescriptor("HTML Encode", "Data format"),
        CodecOperationDescriptor("HTML Decode", "Data format"),
        CodecOperationDescriptor("Uppercase", "String"),
        CodecOperationDescriptor("Lowercase", "String"),
        CodecOperationDescriptor("Strip", "String")
    )

    private val categoryText = mapOf(
        "ARITHMETIC" to "Arithmetic",
        "BYTEOPERATION" to "Byte Operations",
        "COMPRESSION" to "Compression",
        "CONDITIONALS" to "Conditionals",
        "DATAFORMAT" to "Data format",
        "DATES" to "Date / Time",
        "ENCRYPTION" to "Encryption / Encoding",
        "EXTRACTORS" to "Extractors",
        "HASHING" to "Hashing",
        "MISC" to "Misc",
        "NETWORKING" to "Networking",
        "SETTER" to "Setter",
        "SIGNATURE" to "Signature",
        "STRING" to "String",
        "UTILS" to "Utils"
    )

    fun load(): List<CodecOperationDescriptor> {
        val projectRoot = File(System.getProperty("user.dir"))
        val folderCandidates = listOf(
            "temp/codec-source/src/main/java/de/usd/chef/operations",
            "temp/" + "cs" + "tc" + "/src/main/java/de/usd/" + "cs" + "tchef" + "/operations"
        )
        val operationRoot = folderCandidates
            .asSequence()
            .map { File(projectRoot, it) }
            .firstOrNull { it.exists() }
            ?: File(projectRoot, folderCandidates.first())
        if (!operationRoot.exists()) {
            return builtinFallback
        }

        val annotationRegex = Regex(
            """@OperationInfos\((?s).*?name\s*=\s*\"([^\"]+)\".*?category\s*=\s*OperationCategory\.([A-Z]+).*?\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val descriptors = mutableListOf<CodecOperationDescriptor>()
        operationRoot.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { javaFile ->
                val content = runCatching { javaFile.readText() }.getOrNull() ?: return@forEach
                val match = annotationRegex.find(content) ?: return@forEach
                val name = match.groupValues[1].trim()
                val categoryKey = match.groupValues[2].trim()
                val category = categoryText[categoryKey] ?: categoryKey
                descriptors.add(CodecOperationDescriptor(name = name, category = category))
            }

        if (descriptors.isEmpty()) {
            return builtinFallback
        }

        return (descriptors + builtinFallback)
            .distinctBy { "${it.category}::${it.name}" }
            .sortedWith(compareBy({ it.category }, { it.name }))
    }
}
