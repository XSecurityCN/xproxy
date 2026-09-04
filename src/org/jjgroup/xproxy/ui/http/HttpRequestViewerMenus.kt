package org.jjgroup.xproxy.ui.http

import org.jjgroup.xproxy.codec.core.CodecHub
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.kits.core.XappContextMenuHub
import org.jjgroup.xproxy.kits.core.XappHttpMenuSnapshot
import org.jjgroup.xproxy.kits.core.XappMenuMessagePart
import org.jjgroup.xproxy.kits.core.XappMenuTreeNode
import org.jjgroup.xproxy.kits.core.buildXappMenuTree
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import org.fife.ui.rtextarea.RTextScrollPane
import java.awt.Color
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

internal fun HttpRequestResponseViewer.initSectionMenus() {
    requestSectionPanel.add(requestSectionLabel, java.awt.BorderLayout.WEST)
    responseSectionPanel.add(responseSectionLabel, java.awt.BorderLayout.WEST)

    val requestModifiedItem = JMenuItem("Modified request").apply {
        addActionListener {
            requestViewMode = PayloadViewMode.MODIFIED
            refreshRequestSectionHeader()
            renderRequest(resolveDisplayedRequestRaw())
        }
    }
    val requestOriginalItem = JMenuItem("Original request").apply {
        addActionListener {
            requestViewMode = PayloadViewMode.ORIGINAL
            refreshRequestSectionHeader()
            renderRequest(resolveDisplayedRequestRaw())
        }
    }
    requestSectionMenu.add(requestModifiedItem)
    requestSectionMenu.add(requestOriginalItem)

    requestSectionLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    requestSectionLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            maybeShowRequestMenu(e)
        }

        override fun mouseReleased(e: java.awt.event.MouseEvent) {
            maybeShowRequestMenu(e)
        }

        private fun maybeShowRequestMenu(e: java.awt.event.MouseEvent) {
            if (!hasOriginalRequestVariant()) {
                return
            }
            requestSectionMenu.show(requestSectionLabel, 0, requestSectionLabel.height)
        }
    })

    val responseModifiedItem = JMenuItem("Modified response").apply {
        addActionListener {
            responseViewMode = PayloadViewMode.MODIFIED
            refreshResponseSectionHeader()
            renderResponse(resolveDisplayedResponseRaw())
        }
    }
    val responseOriginalItem = JMenuItem("Original response").apply {
        addActionListener {
            responseViewMode = PayloadViewMode.ORIGINAL
            refreshResponseSectionHeader()
            renderResponse(resolveDisplayedResponseRaw())
        }
    }
    responseSectionMenu.add(responseModifiedItem)
    responseSectionMenu.add(responseOriginalItem)

    responseSectionLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    responseSectionLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            maybeShowResponseMenu(e)
        }

        override fun mouseReleased(e: java.awt.event.MouseEvent) {
            maybeShowResponseMenu(e)
        }

        private fun maybeShowResponseMenu(e: java.awt.event.MouseEvent) {
            if (!hasOriginalResponseVariant()) {
                return
            }
            responseSectionMenu.show(responseSectionLabel, 0, responseSectionLabel.height)
        }
    })
}

internal fun HttpRequestResponseViewer.hasOriginalRequestVariant(): Boolean =
    requestOriginalRaw.isNotBlank() && requestOriginalRaw != requestModifiedRaw

internal fun HttpRequestResponseViewer.hasOriginalResponseVariant(): Boolean =
    responseOriginalRaw.isNotBlank() && responseOriginalRaw != responseModifiedRaw

internal fun HttpRequestResponseViewer.refreshRequestSectionHeader() {
    requestSectionLabel.text = if (hasOriginalRequestVariant()) {
        if (requestViewMode == PayloadViewMode.ORIGINAL) {
            I18n.t("http.original_request")
        } else {
            I18n.t("http.modified_request")
        } + " \u25be"
    } else {
        I18n.t("http.request")
    }
    requestSectionLabel.cursor = if (hasOriginalRequestVariant()) {
        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    } else {
        Cursor.getDefaultCursor()
    }
}

