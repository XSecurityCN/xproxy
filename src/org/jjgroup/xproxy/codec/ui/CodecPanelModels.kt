package org.jjgroup.xproxy.codec.ui

import java.awt.Component
import java.awt.Font
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.tree.DefaultMutableTreeNode
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

internal data class CodecTabUi(
    val recordId: String,
    val tabComponent: Component,
    val cardId: String,
    val root: JPanel,
    val inputArea: JTextArea,
    val recipeList: JList<String>,
    val recipeModel: DefaultListModel<String>,
    val outputArea: JTextArea,
    val statusLabel: JLabel,
    val operationTree: JTree,
    var title: String
)

internal data class CodecTabHeaderUi(
    val root: JPanel,
    val label: JLabel,
    val close: javax.swing.JButton,
    val normalFont: Font,
    val selectedFont: Font,
    var hovered: Boolean = false,
    var closeHovered: Boolean = false
)

internal class RuleListTransferHandler(
    private val panel: CodecPanel,
    private val allowImport: Boolean,
    private val moveWithinSameList: Boolean
) : TransferHandler() {
    private var sourceList: JList<*>? = null
    private var sourceIndex: Int = -1

    override fun createTransferable(c: JComponent): Transferable? {
        val list = c as? JList<*> ?: return null
        val value = list.selectedValue as? String ?: return null
        sourceList = list
        sourceIndex = list.selectedIndex
        return StringSelection(value)
    }

    override fun getSourceActions(c: JComponent): Int =
        if (moveWithinSameList && c is JList<*>) MOVE else COPY

    override fun canImport(support: TransferSupport): Boolean {
        if (!allowImport) return false
        if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
        val list = support.component as? JList<*> ?: return false
        if (list.model !is DefaultListModel<*>) return false
        return support.isDrop
    }

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val list = support.component as? JList<*> ?: return false
        val tab = panel.tabByComponent.values.firstOrNull { it.recipeList === list } ?: return false
        val model = tab.recipeModel
        val dropLocation = support.dropLocation as? JList.DropLocation
        val dropIndex = (dropLocation?.index ?: model.size()).coerceIn(0, model.size())

        val text = support.transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return false
        val value = text.trim()
        if (value.isBlank()) return false

        if (moveWithinSameList && sourceList === list && sourceIndex >= 0) {
            val existing = model.getElementAt(sourceIndex)
            model.remove(sourceIndex)
            var adjusted = dropIndex
            if (dropIndex > sourceIndex) {
                adjusted -= 1
            }
            model.add(adjusted.coerceIn(0, model.size()), existing)
            list.selectedIndex = adjusted.coerceIn(0, model.size() - 1)
        } else {
            val operationName = CodecTabContentFactory.operationNameFromLabel(value)
            val token = CodecTabContentFactory.buildRuleToken(
                operationName,
                CodecTabContentFactory.defaultConfigForOperation(operationName)
            )
            model.add(dropIndex, token)
            list.selectedIndex = dropIndex
        }

        panel.recompute(tab)
        panel.persistState()
        return true
    }

    override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
        sourceList = null
        sourceIndex = -1
    }
}

internal class OperationTreeTransferHandler(
    @Suppress("unused") private val panel: CodecPanel
) : TransferHandler() {
    override fun createTransferable(c: JComponent): Transferable? {
        val tree = c as? JTree ?: return null
        val path = tree.selectionPath ?: return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        if (!node.isLeaf) return null
        val operationName = node.userObject?.toString()?.trim().orEmpty()
        if (operationName.isBlank()) return null
        return StringSelection(operationName)
    }

    override fun getSourceActions(c: JComponent): Int = COPY
}
