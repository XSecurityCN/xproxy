package org.jjgroup.xproxy.ui.marking

import java.awt.Component
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer

/**
 * 表格单元格渲染器:在保留默认左对齐文本渲染的基础上,按所在行的流量高亮着色行背景。
 *
 * - 高亮来自 [TrafficHighlightRegistry](按 entry id 查),代理历史表 / WS 历史表 / Target 内容表共用。
 * - 主题感知:tint 底色按 [org.jjgroup.xproxy.settings.core.UiThemePalette.isDark] 取值;
 *   选中行用加深 tint 以保证选中态仍可见高亮色;前景按底色亮度自动选黑/白。
 * - 无高亮回退表格默认(选中 selectionBackground,否则 null->默认背景),与原
 *   `DefaultTableCellRenderer` 行为一致,避免引入回退右对齐等问题。
 *
 * [idProvider] 接收**模型行索引**(渲染器内部已由视图行 convert),返回该行 entry id。
 */
class HighlightCellRenderer(
    private val kind: TrafficHighlightRegistry.Kind,
    private val idProvider: (JTable, Int) -> Long?
) : DefaultTableCellRenderer() {

    init {
        horizontalAlignment = SwingConstants.LEFT
    }

    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val tableRef = table ?: return this
        val modelRow = if (tableRef.rowSorter != null) tableRef.convertRowIndexToModel(row) else row
        val id = idProvider(tableRef, modelRow)
        val highlight = if (id != null && id > 0L) TrafficHighlightRegistry.get(kind, id) else TrafficHighlight.NONE
        val tint = highlight.tint()
        // 显式设定颜色:DefaultTableCellRenderer 复用同一 JLabel 实例,若仅在有高亮时改写、无高亮时保留 super
        // 的值,会残留上次渲染的 tint。故无高亮时回退表格默认(选中 selectionBackground,否则 table.background)。
        if (tint != null) {
            background = if (isSelected) tint.backgroundSelected else tint.background
            foreground = foregroundOn(background)
        } else {
            background = if (isSelected) tableRef.selectionBackground else tableRef.background
            foreground = if (isSelected) tableRef.selectionForeground else tableRef.foreground
        }
        return this
    }
}
