package org.jjgroup.xproxy.proxy.ui

import org.jjgroup.xproxy.i18n.I18n
import org.jjgroup.xproxy.proxy.model.ProxyInterceptItem
import javax.swing.table.AbstractTableModel

class ProxyInterceptTableModel : AbstractTableModel() {
    private val rows = ArrayList<ProxyInterceptItem>()
    // id -> model 行索引,upsert/removeById 用 O(1) 查找替代 indexOfFirst 线性扫描。
    private val idToRow = HashMap<Long, Int>()
    private val columns = listOf(
        "proxy.column.id",
        "proxy.column.state",
        "proxy.column.method",
        "proxy.column.host",
        "proxy.column.path"
    )

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = I18n.t(columns[column])

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val item = rows[rowIndex]
        return when (columnIndex) {
            0 -> item.id
            1 -> when (item.phase) {
                ProxyInterceptItem.Phase.REQUEST -> if (item.interceptThisResponse) "Req -> Resp" else "Request"
                ProxyInterceptItem.Phase.RESPONSE -> "Response"
            }
            2 -> item.method
            3 -> item.host
            4 -> item.path
            else -> ""
        }
    }

    fun upsert(item: ProxyInterceptItem) {
        val index = idToRow[item.id]
        if (index != null) {
            rows[index] = item
            fireTableRowsUpdated(index, index)
        } else {
            add(item)
        }
    }

    fun add(item: ProxyInterceptItem) {
        idToRow[item.id] = rows.size
        rows.add(item)
        fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
    }

    fun removeById(id: Long) {
        val index = idToRow[id] ?: return
        rows.removeAt(index)
        // 后续行下移,整体重建索引(拦截模型小,重建廉价)。
        idToRow.clear()
        rows.forEachIndexed { i, e -> idToRow[e.id] = i }
        fireTableRowsDeleted(index, index)
    }

    fun getAt(row: Int): ProxyInterceptItem? {
        if (row < 0 || row >= rows.size) {
            return null
        }
        return rows[row]
    }

    fun indexOfId(id: Long): Int = idToRow.getOrDefault(id, -1)
}
