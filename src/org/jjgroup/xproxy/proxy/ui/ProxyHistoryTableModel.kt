package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.proxy.model.ProxyHistoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.table.AbstractTableModel

class ProxyHistoryTableModel : AbstractTableModel() {
    private val rows = ArrayList<ProxyHistoryEntry>()
    // id -> model 行索引,O(1) 查找替代 findHistoryRowById 的线性扫描。仅在 add/addAll 增量维护,删除时整体重建。
    private val idToRow = HashMap<Long, Int>()
    private val columns = listOf(
        "proxy.column.id",
        "proxy.column.method",
        "proxy.column.host",
        "proxy.column.path",
        "proxy.column.status",
        "proxy.column.length",
        "proxy.column.mime_type",
        "proxy.column.title",
        "proxy.column.tls",
        "proxy.column.protocol",
        "proxy.column.modified",
        "proxy.column.time",
        "proxy.column.tool"
    )
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss MMM yyyy", Locale.ENGLISH)
        .withZone(ZoneId.systemDefault())
    // time 列每次 repaint 都会调 getValueAt->formatTime(含 DateTimeFormatter 格式化),缓存避免重复格式化。
    private val formattedTimes = HashMap<Long, String>()

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = I18n.t(columns[column])

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            0 -> java.lang.Long::class.java
            4 -> java.lang.Integer::class.java
            5 -> java.lang.Integer::class.java
            else -> String::class.java
        }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = rows[rowIndex]
        return when (columnIndex) {
            0 -> entry.id
            1 -> entry.method
            2 -> entry.host
            3 -> entry.path
            4 -> entry.statusCode
            5 -> entry.length
            6 -> entry.mimeType
            7 -> entry.title
            8 -> if (entry.tls) "✓" else "✗"
            9 -> entry.protocol
            10 -> if (entry.modified) "✓" else "✗"
            11 -> formattedTimes.getOrPut(entry.id) { formatTime(entry.timeMillis) }
            12 -> entry.tool
            else -> ""
        }
    }

    private fun formatTime(timeMillis: Long): String {
        return runCatching { timeFormatter.format(Instant.ofEpochMilli(timeMillis)) }
            .getOrDefault("")
    }

    fun add(entry: ProxyHistoryEntry) {
        idToRow[entry.id] = rows.size
        rows.add(entry)
        fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
    }

    fun addAll(entries: List<ProxyHistoryEntry>) {
        if (entries.isEmpty()) {
            return
        }
        var idx = rows.size
        for (e in entries) {
            idToRow[e.id] = idx++
        }
        rows.addAll(entries)
        fireTableDataChanged()
    }

    /**
     * 原地替换同 id 的历史条目(用于 SSE 流式响应实时刷新:body/length 增长时更新该行,不改行序、不新增行)。
     * 行不存在时安全忽略。timeMillis 不变 -> formattedTimes 缓存保留。必须在 EDT 调用。
     */
    fun update(entry: ProxyHistoryEntry) {
        val row = idToRow[entry.id] ?: return
        rows[row] = entry
        fireTableRowsUpdated(row, row)
    }

    fun getAt(row: Int): ProxyHistoryEntry? {
        if (row < 0 || row >= rows.size) {
            return null
        }
        return rows[row]
    }

    fun indexOfId(id: Long): Int = idToRow.getOrDefault(id, -1)

    fun removeByIds(ids: Set<Long>) {
        if (ids.isEmpty()) {
            return
        }
        rows.removeIf { ids.contains(it.id) }
        formattedTimes.keys.removeAll(ids)
        // 删除导致后续行下移,整体重建索引(删除为用户操作,低频)。
        idToRow.clear()
        rows.forEachIndexed { i, e -> idToRow[e.id] = i }
        fireTableDataChanged()
    }
}
