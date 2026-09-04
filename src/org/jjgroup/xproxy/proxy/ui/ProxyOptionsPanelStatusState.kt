package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.i18n.I18n

internal sealed class ListenerStatusState {
    data object Stopped : ListenerStatusState()
    data object Starting : ListenerStatusState()
    data object Stopping : ListenerStatusState()
    data object InvalidPort : ListenerStatusState()
    data class StartFailed(val error: String) : ListenerStatusState()
    data class Listening(val host: String, val port: String) : ListenerStatusState()
    data class ListeningViaUpstream(val host: String, val port: String, val upstreamHost: String, val upstreamPort: String) : ListenerStatusState()
    data class ListeningIgnoringInvalidUpstream(val host: String, val port: String) : ListenerStatusState()
    data class Raw(val message: String) : ListenerStatusState()

    fun text(): String = when (this) {
        Stopped -> I18n.t("proxy.status.stopped")
        Starting -> I18n.t("proxy.status.starting")
        Stopping -> I18n.t("proxy.status.stopping")
        InvalidPort -> I18n.t("proxy.status.invalid_port")
        is StartFailed -> I18n.t("proxy.status.start_failed", "error" to error)
        is Listening -> I18n.t("proxy.status.listening", "host" to host, "port" to port)
        is ListeningViaUpstream -> I18n.t(
            "proxy.status.listening_via_upstream",
            "host" to host,
            "port" to port,
            "upstreamHost" to upstreamHost,
            "upstreamPort" to upstreamPort
        )
        is ListeningIgnoringInvalidUpstream -> I18n.t("proxy.status.listening_upstream_ignored", "host" to host, "port" to port)
        is Raw -> message
    }

    companion object {
        private val listeningViaUpstreamPattern = Regex("^Listening on (.+):(\\d+) via upstream (.+):(\\d+)$")
        private val listeningIgnoringUpstreamPattern = Regex("^Listening on (.+):(\\d+) \\(upstream ignored: invalid/looped\\)$")
        private val listeningPattern = Regex("^Listening on (.+):(\\d+)$")

        fun fromRuntimeMessage(isRunning: Boolean, message: String): ListenerStatusState {
            val trimmed = message.trim()
            if (trimmed == I18n.t("proxy.status.starting") || trimmed == "Starting...") return Starting
            if (trimmed == I18n.t("proxy.status.stopping") || trimmed == "Stopping...") return Stopping
            if (trimmed == I18n.t("proxy.status.invalid_port") || trimmed == "Invalid port") return InvalidPort
            if (trimmed == I18n.t("proxy.status.stopped") || trimmed == "Proxy stopped") return Stopped

            val failedPrefix = I18n.t("proxy.status.start_failed_prefix")
            if (trimmed.startsWith(failedPrefix)) return StartFailed(trimmed.removePrefix(failedPrefix).trim())
            if (trimmed.startsWith("Failed to start proxy:")) return StartFailed(trimmed.removePrefix("Failed to start proxy:").trim())

            listeningViaUpstreamPattern.matchEntire(trimmed)?.let { match ->
                return ListeningViaUpstream(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
            }
            listeningIgnoringUpstreamPattern.matchEntire(trimmed)?.let { match ->
                return ListeningIgnoringInvalidUpstream(match.groupValues[1], match.groupValues[2])
            }
            listeningPattern.matchEntire(trimmed)?.let { match ->
                return Listening(match.groupValues[1], match.groupValues[2])
            }
            return if (isRunning && trimmed.isBlank()) Raw(I18n.t("proxy.status.listening", "host" to "", "port" to "")) else Raw(trimmed)
        }
    }
}
