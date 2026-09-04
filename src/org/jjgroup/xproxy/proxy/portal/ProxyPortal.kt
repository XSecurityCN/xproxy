package org.jjgroup.xproxy.proxy.portal

import org.jjgroup.xproxy.settings.core.XproxyCaManager
import java.net.URI
import java.util.Base64

data class ProxyPortalResult(
    val statusCode: Int,
    val reason: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray
)

object ProxyPortal {
    private const val HTML_CONTENT_TYPE = "text/html; charset=utf-8"
    private const val DER_CONTENT_TYPE = "application/x-x509-ca-cert"
    private const val PEM_CONTENT_TYPE = "application/pem-certificate-chain; charset=utf-8"

    @JvmStatic
    fun handleRequest(
        method: String,
        uri: String,
        hostHeader: String?,
        listenerHost: String?,
        listenerPort: Int
    ): ProxyPortalResult? {
        val target = parseTarget(uri, hostHeader, listenerHost, listenerPort) ?: return null
        if (!isPortalHost(target.authority, target.port, listenerHost, listenerPort)) return null

        val normalizedMethod = method.uppercase()
        val path = target.path.substringBefore('?').ifBlank { "/" }
        val head = normalizedMethod == "HEAD"
        if (normalizedMethod != "GET" && !head) {
            return methodNotAllowed(head = false)
        }
        val result = when (path) {
            "/" -> landingPage()
            "/cert" -> certificate(CertificateFormat.CRT)
            "/cert/pem" -> certificate(CertificateFormat.PEM)
            "/cert/der" -> certificate(CertificateFormat.DER)
            "/cert/cer" -> certificate(CertificateFormat.CER)
            else -> notFound(path)
        }
        return if (head) result.copy(body = ByteArray(0)) else result
    }

    @JvmStatic
    fun errorPage(
        statusCode: Int,
        reason: String,
        title: String,
        phase: String?,
        cause: Throwable?
    ): ProxyPortalResult {
        val escapedTitle = escapeHtml(title.ifBlank { reason })
        val escapedReason = escapeHtml(reason)
        val phaseHtml = phase?.takeIf { it.isNotBlank() }?.let {
            "<p class=\"meta\"><span>Phase</span><code>${escapeHtml(it)}</code></p>"
        }.orEmpty()
        val causeSummary = sanitizeCause(cause)
        val causeHtml = if (causeSummary.isBlank()) {
            ""
        } else {
            "<p class=\"meta\"><span>Details</span><code>${escapeHtml(causeSummary)}</code></p>"
        }
        return html(
            statusCode = statusCode,
            reason = reason,
            body = htmlShell(
                pageTitle = "$statusCode $escapedReason",
                content = """
                    <section class="card error">
                      <p class="eyebrow">xproxy generated this response</p>
                      <h1>$escapedTitle</h1>
                      <p class="lead">The proxy could not return a normal upstream response.</p>
                      <p class="status">HTTP $statusCode $escapedReason</p>
                      $phaseHtml
                      $causeHtml
                      <p class="hint">Check the target server, proxy settings, TLS trust, and XProxy logs for more details.</p>
                    </section>
                """.trimIndent()
            )
        )
    }

    private data class Target(val authority: String, val port: Int?, val path: String)

    private enum class CertificateFormat(val routeExt: String, val filename: String, val contentType: String) {
        CRT("crt", "xproxy-ca.crt", DER_CONTENT_TYPE),
        DER("der", "xproxy-ca.der", DER_CONTENT_TYPE),
        CER("cer", "xproxy-ca.cer", DER_CONTENT_TYPE),
        PEM("pem", "xproxy-ca.pem", PEM_CONTENT_TYPE)
    }

    private fun parseTarget(uri: String, hostHeader: String?, listenerHost: String?, listenerPort: Int): Target? {
        val token = uri.trim()
        if (token.startsWith("http://", ignoreCase = true) || token.startsWith("https://", ignoreCase = true)) {
            val parsed = runCatching { URI(token) }.getOrNull() ?: return null
            val authority = parsed.rawAuthority ?: return null
            val path = parsed.rawPath?.ifBlank { "/" } ?: "/"
            val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
            return Target(authority, parsed.port.takeIf { it > 0 }, path + query)
        }
        val authority = hostHeader?.takeIf { it.isNotBlank() }
            ?: listenerHost?.takeIf { it.isNotBlank() && listenerPort > 0 }
            ?: return null
        return Target(authority, null, token.substringBefore('?').ifBlank { "/" })
    }

