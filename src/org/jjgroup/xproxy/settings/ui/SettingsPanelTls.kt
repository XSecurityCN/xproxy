package org.jjgroup.xproxy.settings.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.settings.core.TlsCertificateSettings
import org.jjgroup.xproxy.settings.core.XproxyCaManager
import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.Color
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

internal data class TlsCertStatus(
    val title: String,
    val detail: String,
    val color: Color,
    val trusted: Boolean
)

internal fun SettingsPanel.buildTlsCertificateGroup(): JPanel {
    val group = JPanel(GridBagLayout())
    group.border = BorderFactory.createTitledBorder(I18n.t("settings.tls.title"))
    I18nBinder.bindTitleBorder(group, "settings.tls.title")

    val c = GridBagConstraints().apply {
        insets = Insets(4, 6, 4, 6)
        anchor = GridBagConstraints.WEST
        fill = GridBagConstraints.HORIZONTAL
    }

    val statusTitle = JLabel()
    val statusDetail = JLabel()
    fun applyTlsStatus(status: TlsCertStatus) {
        statusTitle.text = status.title
        statusTitle.foreground = status.color
        statusDetail.text = status.detail
        statusDetail.foreground = UiThemePalette.mutedText
    }
    fun refreshTlsStatus() {
        val status = detectTlsCertificateStatus()
        applyTlsStatus(status)
    }

    c.gridx = 0
    c.gridy = 0
    c.gridwidth = 1
    c.weightx = 0.0
    group.add(statusTitle, c)

    c.gridx = 1
    c.weightx = 1.0
    group.add(statusDetail, c)

    c.gridx = 0
    c.gridy = 1
    c.gridwidth = 2
    c.weightx = 1.0
    val description = JLabel(I18n.t("settings.tls.description"))
    I18nBinder.bindText(description, "settings.tls.description")
    group.add(description, c)

    c.gridy = 2
    c.gridwidth = 1
    c.weightx = 0.0
    val exportButton = JButton(I18n.t("settings.tls.export_ca"))
    I18nBinder.bindText(exportButton, "settings.tls.export_ca")
    exportButton.addActionListener {
        exportCertificate(group)
        refreshTlsStatus()
        startTlsTrustPolling { polledStatus ->
            applyTlsStatus(polledStatus)
        }
    }
    group.add(exportButton, c)

    c.gridx = 1
    c.weightx = 1.0
    val note = JLabel(I18n.t("settings.tls.default_export"))
        I18nBinder.bindText(note, "settings.tls.default_export")
        note.foreground = UiThemePalette.mutedText
        group.add(note, c)

    refreshTlsStatus()
    I18nBinder.bind { refreshTlsStatus() }

    return group
}

internal fun SettingsPanel.detectTlsCertificateStatus(): TlsCertStatus {
    if (!Files.exists(XproxyCaManager.caCertPath)) {
        return TlsCertStatus(
            title = I18n.t("settings.tls.status_not_exported"),
            detail = I18n.t("settings.tls.detail_not_exported"),
            color = Color(120, 120, 120),
            trusted = false
        )
    }

    val trusted = isMacOs() && isCertificateTrustedOnMac(XproxyCaManager.caCertPath.toString())
    if (trusted) {
        return TlsCertStatus(
            title = I18n.t("settings.tls.status_trusted"),
            detail = I18n.t("settings.tls.detail_trusted"),
            color = Color(32, 128, 64),
            trusted = true
        )
    }

    return TlsCertStatus(
        title = I18n.t("settings.tls.status_exported_not_trusted"),
        detail = I18n.t("settings.tls.detail_exported_not_trusted"),
        color = Color(196, 122, 0),
        trusted = false
    )
}

@Synchronized
internal fun SettingsPanel.startTlsTrustPolling(onStatusPolled: (TlsCertStatus) -> Unit) {
    stopTlsTrustPolling()
    if (!isMacOs()) {
        return
    }
    val initialStatus = detectTlsCertificateStatus()
    if (initialStatus.trusted) {
        SwingUtilities.invokeLater {
            onStatusPolled(initialStatus)
        }
        return
    }
    tlsTrustPollFuture = tlsTrustPollExecutor.scheduleWithFixedDelay(
        {
            val status = detectTlsCertificateStatus()
            SwingUtilities.invokeLater {
                onStatusPolled(status)
            }
            if (status.trusted) {
                stopTlsTrustPolling()
            }
        },
        3,
        3,
        TimeUnit.SECONDS
    )
}

@Synchronized
internal fun SettingsPanel.stopTlsTrustPolling() {
    tlsTrustPollFuture?.cancel(false)
    tlsTrustPollFuture = null
}

internal fun SettingsPanel.exportCertificate(parent: JPanel) {
    if (!Files.exists(XproxyCaManager.caCertPath)) {
        XproxyCaManager.ensureCaMaterial()
    }
    val target = XproxyCaManager.caCertPath
    val source = XproxyCaManager.caCertPath
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    TlsCertificateSettings.setLastExportDir(target.parent?.toString() ?: "")

    revealInFinder(target.toFile())
    JOptionPane.showMessageDialog(parent, I18n.t("settings.tls.exported_to", "path" to target), I18n.t("settings.tls.export_success"), JOptionPane.INFORMATION_MESSAGE)
}

private fun isMacOs(): Boolean {
    return System.getProperty("os.name").contains("mac", ignoreCase = true)
}

private fun isCertificateTrustedOnMac(certificatePath: String): Boolean {
    return try {
        val process = ProcessBuilder(
            "security",
            "verify-cert",
            "-c",
            certificatePath,
            "-p",
            "ssl"
        )
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        process.waitFor() == 0
    } catch (_: Exception) {
        false
    }
}

private fun revealInFinder(file: File) {
    try {
        ProcessBuilder("open", "-R", file.absolutePath).start()
    } catch (_: Exception) {
    }
}