internal fun HttpRequestResponseViewer.refreshResponseSectionHeader() {
    responseSectionLabel.text = if (hasOriginalResponseVariant()) {
        if (responseViewMode == PayloadViewMode.ORIGINAL) {
            I18n.t("http.original_response")
        } else {
            I18n.t("http.modified_response")
        } + " \u25be"
    } else {
        I18n.t("http.response")
    }
    responseSectionLabel.cursor = if (hasOriginalResponseVariant()) {
        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    } else {
        Cursor.getDefaultCursor()
    }
}

internal fun HttpRequestResponseViewer.resolveDisplayedRequestRaw(): String =
    if (requestViewMode == PayloadViewMode.ORIGINAL && hasOriginalRequestVariant()) {
        requestOriginalRaw
    } else {
        requestModifiedRaw
    }

internal fun HttpRequestResponseViewer.resolveDisplayedResponseRaw(): String =
    if (responseViewMode == PayloadViewMode.ORIGINAL && hasOriginalResponseVariant()) {
        responseOriginalRaw
    } else {
        responseModifiedRaw
    }

internal fun HttpRequestResponseViewer.installContextMenu(
    area: RSyntaxTextArea,
    pane: RTextScrollPane,
    copyProvider: () -> String
) {
    val menu = JPopupMenu()
    when (area) {
        requestRawArea -> requestRawContextMenu = menu
        requestPrettyArea -> requestPrettyContextMenu = menu
        responseRawArea -> responseRawContextMenu = menu
        responsePrettyArea -> responsePrettyContextMenu = menu
        responseRenderArea -> responseRenderContextMenu = menu
    }
    val copyItem = JMenuItem(I18n.t("menu.copy"))
    val copyUrlItem = JMenuItem(I18n.t("menu.copy_url"))
    val pasteHostUrlAsRequest = JMenuItem(I18n.t("menu.paste_host_url_as_request"))
    val changeRequestMethod = JMenuItem(I18n.t("menu.change_request_method"))
    val changeBodyEncoding = JMenu(I18n.t("menu.change_body_encoding"))
    val toJsonItem = JMenuItem("to JSON")
    val toFormDataItem = JMenuItem("to Form-data")
    val toMultipartItem = JMenuItem("to Multipart")
    val toXmlItem = JMenuItem("to XML")
    val sendItem = JMenuItem(I18n.t("menu.send_to_fuzzer"))
    val sendToCodecMenu = JMenu(I18n.t("menu.use_codec"))
    val xappMenu = JMenu(I18n.t("menu.xapp"))
    val interceptResponseItem = JMenuItem(I18n.t("menu.intercept_this_response"))
    I18nBinder.bindText(copyItem, "menu.copy")
    I18nBinder.bindText(copyUrlItem, "menu.copy_url")
    I18nBinder.bindText(pasteHostUrlAsRequest, "menu.paste_host_url_as_request")
    I18nBinder.bindText(changeRequestMethod, "menu.change_request_method")
    I18nBinder.bindText(changeBodyEncoding, "menu.change_body_encoding")
    I18nBinder.bindText(sendItem, "menu.send_to_fuzzer")
    I18nBinder.bindText(sendToCodecMenu, "menu.use_codec")
    I18nBinder.bindText(xappMenu, "menu.xapp")
    I18nBinder.bindText(interceptResponseItem, "menu.intercept_this_response")

    fun selectedTextOnly(): String =
        area.selectedText?.takeIf { it.isNotBlank() }.orEmpty()

    fun applyCodecResultToCurrentArea(output: String) {
        if (area.isEditable) {
            val start = area.selectionStart
            val end = area.selectionEnd
            if (start >= 0 && end > start) {
                area.replaceRange(output, start, end)
                area.select(start, start + output.length)
            }
            return
        }

        val outputArea = javax.swing.JTextArea(12, 60).apply {
            text = output
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            selectionColor = Color(96, 104, 118)
            selectedTextColor = Color.WHITE
            caretColor = Color(56, 56, 60)
            background = Color(250, 250, 251)
        }
        val option = JOptionPane.showOptionDialog(
            this@installContextMenu,
            JScrollPane(outputArea),
            "Codec output",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.INFORMATION_MESSAGE,
            null,
            arrayOf("Copy", "Close"),
            "Copy"
        )
        if (option == 0) {
            copyToClipboard(output)
        }
    }

    fun useCodec(targetTabTitle: String?) {
        val selectedText = selectedTextOnly()
        if (selectedText.isBlank()) {
            return
        }
        val output = CodecHub.process(selectedText, targetTabTitle) ?: return
        applyCodecResultToCurrentArea(output)
    }

    fun rebuildSendToCodecMenu() {
        sendToCodecMenu.removeAll()
        val receiverAvailable = CodecHub.hasReceiver()
        if (!receiverAvailable) {
            sendToCodecMenu.isEnabled = false
            return
        }

        val hasSelection = selectedTextOnly().isNotBlank()

        val defaultItem = JMenuItem(I18n.t("menu.send_to_default_codec")).apply {
            addActionListener { useCodec(null) }
            isEnabled = hasSelection
        }
        sendToCodecMenu.add(defaultItem)

        val tabs = CodecHub.tabTitles()
        if (tabs.size > 1) {
            sendToCodecMenu.addSeparator()
            tabs.drop(1).forEach { tabTitle ->
                val item = JMenuItem(tabTitle).apply {
                    addActionListener { useCodec(tabTitle) }
                    isEnabled = hasSelection
                }
                sendToCodecMenu.add(item)
            }
        }
        sendToCodecMenu.isEnabled = true
    }

    sendItem.addActionListener {
        val requestRaw = requestRawArea.text
        if (!requestRaw.isNullOrBlank()) {
            onSendToFuzzer?.invoke(requestRaw)
        }
    }
    menu.add(sendItem)
    menu.add(sendToCodecMenu)
    val xappSectionSeparator = JSeparator()
    menu.add(xappSectionSeparator)
    menu.add(xappMenu)
    menu.addSeparator()

    copyItem.addActionListener {
        copyToClipboard(copyProvider.invoke())
    }
    copyUrlItem.addActionListener {
        val url = extractFullUrlFromCurrentRequest()
        if (url.isNotBlank()) {
            copyToClipboard(url)
        }
    }

    menu.add(copyItem)
    menu.add(copyUrlItem)
    val isRequestArea = area === requestRawArea || area === requestPrettyArea
    if (onPasteHostUrlAsRequest != null && isRequestArea) {
        pasteHostUrlAsRequest.addActionListener {
            onPasteHostUrlAsRequest.invoke()
        }
        menu.add(pasteHostUrlAsRequest)
    }
    if (onChangeRequestMethod != null && isRequestArea) {
        changeRequestMethod.addActionListener {
            onChangeRequestMethod.invoke()
        }
        menu.add(changeRequestMethod)
    }
    if (onChangeBodyEncoding != null && isRequestArea) {
        toJsonItem.addActionListener { onChangeBodyEncoding.invoke(RequestBodyEncodingTarget.JSON) }
        toFormDataItem.addActionListener { onChangeBodyEncoding.invoke(RequestBodyEncodingTarget.FORM_DATA) }
        toMultipartItem.addActionListener { onChangeBodyEncoding.invoke(RequestBodyEncodingTarget.MULTIPART) }
        toXmlItem.addActionListener { onChangeBodyEncoding.invoke(RequestBodyEncodingTarget.XML) }
        changeBodyEncoding.add(toJsonItem)
        changeBodyEncoding.add(toFormDataItem)
        changeBodyEncoding.add(toMultipartItem)
        changeBodyEncoding.add(toXmlItem)
        menu.add(changeBodyEncoding)
    }
    if (onInterceptThisResponse != null) {
        menu.addSeparator()
        interceptResponseItem.addActionListener {
            onInterceptThisResponse.invoke()
        }
        menu.add(interceptResponseItem)
    }

    fun messagePartForArea(): XappMenuMessagePart = when (area) {
        requestRawArea, requestPrettyArea -> XappMenuMessagePart.REQUEST
        responseRawArea, responsePrettyArea, responseRenderArea -> XappMenuMessagePart.RESPONSE
        else -> XappMenuMessagePart.UNKNOWN
    }

    fun isEditableForPart(part: XappMenuMessagePart): Boolean = when (part) {
        XappMenuMessagePart.REQUEST -> area.isEditable && onApplyRequestMutation != null
        XappMenuMessagePart.RESPONSE -> area.isEditable && onApplyResponseMutation != null
        XappMenuMessagePart.UNKNOWN -> false
    }

    fun snapshotForArea(clickTime: Boolean = true): XappHttpMenuSnapshot {
        val part = messagePartForArea()
        val active = area.text ?: ""
        val start = area.selectionStart.coerceIn(0, active.length)
        val end = area.selectionEnd.coerceIn(0, active.length)
        val selected = if (end > start) active.substring(start, end) else ""
        val requestRaw = currentRequestTextForForward()
        val responseRaw = currentResponseTextForForward()
        return XappHttpMenuSnapshot(
            tool = toolContext,
            messagePart = part,
            editable = isEditableForPart(part),
            activeText = active,
            requestRaw = requestRaw,
            responseRaw = responseRaw,
            selectionStart = if (clickTime) start else 0,
            selectionEnd = if (clickTime) end else 0,
            selectedText = if (clickTime) selected else "",
            requestTextHash = requestRaw.hashCode(),
            responseTextHash = responseRaw.hashCode(),
            currentRequestHashProvider = { currentRequestTextForForward().hashCode() },
            currentResponseHashProvider = { currentResponseTextForForward().hashCode() },
            requestMutation = onApplyRequestMutation,
            responseMutation = onApplyResponseMutation,
            sendToFuzzer = onSendToFuzzer,
            sendToCodec = onSendToCodec,
            clipboardSink = { text -> copyToClipboard(text) },
            logger = { line -> System.err.println(line) }
        )
    }

    fun addXappTreeNode(parent: JMenu, node: XappMenuTreeNode) {
        val action = node.action
        if (action != null) {
            val item = JMenuItem(node.label)
            item.addActionListener {
                val clickSnapshot = snapshotForArea(clickTime = true)
                org.jjgroup.xproxy.kits.core.XappContextMenuInvoker.invoke(action, clickSnapshot)
            }
            parent.add(item)
            return
        }
        val submenu = JMenu(node.label)
        node.children.forEach { child -> addXappTreeNode(submenu, child) }
        parent.add(submenu)
    }

    fun rebuildXappMenu(area: RSyntaxTextArea) {
        xappMenu.removeAll()
        val definitions = XappContextMenuHub.matchingDefinitions(snapshotForArea(clickTime = false))
        if (definitions.isEmpty()) {
            xappSectionSeparator.isVisible = false
            xappMenu.isVisible = false
            xappMenu.isEnabled = false
            return
        }
        val tree = buildXappMenuTree(definitions)
        tree.children.forEach { child -> addXappTreeNode(xappMenu, child) }
        xappSectionSeparator.isVisible = true
        xappMenu.isVisible = true
        xappMenu.isEnabled = true
    }

    fun refreshContextMenuState() {
        copyItem.isEnabled = copyProvider.invoke().isNotBlank()
        copyUrlItem.isEnabled = extractFullUrlFromCurrentRequest().isNotBlank()
        if (onPasteHostUrlAsRequest != null && isRequestArea) {
            pasteHostUrlAsRequest.isEnabled = true
        }
        if (onChangeRequestMethod != null && isRequestArea) {
            changeRequestMethod.isEnabled = requestRawArea.text.isNotBlank()
        }
        if (onChangeBodyEncoding != null && isRequestArea) {
            changeBodyEncoding.isEnabled = requestRawArea.text.isNotBlank()
        }
        sendItem.isEnabled = !requestRawArea.text.isNullOrBlank() && onSendToFuzzer != null
        rebuildSendToCodecMenu()
        rebuildXappMenu(area)
        if (onInterceptThisResponse != null) {
            interceptResponseItem.isEnabled = true
        }
    }

    menu.addPopupMenuListener(object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
            refreshContextMenuState()
        }

        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) {}

        override fun popupMenuCanceled(e: PopupMenuEvent?) {}
    })

    var boundByRsyntaxApi = false
    runCatching {
        val setPopupMenu = area.javaClass.getMethod("setPopupMenu", JPopupMenu::class.java)
        setPopupMenu.invoke(area, menu)
        boundByRsyntaxApi = true
    }
    if (!boundByRsyntaxApi) {
        area.componentPopupMenu = menu
    }
    pane.componentPopupMenu = menu
    pane.viewport.componentPopupMenu = menu
}

