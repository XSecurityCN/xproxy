package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.AttackHandler
import org.jjgroup.xproxy.core.AppLogger
import org.jjgroup.xproxy.core.Settings
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.core.evalJython
import org.jjgroup.xproxy.fuzzer.model.AttackState
import org.jjgroup.xproxy.fuzzer.model.RequestTabState
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.project.core.ProjectDataStore
import org.jjgroup.xproxy.ui.table.RequestTable

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Color
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.security.MessageDigest
import javax.swing.*
import kotlin.concurrent.thread

private fun normalizePlaceholderName(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return "placeholder"
    }
    val normalized = trimmed
        .replace(Regex("\\{\\{\\s*"), "")
        .replace(Regex("\\s*\\}\\}"), "")
        .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        .trim('_')
    return if (normalized.isEmpty()) "placeholder" else normalized
}

private fun styleBottomControlButton(button: JButton, mode: String) {
    when (mode) {
        "pause" -> {
            val normal = Color(245, 140, 40)
            button.background = normal
            button.foreground = Color(255, 255, 255)
            button.isOpaque = true
            button.isContentAreaFilled = true
            button.border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
            button.putClientProperty("JButton.background", normal)
            button.putClientProperty("JButton.default.background", normal)
        }

        "continue" -> {
            val normal = Color(45, 125, 255)
            button.background = normal
            button.foreground = Color(255, 255, 255)
            button.isOpaque = true
            button.isContentAreaFilled = true
            button.border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
            button.putClientProperty("JButton.background", normal)
            button.putClientProperty("JButton.default.background", normal)
        }

        else -> {
            button.background = UIManager.getColor("Button.background")
            button.foreground = UIManager.getColor("Button.foreground")
            button.isOpaque = true
            button.isContentAreaFilled = true
            button.border = UIManager.getBorder("Button.border")
            button.putClientProperty("JButton.background", UIManager.getColor("Button.background"))
            button.putClientProperty("JButton.default.background", UIManager.getColor("Button.background"))
        }
    }
}

private fun refreshBottomControlButton(button: JButton, handler: AttackHandler) {
    when {
        handler.hasFinished() -> {
            button.text = I18n.t("common.close")
            styleBottomControlButton(button, "close")
        }

        handler.isPaused() -> {
            button.text = I18n.t("fuzzer.continue")
            styleBottomControlButton(button, "continue")
        }

        else -> {
            button.text = I18n.t("fuzzer.pause")
            styleBottomControlButton(button, "pause")
        }
    }
}

private fun createWindowControlButton(handler: AttackHandler, resultsWindow: JFrame): JButton {
    val controlButton = JButton(I18n.t("fuzzer.pause"))
    controlButton.horizontalAlignment = SwingConstants.CENTER
    controlButton.verticalAlignment = SwingConstants.CENTER
    controlButton.preferredSize = Dimension(100, 44)
    controlButton.maximumSize = Dimension(Int.MAX_VALUE, 44)

    controlButton.addActionListener {
        when {
            handler.hasFinished() -> {
                val decision = JOptionPane.showConfirmDialog(
                    resultsWindow,
                    I18n.t("fuzzer.close_results_confirm"),
                    I18n.t("fuzzer.confirm_close"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                )
                if (decision == JOptionPane.YES_OPTION) {
                    resultsWindow.dispose()
                }
            }
            handler.isPaused() -> handler.resume()
            else -> handler.pause()
        }
        refreshBottomControlButton(controlButton, handler)
    }
    refreshBottomControlButton(controlButton, handler)
    return controlButton
}

/**
 * 弹出某个 tab 的攻击结果窗口(JFrame + RequestTable)。从 [wireAttackControls] 的局部函数抽出,
 * 使 MCP agent 的 run_attack 也能复用同一条"可见结果窗口"路径,而非 headless 收集。
 *
 * 窗口关闭确认后暂停所有 tab 的 handler(沿用既有语义:关一个结果窗即暂停全部进行中的攻击)。
 */
internal fun showResultsWindow(
    state: RequestTabState,
    frame: IntruderFrame,
    tabStates: Map<Component, RequestTabState>
) {
    val requestTable = state.requestTable ?: return
    val windowHandler = state.handler
    val targetHost = state.target.host.ifBlank { I18n.t("fuzzer.unknown_host") }
    val windowTitle = I18n.t("fuzzer.results_title", "host" to targetHost)
    val existing = state.resultsWindow
    if (existing != null && existing.isDisplayable) {
        existing.title = windowTitle
        existing.isVisible = true
        existing.toFront()
        return
    }

    val resultsWindow = JFrame(windowTitle)
    resultsWindow.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
    resultsWindow.contentPane.layout = BorderLayout()
    resultsWindow.contentPane.add(requestTable, BorderLayout.CENTER)
    val bottomControls = JPanel(BorderLayout())
    val controlButton = createWindowControlButton(windowHandler, resultsWindow)
    bottomControls.border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
    bottomControls.add(controlButton, BorderLayout.CENTER)
    resultsWindow.contentPane.add(bottomControls, BorderLayout.SOUTH)
    val frameSize = Utils.getIntruderFrameSize()
    val baseWidth = if (frame.width > 0) frame.width else frameSize.width
    val baseHeight = if (frame.height > 0) frame.height else frameSize.height
    resultsWindow.setSize(
        (baseWidth * 0.66).toInt().coerceAtLeast(900),
        (baseHeight * 0.66).toInt().coerceAtLeast(620)
    )
    resultsWindow.setLocationRelativeTo(frame)
    val statusTimer = Timer(350) {
        if (!resultsWindow.isDisplayable) {
            return@Timer
        }
        resultsWindow.title = I18n.t("fuzzer.results_title", "host" to targetHost)
        refreshBottomControlButton(controlButton, windowHandler)
        if (windowHandler.hasFinished() && state.handler === windowHandler && state.attackState != AttackState.ATTACK) {
            state.attackState = AttackState.ATTACK
        }
    }
    statusTimer.start()
    resultsWindow.addWindowListener(object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent) {
            val decision = JOptionPane.showConfirmDialog(
                resultsWindow,
                I18n.t("fuzzer.close_results_confirm"),
                I18n.t("fuzzer.confirm_close"),
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )
            if (decision == JOptionPane.YES_OPTION) {
                for (tabState in tabStates.values) {
                    tabState.handler.pause()
                }
                resultsWindow.dispose()
            }
        }

        override fun windowClosed(e: WindowEvent) {
            statusTimer.stop()
            if (state.resultsWindow === resultsWindow) {
                state.resultsWindow = null
            }
        }
    })
    state.resultsWindow = resultsWindow
    resultsWindow.isVisible = true
    resultsWindow.toFront()
}

