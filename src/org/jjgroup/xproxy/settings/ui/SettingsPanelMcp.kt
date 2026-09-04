package org.jjgroup.xproxy.settings.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.mcp.McpModule
import org.jjgroup.xproxy.settings.core.McpSettings
import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Settings 面板"MCP"组。布局(4 列网格,标签列沿用 [SettingsPanel.settingLabel] 的 170px 固定宽,
 * 与其它设置组对齐):
 *  - 第 0 行:启用开关(整行)
 *  - 第 1 行:运行状态文案(整行,按运行态着色)
 *  - 第 2 行:监听地址 host:port(单一输入框,合并展示;底层仍由 [McpSettings] 分开持久化)
 *  - 第 3 行:鉴权 Token + token轮转 + 复制(同一行)
 *  - 第 4 行:Claude / Codex / OpenCode 一键复制全局添加命令
 *  - 第 5 行:帮助文案
 *
 * 由 [SettingsPanel.buildContent] 在 gridy=2(TLS 证书与外观之间)处加入。**鉴权强制开启**(不可选);
 * MCP 默认随应用启动。开关切换会即时 restart [McpModule];监听地址改动后切换开关生效。
 *
 * host/port 在 UI 合并为一个 `host:port` 输入框(避免原先 host 字段拉伸把端口标签顶到最右、
 * 造成文案与输入框间隔过远);编辑时按最后一个 `:` 拆分回写 [McpSettings.setBindHost]/[setPort],
 * IPv6 字面量(如 `[::1]:8080`)的方括号会被剥离。
 */
internal fun SettingsPanel.buildMcpGroup(): JPanel {
    val group = JPanel(GridBagLayout())
    group.border = BorderFactory.createTitledBorder(I18n.t("settings.mcp.title"))
    I18nBinder.bindTitleBorder(group, "settings.mcp.title")

    val c = GridBagConstraints().apply {
        insets = Insets(4, 6, 4, 6)
        anchor = GridBagConstraints.WEST
        fill = GridBagConstraints.HORIZONTAL
        gridx = 0
        gridy = 0
        gridwidth = 1
        weightx = 0.0
    }

    val statusLabel = JLabel()
    fun refreshStatus() {
        val s = McpModule.statusSummary()
        val running = s["running"] == true
        val state = if (running) I18n.t("settings.mcp.state_running") else I18n.t("settings.mcp.state_stopped")
        statusLabel.text = "$state  ·  ${s["endpoint"]}  ·  ${I18n.t("settings.mcp.tools")}: ${s["toolCount"]}"
        statusLabel.foreground = if (running) UiThemePalette.successText else UiThemePalette.dangerText
    }
    refreshStatus()
    // 注册状态监听:MCP autostart 在面板构造之后才跑(后台线程),start/stop 后由此通知刷新状态行,
    // 否则状态行会停在构造时的"已停止"不自更新。
    McpModule.addStatusListener { SwingUtilities.invokeLater { refreshStatus() } }

    fun applyChange(action: () -> String) {
        Thread({
            runCatching { action() }
            SwingUtilities.invokeLater { refreshStatus() }
        }, "xproxy-mcp-settings-apply").apply { isDaemon = true }.start()
    }

    val tokenField = JTextField(McpSettings.ensureAuthToken(), 32).apply { isEditable = false }

    // ---- 第 0 行:启用开关(整行;跨 4 列,不进入 col 0,避免其文字宽度撑宽标签列) ----
    val enabled = JCheckBox(I18n.t("settings.mcp.enable"))
    I18nBinder.bindText(enabled, "settings.mcp.enable")
    enabled.isSelected = McpSettings.isEnabled()
    enabled.addActionListener {
        McpSettings.setEnabled(enabled.isSelected)
        applyChange { McpModule.restart() }
    }
    c.gridx = 0
    c.gridy = 0
    c.gridwidth = 4
    c.weightx = 1.0
    group.add(enabled, c)

    // ---- 第 1 行:运行状态(整行,按运行态着色) ----
    c.gridy = 1
    c.gridx = 0
    c.gridwidth = 4
    c.weightx = 1.0
    group.add(statusLabel, c)

    // ---- 第 2 行:监听地址 host:port(单一输入框;占 col 1-3 填满整行) ----
    c.gridy = 2
    c.gridx = 0
    c.gridwidth = 1
    c.weightx = 0.0
    group.add(settingLabel("settings.mcp.bind_host"), c)
    c.gridx = 1
    c.gridwidth = 3
    c.weightx = 1.0
    val endpointField = JTextField("${McpSettings.getBindHost()}:${McpSettings.getPort()}", 20)
    endpointField.document.addDocumentListener(simpleDocListener {
        val text = endpointField.text.trim()
        val idx = text.lastIndexOf(':')
        if (idx > 0) {
            val host = text.substring(0, idx).trim().removeSurrounding("[", "]")
            val portStr = text.substring(idx + 1).trim()
            if (host.isNotBlank()) McpSettings.setBindHost(host)
            portStr.toIntOrNull()?.takeIf { it in 1..65535 }?.let { McpSettings.setPort(it) }
        } else if (text.isNotBlank() && !text.startsWith(":")) {
            // 仅 host(无冒号):只更新绑定地址,端口保持不变(用户正输入中)。
            McpSettings.setBindHost(text)
        }
    })
    group.add(endpointField, c)

    // ---- 第 3 行:鉴权 Token + token轮转 + 复制 ----
    c.gridy = 3
    c.gridx = 0
    c.gridwidth = 1
    c.weightx = 0.0
    group.add(settingLabel("settings.mcp.token"), c)
    c.gridx = 1
    c.gridwidth = 1
    c.weightx = 1.0
    group.add(tokenField, c)

    c.gridx = 2
    c.gridwidth = 1
    c.weightx = 0.0
    val rotate = JButton(I18n.t("settings.mcp.regenerate"))
    I18nBinder.bindText(rotate, "settings.mcp.regenerate")
    rotate.addActionListener {
        val confirm = JOptionPane.showConfirmDialog(
            group,
            I18n.t("settings.mcp.regenerate_confirm"),
            I18n.t("settings.mcp.regenerate"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirm == JOptionPane.YES_OPTION) {
            tokenField.text = McpSettings.regenerateAuthToken()
        }
    }
    group.add(rotate, c)

    c.gridx = 3
    c.gridwidth = 1
    c.weightx = 0.0
    val copy = JButton(I18n.t("settings.mcp.copy"))
    I18nBinder.bindText(copy, "settings.mcp.copy")
    copy.addActionListener {
        val sel = StringSelection(tokenField.text)
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel) }
    }
    group.add(copy, c)

    // ---- 第 4 行:Claude / Codex / OpenCode 一键复制全局添加命令 ----
    c.gridy = 4
    c.gridx = 0
    c.gridwidth = 4
    c.weightx = 1.0
    val configRow = JPanel(GridBagLayout()).apply { isOpaque = false }
    val cc = GridBagConstraints().apply {
        insets = Insets(2, 0, 2, 6)
        anchor = GridBagConstraints.WEST
        gridx = 0
        gridy = 0
    }
    fun addConfigButton(label: String, buildCommand: () -> String) {
        val btn = JButton(label)
        btn.addActionListener {
            val command = buildCommand()
            val sel = StringSelection(command)
            runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel) }
            JOptionPane.showMessageDialog(
                group,
                I18n.t("settings.mcp.config_copied") + "\n\n" + command,
                label,
                JOptionPane.INFORMATION_MESSAGE
            )
        }
        configRow.add(btn, cc)
        cc.gridx += 1
    }
    addConfigButton("Claude") { claudeAddCommand(McpModule.endpointUrl(), McpSettings.getAuthToken()) }
    addConfigButton("Codex") { codexAddCommand(McpModule.endpointUrl(), McpSettings.getAuthToken()) }
    addConfigButton("OpenCode") { opencodeAddCommand(McpModule.endpointUrl(), McpSettings.getAuthToken()) }
    group.add(configRow, c)

    // ---- 第 5 行:帮助 ----
    c.gridy = 5
    c.gridx = 0
    c.gridwidth = 4
    c.weightx = 1.0
    val help = JLabel(I18n.t("settings.mcp.help"))
    I18nBinder.bindText(help, "settings.mcp.help")
    help.foreground = UiThemePalette.mutedText
    group.add(help, c)

    return group
}