internal fun HttpRequestResponseViewer.copyToClipboard(text: String) {
    if (text.isBlank()) {
        return
    }
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

internal fun HttpRequestResponseViewer.extractFullUrlFromCurrentRequest(): String {
    val raw = currentRequestTextForForward()
    if (raw.isBlank()) {
        return ""
    }

    val lines = raw.lineSequence().toList()
    if (lines.isEmpty()) {
        return ""
    }

    val requestLine = lines.first().trim()
    val parts = requestLine.split(" ")
    if (parts.size < 2) {
        return ""
    }

    val target = parts[1].trim()
    if (target.startsWith("http://", true) || target.startsWith("https://", true)) {
        return target
    }

    val hostHeader = lines.drop(1)
        .firstOrNull { it.lowercase().startsWith("host:") }
        ?.substringAfter(':', "")
        ?.trim()
        ?: return ""

    val hostPort = parseHostAndPort(hostHeader)
    val host = hostPort.first
    val explicitPort = hostPort.second
    val inferredScheme = requestSchemeProvider?.invoke()?.trim()?.lowercase()
    val scheme = when {
        explicitPort == 443 -> "https"
        explicitPort == 80 -> "http"
        inferredScheme == "https" -> "https"
        inferredScheme == "http" -> "http"
        else -> "http"
    }
    val port = explicitPort ?: if (scheme == "https") 443 else 80
    val path = if (target.startsWith("/")) target else "/$target"
    return "$scheme://$host:$port$path"
}

internal fun HttpRequestResponseViewer.parseHostAndPort(hostHeader: String): Pair<String, Int?> {
    if (hostHeader.startsWith("[") && hostHeader.contains("]")) {
        val idx = hostHeader.indexOf(']')
        val host = hostHeader.substring(1, idx)
        val rest = hostHeader.substring(idx + 1)
        val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
        return host to port
    }

    val uriHost = try {
        URI("http://$hostHeader")
    } catch (_: Exception) {
        null
    }
    if (uriHost != null && !uriHost.host.isNullOrBlank()) {
        val port = if (uriHost.port > 0) uriHost.port else null
        return uriHost.host to port
    }

    val idx = hostHeader.lastIndexOf(':')
    if (idx > 0 && hostHeader.indexOf(':') == idx) {
        val host = hostHeader.substring(0, idx)
        val port = hostHeader.substring(idx + 1).toIntOrNull()
        return host to port
    }
    return hostHeader to null
}
