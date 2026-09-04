package org.jjgroup.xproxy.mcp.tools

import org.jjgroup.xproxy.engine.http.uncompressIfNecessary

/**
 * 从 HTTP 响应体中提取 JavaScript 与疑似端点/URL(MCP `extract_js` 工具内核)。
 *
 * 代码库原本无任何 JS 提取逻辑(见架构梳理),此处实现三层:
 * 1. 外链脚本 `<script src="...">`;
 * 2. 内联脚本 `<script>...</script>`(无 src);
 * 3. 响应体(含内联脚本与 .js 响应)中疑似端点/URL 的引号字符串(LinkFinder 风格简化版)。
 *
 * 响应体可能是 gzip/br/zstd/deflate 压缩(代理捕获原样存),用 [uncompressIfNecessary] 按
 * Content-Encoding 解压;该函数对无 header 的压缩体也会嗅探解压。
 */
object JsExtractor {
    private val SCRIPT_SRC_REGEX = Regex("""<script\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val INLINE_SCRIPT_REGEX = Regex("""<script\b(?![^>]*\bsrc\s*=)[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    // 引号包裹的绝对/相对 URL 或路径(含可选转义斜杠 \/)。
    private val ENDPOINT_REGEX = Regex("""["'`]((?:https?:\\?/\\?/|\\?/|@\\?/)[A-Za-z0-9_.\-:/@%~+#?=&]+)["'`]""")
    private val SKIP_EXT = setOf(".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".woff", ".woff2", ".ttf", ".eot", ".bmp", ".webp")

    private const val INLINE_TRUNCATE = 4000

    data class JsExtraction(
        val scriptSrcs: List<String>,
        val inlineScripts: List<String>,
        val endpoints: List<String>
    )

    /** @param responseRaw 完整响应文本(状态行+头+体,ISO-8859-1) */
    fun extract(responseRaw: String): JsExtraction {
        val (headers, body) = splitResponse(responseRaw)
        val decompressed = runCatching { uncompressIfNecessary(headers, body) }.getOrDefault(body)

        val srcs = SCRIPT_SRC_REGEX.findAll(decompressed).map { it.groupValues[1].trim() }.distinct().toList()
        val inline = INLINE_SCRIPT_REGEX.findAll(decompressed)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .map { if (it.length > INLINE_TRUNCATE) it.take(INLINE_TRUNCATE) + "\n...[truncated]" else it }
            .toList()

        val endpoints = ENDPOINT_REGEX.findAll(decompressed)
            .map { it.groupValues[1].replace("\\/", "/").trim() }
            .filter { it.length >= 2 && !isSkippableAsset(it) }
            .distinct()
            .sorted()
            .toList()

        return JsExtraction(scriptSrcs = srcs, inlineScripts = inline, endpoints = endpoints)
    }

    private fun splitResponse(responseRaw: String): Pair<String, String> {
        val marker = responseRaw.indexOf("\r\n\r\n")
        return if (marker < 0) responseRaw to responseRaw
        else responseRaw.substring(0, marker) to responseRaw.substring(marker + 4)
    }

    private fun isSkippableAsset(value: String): Boolean {
        val lower = value.substringBefore('?').lowercase()
        return SKIP_EXT.any { lower.endsWith(it) }
    }
}

