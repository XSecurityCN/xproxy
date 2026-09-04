package org.jjgroup.xproxy.ui.marking

import org.jjgroup.xproxy.settings.core.UiThemePalette
import java.awt.Color

/**
 * 流量行高亮颜色(Burp 式固定调色板)。
 *
 * 存储为枚举名串(`TrafficHighlightDao.color` 列),渲染时按当前主题取一个
 * 可读的 tint 背景,并按 tint 亮度自动选择黑/白前景,避免在任意主题下文字不可读。
 */
enum class TrafficHighlight(val apiName: String, val i18nKey: String) {
    NONE("none", "highlight.color.none"),
    RED("red", "highlight.color.red"),
    ORANGE("orange", "highlight.color.orange"),
    YELLOW("yellow", "highlight.color.yellow"),
    GREEN("green", "highlight.color.green"),
    CYAN("cyan", "highlight.color.cyan"),
    BLUE("blue", "highlight.color.blue"),
    PINK("pink", "highlight.color.pink"),
    GRAY("gray", "highlight.color.gray");

    companion object {
        /** 容错解析:未知/空 -> NONE(等价于清除高亮)。 */
        fun parse(raw: String?): TrafficHighlight {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return NONE
            return entries.firstOrNull { it.apiName == normalized }
                ?: entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
                ?: NONE
        }

        /** 可选颜色(不含 NONE,NONE 由 Clear 菜单项表达)。 */
        val colorChoices: List<TrafficHighlight> = entries.filter { it != NONE }
    }
}

/**
 * 表格行高亮渲染所需的颜色对。
 *
 * [background] 用作未选中行的底色,[backgroundSelected] 用作选中行的底色(加深以保证选中态仍可见颜色)。
 * 两者都按 [UiThemePalette.isDark] 取主题适配值。
 */
data class HighlightTint(val background: Color, val backgroundSelected: Color)

/** 按主题返回该高亮颜色的行底色(未选中 / 选中)。NONE 返回 null,表示走表格默认背景。 */
fun TrafficHighlight.tint(): HighlightTint? {
    if (this == TrafficHighlight.NONE) return null
    val dark = UiThemePalette.isDark()
    // 未选中:亮色主题用浅色 pastel(深字),暗色主题用饱和度较高的暗 tint(浅字)。
    val bg = if (dark) darkBackground() else lightBackground()
    // 选中:与默认选中色混合,既保留高亮色调又区分选中态。
    val selBase = if (dark) blend(bg, Color(75, 110, 175), 0.55f) else blend(bg, Color(160, 190, 240), 0.45f)
    return HighlightTint(background = bg, backgroundSelected = selBase)
}

/** 菜单色块图标所用颜色(饱和、醒目,与主题无关)。 */
fun TrafficHighlight.swatch(): Color = when (this) {
    TrafficHighlight.NONE -> Color(160, 160, 160)
    TrafficHighlight.RED -> Color(219, 68, 55)
    TrafficHighlight.ORANGE -> Color(214, 129, 43)
    TrafficHighlight.YELLOW -> Color(240, 190, 50)
    TrafficHighlight.GREEN -> Color(46, 160, 67)
    TrafficHighlight.CYAN -> Color(0, 150, 136)
    TrafficHighlight.BLUE -> Color(64, 132, 244)
    TrafficHighlight.PINK -> Color(219, 90, 160)
    TrafficHighlight.GRAY -> Color(120, 120, 120)
}

/** 按背景亮度返回可读前景(深底->白字,浅底->黑字)。 */
fun foregroundOn(background: Color): Color =
    if (relativeLuminance(background) > 0.45) Color(30, 30, 30) else Color(245, 245, 245)

private fun TrafficHighlight.lightBackground(): Color = when (this) {
    TrafficHighlight.RED -> Color(255, 218, 214)
    TrafficHighlight.ORANGE -> Color(255, 226, 196)
    TrafficHighlight.YELLOW -> Color(255, 244, 196)
    TrafficHighlight.GREEN -> Color(214, 240, 220)
    TrafficHighlight.CYAN -> Color(206, 240, 238)
    TrafficHighlight.BLUE -> Color(214, 228, 250)
    TrafficHighlight.PINK -> Color(250, 220, 234)
    TrafficHighlight.GRAY -> Color(232, 232, 232)
    TrafficHighlight.NONE -> Color(0, 0, 0)
}

private fun TrafficHighlight.darkBackground(): Color = when (this) {
    TrafficHighlight.RED -> Color(120, 44, 38)
    TrafficHighlight.ORANGE -> Color(118, 70, 26)
    TrafficHighlight.YELLOW -> Color(120, 98, 24)
    TrafficHighlight.GREEN -> Color(30, 84, 44)
    TrafficHighlight.CYAN -> Color(20, 82, 76)
    TrafficHighlight.BLUE -> Color(38, 70, 124)
    TrafficHighlight.PINK -> Color(120, 48, 86)
    TrafficHighlight.GRAY -> Color(64, 64, 64)
    TrafficHighlight.NONE -> Color(0, 0, 0)
}

private fun blend(base: Color, overlay: Color, alpha: Float): Color {
    val a = alpha.coerceIn(0f, 1f)
    val inv = 1f - a
    return Color(
        (base.red * inv + overlay.red * a).toInt().coerceIn(0, 255),
        (base.green * inv + overlay.green * a).toInt().coerceIn(0, 255),
        (base.blue * inv + overlay.blue * a).toInt().coerceIn(0, 255)
    )
}

private fun relativeLuminance(c: Color): Double {
    fun chan(v: Int): Double {
        val n = v / 255.0
        return if (n <= 0.03928) n / 12.92 else Math.pow((n + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * chan(c.red) + 0.7152 * chan(c.green) + 0.0722 * chan(c.blue)
}
