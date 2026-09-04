package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.ui.marking.TrafficHighlightRegistry
import org.jjgroup.xproxy.ui.marking.buildHighlightSubmenu
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

internal fun SiteMapDetailPanel.installContentsPopupMenu() {
    val popup = JPopupMenu()
    val sendToFuzzer = JMenuItem(I18n.t("menu.send_to_fuzzer"))
    val sendToCodec = JMenu(I18n.t("menu.use_codec"))
    val highlightMenu = buildHighlightSubmenu(TrafficHighlightRegistry.Kind.HTTP) {
        selectedModelRows(contentsTable).mapNotNull { contentsModelGetAt(it)?.id }.toSet()
    }
    val deleteItem = JMenuItem(I18n.t("menu.delete_item"))
    val deleteAllItem = JMenuItem(I18n.t("menu.delete_all"))
    popup.add(sendToFuzzer)
    popup.add(sendToCodec)
    popup.add(highlightMenu)
    popup.add(deleteItem)
    popup.add(deleteAllItem)

    sendToFuzzer.addActionListener {
        val entry = contentsModelGetAt(selectedModelRow(contentsTable))
        val detail = entry?.let { resolveDetail(it) ?: it }
        val requestRaw = detail?.requestRaw
        if (!requestRaw.isNullOrBlank()) {
            onSendToFuzzer.invoke(requestRaw, detail?.let { toHttpService(it) })
        }
    }

    fun rebuildSendToCodecMenu() {
        sendToCodec.removeAll()
        val entry = contentsModelGetAt(selectedModelRow(contentsTable))
        val requestRaw = entry?.let { resolveDetail(it)?.requestRaw ?: it.requestRaw }
        if (requestRaw.isNullOrBlank() || (onSendToCodec == null && !org.jjgroup.xproxy.codec.core.CodecHub.hasReceiver())) {
            sendToCodec.isEnabled = false
            return
        }
        val defaultItem = JMenuItem(I18n.t("menu.send_to_default_codec"))
        defaultItem.addActionListener { onSendToCodec?.invoke(requestRaw, null) ?: org.jjgroup.xproxy.codec.core.CodecHub.send(requestRaw, null) }
        sendToCodec.add(defaultItem)
        val tabTitles = org.jjgroup.xproxy.codec.core.CodecHub.tabTitles()
        if (tabTitles.size > 1) {
            sendToCodec.addSeparator()
            tabTitles.drop(1).forEach { tabTitle ->
                val item = JMenuItem(tabTitle)
                item.addActionListener { onSendToCodec?.invoke(requestRaw, tabTitle) ?: org.jjgroup.xproxy.codec.core.CodecHub.send(requestRaw, tabTitle) }
                sendToCodec.add(item)
            }
        }
        sendToCodec.isEnabled = true
    }

    deleteItem.addActionListener {
        val ids = selectedModelRows(contentsTable).mapNotNull { contentsModelGetAt(it)?.id }.toSet()
        onDeleteByIds?.invoke(ids)
    }

    deleteAllItem.addActionListener {
        val confirm = javax.swing.JOptionPane.showConfirmDialog(
            this,
            I18n.t("target.delete_all_contents_confirm"),
            I18n.t("menu.delete_all"),
            javax.swing.JOptionPane.YES_NO_OPTION,
            javax.swing.JOptionPane.WARNING_MESSAGE
        )
        if (confirm == javax.swing.JOptionPane.YES_OPTION) {
            val ids = (0 until contentsTable.rowCount)
                .mapNotNull { contentsModelGetAt(it)?.id }
                .toSet()
            onDeleteByIds?.invoke(ids)
        }
    }

    contentsTable.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) = maybeShow(e)
        override fun mouseReleased(e: java.awt.event.MouseEvent) = maybeShow(e)

        private fun maybeShow(e: java.awt.event.MouseEvent) {
            if (!e.isPopupTrigger) return
            val row = contentsTable.rowAtPoint(e.point)
            if (row >= 0 && !contentsTable.isRowSelected(row)) {
                contentsTable.setRowSelectionInterval(row, row)
            }
            sendToFuzzer.isEnabled = contentsModelGetAt(selectedModelRow(contentsTable)) != null
            rebuildSendToCodecMenu()
            highlightMenu.isEnabled = selectedModelRows(contentsTable).isNotEmpty()
            val selectedRows = selectedModelRows(contentsTable)
            deleteItem.text = if (selectedRows.size <= 1) I18n.t("menu.delete_item") else I18n.t("menu.delete_selected")
            deleteItem.isEnabled = selectedRows.isNotEmpty()
            deleteAllItem.isEnabled = contentsTable.rowCount > 0
            popup.show(e.component, e.x, e.y)
        }
    })
}

internal fun SiteMapDetailPanel.installIssuesPopupMenu() {
    val popup = JPopupMenu()
    val sendToFuzzer = JMenuItem(I18n.t("menu.send_to_fuzzer"))
    val deleteItem = JMenuItem(I18n.t("menu.delete_item"))
    popup.add(sendToFuzzer)
    popup.add(deleteItem)

    sendToFuzzer.addActionListener {
        val issue = selectedIssueRecord() ?: return@addActionListener
        val (reqRaw, _) = resolveIssueRecordRaw(issue)
        if (reqRaw.isBlank()) return@addActionListener
        val service = if (issue.historyId > 0) {
            resolveDetailById(issue.historyId)?.let { toHttpService(it) }
        } else {
            null
        }
        onSendToFuzzer.invoke(reqRaw, service)
    }

    deleteItem.addActionListener {
        val issue = selectedIssueRecord() ?: return@addActionListener
        onDeleteIssue?.invoke(issue)
    }

    issuesTree.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) = maybeShow(e)
        override fun mouseReleased(e: java.awt.event.MouseEvent) = maybeShow(e)

        private fun maybeShow(e: java.awt.event.MouseEvent) {
            if (!e.isPopupTrigger) return
            val path = issuesTree.getPathForLocation(e.x, e.y)
            if (path != null && !issuesTree.isPathSelected(path)) {
                issuesTree.selectionPath = path
            }
            val issue = selectedIssueRecord()
            // script issue(historyId<=0)的 raw 启动期未载入但 DB 必有(实测全非空),允许发送并按需懒加载;
            // history-backed issue 仍以已载入的 requestRaw 为准。
            sendToFuzzer.isEnabled = issue != null && (issue.requestRaw.isNotBlank() || issue.historyId <= 0)
            deleteItem.isEnabled = issue != null
            popup.show(e.component, e.x, e.y)
        }
    })
}
