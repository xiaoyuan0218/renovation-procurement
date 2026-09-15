package com.xiaoyuan.renovation.ui.charts

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyuan.renovation.ui.theme.Ink
import com.xiaoyuan.renovation.util.Fmt

/* ============================================================
   数据模型
   ============================================================ */

data class DonutSlice(val label: String, val value: Double, val color: Color)

data class BarSeries(val label: String, val color: Color, val values: List<Double>)

data class BarItem(val label: String, val value: Double, val caption: String? = null, val color: Color? = null)

/* ============================================================
   环形图（对应网页版的实付构成 / 分类占比 / 采购进度）
   ============================================================ */

@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    diameter: Dp = 132.dp,
    ringWidth: Dp = 15.dp,
    centerTop: String? = null,
    centerBottom: String? = null,
) {
    val visible = slices.filter { it.value > 0 }
    val total = visible.sumOf { it.value }
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "donut-progress",
    )

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val strokePx = ringWidth.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)

            if (total <= 0) {
                drawArc(
                    color = Ink.Divider,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                return@Canvas
            }

            var start = -90f
            visible.forEach { slice ->
                val sweep = (slice.value / total).toFloat() * 360f
                val gap = if (visible.size > 1) 2.5f else 0f
                val drawn = (sweep - gap).coerceAtLeast(0.5f) * progress
                drawArc(
                    color = slice.color,
                    startAngle = start + gap / 2f,
                    sweepAngle = drawn,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (centerTop != null) {
                Text(
                    text = centerTop,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink.TextPrimary,
                    maxLines = 1,
                )
            }
            if (centerBottom != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = centerBottom,
                    fontSize = 11.sp,
                    color = Ink.TextSecondary,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 环形图 + 右侧图例：手机上比"图例在下"更省纵向空间。 */
@Composable
fun DonutWithLegend(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    centerTop: String? = null,
    centerBottom: String? = null,
    valueFormatter: (Double) -> String = { Fmt.money(it) },
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DonutChart(
            slices = slices,
            diameter = 118.dp,
            ringWidth = 14.dp,
            centerTop = centerTop,
            centerBottom = centerBottom,
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            val total = slices.sumOf { it.value }
            slices.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(slice.color),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = slice.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = valueFormatter(slice.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextPrimary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (total > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${((slice.value / total) * 100).toInt()}%",
                            fontSize = 10.sp,
                            color = Ink.TextMuted,
                        )
                    }
                }
            }
        }
    }
}

/* ============================================================
   分组柱状图（类目对比：原价 / 日常价 / 实付）
   ============================================================ */

@Composable
fun GroupedBarChart(
    categories: List<String>,
    series: List<BarSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 172.dp,
) {
    val max = series.flatMap { it.values }.maxOrNull() ?: 0.0
    val safeMax = if (max <= 0) 1.0 else max

    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(s.color),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(s.label, fontSize = 11.sp, color = Ink.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Column {
            // 三条水平参考线，给柱形一个量级参照
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))
            Spacer(Modifier.height(1.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(height),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                categories.forEachIndexed { index, category ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            series.forEach { s ->
                                val value = s.values.getOrNull(index) ?: 0.0
                                val fraction = (value / safeMax).toFloat().coerceIn(0f, 1f)
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 2.dp)
                                        .width(9.dp)
                                        .fillMaxHeight(fraction),
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(s.color, s.color.copy(alpha = 0.35f)),
                                                ),
                                            ),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = category,
                            fontSize = 10.sp,
                            color = Ink.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))
        }
    }
}

/* ============================================================
   横向条形图（房间金额分布 / 未采购 Top）
   ============================================================ */

@Composable
fun HorizontalBarChart(
    items: List<BarItem>,
    modifier: Modifier = Modifier,
    labelWidth: Dp = 64.dp,
    valueFormatter: (BarItem) -> String = { Fmt.moneyCompact(it.value) },
) {
    val max = items.maxOfOrNull { it.value } ?: 0.0
    val safeMax = if (max <= 0) 1.0 else max

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
        items.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.label,
                    fontSize = 12.sp,
                    color = Ink.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(labelWidth),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Ink.GlassFill),
                ) {
                    val fraction = (item.value / safeMax).toFloat().coerceIn(0.02f, 1f)
                    val color = item.color ?: com.xiaoyuan.renovation.ui.theme.paletteColor(index)
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.45f))),
                            ),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(74.dp)) {
                    Text(
                        text = valueFormatter(item),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink.TextPrimary,
                        maxLines = 1,
                    )
                    if (item.caption != null) {
                        Text(
                            text = item.caption,
                            fontSize = 10.sp,
                            color = Ink.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/* ============================================================
   迷你柱（统计卡里的那排小竖条）
   ============================================================ */

@Composable
fun MiniBars(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Ink.Blue,
    barWidth: Dp = 4.dp,
    height: Dp = 28.dp,
) {
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    Row(
        modifier = modifier.height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        values.forEach { v ->
            Box(
                Modifier
                    .width(barWidth)
                    .fillMaxHeight((v / max).coerceIn(0.12f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.4f)))),
            )
        }
    }
}

/** 单条进度条：已付占比这种"一个数 vs 总量"的场景。 */
@Composable
fun ThinProgressBar(
    ratio: Float,
    modifier: Modifier = Modifier,
    color: Color = Ink.Mint,
    height: Dp = 6.dp,
) {
    val animated by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Ink.GlassFill),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.55f)))),
        )
    }
}