    private fun isPortalHost(authority: String, explicitPort: Int?, listenerHost: String?, listenerPort: Int): Boolean {
        val parsed = splitAuthority(authority)
        val host = normalizeHost(parsed.first)
        val port = parsed.second ?: explicitPort
        if (host == "xproxy" || host == "xproxy.local") return true
        val listener = listenerHost?.let { normalizeHost(it) }
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        val sameListener = listener != null && host == listener
        return (loopback || sameListener) && (port == null || port == listenerPort)
    }

    private fun splitAuthority(authority: String): Pair<String, Int?> {
        val trimmed = authority.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end >= 0) {
                val host = trimmed.substring(1, end)
                val port = trimmed.substring(end + 1).removePrefix(":").toIntOrNull()
                return host to port
            }
        }
        val colonCount = trimmed.count { it == ':' }
        if (colonCount > 1) return trimmed to null
        val host = trimmed.substringBefore(':')
        val port = trimmed.substringAfter(':', "").toIntOrNull()
        return host to port
    }

    private fun normalizeHost(host: String): String =
        host.trim().removeSuffix(".").removePrefix("[").removeSuffix("]").lowercase()

    private fun landingPage(): ProxyPortalResult {
        val body = htmlShell(
            pageTitle = "xproxy is running",
            content = """
                <section class="hero">
                  <p class="eyebrow">Proxy listener</p>
                  <h1>xproxy is running</h1>
                  <p class="lead">Your browser reached the XProxy proxy listener. Configure your browser or OS proxy to this listener, then install and trust the XProxy CA certificate for HTTPS interception.</p>
                </section>
                <section class="grid">
                  <article class="card">
                    <h2>CA certificate downloads</h2>
                    <p>Download the public XProxy CA certificate. private keys are never exposed from this portal.</p>
                    <div class="links">
                      <a href="/cert">CRT</a>
                      <a href="/cert/pem">PEM</a>
                      <a href="/cert/der">DER</a>
                      <a href="/cert/cer">CER</a>
                    </div>
                  </article>
                  <article class="card">
                    <h2>Setup checklist</h2>
                    <ol>
                      <li>Configure browser/system proxy to the XProxy listener.</li>
                      <li>Download and install the CA certificate.</li>
                      <li>Trust the certificate in your OS or browser trust store.</li>
                      <li>Use the desktop Proxy tab to inspect and modify traffic.</li>
                    </ol>
                  </article>
                </section>
            """.trimIndent()
        ).toByteArray(Charsets.UTF_8)
        return ProxyPortalResult(200, "OK", htmlHeaders(), body)
    }

    private fun methodNotAllowed(head: Boolean): ProxyPortalResult =
        html(405, "Method Not Allowed", errorContent("Method not allowed", "This portal route only supports safe read requests."), head)

    private fun notFound(path: String): ProxyPortalResult =
        html(404, "Not Found", errorContent("Not found", "No XProxy portal route exists for ${escapeHtml(path)}."))

    private fun certificate(format: CertificateFormat): ProxyPortalResult {
        return try {
            XproxyCaManager.ensureCaMaterial()
            val cert = XproxyCaManager.loadCaCert()
            val body = when (format) {
                CertificateFormat.PEM -> toPem(cert.encoded).toByteArray(Charsets.UTF_8)
                else -> cert.encoded
            }
            ProxyPortalResult(
                200,
                "OK",
                downloadHeaders(format.contentType, format.filename),
                body
            )
        } catch (ex: Exception) {
            errorPage(503, "Service Unavailable", "CA unavailable", "certificate", ex)
        }
    }

    private fun toPem(encoded: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.US_ASCII)).encodeToString(encoded)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }

    private fun errorContent(title: String, message: String): String = htmlShell(
        pageTitle = title,
        content = """
            <section class="card error">
              <p class="eyebrow">xproxy portal</p>
              <h1>${escapeHtml(title)}</h1>
              <p class="lead">${escapeHtml(message)}</p>
              <p class="hint"><a href="/">Back to the XProxy portal</a></p>
            </section>
        """.trimIndent()
    )

    private fun html(statusCode: Int, reason: String, body: String, head: Boolean = false): ProxyPortalResult =
        ProxyPortalResult(statusCode, reason, htmlHeaders(), if (head) ByteArray(0) else body.toByteArray(Charsets.UTF_8))

    private fun htmlHeaders(): List<Pair<String, String>> = listOf(
        "Content-Type" to HTML_CONTENT_TYPE,
        "Cache-Control" to "no-store, no-cache, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0",
        "X-Content-Type-Options" to "nosniff",
        "Connection" to "close"
    )

    private fun downloadHeaders(contentType: String, filename: String): List<Pair<String, String>> = listOf(
        "Content-Type" to contentType,
        "Cache-Control" to "no-store, no-cache, must-revalidate",
        "Pragma" to "no-cache",
        "Expires" to "0",
        "X-Content-Type-Options" to "nosniff",
        "Content-Disposition" to "attachment; filename=\"$filename\"",
        "Connection" to "close"
    )

    private fun htmlShell(pageTitle: String, content: String): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${escapeHtml(pageTitle)} · xproxy</title>
          <style>
            :root { color-scheme: light dark; --bg: #111827; --panel: #1f2937; --text: #f9fafb; --muted: #9ca3af; --accent: #f97316; --line: #374151; }
            body { margin: 0; font: 15px/1.55 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: radial-gradient(circle at top, #312e81, var(--bg) 42rem); color: var(--text); }
            main { max-width: 980px; margin: 0 auto; padding: 56px 24px; }
            h1 { margin: 0 0 12px; font-size: clamp(2rem, 5vw, 4rem); line-height: 1; letter-spacing: -0.04em; }
            h2 { margin: 0 0 10px; font-size: 1.1rem; }
            a { color: #fed7aa; }
            .hero, .card { background: color-mix(in srgb, var(--panel) 88%, transparent); border: 1px solid var(--line); border-radius: 22px; padding: 28px; box-shadow: 0 20px 60px rgba(0,0,0,.25); }
            .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 18px; margin-top: 18px; }
            .eyebrow { margin: 0 0 8px; color: var(--accent); font-weight: 700; text-transform: uppercase; letter-spacing: .12em; font-size: .75rem; }
            .lead { color: var(--muted); max-width: 68ch; }
            .links { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
            .links a { display: inline-block; padding: 10px 14px; border: 1px solid #fb923c; border-radius: 999px; text-decoration: none; font-weight: 700; }
            .status { display: inline-block; padding: 8px 12px; border-radius: 999px; background: rgba(249,115,22,.15); color: #fed7aa; }
            .meta { display: grid; gap: 4px; color: var(--muted); }
            .meta span { font-size: .8rem; text-transform: uppercase; letter-spacing: .08em; }
            code { white-space: pre-wrap; overflow-wrap: anywhere; color: #fde68a; }
            .hint { color: var(--muted); }
          </style>
        </head>
        <body><main>$content</main></body>
        </html>
    """.trimIndent()

    private fun sanitizeCause(cause: Throwable?): String {
        if (cause == null) return ""
        return try {
            val raw = listOfNotNull(cause.javaClass.simpleName, cause.message).joinToString(": ")
            val collapsed = raw.replace(Regex("\\s+"), " ").trim()
            val redacted = redactSensitiveText(collapsed)
            if (redacted.length > 500) redacted.take(500) + "..." else redacted
        } catch (_: Exception) {
            "Additional details were hidden for safety."
        }
    }

    private fun redactSensitiveText(value: String): String {
        var result = value
        result = result.replace(Regex("(?i)(access_token|authorization|token|password|secret|apikey|api_key)=([^\\s&;]+)"), "$1=[redacted]")
        result = result.replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{32,}"), "Bearer [redacted]")
        result = result.replace(Regex("[A-Fa-f0-9]{32,}"), "[redacted]")
        result = result.replace(Regex("(?<![A-Za-z0-9._~+/=-])(?=[A-Za-z0-9+/=_-]{32,})(?=[A-Za-z0-9+/=_-]*[A-Z])(?=[A-Za-z0-9+/=_-]*[a-z])(?=[A-Za-z0-9+/=_-]*(?:[0-9]|[+/=_-]))[A-Za-z0-9+/=_-]{32,}(?![A-Za-z0-9._~+/=-])"), "[redacted]")
        result = result.replace(Regex("/[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+){1,}"), "[path]")
        result = result.replace(Regex("[A-Za-z]:\\\\[^\\s]+"), "[path]")
        return result
    }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
