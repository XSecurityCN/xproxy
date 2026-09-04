package org.jjgroup.xproxy.codec.ui

import org.jjgroup.xproxy.codec.core.CodecHub
import org.jjgroup.xproxy.codec.core.CodecOperationCatalog
import org.jjgroup.xproxy.codec.core.CodecOperationDescriptor
import org.jjgroup.xproxy.codec.core.CodecRecipeEngine
import org.jjgroup.xproxy.codec.core.CodecSettings
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicTabbedPaneUI
import javax.swing.tree.DefaultMutableTreeNode

class CodecPanel : JPanel(BorderLayout()) {

    internal val tabBar = object : JTabbedPane() {
        override fun updateUI() {
            super.updateUI()
            // 主题切换时 FlatLaf.updateUI() 会用默认的 TabbedPaneUI 委托覆盖自定义的
            // BasicTabbedPaneUI（决定 tab 间距与绘制），并使构造期捕获的主题色失效。
            // 这里重新装回自定义委托并刷新容器/表头颜色。tabBarContainerRef 在 init 完成
            // 容器装配前为 null，据此跳过构造期 JTabbedPane 自动触发的 updateUI。
            if (tabBarContainerRef == null) return
            configureTabBarUi()
            tabBarContainerRef?.let { applyTabBarContainerTheme(it) }
            refreshTabStyles()
        }
    }
    internal val addTabPanel = JPanel()
    internal val cardLayout = CardLayout()
    internal val cardPanel = JPanel(cardLayout)
    internal val emptyCardId = "codec-empty"
    internal val tabByComponent = LinkedHashMap<Component, CodecTabUi>()
    internal val tabById = LinkedHashMap<String, CodecTabUi>()
    internal val tabHeaderStates = LinkedHashMap<Component, CodecTabHeaderUi>()
    internal var tabBarContainerRef: JPanel? = null
    internal var defaultTabId = ""
    internal var cardCounter = 1
    internal var suspendPersist = false
    internal var creatingTab = false
    internal var suppressAddTabAutoCreate = false
    internal val hubRegistrationId: String = UUID.randomUUID().toString()
    internal val persistTimer: Timer = Timer(350) { persistStateNow() }

    internal val tabAccentColor get() = UiThemePalette.accent
    internal val tabSelectedBg get() = UiThemePalette.tabSelectedBg
    internal val tabHoverBg get() = UiThemePalette.tabHoverBg
    internal val tabIdleBg get() = UiThemePalette.tabIdleBg
    internal val tabPillBorder get() = UiThemePalette.tabPillBorder
    internal val tabSelectedText: Color get() = UiThemePalette.tabSelectedText
    internal val tabNormalText get() = UiThemePalette.tabNormalText
    internal val tabCloseHoverFg get() = UiThemePalette.tabCloseHoverFg
    private val recipeRemoveHotzonePx = 26
    private val operationCatalog: List<CodecOperationDescriptor> = CodecOperationCatalog
        .load()
        .filter { CodecRecipeEngine.isSupportedOperation(it.name) }

    init {
        CodecSettings.registerSettings()
        persistTimer.isRepeats = false

        val emptyCard = JPanel(BorderLayout()).apply {
            val emptyLabel = JLabel(I18n.t("codec.empty"))
            I18nBinder.bindText(emptyLabel, "codec.empty")
            add(emptyLabel, BorderLayout.CENTER)
        }
        cardPanel.add(emptyCard, emptyCardId)

        configureTabBarUi()
        addTabPanel.apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
        }
        tabBar.addTab("+", addTabPanel)

        tabBar.addChangeListener {
            if (suspendPersist) return@addChangeListener
            if (tabBar.selectedComponent == addTabPanel && !creatingTab) {
                if (suppressAddTabAutoCreate) {
                    cardLayout.show(cardPanel, emptyCardId)
                    refreshTabStyles()
                    return@addChangeListener
                }
                createAndSelectTab()
                return@addChangeListener
            }
            val selected = tabByComponent[tabBar.selectedComponent]
            if (selected != null) {
                cardLayout.show(cardPanel, selected.cardId)
            } else {
                cardLayout.show(cardPanel, emptyCardId)
            }
            refreshTabStyles()
            persistState()
        }

