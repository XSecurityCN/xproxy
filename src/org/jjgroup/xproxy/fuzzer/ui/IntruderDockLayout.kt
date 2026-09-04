package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.codec.core.CodecHub
import org.jjgroup.xproxy.codec.ui.CodecPanel
import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.issue.core.ScriptIssueHub
import org.jjgroup.xproxy.kits.ui.KitsPanel
import org.jjgroup.xproxy.mcp.McpModule
import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.project.core.ProjectBootstrapData
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectRecord
import org.jjgroup.xproxy.proxy.ui.ProxyPanel
import org.jjgroup.xproxy.proxy.ui.deleteHistoryByIds
import org.jjgroup.xproxy.proxy.ui.recordExternalHistory
import org.jjgroup.xproxy.proxy.ws.WsRepeaterTarget
import org.jjgroup.xproxy.settings.core.McpSettings
import org.jjgroup.xproxy.settings.ui.SettingsPanel
import org.jjgroup.xproxy.settings.core.UiThemePalette
import org.jjgroup.xproxy.target.ui.TargetPanel
import org.jjgroup.xproxy.ui.marking.TrafficHighlightRegistry

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import javax.swing.*

internal const val DOCK_CARD_KEY_PROPERTY = "xproxy.dock.cardKey"

internal fun isDockButtonInNotifyWindowForTests(button: AbstractButton, notifyUntilByCard: Map<String, Long>): Boolean =
    isDockButtonInNotifyWindow(button, notifyUntilByCard, System.currentTimeMillis())

private fun isDockButtonInNotifyWindow(button: AbstractButton, notifyUntilByCard: Map<String, Long>, nowMillis: Long): Boolean {
    val card = button.getClientProperty(DOCK_CARD_KEY_PROPERTY) as? String ?: button.text.lowercase()
    val notifyUntil = notifyUntilByCard[card] ?: 0L
    return nowMillis < notifyUntil
}

