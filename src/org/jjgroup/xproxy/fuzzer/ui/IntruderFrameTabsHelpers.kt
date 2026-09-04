package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.fuzzer.model.RequestTabState
import org.jjgroup.xproxy.i18n.I18n

import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

internal fun syncRequestEditorPreservingView(
    state: RequestTabState,
    newRequestRaw: String,
    setRequestSync: (Boolean) -> Unit,
    updateRequestFromRaw: (RequestTabState, String) -> Unit,
    onTabSnapshotChanged: (String, HttpService) -> Unit
): Boolean {
    val normalized = normalizeEditorLineEndings(newRequestRaw)
    val currentNormalized = normalizeEditorLineEndings(state.requestEditor.text)
    if (currentNormalized == normalized) {
        return false
    }

    val rawCaret = state.requestEditor.caretPosition
    val prettyCaret = state.requestPretty.caretPosition
    setRequestSync(true)
    try {
        state.requestEditor.text = normalized
        updateRequestFromRaw(state, normalized)
        state.requestEditor.caretPosition = rawCaret.coerceIn(0, state.requestEditor.document.length)
        state.requestPretty.caretPosition = prettyCaret.coerceIn(0, state.requestPretty.document.length)
        onTabSnapshotChanged(state.requestEditor.text, state.target)
    } finally {
        setRequestSync(false)
    }
    return true
}

internal fun showEditTargetDialog(
    owner: IntruderFrame?,
    state: RequestTabState,
    updateTargetDisplay: (RequestTabState) -> Unit,
    onTabSnapshotChanged: (String, HttpService) -> Unit
) {
    val hostInput = JTextField(state.target.host, 18)
    // target 未设置(新建空白 tab)时,端口/协议给友好默认 80/http,避免端口显示 0
    val portInput = JTextField(if (state.target.port > 0) state.target.port.toString() else "80", 6)
    val protocolInput = JComboBox(arrayOf("http", "https"))
    protocolInput.selectedItem = state.target.protocol.ifBlank { "http" }
    protocolInput.addActionListener {
        val portValue = try {
            portInput.text.trim().toInt()
        } catch (ex: Exception) {
            null
        }
        if (portValue == 80 || portValue == 443) {
            val selected = protocolInput.selectedItem?.toString()?.lowercase()
            if (selected == "http") {
                portInput.text = "80"
            } else if (selected == "https") {
                portInput.text = "443"
            }
        }
    }
    val form = JPanel(GridBagLayout())
    val fgbc = GridBagConstraints().apply {
        insets = Insets(4, 4, 4, 4)
        gridx = 0
        gridy = 0
        anchor = GridBagConstraints.WEST
    }
    form.add(JLabel(I18n.t("common.host")), fgbc)
    fgbc.gridx = 1
    form.add(hostInput, fgbc)
    fgbc.gridx = 0
    fgbc.gridy = 1
    form.add(JLabel(I18n.t("common.port")), fgbc)
    fgbc.gridx = 1
    form.add(portInput, fgbc)
    fgbc.gridx = 0
    fgbc.gridy = 2
    form.add(JLabel(I18n.t("common.protocol")), fgbc)
    fgbc.gridx = 1
    form.add(protocolInput, fgbc)
    val result = JOptionPane.showConfirmDialog(owner, form, I18n.t("fuzzer.edit_target"), JOptionPane.OK_CANCEL_OPTION)
    if (result == JOptionPane.OK_OPTION) {
        val newHost = hostInput.text.trim().ifEmpty { state.target.host }
        val newPort = try {
            portInput.text.trim().toInt()
        } catch (ex: Exception) {
            state.target.port
        }
        val newProtocol = (protocolInput.selectedItem?.toString() ?: state.target.protocol).lowercase()
        state.target = HttpService(newHost, newPort, newProtocol)
        updateTargetDisplay(state)
        onTabSnapshotChanged(state.requestEditor.text, state.target)
    }
}