/**
 * 一次攻击启动的句柄:持有 handler / 结果表 / 基础请求哈希,以及阻塞的 evalJython 执行块。
 * [runEval] 在独立线程上执行 evalJython,完成后回调 [onCompleted](Throwable? 为 null 表示正常完成)。
 *
 * 抽出此句柄使手工 Attack 按钮与 MCP run_attack 复用同一启动逻辑:调用方先 [launchAttackOnTab]
 * 装配 UI(结果窗口、RequestTable、handler),再 [runEval] 驱动脚本。
 */
internal class AttackLaunch(
    val handler: AttackHandler,
    val requestTable: RequestTable,
    val baseRequestHash: String,
    private val evalBlock: () -> Unit
) {
    fun runEval(onCompleted: (Throwable?) -> Unit = {}) {
        thread(isDaemon = true, name = "xproxy-attack-eval") {
            val err = runCatching { evalBlock() }.exceptionOrNull()
            if (err != null) {
                AppLogger.error("Attack eval failed", err)
            }
            onCompleted(err)
        }
    }
}

/**
 * 在指定 tab 上装配并启动一次攻击:清旧结果、建 handler/RequestTable/结果面板、置 state 字段、
 * 弹出结果窗口,返回携带 evalJython 执行块的 [AttackLaunch](尚未执行)。
 *
 * @param scriptCode 攻击脚本源码(queue_requests/handle_response 契约)
 * @param baseRequest 含 `{{placeholder}}` 的基础请求模板
 * @param baseInput 被占位符替换的原始选区文本(透传给脚本 target.base_input)
 */
internal fun launchAttackOnTab(
    state: RequestTabState,
    scriptCode: String,
    baseRequest: String,
    baseInput: String,
    projectDataStore: ProjectDataStore?,
    frame: IntruderFrame,
    tabStates: Map<Component, RequestTabState>,
    onSendToFuzzer: ((String, HttpService?) -> Unit)?,
    onSendToCodec: ((String, String?) -> Unit)?
): AttackLaunch {
    val targetService = state.target
    val inputHost = targetService.host
    val inputPort = targetService.port
    val inputProtocol = targetService.protocol.lowercase()

    val baseRequestHash = hashBaseRequest(baseRequest)
    state.resultsWindow = null
    state.requestTable?.shutdown()
    projectDataStore?.clearFuzzerResults(baseRequestHash)
    val handler = AttackHandler()
    val requestTable = RequestTable(
        handler = handler,
        initialRequests = emptyList(),
        onRequestAdded = { req -> projectDataStore?.saveFuzzerResult(baseRequestHash, req) },
        onSendToFuzzer = { requestRaw -> onSendToFuzzer?.invoke(requestRaw, state.target) },
        onSendToCodec = onSendToCodec
    )
    state.handler = handler
    state.requestTable = requestTable
    val resultsPanel = JPanel(BorderLayout())
    resultsPanel.add(requestTable, BorderLayout.CENTER)
    state.resultsPanel = resultsPanel
    state.attackState = AttackState.HALT
    state.intruderVisible = true

    SwingUtilities.invokeLater {
        showResultsWindow(state, frame, tabStates)
    }

    val target = if (inputHost.contains(":")) {
        "$inputProtocol://[$inputHost]:$inputPort"
    } else {
        "$inputProtocol://$inputHost:$inputPort"
    }
    val script = scriptCode.replace("\r\n", "\n").replace("\n", "\r\n")
    val baseReqNorm = baseRequest.replace("\r\n", "\n").replace("\n", "\r\n")
    val rawRequest = Utils.stringToBytes(baseReqNorm)

    return AttackLaunch(
        handler = handler,
        requestTable = requestTable,
        baseRequestHash = baseRequestHash,
        evalBlock = {
            evalJython(script, baseReqNorm, rawRequest, target, inputHost, baseInput, requestTable, handler, null)
        }
    )
}

