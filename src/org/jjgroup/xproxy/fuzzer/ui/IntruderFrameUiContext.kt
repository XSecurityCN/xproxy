package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.model.RequestTabState
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.settings.core.UiThemePalette

import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import javax.swing.*

internal data class TabHeaderUi(
    val root: JPanel,
    val titleContainer: JPanel,
    val label: JLabel,
    val groupHeader: JPanel,
    val groupIcon: JComponent,
    val groupNameLabel: JLabel,
    val groupCountLabel: JLabel,
    val groupToggleLabel: JLabel,
    val close: JButton,
    val normalFont: java.awt.Font,
    val selectedFont: java.awt.Font,
    var hovered: Boolean = false,
    var closeHovered: Boolean = false,
    var groupName: String = ""
)

internal class IntruderUiContext(
    val frame: IntruderFrame,
    val initialService: HttpService,
    val initialRequestText: String,
    val projectDataStore: ProjectDataStore?,
    val pane: JSplitPane,
    val intruderPanel: JPanel,
    val defaultPaneDividerSize: Int,
    val intruderPlaceholder: JPanel,
    val requestTabBar: JTabbedPane,
    val cardLayout: CardLayout,
    val cardPanel: JPanel,
    val emptyCardId: String,
    val addTabPanel: JPanel,
    val addPlaceholderButton: JButton,
    val placeholderRegex: Regex,
) {
    val tabStates = mutableMapOf<Component, RequestTabState>()
    val tabHeaderStates = mutableMapOf<Component, TabHeaderUi>()
    val tabPersistentIds = mutableMapOf<Component, String>()
    val tabSendHistories = mutableMapOf<Component, MutableList<FuzzerSendHistoryEntry>>()
    // 每个 tab 的"外部注入发送历史"回调(由 createRequestTabComponent 注册),供 MCP agent 的
    // send_request 把交换记录进该 tab 的 back/forward 历史。键即 tab 组件,tab 关闭时移除。
    val tabRecordExchange = mutableMapOf<Component, (FuzzerSendHistoryEntry, Boolean) -> Unit>()
    val tabGroups = mutableMapOf<Component, String>()
    val tabGroupColors = mutableMapOf<String, Color>()
    val groupHeaderTabGroups = mutableMapOf<Component, String>()
    val tabOrder = mutableListOf<Component>()
    val collapsedTabGroups = mutableSetOf<String>()

    var tabCounter = 1
    var cardCounter = 1
    var restoreDepth = 0
    var pendingPersistAfterRestore = false
    var suppressAddTabAutoCreate = false
    var creatingTab = false
    var dragTabComponent: Component? = null
    var dragGroupName: String? = null
    var dragReordered = false
    var dragPendingTargetIndex: Int = -1
    var dragInsertBefore: Boolean = true
    var dragIndicatorX: Int = -1
    var dragGhostWindow: JWindow? = null
    var lastDragGhostUpdateMillis: Long = 0L
    // 记录 mousePressed 时的落点(相对按下组件),用于在 mouseDragged 里做拖动位移阈值判定,
    // 避免单击抖动一闪而过地触发拖动视觉(橙框/拖影)。
    var dragPressPoint: java.awt.Point? = null
    var pendingTabPersist = false
    var persistTimer: Timer? = null
    @Volatile
    var tabPersistGeneration: Long = 0L
    var requestTabBarContainerRef: JPanel? = null
    var notifyFuzzerNewTab: (() -> Unit)? = null
    // MCP agent 的 send_request 复用的专用 tab,按站点 host 分组键索引(每个 host 一个 probe tab,
    // 归入对应 host 分组)。agent 连发同站请求累积进同一 tab 的 back/forward 历史;切站则另建。
    // 为空表示尚未创建或已被人工关闭,下次 send_request 会新建。仅 EDT 访问。
    val mcpSendTabs = mutableMapOf<String, Component>()
    // 共享的攻击脚本编辑器(顶部 intruderPanel 内),由 buildIntruderUI 装配后写入。
    // MCP agent 的 run_attack 把其内联脚本载入此编辑器,使人工能看到 agent 实际运行的脚本。
    @Volatile
    var scriptEditor: org.fife.ui.rsyntaxtextarea.RSyntaxTextArea? = null

    val tabAccentColor get() = UiThemePalette.accent
    val tabSelectedBg get() = UiThemePalette.tabSelectedBg
    val tabHoverBg get() = UiThemePalette.tabHoverBg
    val tabIdleBg get() = UiThemePalette.tabIdleBg
    val tabPillBorder get() = UiThemePalette.tabPillBorder
    val tabSelectedText: Color get() = UiThemePalette.tabSelectedText
    val tabNormalText get() = UiThemePalette.tabNormalText
    val tabCloseHoverFg get() = UiThemePalette.tabCloseHoverFg
    val tabGroupPalette = listOf(
        Color(64, 132, 244),
        Color(46, 160, 67),
        Color(156, 89, 209),
        Color(214, 129, 43),
        Color(219, 68, 55),
        Color(0, 150, 136),
        Color(121, 85, 72),
        Color(96, 125, 139)
    )
    val tabMinWidth = 48

    lateinit var createRequestTab: (String, String) -> Component
    lateinit var selectFirstRequestTab: () -> Unit
    lateinit var updateAttackButtonState: (RequestTabState?) -> Unit
    lateinit var applyIntruderVisibility: (RequestTabState?) -> Unit
}
