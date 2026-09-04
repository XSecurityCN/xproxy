package org.jjgroup.xproxy.target.ui

import org.jjgroup.xproxy.fuzzer.core.HttpService
import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.i18n.I18nBinder
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import org.jjgroup.xproxy.target.core.SiteMapService
import org.jjgroup.xproxy.target.model.SiteMapEntry
import java.awt.BorderLayout
import java.awt.Component
import java.net.URI
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

internal data class SiteMapNode(
    val label: String,
    val key: String? = null,
    val hostKey: String? = null,
    val pathPrefix: String? = null
) {
    override fun toString(): String = label
}

internal data class HistoryRecord(
    val history: ProxyHistoryEntry,
    val hostKey: String,
    val normalizedPath: String,
    val key: String
)

internal class SiteMapTreePanel(
    private val service: SiteMapService,
    private val onSelectionChanged: () -> Unit,
    private val onSendToFuzzer: (String, HttpService?) -> Unit,
    private val onSendToCodec: ((String, String?) -> Unit)?,
    private val onDeleteHistoryIds: ((Set<Long>) -> Unit)?
) : JPanel(BorderLayout()) {

    private val root = DefaultMutableTreeNode(I18n.t("target.site_map"))
    private val treeModel = DefaultTreeModel(root)
    val tree: JTree = JTree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(
                tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
            ): Component {
                val rendered = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                val node = value as? DefaultMutableTreeNode
                val data = node?.userObject as? SiteMapNode
                if (data != null) {
                    text = data.label
                }
                return rendered
            }
        }
    }

    private val hostNodes = LinkedHashMap<String, DefaultMutableTreeNode>()
    private val pathNodes = LinkedHashMap<String, DefaultMutableTreeNode>()
    private val leafNodes = LinkedHashMap<String, DefaultMutableTreeNode>()
    val allRecords = ArrayList<HistoryRecord>()
    val seenHistoryIds = HashSet<Long>()
    val recordsByHost = LinkedHashMap<String, MutableList<HistoryRecord>>()
    val recordsByKey = LinkedHashMap<String, MutableList<HistoryRecord>>()

    init {
        tree.addTreeSelectionListener { onSelectionChanged() }
        installTreePopupMenu()

        val titleLabel = JLabel(I18n.t("target.site_map"))
        I18nBinder.bindText(titleLabel, "target.site_map")
        add(titleLabel, BorderLayout.NORTH)
        add(JScrollPane(tree), BorderLayout.CENTER)
        I18nBinder.bind {
            root.userObject = I18n.t("target.site_map")
            treeModel.nodeChanged(root)
        }
    }

    fun indexHistory(history: ProxyHistoryEntry): HistoryRecord {
        val target = inferTarget(history)
        val normalizedPath = normalizePath(history.path)
        val hostKey = "${target.protocol}://${target.host}:${target.port}"
        val key = "$hostKey|$normalizedPath"
        return HistoryRecord(history = history, hostKey = hostKey, normalizedPath = normalizedPath, key = key)
    }

    fun upsertSiteMapEntry(history: ProxyHistoryEntry) {
        val entry = service.upsert(history)
        val hostKey = "${entry.protocol}://${entry.host}:${entry.port}"
        val hostNode = hostNodes.getOrPut(hostKey) {
            val node = DefaultMutableTreeNode(SiteMapNode(label = hostKey, hostKey = hostKey))
            treeModel.insertNodeInto(node, root, root.childCount)
            node
        }

        val existingLeaf = leafNodes[entry.key]
        if (existingLeaf == null) {
            val node = createLeafNode(hostKey, hostNode, entry)
            leafNodes[entry.key] = node
            expandPathTo(hostNode)
        } else {
            existingLeaf.userObject = SiteMapNode(
                label = buildLeafLabel(entry),
                key = entry.key,
                hostKey = hostKey,
                pathPrefix = entry.path
            )
            treeModel.nodeChanged(existingLeaf)
        }
    }

    fun selectedSiteMapNode(): SiteMapNode? {
        val path: TreePath = tree.selectionPath ?: return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? SiteMapNode
    }

    fun selectedEntry(): SiteMapEntry? {
        val node = selectedSiteMapNode() ?: return null
        val key = node.key ?: return null
        return service.get(key)
    }

    fun selectedHistoryIdsFromTreeSelection(): Set<Long> {
        val paths = tree.selectionPaths ?: return emptySet()
        val ids = LinkedHashSet<Long>()
        for (treePath in paths) {
            val node = treePath.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val data = node.userObject as? SiteMapNode ?: continue
            val records = when {
                !data.key.isNullOrBlank() -> recordsByKey[data.key].orEmpty()
                !data.hostKey.isNullOrBlank() && !data.pathPrefix.isNullOrBlank() -> {
                    recordsByHost[data.hostKey].orEmpty().filter { isPathUnder(it.normalizedPath, data.pathPrefix) }
                }
                !data.hostKey.isNullOrBlank() -> recordsByHost[data.hostKey].orEmpty()
                else -> allRecords
            }
            records.forEach { ids.add(it.history.id) }
        }
        return ids
    }

    fun rebuildStateFromRecords() {
        val histories = allRecords.map { it.history }.sortedBy { it.id }
        allRecords.clear()
        recordsByHost.clear()
        recordsByKey.clear()
        hostNodes.clear()
        pathNodes.clear()
        leafNodes.clear()
        root.removeAllChildren()
        service.clear()

        histories.forEach { history ->
            val record = indexHistory(history)
            allRecords.add(record)
            recordsByHost.getOrPut(record.hostKey) { ArrayList() }.add(record)
            recordsByKey.getOrPut(record.key) { ArrayList() }.add(record)
            upsertSiteMapEntry(history)
        }
        treeModel.reload(root)
    }

    fun shouldRefreshForSelection(record: HistoryRecord, selectedNode: SiteMapNode?): Boolean {
        if (selectedNode == null) {
            return true
        }
        if (!selectedNode.key.isNullOrBlank()) {
            return selectedNode.key == record.key
        }
        if (!selectedNode.hostKey.isNullOrBlank() && !selectedNode.pathPrefix.isNullOrBlank()) {
            return record.hostKey == selectedNode.hostKey && isPathUnder(record.normalizedPath, selectedNode.pathPrefix)
        }
        if (!selectedNode.hostKey.isNullOrBlank()) {
            return record.hostKey == selectedNode.hostKey
        }
        return true
    }

    fun toHttpService(history: ProxyHistoryEntry): HttpService {
        val target = inferTarget(history)
        return HttpService(target.host, target.port, target.protocol)
    }

    fun normalizePath(path: String): String {
        if (path.isBlank()) {
            return "/"
        }
        return try {
            val token = path.trim()
            if (token.startsWith("http://") || token.startsWith("https://")) {
                val uri = URI(token)
                uri.path.ifBlank { "/" }
            } else {
                token.substringBefore('?').ifBlank { "/" }
            }
        } catch (_: Exception) {
            path.substringBefore('?').ifBlank { "/" }
        }
    }

    fun isPathUnder(path: String, prefix: String?): Boolean {
        if (prefix.isNullOrBlank()) {
            return true
        }
        if (prefix == "/") {
            return true
        }
        return path == prefix || path.startsWith("$prefix/")
    }

    private data class TargetInfo(val protocol: String, val host: String, val port: Int)

    private fun protocolFromHistory(history: ProxyHistoryEntry): String? {
        val normalized = history.protocol.trim().lowercase()
        return when (normalized) {
            "http/2", "h2", "http2" -> if (history.tls) "https" else "http"
            "https" -> "https"
            "http", "http/1.1" -> "http"
            else -> null
        }
    }

    private fun inferTarget(history: ProxyHistoryEntry): TargetInfo {
        val requestLine = history.requestRaw.lineSequence().firstOrNull()?.trim().orEmpty()
        val parts = requestLine.split(" ")
        val targetToken = if (parts.size >= 2) parts[1] else ""

        var host: String? = null
        var port: Int? = null
        var protocol: String? = null

        if (targetToken.startsWith("http://") || targetToken.startsWith("https://")) {
            try {
                val uri = URI(targetToken)
                if (!uri.host.isNullOrBlank()) {
                    host = uri.host
                }
                protocol = uri.scheme?.lowercase()
                port = if (uri.port != -1) uri.port else if (protocol == "https") 443 else 80
            } catch (_: Exception) {
            }
        }

        val headers = history.requestRaw.lineSequence().drop(1)
        val hostHeader = headers.firstOrNull { it.lowercase().startsWith("host:") }?.substringAfter(':', "")?.trim()
        if (host.isNullOrBlank() && !hostHeader.isNullOrBlank()) {
            parseHostHeader(hostHeader).let { (h, p) ->
                host = h
                p?.let { port = it }
            }
        }

        val resolvedHost = host.takeUnless { it.isNullOrBlank() }
            ?: history.host.substringBefore(':')
        val resolvedProtocol = protocol.takeUnless { it.isNullOrBlank() }
            ?: protocolFromHistory(history)
            ?: if (history.tls) "https" else "http"
        val resolvedPort = port
            ?: history.host.substringAfter(':', "").toIntOrNull()
            ?: if (resolvedProtocol == "https") 443 else 80

        return TargetInfo(resolvedProtocol, resolvedHost, resolvedPort)
    }

    private fun parseHostHeader(hostHeader: String): Pair<String, Int?> = when {
        hostHeader.startsWith("[") && hostHeader.contains("]") -> {
            val end = hostHeader.indexOf(']')
            val h = hostHeader.substring(1, end)
            val rest = hostHeader.substring(end + 1)
            val p = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
            h to p
        }
        else -> {
            val idx = hostHeader.lastIndexOf(':')
            if (idx > 0 && hostHeader.indexOf(':') == idx) {
                hostHeader.substring(0, idx) to hostHeader.substring(idx + 1).toIntOrNull()
            } else {
                hostHeader to null
            }
        }
    }

    private fun createLeafNode(hostKey: String, hostNode: DefaultMutableTreeNode, entry: SiteMapEntry): DefaultMutableTreeNode {
        val segments = splitPathSegments(entry.path)
        if (segments.isEmpty()) {
            val leaf = DefaultMutableTreeNode(
                SiteMapNode(label = buildLeafLabel(entry), key = entry.key, hostKey = hostKey, pathPrefix = entry.path)
            )
            treeModel.insertNodeInto(leaf, hostNode, hostNode.childCount)
            return leaf
        }

        var parent = hostNode
        val parentSegments = segments.dropLast(1)
        for (index in parentSegments.indices) {
            val segment = parentSegments[index]
            val prefix = "/" + parentSegments.take(index + 1).joinToString("/")
            val mapKey = "$hostKey|$prefix"
            val pathNode = pathNodes.getOrPut(mapKey) {
                val node = DefaultMutableTreeNode(
                    SiteMapNode(label = segment, hostKey = hostKey, pathPrefix = prefix)
                )
                treeModel.insertNodeInto(node, parent, parent.childCount)
                node
            }
            parent = pathNode
        }

        val leaf = DefaultMutableTreeNode(
            SiteMapNode(label = buildLeafLabel(entry), key = entry.key, hostKey = hostKey, pathPrefix = entry.path)
        )
        treeModel.insertNodeInto(leaf, parent, parent.childCount)
        return leaf
    }

    private fun buildLeafLabel(entry: SiteMapEntry): String = pathNodeName(entry.path)

    private fun splitPathSegments(path: String): List<String> =
        path.trim().trim('/').split('/').filter { it.isNotBlank() }

    private fun pathNodeName(path: String): String {
        val segments = splitPathSegments(path)
        return if (segments.isEmpty()) "/" else segments.last()
    }

    private fun expandPathTo(node: DefaultMutableTreeNode) {
        val treePath = TreePath(treeModel.getPathToRoot(node))
        tree.expandPath(treePath)
    }

    private fun installTreePopupMenu() {
        val popup = JPopupMenu()
        val sendToFuzzer = JMenuItem(I18n.t("menu.send_to_fuzzer"))
        val sendToCodec = JMenu(I18n.t("menu.use_codec"))
        val deleteItem = JMenuItem(I18n.t("menu.delete_item"))
        popup.add(sendToFuzzer)
        popup.add(sendToCodec)
        popup.add(deleteItem)
        sendToFuzzer.addActionListener {
            val selectedId = selectedHistoryIdsFromTreeSelection().firstOrNull()
            val detail = selectedId?.let { resolveDetailCallback?.invoke(it) }
            val requestRaw = detail?.requestRaw
            if (!requestRaw.isNullOrBlank()) {
                onSendToFuzzer.invoke(requestRaw, detail?.let { toHttpService(it) })
            }
        }

        fun rebuildSendToCodecMenu() {
            sendToCodec.removeAll()
            val selectedId = selectedHistoryIdsFromTreeSelection().firstOrNull()
            val requestRaw = selectedId?.let { resolveDetailCallback?.invoke(it)?.requestRaw }
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
            onDeleteCallback?.invoke(selectedHistoryIdsFromTreeSelection())
        }

        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = maybeShow(e)

            private fun maybeShow(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) {
                    return
                }
                val path = tree.getPathForLocation(e.x, e.y)
                if (path != null) {
                    if (tree.selectionCount <= 1 || !tree.isPathSelected(path)) {
                        tree.selectionPath = path
                    }
                }
                sendToFuzzer.isEnabled = selectedEntry() != null
                rebuildSendToCodecMenu()
                val selectedIds = selectedHistoryIdsFromTreeSelection()
                deleteItem.text = if (selectedIds.size <= 1) I18n.t("menu.delete_item") else I18n.t("menu.delete_selected")
                deleteItem.isEnabled = selectedIds.isNotEmpty()
                popup.show(e.component, e.x, e.y)
            }
        })
    }

    var resolveDetailCallback: ((Long) -> ProxyHistoryEntry?)? = null
    var onDeleteCallback: ((Set<Long>) -> Unit)? = null
}
