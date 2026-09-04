package org.jjgroup.xproxy.mcp.attack

import org.jjgroup.xproxy.fuzzer.core.HttpService

/**
 * 从原始 HTTP 请求文本解析出目标服务信息([HttpService])与请求行字段。
 *
 * 没有"统一的 raw->SeedRequest 解析器",各处自行解析(见 FuzzerRequestSending/IntruderFrame)。
 * MCP 的 `send_request` / `run_attack` 需要从 agent 给的原始请求里推断 host/port/protocol:
 * - Host 头(可带端口)决定 host 与 port;缺省端口按 scheme 推断(https=443, http=80)。
 * - scheme 由端口推断(443->https,否则 http),可被 [protocolOverride] 覆盖。
 */
data class ParsedRawRequest(
    val method: String,
    val path: String,
    val httpVersion: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val service: HttpService
)

object RawRequestParser {
    /**
     * @param raw 原始 HTTP 请求(头+体,ISO-8859-1 文本)
     * @param protocolOverride 可选 "http"/"https" 显式指定 scheme
     */
    fun parse(raw: String, protocolOverride: String? = null): ParsedRawRequest {
        val headerEnd = raw.indexOf("\r\n\r\n").let { if (it < 0) raw.indexOf("\n\n") else it }
        val headerSection = if (headerEnd < 0) raw else raw.substring(0, headerEnd)
        val lines = headerSection.split("\r\n", "\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)?.trim()?.ifBlank { "GET" } ?: "GET"
        val path = parts.getOrNull(1)?.trim()?.ifBlank { "/" } ?: "/"
        val httpVersion = parts.getOrNull(2)?.trim()?.ifBlank { "HTTP/1.1" } ?: "HTTP/1.1"

        var hostHeader = ""
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val name = line.substring(0, colon).trim()
            if (name.equals("Host", ignoreCase = true)) {
                hostHeader = line.substring(colon + 1).trim()
                break
            }
        }

        val protocol = (protocolOverride?.trim()?.lowercase()?.takeIf { it == "http" || it == "https" })
            ?: if (hostHeader.startsWith("[")) "https" else "https" // 默认 https,显式端口再校正

        var (host, portFromHeader) = splitHostPort(hostHeader)
        val port = portFromHeader ?: if (protocol == "https") 443 else 80
        // 按端口推断 scheme(若未显式覆盖):443->https,否则 http。
        val effectiveProtocol = protocolOverride?.trim()?.lowercase()?.takeIf { it == "http" || it == "https" }
            ?: if (port == 443) "https" else "http"

        if (host.isBlank()) host = "localhost"
        return ParsedRawRequest(
            method = method,
            path = path,
            httpVersion = httpVersion,
            host = host,
            port = port,
            protocol = effectiveProtocol,
            service = HttpService(host, port, effectiveProtocol)
        )
    }

    private fun splitHostPort(authority: String): Pair<String, Int?> {
        val trimmed = authority.trim().removeSuffix("/")
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end >= 0) {
                val host = trimmed.substring(1, end)
                val rest = trimmed.substring(end + 1).removePrefix(":")
                return host to rest.toIntOrNull()
            }
        }
        val colon = trimmed.lastIndexOf(':')
        if (colon > 0) {
            val host = trimmed.substring(0, colon)
            val port = trimmed.substring(colon + 1).toIntOrNull()
            if (port != null) return host to port
        }
        return trimmed to null
    }
}