internal fun renderMainDockLayout(
    frame: IntruderFrame,
    pane: JSplitPane,
    button: JButton,
    hideIntruderDrawer: () -> Unit,
    projectDataStore: ProjectDataStore?,
    bootstrapData: ProjectBootstrapData?,
    selectedProject: ProjectRecord?,
    sendRequestToFuzzer: (String, HttpService?) -> Unit,
    onSendToWsRepeater: (WsRepeaterTarget, Int, String, org.jjgroup.xproxy.proxy.ws.WsLiveConnection?) -> Unit,
    shutdownFuzzer: () -> Unit
): () -> Unit {
    var flashFuzzerDockButton: (() -> Unit) = {}
    var notifyTargetDockButton: (() -> Unit) = {}
    val dockNotifyDurationMillis = 5000
    var proxyPanelRef: ProxyPanel? = null
    val sendToFuzzerAndNotify: (String, HttpService?) -> Unit = { requestRaw, targetHint ->
        sendRequestToFuzzer(requestRaw, targetHint)
        SwingUtilities.invokeLater {
            flashFuzzerDockButton()
        }
    }
    val targetPanel = TargetPanel(
        onSendToFuzzer = sendToFuzzerAndNotify,
        onSendToCodec = { text, tabTitle -> CodecHub.send(text, tabTitle) },
        onDeleteHistoryIds = { ids -> proxyPanelRef?.deleteHistoryByIds(ids) },
        onDeleteReportedIssueId = { issueId -> projectDataStore?.deleteReportedIssue(issueId) },
        detailLoader = { historyId -> projectDataStore?.loadHistoryById(historyId) },
        issueRawLoader = { issueId -> projectDataStore?.loadReportedIssueRaw(issueId) }
    )
    targetPanel.ingestFromProxyBatch(bootstrapData?.proxyHistory.orEmpty())
    targetPanel.ingestReportedIssuesBatch(projectDataStore?.loadReportedIssueMetadata().orEmpty())
    val disposeIssueSubscription = ScriptIssueHub.subscribe { issue ->
        targetPanel.ingestReportedIssue(issue)
        projectDataStore?.saveReportedIssue(issue)
        SwingUtilities.invokeLater {
            notifyTargetDockButton()
        }
    }
    frame.addWindowListener(object : java.awt.event.WindowAdapter() {
        override fun windowClosing(e: java.awt.event.WindowEvent?) {
            disposeIssueSubscription.invoke()
        }

        override fun windowClosed(e: java.awt.event.WindowEvent?) {
            disposeIssueSubscription.invoke()
        }
    })
    val cardBorderColor = UIManager.getColor("Component.borderColor")
        ?: UIManager.getColor("Separator.foreground")
        ?: Color(188, 188, 194)
    val cardBackgroundColor = UIManager.getColor("Panel.background")
        ?: UIManager.getColor("Component.background")
        ?: Color(252, 252, 253)

    fun wrapModuleCard(content: Component): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = true
            background = cardBackgroundColor
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(4, 6, 4, 6),
                    BorderFactory.createLineBorder(cardBorderColor, 1, true)
                ),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
            )
            add(content, BorderLayout.CENTER)
        }

    val targetTab = wrapModuleCard(targetPanel)
    val kitsPanel = KitsPanel(
        projectDataStore = projectDataStore,
        onXappHistoryEntry = { entry -> proxyPanelRef?.recordExternalHistory(entry) }
    )
    val proxyTab = JPanel(BorderLayout())
    val proxyPanel = ProxyPanel(
        onSendToFuzzer = sendToFuzzerAndNotify,
        onSendToCodec = { text, tabTitle -> CodecHub.send(text, tabTitle) },
        onSendToWsRepeater = { target, opcode, payload, liveConnection ->
            onSendToWsRepeater(target, opcode, payload, liveConnection)
            // 仅闪烁 fuzzer dock 按钮提示已新开 WS tab,不跳转卡片(保持当前视图)。
            SwingUtilities.invokeLater { flashFuzzerDockButton() }
        },
        projectDataStore = projectDataStore,
        initialHistory = bootstrapData?.proxyHistory,
        initialWsHistory = bootstrapData?.wsHistory,
        onHistoryEntryAdded = { entry -> targetPanel.ingestFromProxy(entry) },
        onHistoryEntryCaptured = { entry -> kitsPanel.onProxyHistoryEntry(entry) },
        onBeforeRequestRewrite = { requestRaw, host, tls -> kitsPanel.onBeforeRequestRewrite(requestRaw, host, tls) },
        onAfterResponseRewrite = { requestRaw, responseRaw, host, tls ->
            kitsPanel.onAfterResponseRewrite(requestRaw, responseRaw, host, tls)
        }
    )
    proxyPanelRef = proxyPanel
    proxyTab.add(wrapModuleCard(proxyPanel), BorderLayout.CENTER)
    val fuzzerTab = JPanel(BorderLayout())
    val codecPanel = CodecPanel()
    val codecTab = wrapModuleCard(codecPanel)
    val kitsTab = wrapModuleCard(kitsPanel)
    val settingsTab = SettingsPanel()
    val settingsTabWrapper = wrapModuleCard(settingsTab)
    fuzzerTab.add(wrapModuleCard(pane), BorderLayout.CENTER)

    val mainCardLayout = CardLayout()
    val mainCards = JPanel(mainCardLayout).apply {
        add(targetTab, "target")
        add(proxyTab, "proxy")
        add(fuzzerTab, "fuzzer")
        add(codecTab, "codec")
        add(kitsTab, "kits")
        add(settingsTabWrapper, "settings")
    }

    val dockBar = JPanel(FlowLayout(FlowLayout.CENTER, 12, 6)).apply {
        border = BorderFactory.createEmptyBorder(6, 8, 10, 8)
    }
    val dockGroup = ButtonGroup()
    val dockButtons = mutableMapOf<String, JToggleButton>()
    val dockNotifyUntil = mutableMapOf<String, Long>()
    val dockNotifyTimers = mutableMapOf<String, Timer>()

    fun notifyDock(card: String) {
        val btn = dockButtons[card] ?: return
        val until = System.currentTimeMillis() + dockNotifyDurationMillis
        dockNotifyUntil[card] = until
        dockNotifyTimers.remove(card)?.stop()
        val timer = Timer(dockNotifyDurationMillis) {
            val latestUntil = dockNotifyUntil[card] ?: 0L
            if (System.currentTimeMillis() >= latestUntil) {
                dockNotifyUntil.remove(card)
            }
            btn.repaint()
        }
        timer.isRepeats = false
        dockNotifyTimers[card] = timer
        btn.repaint()
        dockBar.repaint()
        timer.start()
    }

    class DockGlyphIcon(private val key: String) : Icon {
        override fun getIconWidth(): Int = 24
        override fun getIconHeight(): Int = 24

        override fun paintIcon(c: Component?, g: java.awt.Graphics?, x: Int, y: Int) {
            val g2 = g?.create() as? Graphics2D ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.stroke = BasicStroke(2.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            // 与按钮文案同色：notify 窗口内按钮 foreground 被设为 accent，图标随之变橙；
            // 选中/空闲态也自动跟随文案色。
            g2.color = (c as? JComponent)?.foreground ?: UiThemePalette.dockIdleText
            when (key) {
                "target" -> {
                    g2.drawOval(x + 2, y + 2, 19, 19)
                    g2.drawLine(x + 12, y + 4, x + 12, y + 20)
                    g2.drawLine(x + 4, y + 12, x + 20, y + 12)
                }
                "proxy" -> {
                    g2.drawRoundRect(x + 2, y + 5, 19, 14, 4, 4)
                    g2.drawLine(x + 9, y + 9, x + 9, y + 15)
                    g2.drawLine(x + 15, y + 9, x + 15, y + 15)
                }
                "fuzzer" -> {
                    g2.drawLine(x + 3, y + 20, x + 10, y + 4)
                    g2.drawLine(x + 10, y + 4, x + 15, y + 12)
                    g2.drawLine(x + 15, y + 12, x + 21, y + 4)
                }
                "codec" -> {
                    g2.drawRoundRect(x + 4, y + 5, 16, 14, 3, 3)
                    g2.drawLine(x + 8, y + 9, x + 16, y + 9)
                    g2.drawLine(x + 8, y + 12, x + 16, y + 12)
                    g2.drawLine(x + 8, y + 15, x + 13, y + 15)
                }
                "kits" -> {
                    // handle
                    g2.drawLine(x + 9, y + 3, x + 9, y + 7)
                    g2.drawLine(x + 9, y + 3, x + 15, y + 3)
                    g2.drawLine(x + 15, y + 3, x + 15, y + 7)
                    // box body
                    g2.drawRoundRect(x + 3, y + 7, 18, 14, 3, 3)
                    // divider
                    g2.drawLine(x + 3, y + 12, x + 21, y + 12)
                }
                else -> {
                    g2.drawOval(x + 3, y + 3, 18, 18)
                    g2.drawLine(x + 12, y + 2, x + 12, y + 7)
                    g2.drawLine(x + 12, y + 17, x + 12, y + 22)
                    g2.drawLine(x + 2, y + 12, x + 7, y + 12)
                    g2.drawLine(x + 17, y + 12, x + 22, y + 12)
                }
            }
            g2.dispose()
        }
    }

    class DockToggleButton(label: String, icon: Icon) : JToggleButton(label, icon) {
        private var hovered = false

        init {
            isOpaque = false
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            horizontalAlignment = SwingConstants.CENTER
            horizontalTextPosition = SwingConstants.RIGHT
            verticalTextPosition = SwingConstants.CENTER
            iconTextGap = 6
            margin = Insets(3, 10, 3, 10)
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
            preferredSize = Dimension(108, 45)
            minimumSize = preferredSize
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    hovered = true
                    repaint()
                }

                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    hovered = false
                    repaint()
                }
            })
        }

        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = when {
                isSelected -> UiThemePalette.dockSelectedBg
                hovered -> UiThemePalette.dockHoverBg
                else -> null
            }
            if (bg != null) {
                g2.color = bg
                g2.fillRoundRect(4, 4, width - 8, height - 8, 16, 16)
                if (isSelected) {
                    g2.color = UiThemePalette.dockSelectedBorder
                    g2.drawRoundRect(4, 4, width - 9, height - 9, 16, 16)
                }
            }
            if (isSelected) {
                g2.color = UiThemePalette.accent
                g2.fillRoundRect(width / 2 - 42, height - 6, 84, 3, 3, 3)
            }
            g2.dispose()
            val inNotifyWindow = isDockButtonInNotifyWindow(this, dockNotifyUntil, System.currentTimeMillis())
            foreground = when {
                inNotifyWindow -> UiThemePalette.accent
                isSelected -> UiThemePalette.dockSelectedText
                else -> UiThemePalette.dockIdleText
            }
            super.paintComponent(g)
        }
    }

    fun createDockIcon(card: String): Icon =
        DockGlyphIcon(card)

    fun addDockButton(labelKey: String, card: String, selected: Boolean) {
        lateinit var dockButton: DockToggleButton
        dockButton = DockToggleButton(I18n.t(labelKey), createDockIcon(card))
        dockButton.putClientProperty(DOCK_CARD_KEY_PROPERTY, card)
        I18nBinder.bindText(dockButton, labelKey)
        dockButton.addActionListener {
            mainCardLayout.show(mainCards, card)
            frame.title = formatMainWindowTitle(selectedProject)
            dockBar.repaint()
        }
        dockGroup.add(dockButton)
        dockBar.add(dockButton)
        dockButtons[card] = dockButton
        if (selected) {
            dockButton.isSelected = true
            mainCardLayout.show(mainCards, card)
            frame.title = formatMainWindowTitle(selectedProject)
        }
    }

    addDockButton("tabs.target", "target", false)
    addDockButton("tabs.proxy", "proxy", true)
    addDockButton("tabs.fuzzer", "fuzzer", false)
    addDockButton("tabs.codec", "codec", false)
    addDockButton("tabs.kits", "kits", false)
    addDockButton("tabs.settings", "settings", false)

    fun selectCard(card: String) {
        mainCardLayout.show(mainCards, card)
        dockButtons[card]?.isSelected = true
        dockBar.repaint()
    }

    flashFuzzerDockButton = {
        notifyDock("fuzzer")
    }
    notifyTargetDockButton = {
        notifyDock("target")
    }

    CodecHub.registerMessageListener("main-dock-notify") { _, _ ->
        SwingUtilities.invokeLater {
            notifyDock("codec")
        }
    }

    val contentShell = JPanel(BorderLayout()).apply {
        add(mainCards, BorderLayout.CENTER)
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, UiThemePalette.dockShellBorder)
    }

    val dockShell = JPanel(BorderLayout()).apply {
        add(dockBar, BorderLayout.CENTER)
    }

    val mainLayout = JPanel(BorderLayout()).apply {
        add(contentShell, BorderLayout.CENTER)
        add(dockShell, BorderLayout.SOUTH)
    }

    frame.add(mainLayout)
    frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
    frame.addWindowListener(object : java.awt.event.WindowAdapter() {
        override fun windowClosing(e: java.awt.event.WindowEvent?) {
            val result = JOptionPane.showConfirmDialog(
                frame,
                "Are you sure you want to exit?",
                "Exit Confirmation",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )
            if (result == JOptionPane.YES_OPTION) {
                runCatching { McpModule.shutdown() }
                XproxyAppContext.clear()
                TrafficHighlightRegistry.clear()
                proxyPanel.shutdown()
                shutdownFuzzer()
                codecPanel.shutdown()
                kitsPanel.shutdown()
                CodecHub.unregisterMessageListener("main-dock-notify")
                frame.dispose()
            }
        }

        override fun windowClosed(e: java.awt.event.WindowEvent?) {
            CodecHub.unregisterMessageListener("main-dock-notify")
        }
    })
    pane.rootPane.defaultButton = button
    frame.pack()
    applyWindowIcon(frame)
    val screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val defaultHeight = (screenBounds.height * 0.80).toInt()
    val defaultWidth = (defaultHeight * 16.0 / 9.0).toInt()
    frame.minimumSize = Dimension(1080, 720)
    frame.setSize(
        defaultWidth.coerceIn(frame.minimumSize.width, screenBounds.width),
        defaultHeight.coerceIn(frame.minimumSize.height, screenBounds.height)
    )
    frame.setLocationRelativeTo(null)
    frame.isVisible = true
    button.requestFocus()
    button.requestFocusInWindow()
    hideIntruderDrawer()

    // 暴露活跃应用状态给 MCP 服务端(dock 面板此前无全局持有者),并在 Settings 开启时启动 MCP。
    XproxyAppContext.bind(
        projectRecord = selectedProject,
        projectDataStore = projectDataStore,
        proxyPanel = proxyPanel,
        targetPanel = targetPanel,
        kitsPanel = kitsPanel,
        frame = frame
    )
    // 绑定流量高亮注册表到当前项目存储(代理历史表 / WS 历史表 / Target 内容表共享),并 hydrate 已有标记。
    TrafficHighlightRegistry.bind(projectDataStore)
    // 激活 dock 卡片提供者:读取当前选中的 dock 按钮对应的卡片名(须在 EDT 调用)。
    XproxyAppContext.setActiveDockCardProvider { dockButtons.entries.firstOrNull { it.value.isSelected }?.key }
    if (McpSettings.isEnabled()) {
        Thread({ runCatching { McpModule.start() }.onFailure { AppLogger.error("MCP autostart failed", it) } }, "xproxy-mcp-autostart")
            .apply { isDaemon = true }.start()
    }

    return {
        SwingUtilities.invokeLater {
            notifyDock("fuzzer")
        }
    }
}
internal fun applyWindowIcon(frame: JFrame) {
    runCatching {
        val url = frame.javaClass.classLoader.getResource("xproxy-icon.png") ?: return@runCatching
        val image = javax.imageio.ImageIO.read(url)
        frame.setIconImage(image)
    }
}
