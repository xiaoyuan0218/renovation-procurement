package com.xiaoyuan.renovation.mobile.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyuan.renovation.mobile.ui.theme.Ink

/* ============================================================
   光斑背景：深紫渐变 + 四处霓虹光晕（对应参考图的氛围层）

   预渲染成一张小图，每帧只贴一次。之前是每帧现场画「一个全屏竖向渐变 +
   四个全屏径向光斑」—— 五层全屏填充，实测 GPU 每帧要 13ms 上下，
   16ms 的预算里几乎占满，滚动时就和 UI 线程抢时间、开始掉帧。
   光斑和底色都是低频画面，缩到三分之一再拉伸肉眼看不出来。
   ============================================================ */

@Composable
fun GlowBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberGlowBackdrop()
    Box(modifier.fillMaxSize()) {
        // 背景和内容做成兄弟节点：内容滚动、重组时这一层不会被牵连着重绘
        Box(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val srcSize = IntSize(backdrop.width, backdrop.height)
                    val dstSize = IntSize(
                        size.width.toInt().coerceAtLeast(1),
                        size.height.toInt().coerceAtLeast(1),
                    )
                    onDrawBehind {
                        drawImage(
                            image = backdrop,
                            srcSize = srcSize,
                            dstSize = dstSize,
                            filterQuality = FilterQuality.Low,
                        )
                    }
                },
        )
        content()
    }
}

private class GlowSpec(
    val color: Color,
    val alpha: Float,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
)

private val GLOWS = listOf(
    GlowSpec(Ink.Blue, 0.34f, 0.10f, 0.02f, 0.90f),
    GlowSpec(Ink.IndigoDeep, 0.32f, 1.00f, 0.16f, 0.80f),
    GlowSpec(Ink.Cyan, 0.16f, 0.02f, 0.94f, 0.72f),
    GlowSpec(Ink.Sky, 0.14f, 0.95f, 0.88f, 0.62f),
)

/** 画布尺寸只影响清晰度：比例与常见的 9:20 接近，拉伸到全屏看不出变形。 */
private const val BACKDROP_WIDTH = 360
private const val BACKDROP_HEIGHT = 780

@Composable
private fun rememberGlowBackdrop(): ImageBitmap = remember {
    val bitmap = ImageBitmap(BACKDROP_WIDTH, BACKDROP_HEIGHT)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = Size(BACKDROP_WIDTH.toFloat(), BACKDROP_HEIGHT.toFloat()),
    ) {
        drawRect(brush = Brush.verticalGradient(listOf(Ink.BgTop, Ink.BgMid, Ink.BgBottom)))
        GLOWS.forEach { spec ->
            val center = Offset(size.width * spec.centerX, size.height * spec.centerY)
            val radius = size.width * spec.radius
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(spec.color.copy(alpha = spec.alpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
    bitmap
}

/* ============================================================
   玻璃卡
   ============================================================ */

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    accent: Color? = null,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 渐变与形状按参数缓存：滑动长列表时若每帧重建，光是这些对象就够 GC 忙的
    val shape = remember(corner) { RoundedCornerShape(corner) }
    val fill = remember(accent) {
        if (accent == null) {
            Brush.verticalGradient(listOf(Ink.GlassFillStrong, Ink.GlassFill))
        } else {
            Brush.verticalGradient(listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.05f)))
        }
    }
    val borderColor = accent?.copy(alpha = 0.45f) ?: Ink.GlassBorder

    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** 圆角更小、内边距更紧的次级容器（列表行、子表用）。 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    padding: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(modifier = modifier, corner = corner, padding = padding, onClick = onClick, content = content)
}

/* ============================================================
   按钮
   ============================================================ */

