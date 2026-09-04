package org.jjgroup.xproxy.settings.ui

import org.jjgroup.xproxy.settings.core.UpstreamProxySettings
import org.jjgroup.xproxy.settings.core.UpstreamProxyProtocol
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.i18n.I18nLocaleOption
import org.jjgroup.xproxy.settings.core.CharsetPolicy
import org.jjgroup.xproxy.settings.core.ResponsePrettySettings
import org.jjgroup.xproxy.settings.core.UiThemeOption
import org.jjgroup.xproxy.settings.core.UiThemeSettings
import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent

class SettingsPanel : JPanel(BorderLayout()) {

    private val labelColumnWidth = 170
    internal val tlsTrustPollExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "xproxy-tls-trust-poller").apply { isDaemon = true }
    }
    @Volatile
    internal var tlsTrustPollFuture: ScheduledFuture<*>? = null

    init {
        val scrollPane = JScrollPane(buildContent())
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBar.unitIncrement = 16
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun buildContent(): JPanel {
        val content = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(6, 8, 6, 8)
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        content.add(buildAboutGroup(), gbc)
        gbc.gridy = 1
        content.add(buildTlsCertificateGroup(), gbc)
        gbc.gridy = 2
        content.add(buildMcpGroup(), gbc)
        gbc.gridy = 3
        content.add(buildAppearanceGroup(), gbc)
        gbc.gridy = 4
        content.add(buildLanguageGroup(), gbc)
        gbc.gridy = 5
        content.add(buildEncodingGroup(), gbc)
        gbc.gridy = 6
        content.add(buildUpstreamProxyGroup(), gbc)
        gbc.gridy = 7
        content.add(buildResponsePrettyGroup(), gbc)
        gbc.gridy = 8
        gbc.weighty = 1.0
        content.add(JPanel(), gbc)
        return content
    }

    private fun buildAboutGroup(): JPanel {
        val group = JPanel(GridBagLayout())
        group.border = BorderFactory.createTitledBorder(I18n.t("settings.about.title"))
        I18nBinder.bindTitleBorder(group, "settings.about.title")

        val c = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 1.0
        }
        val intro = JLabel(I18n.t("settings.about.intro"))
        I18nBinder.bindText(intro, "settings.about.intro")
        intro.foreground = UiThemePalette.mutedText
        group.add(intro, c)

        c.gridy = 1
        val author = JLabel(designerText())
        I18nBinder.bind { author.text = designerText() }
        author.foreground = UiThemePalette.mutedText
        group.add(author, c)

        c.gridy = 2
        val links = createLinkLabel(
            """
            <html>
            ${I18n.t("settings.about.contributors")}:
            <a href='https://github.com/TheKingOfDuck'>@TheKingOfDuck</a>,
            <a href='https://github.com/Phelaine'>@medi0cr1ty</a>
            </html>
            """.trimIndent()
        )
        I18nBinder.bind { links.text = contributorsHtml() }
        group.add(links, c)

        return group
    }

    private fun contributorsHtml(): String =
        """
        <html>
        ${I18n.t("settings.about.contributors")}:
        <a href='https://github.com/TheKingOfDuck'>@TheKingOfDuck</a>,
        <a href='https://github.com/Phelaine'>@medi0cr1ty</a>
        </html>
        """.trimIndent()

    private fun designerText(): String = I18n.t("settings.about.maintained_by") + "：@TheKingOfDuck"

    private fun createLinkLabel(html: String): javax.swing.JEditorPane {
        val pane = javax.swing.JEditorPane("text/html", html)
        pane.isEditable = false
        pane.isOpaque = false
        pane.border = null
        pane.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        pane.font = JLabel().font
        pane.addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                runCatching {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI(event.url.toString()))
                    }
                }
            }
        }
        return pane
    }

    private fun buildAppearanceGroup(): JPanel {
        val group = JPanel(GridBagLayout())
        group.border = BorderFactory.createTitledBorder(I18n.t("settings.appearance.title"))
        I18nBinder.bindTitleBorder(group, "settings.appearance.title")

        val c = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 0.0
        }
        group.add(settingLabel("settings.appearance.theme"), c)

        c.gridx = 1
        c.weightx = 1.0
        val themeBox = JComboBox(UiThemeOption.entries.toTypedArray())
        themeBox.selectedItem = UiThemeSettings.getThemeOption()
        themeBox.addActionListener {
            val selected = themeBox.selectedItem as? UiThemeOption ?: return@addActionListener
            UiThemeSettings.setThemeOption(selected)
            val applied = UiThemeSettings.applyCurrentTheme()
            if (!applied) {
                JOptionPane.showMessageDialog(
                    group,
                    I18n.t("settings.appearance.apply_failed", "theme" to selected.displayName),
                    I18n.t("settings.appearance.apply_failed_title"),
                    JOptionPane.WARNING_MESSAGE
                )
            } else {
                SwingUtilities.getWindowAncestor(group)?.repaint()
            }
        }
        group.add(themeBox, c)

        c.gridx = 0
        c.gridy = 1
        c.gridwidth = 2
        c.weightx = 1.0
        val help = JLabel(I18n.t("settings.appearance.help"))
        I18nBinder.bindText(help, "settings.appearance.help")
        help.foreground = UiThemePalette.mutedText
        group.add(help, c)

        return group
    }

    private fun buildLanguageGroup(): JPanel {
        val group = JPanel(GridBagLayout())
        group.border = BorderFactory.createTitledBorder(I18n.t("settings.language.title"))
        I18nBinder.bindTitleBorder(group, "settings.language.title")

        val c = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 0.0
        }
        val languageLabel = settingLabel("settings.language.current")
        group.add(languageLabel, c)

        c.gridx = 1
        c.weightx = 1.0
        val languageBox = JComboBox(I18nLocaleOption.entries.toTypedArray())
        languageBox.selectedItem = I18n.localeOption()
        group.add(languageBox, c)

        c.gridx = 2
        c.weightx = 0.0
        val reloadButton = javax.swing.JButton(I18n.t("settings.language.reload_translations"))
        I18nBinder.bindText(reloadButton, "settings.language.reload_translations")
        group.add(reloadButton, c)

        c.gridx = 0
        c.gridy = 1
        c.gridwidth = 3
        c.weightx = 1.0
        val status = JLabel()
        status.foreground = UiThemePalette.mutedText
        I18nBinder.bindText(status, "settings.language.directory", "path" to I18n.userBundleRoot().toString())
        group.add(status, c)

        languageBox.addActionListener {
            val selected = languageBox.selectedItem as? I18nLocaleOption ?: return@addActionListener
            val result = I18n.setLocaleOption(selected)
            if (!result.success) {
                status.text = I18n.t("settings.language.reload_failed", "error" to (result.error ?: "unknown"))
            }
        }
        reloadButton.addActionListener {
            val result = I18n.reload()
            status.text = if (result.success) {
                I18n.t("settings.language.reloaded")
            } else {
                I18n.t("settings.language.reload_failed", "error" to (result.error ?: "unknown"))
            }
        }

        return group
    }

    private fun buildEncodingGroup(): JPanel {
        val group = JPanel(GridBagLayout())
        group.border = BorderFactory.createTitledBorder(I18n.t("settings.encoding.title"))
        I18nBinder.bindTitleBorder(group, "settings.encoding.title")

        val c = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
        }
        group.add(settingLabel("settings.encoding.display_charset"), c)

        c.gridx = 1
        c.weightx = 1.0
        val displayCharset = JComboBox(CharsetPolicy.displayOptions.toTypedArray())
        displayCharset.selectedItem = CharsetPolicy.getDisplayOption()
        displayCharset.addActionListener {
            CharsetPolicy.setDisplayOption(displayCharset.selectedItem?.toString() ?: CharsetPolicy.DISPLAY_AUTO)
        }
        group.add(displayCharset, c)

        c.gridx = 0
        c.gridy = 1
        c.weightx = 0.0
        group.add(settingLabel("settings.encoding.forward_charset"), c)

        c.gridx = 1
        c.weightx = 1.0
        val forwardCharset = JComboBox(CharsetPolicy.forwardOptions.toTypedArray())
        forwardCharset.selectedItem = CharsetPolicy.getForwardOption()
        forwardCharset.addActionListener {
            CharsetPolicy.setForwardOption(forwardCharset.selectedItem?.toString() ?: CharsetPolicy.FORWARD_FOLLOW_DISPLAY)
        }
        group.add(forwardCharset, c)

        c.gridx = 0
        c.gridy = 2
        c.gridwidth = 2
        c.weightx = 1.0
        val help = JLabel(I18n.t("settings.encoding.help"))
        I18nBinder.bindText(help, "settings.encoding.help")
        help.foreground = UiThemePalette.mutedText
        group.add(help, c)

        return group
    }

    private fun buildUpstreamProxyGroup(): JPanel {
        val group = JPanel(GridBagLayout())
        group.border = BorderFactory.createTitledBorder(I18n.t("settings.upstream.title"))
        I18nBinder.bindTitleBorder(group, "settings.upstream.title")

        val c = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            gridwidth = 2
        }
        val enabled = JCheckBox(I18n.t("settings.upstream.enable"))
        I18nBinder.bindText(enabled, "settings.upstream.enable")
        enabled.isSelected = UpstreamProxySettings.isEnabled()
        enabled.addActionListener {
            UpstreamProxySettings.setEnabled(enabled.isSelected)
        }
        group.add(enabled, c)

        c.gridwidth = 1
        c.gridx = 0
        c.gridy = 1
        c.weightx = 0.0
        c.gridwidth = 1
        group.add(settingLabel("common.protocol"), c)

        c.gridx = 1
        c.weightx = 1.0
        val protocolBox = JComboBox(UpstreamProxyProtocol.entries.toTypedArray())
        protocolBox.selectedItem = UpstreamProxySettings.getProtocol()
        protocolBox.addActionListener {
            val protocol = protocolBox.selectedItem as? UpstreamProxyProtocol ?: UpstreamProxyProtocol.HTTP
            UpstreamProxySettings.setProtocol(protocol)
        }
        group.add(protocolBox, c)

        c.gridx = 0
        c.gridy = 2
        c.weightx = 0.0
        group.add(settingLabel("common.host"), c)

        c.gridx = 1
        c.weightx = 1.0
        val hostField = JTextField(UpstreamProxySettings.getHost().ifBlank { "127.0.0.1" }, 18)
        hostField.document.addDocumentListener(simpleDocumentListener {
            UpstreamProxySettings.setHost(hostField.text)
        })
        group.add(hostField, c)

        c.gridx = 0
        c.gridy = 3
        c.weightx = 0.0
        group.add(settingLabel("common.port"), c)

        c.gridx = 1
        c.weightx = 1.0
        val portField = JTextField(UpstreamProxySettings.getPort().toString(), 8)
        portField.document.addDocumentListener(simpleDocumentListener {
            val parsed = portField.text.trim().toIntOrNull()
            if (parsed != null && parsed in 1..65535) {
                UpstreamProxySettings.setPort(parsed)
            }
        })
        group.add(portField, c)

        c.gridx = 0
        c.gridy = 4
        c.weightx = 0.0
        group.add(settingLabel("settings.upstream.username"), c)

        c.gridx = 1
        c.weightx = 1.0
        val usernameField = JTextField(UpstreamProxySettings.getUsername(), 18)
        usernameField.document.addDocumentListener(simpleDocumentListener {
            UpstreamProxySettings.setUsername(usernameField.text)
        })
        group.add(usernameField, c)

        c.gridx = 0
        c.gridy = 5
        c.weightx = 0.0
        group.add(settingLabel("settings.upstream.password"), c)

        c.gridx = 1
        c.weightx = 1.0
        val passwordField = JPasswordField(UpstreamProxySettings.getPassword(), 18)
        passwordField.document.addDocumentListener(simpleDocumentListener {
            UpstreamProxySettings.setPassword(String(passwordField.password))
        })
        group.add(passwordField, c)

        c.gridx = 0
        c.gridy = 6
        c.gridwidth = 2
        c.weightx = 1.0
        val help = JLabel(I18n.t("settings.upstream.help"))
        I18nBinder.bindText(help, "settings.upstream.help")
        help.foreground = UiThemePalette.mutedText
        group.add(help, c)

        return group
    }

    private fun simpleDocumentListener(onChange: () -> Unit): DocumentListener {
        return object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onChange()
            override fun removeUpdate(e: DocumentEvent?) = onChange()
            override fun changedUpdate(e: DocumentEvent?) = onChange()
        }
    }

    private fun buildResponsePrettyGroup(): JPanel {
        val group = JPanel(GridBagLayout())
        group.border = BorderFactory.createTitledBorder(I18n.t("settings.response_pretty.title"))
        I18nBinder.bindTitleBorder(group, "settings.response_pretty.title")

        val c = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            gridx = 0
            gridy = 0
            weightx = 0.0
        }

        fun addRow(row: Int, labelKey: String, field: JTextField) {
            c.gridx = 0
            c.gridy = row
            c.gridwidth = 1
            c.weightx = 0.0
            group.add(settingLabel(labelKey), c)
            c.gridx = 1
            c.weightx = 1.0
            group.add(field, c)
        }

        val displayLimitKb = minOf(
            ResponsePrettySettings.getRawBodyPreviewMaxKb(),
            ResponsePrettySettings.getLargeResponsePreviewMaxKb(),
            ResponsePrettySettings.getHtmlRenderMaxKb()
        )
        val displayLimitsField = JTextField(displayLimitKb.toString(), 10)
        displayLimitsField.toolTipText = I18n.t("settings.response_pretty.display_limits_hint")
        I18nBinder.bind { displayLimitsField.toolTipText = I18n.t("settings.response_pretty.display_limits_hint") }
        displayLimitsField.document.addDocumentListener(simpleDocumentListener {
            val value = displayLimitsField.text.trim().toIntOrNull() ?: return@simpleDocumentListener
            if (value >= 0) {
                ResponsePrettySettings.setRawBodyPreviewMaxKb(value)
                if (value >= 1) ResponsePrettySettings.setLargeResponsePreviewMaxKb(value)
                ResponsePrettySettings.setHtmlRenderMaxKb(value)
            }
        })
        addRow(0, "settings.response_pretty.display_limits", displayLimitsField)

        val processingLimitKb = minOf(
            ResponsePrettySettings.getAutoPrettyMaxKb(),
            ResponsePrettySettings.getAutoHighlightMaxKb(),
            ResponsePrettySettings.getSmoothTextViewMaxBodyHighlightKb()
        )
        val processingLimitsField = JTextField(processingLimitKb.toString(), 10)
        processingLimitsField.toolTipText = I18n.t("settings.response_pretty.processing_limits_hint")
        I18nBinder.bind { processingLimitsField.toolTipText = I18n.t("settings.response_pretty.processing_limits_hint") }
        processingLimitsField.document.addDocumentListener(simpleDocumentListener {
            val value = processingLimitsField.text.trim().toIntOrNull() ?: return@simpleDocumentListener
            if (value >= 0) {
                ResponsePrettySettings.setAutoPrettyMaxKb(value)
                if (value > 0) ResponsePrettySettings.setAutoHighlightMaxKb(value)
                ResponsePrettySettings.setSmoothTextViewMaxBodyHighlightKb(value)
            }
        })
        addRow(1, "settings.response_pretty.processing_limits", processingLimitsField)

        val smoothLimitsField = JTextField(ResponsePrettySettings.getSmoothTextViewMaxKb().toString(), 10)
        smoothLimitsField.toolTipText = I18n.t("settings.response_pretty.smooth_limits_hint")
        I18nBinder.bind { smoothLimitsField.toolTipText = I18n.t("settings.response_pretty.smooth_limits_hint") }
        smoothLimitsField.document.addDocumentListener(simpleDocumentListener {
            val value = smoothLimitsField.text.trim().toIntOrNull() ?: return@simpleDocumentListener
            if (value >= 0) {
                ResponsePrettySettings.setSmoothTextViewMaxKb(value)
            }
        })
        addRow(2, "settings.response_pretty.smooth_limits", smoothLimitsField)

        val mimeField = JTextField(ResponsePrettySettings.getAutoPrettyMimeWhitelist(), 40)
        mimeField.document.addDocumentListener(simpleDocumentListener {
            ResponsePrettySettings.setAutoPrettyMimeWhitelist(mimeField.text)
        })
        addRow(3, "settings.response_pretty.mime_whitelist", mimeField)

        c.gridx = 0
        c.gridy = 4
        c.gridwidth = 2
        c.weightx = 1.0
        val help = JLabel(I18n.t("settings.response_pretty.help"))
        I18nBinder.bindText(help, "settings.response_pretty.help")
        help.foreground = UiThemePalette.mutedText
        group.add(help, c)

        c.gridx = 0
        c.gridy = 5
        c.weightx = 1.0
        val help2 = JLabel(I18n.t("settings.response_pretty.default_help"))
        I18nBinder.bindText(help2, "settings.response_pretty.default_help")
        help2.foreground = UiThemePalette.mutedText
        group.add(help2, c)

        return group
    }

    internal fun settingLabel(key: String): JLabel {
        return JLabel(I18n.t(key)).apply {
            I18nBinder.bindText(this, key)
            preferredSize = Dimension(labelColumnWidth, preferredSize.height)
            minimumSize = Dimension(labelColumnWidth, minimumSize.height)
        }
    }

    override fun removeNotify() {
        stopTlsTrustPolling()
        super.removeNotify()
    }
}
