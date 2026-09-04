package org.jjgroup.xproxy.fuzzer.ui

import org.jjgroup.xproxy.i18n.I18n
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.*

internal fun Color.toHexString(): String = "#%02X%02X%02X".format(red, green, blue)

internal fun colorFromHex(value: String?): Color? {
    val text = value?.trim().orEmpty()
    if (!Regex("^#[0-9a-fA-F]{6}$").matches(text)) {
        return null
    }
    return runCatching { Color(Integer.parseInt(text.substring(1), 16)) }.getOrNull()
}

private fun IntruderUiContext.defaultTabGroupColor(groupName: String): Color? {
    val normalized = groupName.trim()
    if (normalized.isEmpty()) {
        return null
    }
    val index = kotlin.math.abs(normalized.hashCode()).rem(tabGroupPalette.size)
    return tabGroupPalette[index]
}

internal fun IntruderUiContext.tabGroupColor(groupName: String): Color? {
    val normalized = groupName.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return tabGroupColors[normalized] ?: defaultTabGroupColor(normalized)
}

internal fun blendTabColor(base: Color, overlay: Color?, alpha: Float): Color {
    if (overlay == null) {
        return base
    }
    val a = alpha.coerceIn(0f, 1f)
    val inverse = 1f - a
    return Color(
        (base.red * inverse + overlay.red * a).toInt().coerceIn(0, 255),
        (base.green * inverse + overlay.green * a).toInt().coerceIn(0, 255),
        (base.blue * inverse + overlay.blue * a).toInt().coerceIn(0, 255),
        base.alpha
    )
}

internal fun IntruderUiContext.isVisibleGroupHead(component: Component, groupName: String): Boolean = false

internal fun IntruderUiContext.moveTabAfterLastInGroup(component: Component, groupName: String) {
    if (groupName.isBlank()) {
        return
    }
    val originalIndex = tabOrder.indexOf(component)
    if (originalIndex < 0) {
        return
    }
    tabOrder.removeAt(originalIndex)
    val lastGroupIndex = tabOrder.indexOfLast { tabGroups[it].orEmpty() == groupName }
    // 新分组(尚无其它成员):保持该 tab 在原位,而不是挪到 tabOrder 末尾;
    // 加入已有分组时,才挪到该分组最后一名成员之后以聚合分组。
    val insertIndex = if (lastGroupIndex == -1) {
        originalIndex.coerceIn(0, tabOrder.size)
    } else {
        (lastGroupIndex + 1).coerceIn(0, tabOrder.size)
    }
    tabOrder.add(insertIndex, component)
}

internal fun IntruderUiContext.rememberTabGroupColor(groupName: String, color: Color?) {
    val normalized = groupName.trim()
    if (normalized.isEmpty()) {
        return
    }
    tabGroupColors[normalized] = color ?: defaultTabGroupColor(normalized) ?: tabAccentColor
}

internal fun IntruderUiContext.renameTabGroup(oldName: String, newName: String, color: Color?) {
    val oldNormalized = oldName.trim()
    val newNormalized = newName.trim()
    if (oldNormalized.isEmpty() || newNormalized.isEmpty()) {
        return
    }
    val existingColor = tabGroupColors[oldNormalized]
    if (!oldNormalized.equals(newNormalized, ignoreCase = false)) {
        tabGroups.keys.toList().forEach { component ->
            if (tabGroups[component].orEmpty() == oldNormalized) {
                tabGroups[component] = newNormalized
                tabHeaderStates[component]?.groupName = newNormalized
            }
        }
        if (collapsedTabGroups.remove(oldNormalized)) {
            collapsedTabGroups.add(newNormalized)
        }
        tabGroupColors.remove(oldNormalized)
    }
    tabGroupColors[newNormalized] = color ?: existingColor ?: defaultTabGroupColor(newNormalized) ?: tabAccentColor
    rebuildRequestTabBar(requestTabBar.selectedComponent)
    scheduleFuzzerTabsPersist()
}

internal fun IntruderUiContext.promptForGroupInfo(
    title: String,
    initialName: String = "",
    initialColor: Color? = null
): Pair<String, Color>? {
    val nameField = JTextField(initialName, 18)
    var selectedColor = initialColor ?: defaultTabGroupColor(initialName) ?: tabAccentColor
    val colorPreview = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = selectedColor
            g2.fillRoundRect(1, 1, width - 2, height - 2, 8, 8)
            g2.dispose()
        }
    }.apply {
        preferredSize = Dimension(42, 22)
        minimumSize = preferredSize
    }
    val chooseColorButton = JButton(I18n.t("tabs.choose_color"))
    chooseColorButton.addActionListener {
        val chosen = JColorChooser.showDialog(frame, I18n.t("tabs.choose_color"), selectedColor)
        if (chosen != null) {
            selectedColor = chosen
            colorPreview.repaint()
        }
    }
    val form = JPanel(GridBagLayout())
    val gbc = GridBagConstraints().apply {
        insets = Insets(4, 4, 4, 4)
        anchor = GridBagConstraints.WEST
        gridx = 0
        gridy = 0
    }
    form.add(JLabel(I18n.t("tabs.group_name")), gbc)
    gbc.gridx = 1
    gbc.gridwidth = 2
    gbc.fill = GridBagConstraints.HORIZONTAL
    form.add(nameField, gbc)
    gbc.gridx = 0
    gbc.gridy = 1
    gbc.gridwidth = 1
    gbc.fill = GridBagConstraints.NONE
    form.add(JLabel(I18n.t("tabs.group_color")), gbc)
    gbc.gridx = 1
    form.add(colorPreview, gbc)
    gbc.gridx = 2
    form.add(chooseColorButton, gbc)
    val result = JOptionPane.showConfirmDialog(frame, form, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
    if (result != JOptionPane.OK_OPTION) {
        return null
    }
    val name = nameField.text.trim().takeIf { it.isNotEmpty() } ?: return null
    return name to selectedColor
}

internal fun IntruderUiContext.setTabGroup(component: Component, rawGroupName: String?, color: Color? = null) {
    val groupName = rawGroupName?.trim().orEmpty()
    if (groupName.isEmpty()) {
        tabGroups.remove(component)
    } else {
        rememberTabGroupColor(groupName, color ?: tabGroupColor(groupName))
        moveTabAfterLastInGroup(component, groupName)
        tabGroups[component] = groupName
        collapsedTabGroups.remove(groupName)
    }
    tabHeaderStates[component]?.groupName = groupName
    rebuildRequestTabBar(component)
    scheduleFuzzerTabsPersist()
}