@Composable
fun NeonButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    gradient: List<Color> = Ink.PrimaryGradient,
    fillWidth: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    val active = enabled && !loading
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .shadow(
                elevation = if (active) 16.dp else 0.dp,
                shape = shape,
                spotColor = gradient.first().copy(alpha = 0.6f),
                ambientColor = gradient.first().copy(alpha = 0.4f),
            )
            .clip(shape)
            .background(
                if (active) {
                    Brush.horizontalGradient(gradient)
                } else {
                    Brush.horizontalGradient(listOf(Ink.TextMuted.copy(alpha = 0.5f), Ink.TextMuted.copy(alpha = 0.5f)))
                },
            )
            .clickable(enabled = active, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            if (loading || icon != null) Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentColor: Color = Ink.TextPrimary,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Ink.GlassFill)
            .border(1.dp, Ink.GlassBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            color = if (enabled) contentColor else Ink.TextMuted,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
    }
}

/** 圆形玻璃图标按钮（顶栏用）。 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = Ink.TextPrimary,
    enabled: Boolean = true,
) {
    val shape = remember { CircleShape }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(shape)
            .background(Ink.GlassFill)
            .border(1.dp, Ink.GlassBorderSoft, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else Ink.TextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/* ============================================================
   标签 / 胶囊
   ============================================================ */

@Composable
fun TagPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    showDot: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = remember { RoundedCornerShape(50) }
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (filled) color.copy(alpha = 0.9f) else color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = if (filled) 1f else 0.35f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showDot) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (filled) Color.White else color),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = if (filled) Color.White else color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/* ============================================================
   标题 / 说明
   ============================================================ */

@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.verticalGradient(Ink.PrimaryGradient)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.TextPrimary,
                )
            }
            if (caption != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                    modifier = Modifier.padding(start = 11.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 统计卡副行：左侧口径名、右侧金额，用于把合计拆成几段。
 *
 * `footnotes` 里允许出现 `null`，表示**留白占位**：那一行不显示内容，但仍占一行高。
 * 同排几张卡片副行数不一致时，用留白补齐，卡片才会一样高（见 DashboardScreen 的统计卡）。
 */
data class StatFootnote(
    val label: String,
    val value: String,
    val valueColor: Color = Ink.TextPrimary,
)

/** 统计卡片：大号数字 + 说明 + 可选图形，对应参考图里的指标块。 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Color = Ink.Blue,
    valueColor: Color = Ink.TextPrimary,
    trailing: (@Composable () -> Unit)? = null,
    footnotes: List<StatFootnote?> = emptyList(),
) {
    GlassCard(modifier = modifier, corner = 20.dp, padding = 14.dp, accent = accent) {
        val density = LocalDensity.current
        // 大数字与副行的行高都钉死：字号按位数降档、副行数不同的卡片，
        // 只有行高确定，几张贴在一起时才会一样高（dp 由 sp 换算，跟着 fontScale 走）
        val valueLineHeight = 26.sp
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.height(with(density) { valueLineHeight.toDp() }),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = value,
                fontSize = when {
                    value.length >= 12 -> 17.sp
                    value.length >= 9 -> 19.sp
                    else -> 22.sp
                },
                lineHeight = valueLineHeight,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
            )
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { trailing() }
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextMuted,
                maxLines = 2,
            )
        }
        val footnoteLineHeight = 18.sp
        footnotes.forEach { f ->
            Spacer(Modifier.height(3.dp))
            if (f == null) {
                // 占位行：用全角空格而不是半角空格/固定高度 —— 只有它和中文的字形高度一致，
                // 真实副行与占位行才会一样高（半角空格只有 49px，中文是 61px）
                Text(
                    text = "\u3000",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = footnoteLineHeight,
                    maxLines = 1,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = f.label,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = footnoteLineHeight,
                        color = Ink.TextMuted,
                        maxLines = 1,
                    )
                    Text(
                        text = f.value,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = footnoteLineHeight,
                        color = f.valueColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun HintText(text: String, modifier: Modifier = Modifier, color: Color = Ink.TextSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier,
    )
}

@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Ink.TextPrimary,
    emphasize: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = key, style = MaterialTheme.typography.bodyMedium, color = Ink.TextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun LegendDot(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = Ink.TextSecondary)
    }
}