// ---- 各 agent 工具"全局添加本 MCP 服务"的命令/配置(用当前 endpoint + token 实时填充) ----
// 语法据 `claude mcp add -h` / `codex mcp add -h` / opencode config schema 实测确认。

/** Claude Code:全局(user scope)添加 HTTP MCP。`--header` 对 HTTP 生效(见 -h 示例)。 */
private fun claudeAddCommand(endpoint: String, token: String): String =
    "claude mcp add --transport http --scope user xproxy $endpoint --header \"Authorization: Bearer $token\""

/**
 * Codex:`codex mcp add` 的 HTTP MCP 仅支持 `--bearer-token-env-var`(实测无 `--bearer-token`/`--header` 直接选项,
 * 均报 unexpected argument)。token 只能从环境变量读:把 export 写入 ~/.zshrc 持久化、source 生效、再 add。
 * 注意:不能用 `export VAR=val >> ~/.zshrc`(export 无 stdout,只会追加空行),须用 echo 写入。
 */
private fun codexAddCommand(endpoint: String, token: String): String =
    "# codex HTTP MCP 仅支持从环境变量读 token;先写入 ~/.zshrc 持久化:\n" +
    "echo 'export XPROXY_TOKEN=\"$token\"' >> ~/.zshrc\n" +
    "source ~/.zshrc\n" +
    "# 添加 MCP 服务(codex 运行时从 \$XPROXY_TOKEN 读 token):\n" +
    "codex mcp add xproxy --url $endpoint --bearer-token-env-var XPROXY_TOKEN"

/** OpenCode:`opencode mcp add <name> --url <url> --header "Authorization=Bearer <token>"`(header 用 = 分隔)。 */
private fun opencodeAddCommand(endpoint: String, token: String): String =
    "opencode mcp add xproxy --url $endpoint --header \"Authorization=Bearer $token\""

/** 轻量 DocumentListener(javax.swing.DocumentListener 有 3 个抽象方法,不能直接 SAM 转换)。 */
private fun simpleDocListener(onChange: () -> Unit): DocumentListener = object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) = onChange()
    override fun removeUpdate(e: DocumentEvent) = onChange()
    override fun changedUpdate(e: DocumentEvent) = onChange()
}
