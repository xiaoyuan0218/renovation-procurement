package com.xiaoyuan.renovation.ui.theme

import androidx.compose.ui.graphics.Color
import com.xiaoyuan.renovation.data.model.STATUS_DONE
import com.xiaoyuan.renovation.data.model.STATUS_NONE
import com.xiaoyuan.renovation.data.model.STATUS_PARTIAL

/** 蓝色调霓虹：深海军蓝底 + 蓝/青玻璃层，保留参考图的层次与发光质感。 */
object Ink {

    /* 背景：深海军蓝 */
    val BgTop = Color(0xFF050A18)
    val BgMid = Color(0xFF081428)
    val BgBottom = Color(0xFF0A1B36)

    /* 霓虹强调色 */
    val Blue = Color(0xFF3D9BFF)
    val BlueSoft = Color(0xFF7FC0FF)
    val BlueDeep = Color(0xFF2563EB)
    val Indigo = Color(0xFF6366F1)
    val IndigoDeep = Color(0xFF4F46E5)
    val Cyan = Color(0xFF22D3EE)
    val Sky = Color(0xFF38BDF8)
    val Amber = Color(0xFFFBBF24)
    val Mint = Color(0xFF34D399)

    /* 危险 / 错误：删除、清零、断线告警。蓝色主题下必须独立于主色 */
    val Danger = Color(0xFFF87171)
    val DangerSoft = Color(0xFFFB7185)

    /* 文字：冷白与蓝灰 */
    val TextPrimary = Color(0xFFE8EEF9)
    val TextSecondary = Color(0xFF9BB0CE)
    val TextMuted = Color(0xFF5C7096)

    /* 玻璃层 */
    val GlassFill = Color(0x12FFFFFF)
    val GlassFillStrong = Color(0x1FFFFFFF)
    val GlassBorder = Color(0x26FFFFFF)
    val GlassBorderSoft = Color(0x14FFFFFF)
    val Divider = Color(0x1AFFFFFF)

    /* 渐变 */
    val PrimaryGradient = listOf(Color(0xFF2E7BFF), Color(0xFF22D3EE))
    val IndigoGradient = listOf(Color(0xFF6366F1), Color(0xFF3D9BFF))
    val CyanGradient = listOf(Color(0xFF22D3EE), Color(0xFF3D9BFF))
    val DangerGradient = listOf(Color(0xFFE11D48), Color(0xFFFB7185))
}

/** 采购状态配色：未买=蓝、部分=金、已买完=青绿、无需采购=蓝灰。 */
fun statusColor(status: String): Color = when (status) {
    STATUS_DONE -> Ink.Mint
    STATUS_PARTIAL -> Ink.Amber
    STATUS_NONE -> Ink.TextMuted
    else -> Ink.Blue
}

private val CategoryFallback = listOf(
    Color(0xFF60A5FA),
    Color(0xFF22D3EE),
    Color(0xFF818CF8),
    Color(0xFF34D399),
    Color(0xFFFBBF24),
    Color(0xFFF472B6),
)

/**
 * 类目配色：按关键词命中常见装修类目，没命中的按名称固定散列到备用色板 ——
 * 这样用户自己新增的类目也有稳定且不重复的颜色。
 */
fun categoryColor(name: String?): Color {
    val n = name.orEmpty()
    return when {
        n.isBlank() -> Ink.TextMuted
        n.contains("灯") || n.contains("照明") -> Color(0xFF60A5FA)
        n.contains("开关") || n.contains("插座") || n.contains("面板") -> Color(0xFF34D399)
        n.contains("网络") || n.contains("弱电") || n.contains("智能") -> Color(0xFFFBBF24)
        n.contains("家装") || n.contains("家具") || n.contains("软装") -> Color(0xFFF472B6)
        n.contains("卫浴") || n.contains("水") || n.contains("厨") -> Color(0xFF22D3EE)
        n.contains("五金") || n.contains("工具") -> Color(0xFFA78BFA)
        n.contains("地") || n.contains("砖") || n.contains("石") -> Color(0xFFFB923C)
        n.contains("漆") || n.contains("墙") || n.contains("涂料") -> Color(0xFF818CF8)
        else -> CategoryFallback[n.hashCode().let { if (it < 0) -it else it } % CategoryFallback.size]
    }
}

/** 图表用的循环色板：蓝色系为主，穿插几个对比色保证多分类时可区分。 */
val ChartPalette = listOf(
    Color(0xFF3D9BFF),
    Color(0xFF22D3EE),
    Color(0xFF6366F1),
    Color(0xFFFBBF24),
    Color(0xFF34D399),
    Color(0xFFF472B6),
    Color(0xFF60A5FA),
    Color(0xFF818CF8),
)

fun paletteColor(index: Int): Color = ChartPalette[index % ChartPalette.size]
