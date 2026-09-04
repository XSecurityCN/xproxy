package org.jjgroup.xproxy.issue.model

data class ReportedIssue(
    val issueId: String,
    val source: String,
    val name: String,
    val severity: String,
    val confidence: String,
    val detail: String,
    val remediation: String,
    val url: String,
    val host: String,
    val path: String,
    val method: String,
    val requestRaw: String,
    val responseRaw: String,
    val tagsCsv: String,
    /** 要在响应区高亮的证据片段,`\n` 分隔多个。agent 调 confirm_vuln 时传入,UI 展示时高亮匹配区间。 */
    val evidenceCsv: String = "",
    val createdAtMillis: Long
)
