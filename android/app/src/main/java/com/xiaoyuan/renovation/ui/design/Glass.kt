package com.xiaoyuan.renovation.ui.design

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyuan.renovation.ui.theme.Ink

/* ============================================================
   光斑背景：深紫渐变 + 四处霓虹光晕（对应参考图的氛围层）
   ============================================================ */

@Composable
fun GlowBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val base = Brush.verticalGradient(listOf(Ink.BgTop, Ink.BgMid, Ink.BgBottom))
                val glows = listOf(
                    glow(Ink.Blue, 0.34f, 0.10f, 0.02f, 0.90f),
                    glow(Ink.IndigoDeep, 0.32f, 1.00f, 0.16f, 0.80f),
                    glow(Ink.Cyan, 0.16f, 0.02f, 0.94f, 0.72f),
                    glow(Ink.Sky, 0.14f, 0.95f, 0.88f, 0.62f),
                )
                onDrawBehind {
                    drawRect(brush = base)
                    glows.forEach { drawCircle(brush = it.brush, radius = it.radius, center = it.center) }
                }
            },
        content = content,
    )
}

private class Glow(val center: Offset, val radius: Float, val brush: Brush)

private fun CacheDrawScope.glow(
    color: Color,
    alpha: Float,
    centerXRatio: Float,
    centerYRatio: Float,
    radiusRatio: Float,
): Glow {
    val center = Offset(size.width * centerXRatio, size.height * centerYRatio)
    val radius = size.width.coerceAtLeast(1f) * radiusRatio
    return Glow(
        center = center,
        radius = radius,
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
    )
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
    val shape = RoundedCornerShape(corner)
    val fill = if (accent == null) {
        Brush.verticalGradient(listOf(Ink.GlassFillStrong, Ink.GlassFill))
    } else {
        Brush.verticalGradient(listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.05f)))
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
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Ink.GlassFill)
            .border(1.dp, Ink.GlassBorderSoft, CircleShape)
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
    val shape = RoundedCornerShape(50)
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
) {
    GlassCard(modifier = modifier, corner = 20.dp, padding = 14.dp, accent = accent) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = when {
                    value.length >= 12 -> 17.sp
                    value.length >= 9 -> 19.sp
                    else -> 22.sp
                },
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
