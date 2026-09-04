package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.proxy.model.ProxyWsHistoryEntry
import javax.swing.table.AbstractTableModel

class ProxyWsHistoryTableModel : AbstractTableModel() {
    private val rows = ArrayList<ProxyWsHistoryEntry>()
    // id -> model 行索引,O(1) 查找替代 findWsHistoryRowById 的线性扫描。
    private val idToRow = HashMap<Long, Int>()
    private val columns = listOf(
        "proxy.column.id",
        "proxy.column.host",
        "proxy.column.path",
        "proxy.column.direction",
        "proxy.column.type",
        "proxy.column.mime",
        "proxy.column.length",
        "proxy.column.preview"
    )

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = I18n.t(columns[column])

    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            0 -> java.lang.Long::class.java
            6 -> java.lang.Integer::class.java
            else -> String::class.java
        }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val entry = rows[rowIndex]
        return when (columnIndex) {
            0 -> entry.id
            1 -> entry.host
            2 -> entry.path
            3 -> entry.direction
            4 -> entry.messageType
            5 -> entry.mimeType
            6 -> entry.length
            7 -> entry.preview
            else -> ""
        }
    }

    fun add(entry: ProxyWsHistoryEntry) {
        idToRow[entry.id] = rows.size
        rows.add(entry)
        fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
    }

    fun addAll(entries: List<ProxyWsHistoryEntry>) {
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

    fun getAt(row: Int): ProxyWsHistoryEntry? {
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
        idToRow.clear()
        rows.forEachIndexed { i, e -> idToRow[e.id] = i }
        fireTableDataChanged()
    }
}
