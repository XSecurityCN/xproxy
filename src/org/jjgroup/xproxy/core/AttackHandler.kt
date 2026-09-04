package org.jjgroup.xproxy

import org.jjgroup.xproxy.issue.core.ScriptIssueHub
import org.jjgroup.xproxy.issue.model.ReportedIssue
import org.jjgroup.xproxy.i18n.I18n
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class AttackHandler (){
    private var running = false
    private var engine: RequestEngine? = null
    private var statusOverride: String? = null
    var msg = ""
    var code = ""
    var baseRequest = ""
    var rawRequest = byteArrayOf()
    private val reportedIssues = CopyOnWriteArrayList<ReportedIssue>()

    fun isRunning() = running

    fun setComplete() {
        engine?.showStats(-1)
    }

    fun hasFinished(): Boolean {
        return engine?.attackState?.get()?.let { it >= 3 } ?: false
    }

    fun setRequestEngine(engine: RequestEngine) {
        running = true
        this.engine = engine
        statusOverride = null
    }

    fun statusString(): String {
        val override = statusOverride
        return when {
            override != null -> override
            engine != null -> "${engine!!.statusString()} | $msg"
            else -> I18n.t("fuzzer.status_warming_up")
        }
    }

    fun overrideStatus(msg: String) {
        statusOverride = msg
    }

    fun setMessage(msg: String) {
        this.msg = msg
    }

    fun abort() {
        running = false
        statusOverride = null
        this.engine?.cancel()
    }

    fun pause() {
        this.engine?.pauseAttack()
        statusOverride = I18n.t("fuzzer.status_paused")
    }

    fun resume() {
        this.engine?.resumeAttack()
        statusOverride = null
    }

    fun isPaused(): Boolean {
        return this.engine?.isPausedAttack() == true
    }

    fun reportIssue(
        name: String,
        severity: String = "Information",
        detail: String = "",
        requestRaw: String = "",
        responseRaw: String = "",
        confidence: String = "Tentative",
        remediation: String = "",
        url: String = "",
        host: String = "",
        path: String = "",
        method: String = "",
        tagsCsv: String = "",
        source: String = "python"
    ) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) {
            return
        }
        val issue = ReportedIssue(
            issueId = UUID.randomUUID().toString(),
            source = source.ifBlank { "python" },
            name = normalizedName,
            severity = normalizeSeverity(severity),
            confidence = confidence.ifBlank { "Tentative" },
            detail = detail.trim(),
            remediation = remediation.trim(),
            url = url.trim(),
            host = host.trim(),
            path = path.trim(),
            method = method.trim().uppercase(Locale.getDefault()),
            requestRaw = requestRaw,
            responseRaw = responseRaw,
            tagsCsv = tagsCsv.trim(),
            createdAtMillis = System.currentTimeMillis()
        )
        reportedIssues.add(issue)
        ScriptIssueHub.publish(issue)
    }

    fun getReportedIssues(): List<ReportedIssue> {
        return reportedIssues.toList()
    }

    private fun normalizeSeverity(raw: String): String {
        val normalized = raw.trim().lowercase(Locale.getDefault())
        return when (normalized) {
            "high", "medium", "low", "information", "info" -> {
                when (normalized) {
                    "info" -> "Information"
                    else -> normalized.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
                }
            }
            else -> if (raw.isBlank()) "Information" else raw
        }
    }
}