fun wireAttackControls(
    frame: IntruderFrame,
    button: JButton,
    panel: JPanel,
    pane: JSplitPane,
    requestPanel: JPanel,
    tabStates: MutableMap<Component, RequestTabState>,
    currentTabState: () -> RequestTabState?,
    updateAttackButtonStateSetter: (((RequestTabState?) -> Unit) -> Unit),
    applyIntruderVisibilitySetter: (((RequestTabState?) -> Unit) -> Unit),
    showIntruderDrawer: () -> Unit,
    showResultsPanel: (JPanel) -> Unit,
    hideIntruderDrawer: () -> Unit,
    initialService: HttpService,
    textEditor: RSyntaxTextArea,
    projectDataStore: ProjectDataStore?,
    onSendToFuzzer: ((String, HttpService?) -> Unit)?,
    onSendToCodec: ((String, String?) -> Unit)?
) {
    val frameSize = Utils.getIntruderFrameSize()
    requestPanel.preferredSize = Dimension(frameSize.width, frameSize.height)
    panel.preferredSize = Dimension(frameSize.width, (frameSize.height * 0.6).toInt())

    val updateAttackButtonState: (RequestTabState?) -> Unit = { state ->
        if (state == null) {
            button.text = I18n.t("fuzzer.attack")
            button.isEnabled = false
        } else {
            button.isEnabled = true
            button.text = I18n.t("fuzzer.attack")
        }
    }

    val applyIntruderVisibility: (RequestTabState?) -> Unit = { state ->
        if (state == null) {
            hideIntruderDrawer()
        } else if (state.intruderVisible) {
            showIntruderDrawer()
        } else {
            hideIntruderDrawer()
        }
    }

    updateAttackButtonStateSetter(updateAttackButtonState)
    applyIntruderVisibilitySetter(applyIntruderVisibility)

    val toggleAttack = ActionListener {
        thread(isDaemon = true) {
            val state = currentTabState()
            if (state == null) {
                Utils.out("No request tab selected")
                return@thread
            }
            when (state.attackState) {
                AttackState.HALT,
                AttackState.CONFIGURE -> {
                    SwingUtilities.invokeLater {
                        showResultsWindow(state, frame, tabStates)
                        updateAttackButtonState(state)
                        applyIntruderVisibility(state)
                    }
                }

                AttackState.ATTACK -> {
                    val activeEditor = state.requestEditor
                    val selectionStart = activeEditor.selectionStart
                    val selectionEnd = activeEditor.selectionEnd
                    var baseInput = ""
                    var baseRequest = activeEditor.text
                    if (selectionStart != selectionEnd && selectionStart >= 0 && selectionEnd <= baseRequest.length) {
                        baseInput = baseRequest.substring(selectionStart, selectionEnd)
                        val placeholder = "{{${normalizePlaceholderName(baseInput)}}}"
                        baseRequest = baseRequest.substring(0, selectionStart) + placeholder + baseRequest.substring(selectionEnd)
                    }

                    val scriptCode = textEditor.text
                    Settings.setString("defaultScript", scriptCode)

                    val launch = launchAttackOnTab(
                        state = state,
                        scriptCode = scriptCode,
                        baseRequest = baseRequest,
                        baseInput = baseInput,
                        projectDataStore = projectDataStore,
                        frame = frame,
                        tabStates = tabStates,
                        onSendToFuzzer = onSendToFuzzer,
                        onSendToCodec = onSendToCodec
                    )
                    SwingUtilities.invokeLater {
                        updateAttackButtonState(state)
                        applyIntruderVisibility(state)
                        if (currentTabState() == state) {
                            button.requestFocusInWindow()
                            pane.rootPane.defaultButton = button
                        }
                    }
                    launch.runEval()
                }
            }
        }
    }

    button.addActionListener(toggleAttack)

    button.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
        KeyStroke.getKeyStroke("control ENTER"), "toggleAttack"
    )

    button.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
        KeyStroke.getKeyStroke("control SPACE"), "toggleAttack"
    )

    button.actionMap.put("toggleAttack", object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent) {
            toggleAttack.actionPerformed(e)
        }
    })

    frame.addWindowListener(object : WindowAdapter() {
        override fun windowClosed(e: WindowEvent) {
            for (state in tabStates.values) {
                state.handler.abort()
                state.requestTable?.clear()
                state.requestTable?.shutdown()
                state.resultsWindow?.dispose()
                state.resultsWindow = null
            }
        }
    })
}

internal fun hashBaseRequest(raw: String): String =
    MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.ISO_8859_1)).joinToString("") { b -> "%02x".format(b) }