        tabBar.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                val index = tabBar.indexAtLocation(e.x, e.y)
                if (index != -1 && tabBar.getComponentAt(index) == addTabPanel) {
                    if (tabByComponent.isEmpty() || tabBar.selectedComponent == addTabPanel) {
                        createAndSelectTab()
                    }
                }
            }
        })

        val tabContainer = JPanel(BorderLayout()).apply {
            add(tabBar, BorderLayout.CENTER)
        }
        applyTabBarContainerTheme(tabContainer)
        tabBarContainerRef = tabContainer

        tabBar.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                updateTabBarHeight()
            }
        })

        add(tabContainer, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)

        restoreState(CodecSettings.loadState())
        registerHub()
    }

    fun shutdown() {
        persistStateNow()
        CodecHub.unregister(hubRegistrationId)
    }

    fun tabTitles(): List<String> =
        tabByComponent.values.map { it.title }

    fun acceptText(text: String, targetTabTitle: String? = null) {
        val payload = text.trimEnd('\n', '\r')
        if (payload.isBlank()) return
        val target = resolveTargetTab(targetTabTitle)
        target.inputArea.text = payload
        tabBar.selectedComponent = target.tabComponent
        recompute(target)
        persistState()
    }

    fun processText(text: String, targetTabTitle: String? = null): String {
        val payload = text.trimEnd('\n', '\r')
        if (payload.isBlank()) return ""
        val target = resolveTargetTab(targetTabTitle)
        target.inputArea.text = payload
        tabBar.selectedComponent = target.tabComponent
        val output = recompute(target)
        persistState()
        return output
    }

    private fun registerHub() {
        CodecHub.register(
            ownerId = hubRegistrationId,
            tabTitlesProvider = { tabTitles() },
            sendHandler = { text, tabTitle -> acceptText(text, tabTitle) },
            processHandler = { text, tabTitle -> processText(text, tabTitle) }
        )
    }

    private fun resolveTargetTab(targetTabTitle: String?): CodecTabUi = when {
        !targetTabTitle.isNullOrBlank() -> tabByComponent.values.firstOrNull { it.title == targetTabTitle }
        else -> tabById[defaultTabId]
    } ?: tabByComponent.values.firstOrNull() ?: createAndSelectTab()

    private fun applyTabBarContainerTheme(container: JPanel) {
        // 标签条容器透明，直接透出父面板背景，避免 dark 模式下出现比面板更深的突兀色块。
        container.isOpaque = false
        container.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UiThemePalette.tabBarBorder),
            BorderFactory.createEmptyBorder(4, 0, 2, 0)
        )
    }

    private fun configureTabBarUi() {
        tabBar.apply {
            tabPlacement = JTabbedPane.TOP
            tabLayoutPolicy = JTabbedPane.WRAP_TAB_LAYOUT
            font = font.deriveFont(13f)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(3, 3, 3, 3)
            ui = object : BasicTabbedPaneUI() {
                override fun createLayoutManager(): java.awt.LayoutManager {
                    return object : TabbedPaneLayout() {
                        override fun normalizeTabRuns(tabPlacement: Int, tabCount: Int, start: Int, max: Int) {
                        }

                        override fun rotateTabRuns(tabPlacement: Int, selectedRun: Int) {
                            if (tabPlacement != JTabbedPane.TOP || runCount <= 1) return
                            val last = tabRuns[runCount - 1]
                            for (index in runCount - 1 downTo 1) {
                                tabRuns[index] = tabRuns[index - 1]
                            }
                            tabRuns[0] = last
                        }
                    }
                }

                override fun paintContentBorder(g: java.awt.Graphics?, tabPlacement: Int, selectedIndex: Int) {
                }

                override fun paintTabBackground(
                    g: java.awt.Graphics?,
                    tabPlacement: Int,
                    tabIndex: Int,
                    x: Int,
                    y: Int,
                    w: Int,
                    h: Int,
                    isSelected: Boolean
                ) {
                }

                override fun paintTabBorder(
                    g: java.awt.Graphics?,
                    tabPlacement: Int,
                    tabIndex: Int,
                    x: Int,
                    y: Int,
                    w: Int,
                    h: Int,
                    isSelected: Boolean
                ) {
                }

                override fun paintFocusIndicator(
                    g: java.awt.Graphics?,
                    tabPlacement: Int,
                    rects: Array<out java.awt.Rectangle>?,
                    tabIndex: Int,
                    iconRect: java.awt.Rectangle?,
                    textRect: java.awt.Rectangle?,
                    isSelected: Boolean
                ) {
                }

                override fun calculateTabWidth(tabPlacement: Int, tabIndex: Int, metrics: java.awt.FontMetrics): Int {
                    val custom = tabPane.getTabComponentAt(tabIndex)
                    return if (custom != null) custom.preferredSize.width + 2
                    else super.calculateTabWidth(tabPlacement, tabIndex, metrics)
                }

                override fun calculateTabHeight(tabPlacement: Int, tabIndex: Int, fontHeight: Int): Int {
                    val custom = tabPane.getTabComponentAt(tabIndex)
                    return if (custom != null) custom.preferredSize.height + 2
                    else super.calculateTabHeight(tabPlacement, tabIndex, fontHeight)
                }

                override fun shouldPadTabRun(tabPlacement: Int, run: Int): Boolean = false

                override fun getTabRunOverlay(tabPlacement: Int): Int =
                    if (tabPlacement == JTabbedPane.TOP) -2 else 0

                override fun shouldRotateTabRuns(tabPlacement: Int): Boolean =
                    tabPlacement == JTabbedPane.TOP
            }
        }
    }

    internal fun createAndSelectTab(
        title: String = nextTabTitle("codec"),
        tabId: String = UUID.randomUUID().toString(),
        input: String = "",
        rules: List<String> = emptyList(),
        select: Boolean = true,
        makeDefault: Boolean = tabByComponent.isEmpty()
    ): CodecTabUi {
        val cardId = "codec-card-${cardCounter++}"

        val content = CodecTabContentFactory.buildContentPanel(
            operationCatalog = operationCatalog,
            input = input,
            rules = rules,
            operationTreeTransferHandler = OperationTreeTransferHandler(this),
            recipeListTransferHandler = RuleListTransferHandler(this, allowImport = true, moveWithinSameList = true)
        )

        val tabComponent = JPanel()
        val ui = CodecTabUi(
            recordId = tabId,
            tabComponent = tabComponent,
            cardId = cardId,
            root = content.root,
            inputArea = content.inputArea,
            recipeList = content.recipeList,
            recipeModel = content.recipeModel,
            outputArea = content.outputArea,
            statusLabel = content.statusLabel,
            operationTree = content.operationTree,
            title = uniqueTitle(title)
        )

        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onChanged()
            override fun removeUpdate(e: DocumentEvent?) = onChanged()
            override fun changedUpdate(e: DocumentEvent?) = onChanged()

            private fun onChanged() {
                recompute(ui)
                persistState()
            }
        }
        ui.inputArea.document.addDocumentListener(listener)
        ui.operationTree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount < 2) return
                val path = ui.operationTree.selectionPath ?: return
                val selectedNode = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                if (!selectedNode.isLeaf) return
                val operationName = selectedNode.userObject?.toString()?.trim().orEmpty()
                if (operationName.isBlank()) return
                ui.recipeModel.addElement(
                    CodecTabContentFactory.buildRuleToken(
                        operationName,
                        CodecTabContentFactory.defaultConfigForOperation(operationName)
                    )
                )
                recompute(ui)
                persistState()
            }
        })
        val recipePopup = JPopupMenu()
        val configureItem = JMenuItem(I18n.t("codec.configure_node"))
        val removeItem = JMenuItem(I18n.t("codec.remove_node"))
        val removeAllItem = JMenuItem(I18n.t("codec.remove_all_nodes"))
        configureItem.addActionListener {
            val selectedIndex = ui.recipeList.selectedIndex
            if (selectedIndex < 0) return@addActionListener
            val currentToken = ui.recipeModel.get(selectedIndex)
            val operationName = CodecTabContentFactory.tokenName(currentToken)
            val existing = CodecTabContentFactory.tokenConfig(currentToken)
            val configArea = JTextArea(10, 40)
            val configMap = if (existing.isEmpty()) CodecTabContentFactory.defaultConfigForOperation(operationName) else existing
            configArea.text = configMap.entries.joinToString("\n") { "${it.key}=${it.value}" }
            val result = JOptionPane.showConfirmDialog(
                this@CodecPanel,
                JScrollPane(configArea),
                I18n.t("codec.configure_title", "operation" to operationName, "hint" to CodecTabContentFactory.configHintForOperation(operationName)),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
            )
            if (result == JOptionPane.OK_OPTION) {
                val parsed = CodecTabContentFactory.parseConfigText(configArea.text)
                ui.recipeModel.set(selectedIndex, CodecTabContentFactory.buildRuleToken(operationName, parsed))
                recompute(ui)
                persistState()
            }
        }
        removeItem.addActionListener {
            val selectedIndex = ui.recipeList.selectedIndex
            if (selectedIndex < 0) return@addActionListener
            ui.recipeModel.remove(selectedIndex)
            recompute(ui)
            persistState()
        }
        removeAllItem.addActionListener {
            if (ui.recipeModel.isEmpty) return@addActionListener
            ui.recipeModel.clear()
            recompute(ui)
            persistState()
        }
        recipePopup.add(configureItem)
        recipePopup.add(removeItem)
        recipePopup.add(removeAllItem)
        ui.recipeList.componentPopupMenu = recipePopup
        ui.recipeList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                val index = ui.recipeList.locationToIndex(e.point)
                if (index < 0 || index >= ui.recipeModel.size) return
                val bounds = ui.recipeList.getCellBounds(index, index) ?: return
                val clickedRemove = e.x >= (bounds.x + bounds.width - recipeRemoveHotzonePx)
                if (!clickedRemove) return
                ui.recipeModel.remove(index)
                recompute(ui)
                persistState()
            }
        })
        ui.recipeList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                recompute(ui)
            }
        }

        cardPanel.add(ui.root, ui.cardId)
        tabByComponent[ui.tabComponent] = ui
        tabById[ui.recordId] = ui
        if (makeDefault || defaultTabId.isBlank()) {
            defaultTabId = ui.recordId
        }

        creatingTab = true
        try {
            val plusIndex = tabBar.indexOfComponent(addTabPanel)
            val insertIndex = if (plusIndex >= 0) plusIndex else tabBar.tabCount
            tabBar.insertTab(ui.title, null, ui.tabComponent, null, insertIndex)
            tabBar.setTabComponentAt(insertIndex, buildTabHeader(ui.tabComponent))
        } finally {
            creatingTab = false
        }
        recompute(ui)
        refreshTabStyles()
        refreshTabHeaderTitles()
        if (select) {
            tabBar.selectedComponent = ui.tabComponent
            cardLayout.show(cardPanel, ui.cardId)
        }
        return ui
    }

    internal fun closeTab(component: Component, persist: Boolean = true) {
        suppressAddTabAutoCreate = true
        try {
            val tab = tabByComponent.remove(component) ?: return
            tabById.remove(tab.recordId)
            tabHeaderStates.remove(component)

            val index = tabBar.indexOfComponent(component)
            val wasSelected = tabBar.selectedComponent == component
            if (index >= 0) {
                tabBar.removeTabAt(index)
            }
            cardPanel.remove(tab.root)

            if (tabByComponent.isEmpty()) {
                tabBar.selectedComponent = addTabPanel
                cardLayout.show(cardPanel, emptyCardId)
            } else if (wasSelected) {
                val preferredIndex = (index - 1).coerceAtLeast(0)
                var targetComponent: Component? = null
                if (preferredIndex < tabBar.tabCount) {
                    val candidate = tabBar.getComponentAt(preferredIndex)
                    if (candidate != addTabPanel) {
                        targetComponent = candidate
                    }
                }
                if (targetComponent == null) {
                    for (tabIndex in tabBar.tabCount - 1 downTo 0) {
                        val candidate = tabBar.getComponentAt(tabIndex)
                        if (candidate != addTabPanel) {
                            targetComponent = candidate
                            break
                        }
                    }
                }
                if (targetComponent != null) {
                    tabBar.selectedComponent = targetComponent
                    tabByComponent[targetComponent]?.let { cardLayout.show(cardPanel, it.cardId) }
                }
            } else if (tabBar.selectedComponent == addTabPanel) {
                tabByComponent.keys.firstOrNull()?.let { first ->
                    tabBar.selectedComponent = first
                    tabByComponent[first]?.let { cardLayout.show(cardPanel, it.cardId) }
                }
            }

            if (defaultTabId == tab.recordId) {
                defaultTabId = tabByComponent.values.firstOrNull()?.recordId.orEmpty()
            }

            refreshTabStyles()
            refreshTabHeaderTitles()
            if (persist) {
                persistState()
            }
        } finally {
            suppressAddTabAutoCreate = false
        }
    }

    internal fun recompute(tab: CodecTabUi): String {
        val rules = CodecTabContentFactory.modelRules(tab.recipeModel)
        val result = CodecRecipeEngine.apply(tab.inputArea.text, rules)
        tab.outputArea.text = result.output
        tab.outputArea.caretPosition = 0
        tab.statusLabel.text = result.error ?: I18n.t("codec.applied_operations", "count" to rules.size)
        return result.output
    }
}
