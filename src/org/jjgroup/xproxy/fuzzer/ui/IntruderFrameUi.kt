package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.codec.core.CodecHub
import org.jjgroup.xproxy.core.Settings
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.core.SeedRequest
import org.jjgroup.xproxy.fuzzer.model.RecordResize
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.mcp.XproxyAppContext
import org.jjgroup.xproxy.project.core.ProjectBootstrapData
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.project.core.ProjectRecord
import org.jjgroup.xproxy.settings.core.CharsetPolicy
import org.jjgroup.xproxy.settings.core.McpSettings
import org.jjgroup.xproxy.settings.core.ResponsePrettySettings
import org.jjgroup.xproxy.settings.core.TlsCertificateSettings
import org.jjgroup.xproxy.settings.core.UiThemePalette
import org.jjgroup.xproxy.settings.core.UiThemeSettings
import org.jjgroup.xproxy.settings.core.UpstreamProxySettings

import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.nio.file.Files
import java.nio.file.Paths
import javax.swing.*

fun buildIntruderUI(
    frame: IntruderFrame,
    seed: SeedRequest,
    fixedScript: String?,
    requestOverride: ByteArray?,
    defaultScript: String,
    selectedProject: ProjectRecord?,
    bootstrapData: ProjectBootstrapData? = null
) {
    Settings.registerSetting("font-size", 14)
    Settings.registerSetting("line-numbers", true)
    Settings.registerSetting("show-eol", false)
    Settings.registerSetting("visible-whitespace", false)
    CharsetPolicy.registerSettings()
    ResponsePrettySettings.registerSettings()
    UpstreamProxySettings.registerSettings()
    TlsCertificateSettings.registerSettings()
    UiThemeSettings.registerSettings()
    McpSettings.registerSettings()
    val projectDataStore = selectedProject?.let { ProjectDataStore(it) }

    val pane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
    pane.resizeWeight = 1.0
    pane.setDividerLocation(1.0)
    pane.addComponentListener(RecordResize())
    val defaultPaneDividerSize = pane.dividerSize

    val panel = JPanel(BorderLayout())
    val editor = createScriptEditorBundle(frame, projectDataStore)
    val textEditor = editor.textEditor
    val scrollableTextEditor = editor.scrollableTextEditor
    val codeCombo = editor.codeCombo
    val loadDirectoryButton = editor.loadDirectoryButton
    val saveButton = editor.saveButton
    val closeIntruderButton = JButton(I18n.t("common.close"))
    I18nBinder.bindText(closeIntruderButton, "common.close")

    val topPanel = JPanel(BorderLayout())
    val initialService = seed.service

    val rightPanel = JPanel(GridBagLayout())
    val gbc = GridBagConstraints().apply {
        insets = Insets(0, 4, 0, 0)
        gridy = 0
        gridx = 0
        weightx = 0.0
        fill = GridBagConstraints.NONE
    }
    val placeholderLabel = JLabel(I18n.t("fuzzer.placeholder"))
    I18nBinder.bindText(placeholderLabel, "fuzzer.placeholder")
    rightPanel.add(placeholderLabel, gbc)

    gbc.gridx = 1
    val addButtonNormal = UiThemePalette.accent
    val addButtonPressed = UiThemePalette.accentPressed
    val addPlaceholderButton = JButton(I18n.t("common.add")).apply {
        background = addButtonNormal
        foreground = UiThemePalette.accentText
        putClientProperty("JButton.background", addButtonNormal)
        putClientProperty("JButton.default.background", addButtonNormal)
        model.addChangeListener {
            val activeColor = if (model.isArmed || model.isPressed) {
                addButtonPressed
            } else {
                addButtonNormal
            }
            background = activeColor
            putClientProperty("JButton.background", activeColor)
            putClientProperty("JButton.default.background", activeColor)
        }
        isEnabled = false
    }
    I18nBinder.bindText(addPlaceholderButton, "common.add")
    rightPanel.add(addPlaceholderButton, gbc)

    gbc.gridx = 2
    val clearPlaceholderButton = JButton(I18n.t("common.clear"))
    I18nBinder.bindText(clearPlaceholderButton, "common.clear")
    rightPanel.add(clearPlaceholderButton, gbc)

    gbc.gridx = 3
    gbc.insets = Insets(0, 16, 0, 0)
    val attackScriptLabel = JLabel(I18n.t("fuzzer.attack_script"))
    I18nBinder.bindText(attackScriptLabel, "fuzzer.attack_script")
    rightPanel.add(attackScriptLabel, gbc)

    gbc.gridx = 4
    gbc.insets = Insets(0, 4, 0, 0)
    gbc.weightx = 1.0
    gbc.fill = GridBagConstraints.HORIZONTAL
    val controlHeight = loadDirectoryButton.preferredSize.height
    codeCombo.apply {
        preferredSize = Dimension(preferredSize.width.coerceAtLeast(210), controlHeight)
        minimumSize = Dimension(160, controlHeight)
        maximumSize = Dimension(Int.MAX_VALUE, controlHeight)
    }
    rightPanel.add(codeCombo, gbc)

    gbc.gridx = 5
    gbc.weightx = 0.0
    gbc.fill = GridBagConstraints.NONE
    rightPanel.add(loadDirectoryButton, gbc)

    gbc.gridx = 6
    rightPanel.add(saveButton, gbc)

    gbc.gridx = 7
    rightPanel.add(closeIntruderButton, gbc)

    topPanel.add(rightPanel, BorderLayout.CENTER)
    panel.add(topPanel, BorderLayout.NORTH)
    panel.add(scrollableTextEditor, BorderLayout.CENTER)

    val requestTabBar = RequestTabBar().apply { applyChrome() }

    val addTabPanel = JPanel()
    addTabPanel.preferredSize = Dimension(0, 0)
    addTabPanel.minimumSize = Dimension(0, 0)
    addTabPanel.maximumSize = Dimension(0, 0)
    fun applyAddTabHeader() {
        val plus = JLabel("+").apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            font = font.deriveFont(java.awt.Font.BOLD, 18f)
            foreground = UiThemePalette.tabNormalText
            border = BorderFactory.createEmptyBorder(4, 13, 4, 13)
            preferredSize = Dimension(42, 32)
            minimumSize = preferredSize
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
        val index = requestTabBar.indexOfComponent(addTabPanel)
        if (index != -1) {
            requestTabBar.setTabComponentAt(index, plus)
            requestTabBar.revalidate()
            requestTabBar.repaint()
        }
    }
    requestTabBar.addTab("+", addTabPanel)
    applyAddTabHeader()

    val cardLayout = CardLayout()
    val cardPanel = JPanel(cardLayout)
    val emptyCardId = "empty-card"
    val emptyCard = JPanel(BorderLayout())
    val emptyCardLabel = JLabel(I18n.t("fuzzer.no_request_tabs"))
    I18nBinder.bindText(emptyCardLabel, "fuzzer.no_request_tabs")
    emptyCard.add(emptyCardLabel, BorderLayout.CENTER)
    cardPanel.add(emptyCard, emptyCardId)

    val intruderPlaceholder = JPanel()
    intruderPlaceholder.minimumSize = Dimension(0, 0)
    intruderPlaceholder.preferredSize = Dimension(0, 0)

    val initialRequestText = if (fixedScript != null) {
        Utils.bytesToString(requestOverride ?: seed.request)
    } else {
        Utils.bytesToString(seed.request)
    }

    val placeholderRegex = Regex("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*\\}\\}")

    val ctx = IntruderUiContext(
        frame = frame,
        initialService = initialService,
        initialRequestText = initialRequestText,
        projectDataStore = projectDataStore,
        pane = pane,
        intruderPanel = panel,
        defaultPaneDividerSize = defaultPaneDividerSize,
        intruderPlaceholder = intruderPlaceholder,
        requestTabBar = requestTabBar,
        cardLayout = cardLayout,
        cardPanel = cardPanel,
        emptyCardId = emptyCardId,
        addTabPanel = addTabPanel,
        addPlaceholderButton = addPlaceholderButton,
        placeholderRegex = placeholderRegex,
    )
    requestTabBar.putClientProperty("xproxy.ctx", ctx)
    requestTabBar.putClientProperty("xproxy.applyAddTabHeader", Runnable { applyAddTabHeader() })
    // 暴露 fuzzer tab 上下文给 MCP get_current_request 工具读取当前查看的请求 tab。
    XproxyAppContext.bindIntruderUiContext(ctx)
    // 暴露共享脚本编辑器给 MCP run_attack:agent 的内联攻击脚本载入此编辑器,使人工可见。
    ctx.scriptEditor = textEditor

    addPlaceholderButton.addActionListener { ctx.addPlaceholderAroundSelection() }
    clearPlaceholderButton.addActionListener { ctx.clearPlaceholderInSelectionOrAll() }

    closeIntruderButton.addActionListener {
        val state = ctx.currentTabState()
        if (state != null) {
            state.intruderVisible = false
            ctx.applyIntruderVisibility(state)
            ctx.updateAttackButtonState(state)
        } else {
            ctx.hideIntruderDrawer()
        }
    }

    ctx.selectFirstRequestTab = {
        val first = ctx.tabStates.keys.firstOrNull()
        if (first != null) {
            requestTabBar.selectedComponent = first
        }
    }

    ctx.createRequestTab = { title: String, requestText: String ->
        ctx.createRequestTabWithState(title = title, requestText = requestText)
    }

    requestTabBar.addChangeListener {
        if (ctx.restoreDepth > 0) {
            return@addChangeListener
        }
        val selected = requestTabBar.selectedComponent
        if (selected == addTabPanel) {
            if (!ctx.suppressAddTabAutoCreate && ctx.tabStates.isNotEmpty()) {
                ctx.createAndSelectRequestTab()
                return@addChangeListener
            }
            cardLayout.show(cardPanel, emptyCardId)
            ctx.applyIntruderVisibility(null)
            ctx.updateAttackButtonState(null)
        } else {
            val groupName = ctx.groupHeaderTabGroups[selected]
            val effectiveSelected = if (groupName != null) {
                ctx.visibleRequestTabs().firstOrNull { ctx.tabGroups[it].orEmpty() == groupName }
            } else {
                selected
            }
            val state = ctx.tabStates[effectiveSelected]
            if (state != null) {
                val wsCard = state.wsCardId
                if (wsCard != null) {
                    // WS 模式:展示 WS 重放覆盖视图,隐藏 HTTP 专属的 intruder/attack。
                    cardLayout.show(cardPanel, wsCard)
                    ctx.applyIntruderVisibility(null)
                    ctx.updateAttackButtonState(null)
                } else {
                    cardLayout.show(cardPanel, state.cardId)
                    updateTargetDisplay(state)
                    ctx.applyIntruderVisibility(state)
                    ctx.updateAttackButtonState(state)
                }
            }
        }
        ctx.refreshRequestTabStyles()
        ctx.refreshPlaceholderButtons()
        ctx.scheduleFuzzerTabsPersist()
    }

    requestTabBar.addMouseListener(object : MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            val index = requestTabBar.indexAtLocation(e.x, e.y)
            if (index != -1 && requestTabBar.getComponentAt(index) == addTabPanel) {
                if (ctx.tabStates.isEmpty() || requestTabBar.selectedComponent == addTabPanel) {
                    ctx.createAndSelectRequestTab()
                }
            }
        }
    })
    requestTabBar.addComponentListener(object : java.awt.event.ComponentAdapter() {
        override fun componentResized(e: java.awt.event.ComponentEvent?) {
            ctx.updateRequestTabBarHeight()
        }
    })

    val requestPanel = JPanel(BorderLayout())
    val requestTabBarContainer = JPanel(BorderLayout())
    ctx.requestTabBarContainerRef = requestTabBarContainer
    applyRequestTabBarContainerTheme(requestTabBarContainer)
    requestTabBarContainer.add(requestTabBar, BorderLayout.CENTER)
    requestPanel.add(requestTabBarContainer, BorderLayout.NORTH)
    requestPanel.add(cardPanel, BorderLayout.CENTER)

    textEditor.text = defaultScript
    textEditor.isEditable = true

    codeCombo.addActionListener {
        if (codeCombo.itemCount > 0 && !(codeCombo.selectedItem is JSeparator)) {
            if (codeCombo.selectedIndex == 0) {
                saveButton.isEnabled = false
                textEditor.text = defaultScript
            } else {
                val comboItem = codeCombo.selectedItem
                if (comboItem is DirectoryItem) {
                    saveButton.isEnabled = true
                    textEditor.text = String(Files.readAllBytes(Paths.get(comboItem.fullPath)))
                } else {
                    saveButton.isEnabled = false
                }
            }
        }
    }

    readScriptDirectoriesIntoCombo(codeCombo, projectDataStore)
    pane.topComponent = requestPanel
    ctx.hideIntruderDrawer()

    val button = OrangePrimaryButton(I18n.t("fuzzer.attack"))
    I18nBinder.bindText(button, "fuzzer.attack")
    button.preferredSize = Dimension(button.preferredSize.width.coerceAtLeast(100), 44)
    button.minimumSize = Dimension(80, 44)
    panel.add(button, BorderLayout.SOUTH)

    wireAttackControls(
        frame = frame,
        button = button,
        panel = panel,
        pane = pane,
        requestPanel = requestPanel,
        tabStates = ctx.tabStates,
        currentTabState = ctx::currentTabState,
        updateAttackButtonStateSetter = { fn -> ctx.updateAttackButtonState = fn },
        applyIntruderVisibilitySetter = { fn -> ctx.applyIntruderVisibility = fn },
        showIntruderDrawer = ctx::showIntruderDrawer,
        showResultsPanel = ctx::showResultsPanel,
        hideIntruderDrawer = ctx::hideIntruderDrawer,
        initialService = initialService,
        textEditor = textEditor,
        projectDataStore = projectDataStore,
        onSendToFuzzer = { requestRaw, targetHint ->
            ctx.createAndSelectRequestTabWithRequest(requestRaw, true, targetHint ?: ctx.currentTabState()?.target)
        },
        onSendToCodec = { text, tabTitle -> CodecHub.send(text, tabTitle) }
    )

    val restoredTabs = bootstrapData?.fuzzerTabs ?: projectDataStore?.loadFuzzerTabs().orEmpty()
    if (restoredTabs.isNotEmpty()) {
        ctx.beginRestorePhase()
        try {
            var selectedTab: Component? = null
            for (record in restoredTabs) {
                val history = (bootstrapData?.fuzzerTabHistories?.get(record.tabId)
                    ?: projectDataStore?.loadFuzzerTabHistory(record.tabId).orEmpty()).map {
                    FuzzerSendHistoryEntry(
                        requestRaw = it.requestRaw,
                        responseText = it.responseText,
                        fullUrl = it.fullUrl,
                        statusText = it.statusText,
                        responseBytes = it.responseBytes,
                        elapsedMillis = it.elapsedMillis
                    )
                }
                val target = HttpService(record.targetHost, record.targetPort, record.targetProtocol)
                val tab = ctx.createRequestTabWithState(
                    title = record.title,
                    requestText = record.requestRaw,
                    tabId = record.tabId,
                    responseText = record.responseText,
                    target = target,
                    initialHistory = history,
                    groupName = record.groupName,
                    groupColorHex = record.groupColor
                )
                // 持久化的 WS 模式 tab:重建 WS 重放覆盖视图(握手取自 requestRaw,帧类型/载荷取自持久化字段)。
                if (record.isWsMode) {
                    val state = ctx.tabStates[tab]
                    if (state != null) {
                        ctx.activateWsMode(state, ctx.buildWsTargetFromState(state), record.wsOpcode, record.wsPayload)
                        // 回填之前交换的帧(出站+入站),与持久化前的入站帧表一致。
                        val frames = projectDataStore?.loadFuzzerTabWsFrames(record.tabId).orEmpty()
                        state.wsPanel?.restoreFrames(frames)
                    }
                }
                if (record.selected) {
                    selectedTab = tab
                }
            }
            ctx.rebuildRequestTabBar(selectedTab ?: ctx.tabStates.keys.firstOrNull())
            ctx.tabCounter = maxOf(ctx.tabCounter, nextTabCounterFromRecords(restoredTabs))
        } finally {
            ctx.endRestorePhase()
        }
    } else {
        ctx.createAndSelectRequestTab(false, fillSeedContent = true)
        requestTabBar.selectedComponent?.let { firstTab ->
            ctx.tabHeaderStates[firstTab]?.label?.text = "example request"
        }
        ctx.persistAllFuzzerTabs()
    }
    ctx.currentTabState()?.let {
        updateTargetDisplay(it)
        cardLayout.show(cardPanel, it.cardId)
        ctx.applyIntruderVisibility(it)
        ctx.updateAttackButtonState(it)
    }
    ctx.refreshRequestTabStyles()
    ctx.refreshPlaceholderButtons()

    ctx.notifyFuzzerNewTab = renderMainDockLayout(
        frame = frame,
        pane = pane,
        button = button,
        hideIntruderDrawer = ctx::hideIntruderDrawer,
        projectDataStore = projectDataStore,
        bootstrapData = bootstrapData,
        selectedProject = selectedProject,
        sendRequestToFuzzer = { requestRaw, targetHint ->
            ctx.createAndSelectRequestTabWithRequest(requestRaw, true, targetHint)
        },
        onSendToWsRepeater = { target, opcode, payload, liveConnection ->
            // 其他模块(代理 WS 历史)发来 WS 信息:在 fuzzer 中新开一个 tab,以握手请求作为 HTTP 请求文本,
            // 并立即激活 WS 重放 UI 布局(覆盖 HTTP 视图)。复用代理侧原连接(若存活)。
            val httpService = HttpService(
                target.host,
                target.port,
                if (target.tls) "https" else "http"
            )
            SwingUtilities.invokeLater {
                val tab = ctx.createAndSelectRequestTabWithRequest(target.handshakeRequest, false, httpService)
                val state = ctx.tabStates[tab]
                if (state != null) {
                    ctx.activateWsMode(state, target, opcode, payload, liveConnection)
                }
            }
        },
        shutdownFuzzer = {
            // 窗口关闭前同步落库(WS 模式 + 已交换帧 + HTTP tab),避免去抖定时器未触发导致重启后丢失。
            ctx.persistAllFuzzerTabs(await = true)
            ctx.tabStates.values.forEach { it.wsPanel?.shutdown() }
        }
    )
}
