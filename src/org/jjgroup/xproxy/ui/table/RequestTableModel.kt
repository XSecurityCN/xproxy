package org.jjgroup.xproxy.ui.table

import org.jjgroup.xproxy.Request
import org.jjgroup.xproxy.core.Utils
import org.jjgroup.xproxy.i18n.I18n

import javax.swing.table.AbstractTableModel
import java.util.ArrayList
import javax.swing.SwingUtilities

class RequestTableModel : AbstractTableModel() {

    private val requests: MutableList<Request> = ArrayList()
    private var payloadColumns = 1
    private var payloadColumnNames: MutableList<String> = mutableListOf(defaultPayloadColumnName(0))

    private val rowColumnKey = "request_table.column.row"
    private val trailingColumns = listOf(
        "request_table.column.status",
        "request_table.column.words",
        "request_table.column.length",
        "request_table.column.time",
        "request_table.column.anomaly_rank",
        "request_table.column.label"
    )

    private fun payloadColumnCount(): Int {
        return payloadColumns
    }

    private fun defaultPayloadColumnName(index: Int): String {
        return I18n.t("request_table.column.payload_n", "index" to index + 1)
    }

    private fun normalizePlaceholderName(name: String?): String {
        val normalized = name?.trim().orEmpty()
        return if (normalized.isEmpty()) "" else normalized
    }

    private fun currentPayloadNames(expectedColumns: Int): List<String> {
        val names = payloadColumnNames.toMutableList()
        while (names.size < expectedColumns) {
            names.add(defaultPayloadColumnName(names.size))
        }
        if (names.size > expectedColumns) {
            return names.take(expectedColumns)
        }
        return names
    }

    private fun mergePayloadNames(req: Request, expectedColumns: Int): List<String> {
        val merged = currentPayloadNames(expectedColumns).toMutableList()
        val placeholders = req.getPlaceholders()
        for (index in 0 until minOf(expectedColumns, placeholders.size)) {
            val placeholder = normalizePlaceholderName(placeholders[index])
            if (placeholder.isNotEmpty()) {
                merged[index] = placeholder
            }
        }
        return merged
    }

    private fun payloadStartColumn(): Int {
        return 1
    }

    private fun payloadEndColumnExclusive(): Int {
        return payloadStartColumn() + payloadColumnCount()
    }

    private fun trailingStartColumn(): Int {
        return payloadEndColumnExclusive()
    }

    fun anomalyRankColumnIndex(): Int {
        return trailingStartColumn() + trailingColumns.indexOf("request_table.column.anomaly_rank")
    }

    fun timeColumnIndex(): Int {
        return trailingStartColumn() + trailingColumns.indexOf("request_table.column.time")
    }

    override fun getRowCount(): Int {
        return requests.size
    }

    override fun getColumnCount(): Int {
        return 1 + payloadColumnCount() + trailingColumns.size
    }

    override fun getColumnName(column: Int): String {
        try {
            if (column == 0) {
                return I18n.t(rowColumnKey)
            }
            if (column in payloadStartColumn() until payloadEndColumnExclusive()) {
                val payloadIndex = column - payloadStartColumn()
                return payloadColumnNames.getOrElse(payloadIndex) { defaultPayloadColumnName(payloadIndex) }
            }
            val trailingIndex = column - trailingStartColumn()
            return I18n.t(trailingColumns[trailingIndex])
        } catch (e: Exception) {
            Utils.err("Error getting column name: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    override fun getColumnClass(columnIndex: Int): Class<*> {
        try {
            if (columnIndex == 0) {
                return java.lang.Integer::class.java
            }
            if (columnIndex in payloadStartColumn() until payloadEndColumnExclusive()) {
                return String::class.java
            }
            return when (columnIndex - trailingStartColumn()) {
                0, 1, 2, 4 -> java.lang.Integer::class.java
                3 -> java.lang.Long::class.java
                5 -> String::class.java
                else -> throw RuntimeException("Invalid column requested")
            }
        } catch (e: Exception) {
            Utils.err("Error getting column class: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        try {
            val request = requests[rowIndex]

            if (columnIndex == 0) {
                return rowIndex
            }
            if (columnIndex in payloadStartColumn() until payloadEndColumnExclusive()) {
                val payloadIndex = columnIndex - payloadStartColumn()
                return request.words.getOrNull(payloadIndex) ?: ""
            }

            return when (columnIndex - trailingStartColumn()) {
                0 -> request.code
                1 -> request.wordcount
                2 -> request.length
                3 -> request.time
                4 -> request.anomalyRank ?: 0
                5 -> request.label
                else -> throw RuntimeException("Invalid column requested")
            }
        } catch (e: Exception) {
            Utils.err("Error getting value at row $rowIndex, column $columnIndex: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
        return false
    }

    fun getRequest(index: Int): Request? {
        return try {
            requests[index]
        } catch (ex: ArrayIndexOutOfBoundsException) {
            Utils.out("Couldn't get request at index $index")
            throw ex
        }

    }

    fun getAllRequests(): List<Request> {
        return requests
    }

    fun addRow(req: Request) {
        val expectedPayloadColumns = maxOf(payloadColumns, maxOf(1, req.words.size))
        val nextPayloadNames = mergePayloadNames(req, expectedPayloadColumns)
        val requiresStructureUpdate = expectedPayloadColumns != payloadColumns || nextPayloadNames != currentPayloadNames(expectedPayloadColumns)
        requests.add(req)
        try {
            if (requiresStructureUpdate) {
                payloadColumns = expectedPayloadColumns
                payloadColumnNames = nextPayloadNames.toMutableList()
                fireTableStructureChanged()
            } else {
                fireTableRowsInserted(requests.lastIndex, requests.lastIndex)
            }
        } catch (e: Exception) {
//            Utils.err("Error firing table rows inserted: "+e.message)
//            Utilities.showError(e)
//            e.printStackTrace()
        }
    }

    fun clear() {
        SwingUtilities.invokeLater {
            requests.clear()
            payloadColumns = 1
            payloadColumnNames = mutableListOf(defaultPayloadColumnName(0))
            fireTableStructureChanged()
        }
    }

    fun updateRankings() {
        SwingUtilities.invokeLater {
            fireTableDataChanged()
        }
    }
}
