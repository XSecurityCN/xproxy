package org.jjgroup.xproxy.settings.core

import java.awt.Color
import javax.swing.UIManager

object UiThemePalette {
    fun isDark(): Boolean = (UIManager.get("laf.dark") as? Boolean) == true
    fun themeColor(dark: Color, light: Color): Color = if (isDark()) dark else light
    val accent: Color get() = Color(0xF58C28)
    val accentPressed: Color get() = Color(0xE47C1A)
    val accentDisabled: Color get() = themeColor(Color(0x46484E), Color(0xE1E1E1))
    val accentText: Color get() = Color(0xFFFFFF)
    val accentTextDisabled: Color get() = themeColor(Color(0x787A80), Color(0xE0E0E0))
    fun accentRgba(alpha: Int): Color = Color(accent.red, accent.green, accent.blue, alpha)
    fun primaryFill(enabled: Boolean, pressed: Boolean): Color = when {
        !enabled -> accentDisabled
        pressed -> accentPressed
        else -> accent
    }
    val secondaryFill: Color get() = themeColor(Color(0x3A3C42), Color(0xFAFAFA))
    val secondaryFillPressed: Color get() = themeColor(Color(0x48484E), Color(0xEEEEEE))
    val secondaryFillDisabled: Color get() = themeColor(Color(0x34363A), Color(0xF0F0F0))
    val secondaryBorder: Color get() = themeColor(Color(0x52545A), Color(0xBABABA))
    val secondaryText: Color get() = themeColor(Color(0xDCDEE2), Color(0x1C1C1C))
    val secondaryTextDisabled: Color get() = themeColor(Color(0x6E7076), Color(0xA5A5A5))
    val secondarySeparator: Color get() = themeColor(Color(0xFFFFFF), Color(0xC4C4C4))
    fun secondaryFill(enabled: Boolean, pressed: Boolean): Color = when {
        !enabled -> secondaryFillDisabled
        pressed -> secondaryFillPressed
        else -> secondaryFill
    }
    val tabBarBorder: Color get() = themeColor(Color(0x3A3C40), Color(0xCECED2))
    val tabSelectedBg: Color get() = themeColor(Color(0x4A4C52), Color(0xD4D4D6))
    val tabHoverBg: Color get() = themeColor(Color(0x42444A), Color(0xD8D8DA))
    val tabIdleBg: Color get() = themeColor(Color(0x383A3E), Color(0xD0D0D2))
    val tabPillBorder: Color get() = themeColor(Color(0x4A4C52), Color(0xCACACD))
    val tabSelectedText: Color
        get() = UIManager.getColor("Label.foreground") ?: themeColor(Color(0xE6E6EA), Color(0x232326))
    val tabNormalText: Color get() = themeColor(Color(0x96989E), Color(0x5C5C62))
    val tabCloseHoverFg: Color get() = themeColor(Color(0xB4B6BC), Color(0x5A5A60))
    val dockSelectedBg: Color get() = themeColor(Color(0x3C3E44), Color(0xE5E5EA))
    val dockHoverBg: Color get() = themeColor(Color(0x323438), Color(0xF2F2F7))
    val dockSelectedBorder: Color get() = themeColor(Color(0x52545A), Color(0xD0D0D6))
    val dockSelectedText: Color get() = themeColor(Color(0xE8E8EC), Color(0x2C2C2E))
    val dockIdleText: Color get() = themeColor(Color(0x96989E), Color(0x606066))
    val dockShellBorder: Color get() = themeColor(Color(0x3A3C40), Color(0xDCDCE0))
    val mutedText: Color get() = themeColor(Color(0x96989E), Color(0x6E6E6E))
    // 语义状态色(适配亮/暗主题),供运行状态等高亮复用。
    val successText: Color get() = themeColor(Color(0x4ADE80), Color(0x16A34A)) // 绿:运行中/正常
    val warningText: Color get() = themeColor(Color(0xFBBF24), Color(0xCA8A04)) // 琥珀:告警
    val dangerText: Color get() = themeColor(Color(0xF87171), Color(0xDC2626))   // 红:已停止/异常
    // 证据高亮(响应区黄底标注 agent 标记的关键片段)。半透明,叠在语法高亮之上不影响阅读。
    val evidenceHighlight: Color get() = themeColor(Color(0xFFE082), Color(0xFFF59D))
}
