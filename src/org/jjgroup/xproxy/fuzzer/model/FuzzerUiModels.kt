package org.jjgroup.xproxy.fuzzer.model

import org.jjgroup.xproxy.AttackHandler
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.proxy.ui.WsRepeaterSessionPanel
import org.jjgroup.xproxy.ui.http.HttpRequestResponseViewer
import org.jjgroup.xproxy.ui.table.RequestTable

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JFrame
import javax.swing.JPanel

enum class BodyKind {
    NONE,
    JSON,
    HTML,
    FORM,
    OTHER
}

enum class AttackState {
    ATTACK,
    HALT,
    CONFIGURE
}

data class ParsedMessage(val headers: String, val body: String, val separator: String)

data class RequestTabState(
    val tabComponent: Component,
    val cardId: String,
    val cardComponent: Component,
    val requestEditor: RSyntaxTextArea,
    val requestPretty: RSyntaxTextArea,
    val responseRaw: RSyntaxTextArea,
    val responsePretty: RSyntaxTextArea,
    val responseRender: RSyntaxTextArea,
    val responseViewer: HttpRequestResponseViewer,
    val targetLabel: JLabel,
    var intruderVisible: Boolean = false,
    var attackState: AttackState = AttackState.ATTACK,
    var handler: AttackHandler = AttackHandler(),
    var requestTable: RequestTable? = null,
    var resultsPanel: JPanel? = null,
    var resultsWindow: JFrame? = null,
    var responseText: String = "",
    var target: HttpService
) {
    // WS 重放覆盖:当 HTTP tab 收到 101 响应、或其它模块发来 WS 信息时,以 [wsPanel] 覆盖该 tab 的 HTTP 视图。
    // wsCardId / wsCardComponent 为该覆盖视图在 cardPanel 中的卡片;为 null 表示该 tab 仍为 HTTP 模式。
    var wsPanel: WsRepeaterSessionPanel? = null
    var wsCardId: String? = null
    var wsCardComponent: JComponent? = null
    val isWsMode: Boolean get() = wsPanel != null
}

class RecordResize : ComponentAdapter() {
    override fun componentResized(e: ComponentEvent?) {
        super.componentResized(e)
        Utils.setIntruderFrameSize(e?.component?.size)
    }
}
