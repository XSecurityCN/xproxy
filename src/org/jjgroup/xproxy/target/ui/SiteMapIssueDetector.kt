package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import java.util.Locale
import javax.swing.Icon

internal fun SiteMapDetailPanel.detectIssues(rows: List<ProxyHistoryEntry>): List<IssueRecord> {
    return emptyList()
}

internal fun SiteMapDetailPanel.mapReportedIssues(selectedNode: SiteMapNode?): List<IssueRecord> {
    if (reportedIssuesById.isEmpty()) return emptyList()

    val selectedHost = selectedNode?.hostKey?.substringAfter("://")
    val selectedPathPrefix = selectedNode?.pathPrefix?.let { normalizePath(it) }

    return reportedIssuesById.values
        .asSequence()
        .filter { issue ->
            if (selectedHost.isNullOrBlank()) true
            else issue.host.isBlank() || hostMatches(issue.host, selectedHost)
        }
        .filter { issue ->
            if (selectedPathPrefix.isNullOrBlank()) true
            else issue.path.isBlank() || isPathUnder(normalizePath(issue.path), selectedPathPrefix)
        }
        .map { issue ->
            val requestRaw = issue.requestRaw
            val responseRaw = issue.responseRaw
            val inferredMethod = issue.method.ifBlank { inferRequestMethod(requestRaw) }
            val inferredPath = issue.path.ifBlank { inferRequestPath(requestRaw) }
            IssueRecord(
                key = "script:${issue.issueId}",
                category = issue.name,
                severity = issue.severity.ifBlank { "Information" },
                description = issue.detail,
                historyId = -1,
                requestRaw = requestRaw,
                responseRaw = responseRaw,
                method = inferredMethod.ifBlank { "-" },
                path = inferredPath.ifBlank { issue.url.ifBlank { "/" } },
                host = issue.host,
                evidence = issue.evidenceCsv.split("\n").filter { it.isNotEmpty() }
            )
        }
        .toList()
}

internal fun SiteMapDetailPanel.inferRequestMethod(requestRaw: String): String {
    if (requestRaw.isBlank()) return ""
    val first = requestRaw.substringBefore('\n').substringBefore('\r').trim()
    return if (first.isBlank()) "" else first.substringBefore(' ').trim()
}

internal fun SiteMapDetailPanel.inferRequestPath(requestRaw: String): String {
    if (requestRaw.isBlank()) return ""
    val first = requestRaw.substringBefore('\n').substringBefore('\r').trim()
    if (first.isBlank()) return ""
    val parts = first.split(' ')
    return if (parts.size >= 2) parts[1].trim() else ""
}

internal fun SiteMapDetailPanel.hostMatches(issueHost: String, selectedHost: String): Boolean {
    val normalizedIssueHost = normalizeHostToken(issueHost)
    val normalizedSelectedHost = normalizeHostToken(selectedHost)
    if (normalizedIssueHost.isBlank() || normalizedSelectedHost.isBlank()) return true
    if (normalizedIssueHost.equals(normalizedSelectedHost, ignoreCase = true)) return true
    val issueWithoutPort = stripPort(normalizedIssueHost)
    val selectedWithoutPort = stripPort(normalizedSelectedHost)
    return issueWithoutPort.equals(selectedWithoutPort, ignoreCase = true)
}

internal fun SiteMapDetailPanel.normalizeHostToken(host: String): String {
    var token = host.trim().lowercase(Locale.getDefault())
    if (token.contains("://")) token = token.substringAfter("://")
    if (token.contains('/')) token = token.substringBefore('/')
    return token
}

internal fun SiteMapDetailPanel.stripPort(host: String): String {
    val token = host.trim()
    if (token.isBlank()) return token
    if (token.startsWith("[") && token.contains("]")) {
        return token.substringAfter("[").substringBefore("]")
    }
    val colonCount = token.count { it == ':' }
    return if (colonCount == 1) token.substringBefore(':') else token
}

internal fun SiteMapDetailPanel.parseResponseHeaders(responseRaw: String): Map<String, List<String>> {
    val lines = responseRaw.split("\r\n", "\n")
    if (lines.isEmpty()) return emptyMap()
    val headers = LinkedHashMap<String, MutableList<String>>()
    for (line in lines.drop(1)) {
        if (line.isBlank()) break
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        val name = line.substring(0, idx).trim()
        val value = line.substring(idx + 1).trim()
        headers.getOrPut(name) { ArrayList() }.add(value)
    }
    return headers
}

internal fun SiteMapDetailPanel.issueSeverityRank(severity: String): Int =
    when (severity.lowercase(Locale.getDefault())) {
        "high" -> 3
        "medium" -> 2
        "low" -> 1
        else -> 0
    }

internal fun SiteMapDetailPanel.issueSeverityIcon(severity: String): Icon =
    issueSeverityIcons[severity.trim().lowercase(Locale.getDefault())] ?: issueDefaultSeverityIcon
