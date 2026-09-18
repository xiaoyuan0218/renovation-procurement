package com.xiaoyuan.renovation.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.data.model.ItemDto
import com.xiaoyuan.renovation.data.model.STATUS_DONE
import com.xiaoyuan.renovation.data.model.STATUS_NONE
import com.xiaoyuan.renovation.data.model.STATUS_PARTIAL
import com.xiaoyuan.renovation.data.model.STATUS_UNBOUGHT
import com.xiaoyuan.renovation.data.model.SummaryDto
import com.xiaoyuan.renovation.data.model.statusLabel
import com.xiaoyuan.renovation.ui.charts.BarItem
import com.xiaoyuan.renovation.ui.charts.BarSeries
import com.xiaoyuan.renovation.ui.charts.DonutSlice
import com.xiaoyuan.renovation.ui.charts.DonutWithLegend
import com.xiaoyuan.renovation.ui.charts.GroupedBarChart
import com.xiaoyuan.renovation.ui.charts.HorizontalBarChart
import com.xiaoyuan.renovation.ui.charts.MiniBars
import com.xiaoyuan.renovation.ui.charts.ThinProgressBar
import com.xiaoyuan.renovation.ui.common.LoadState
import com.xiaoyuan.renovation.ui.design.BrandHeader
import com.xiaoyuan.renovation.ui.design.EmptyState
import com.xiaoyuan.renovation.ui.design.ErrorState
import com.xiaoyuan.renovation.ui.design.GlassCard
import com.xiaoyuan.renovation.ui.design.GlassDivider
import com.xiaoyuan.renovation.ui.design.GlassIconButton
import com.xiaoyuan.renovation.ui.design.LoadingState
import com.xiaoyuan.renovation.ui.design.SectionTitle
import com.xiaoyuan.renovation.ui.design.StatFootnote
import com.xiaoyuan.renovation.ui.design.StatTile
import com.xiaoyuan.renovation.ui.design.TagPill
import com.xiaoyuan.renovation.ui.theme.Ink
import com.xiaoyuan.renovation.ui.theme.categoryColor
import com.xiaoyuan.renovation.ui.theme.statusColor
import com.xiaoyuan.renovation.util.Fmt

@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    serverUrl: String,
    refreshKey: Int,
    onGoItems: () -> Unit,
    onOpenServerSettings: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(refreshKey) { vm.load() }

    when (val current = state) {
        is LoadState.Loading -> LoadingState(text = "正在汇总…")

        is LoadState.Failed -> ErrorState(
            message = current.message,
            serverUrl = serverUrl,
            hint = current.hint,
            onRetry = vm::load,
            onOpenSettings = onOpenServerSettings,
        )

        is LoadState.Ready -> DashboardContent(
            summary = current.data,
            onRefresh = vm::load,
            onGoItems = onGoItems,
        )
    }
}

@Composable
private fun DashboardContent(
    summary: SummaryDto,
    onRefresh: () -> Unit,
    onGoItems: () -> Unit,
) {
    val totals = summary.totals

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        BrandHeader(
            title = "总览",
            subtitle = "共 ${totals.itemCount} 项物料 · 未买齐 ${totals.pendingCount} 项",
            trailing = {
                GlassIconButton(
                    icon = Icons.Filled.Refresh,
                    onClick = onRefresh,
                    contentDescription = "刷新",
                )
            },
        )

        Spacer(Modifier.height(18.dp))

        if (totals.itemCount == 0) {
            EmptyState(
                title = "还没有物料",
                hint = "到「清单」页新增第一条，或在「设置」里导入现成的表格",
            )
            return@Column
        }

        /* ---------- 四张统计卡（等高，避免长金额把卡片撑得不齐） ---------- */
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatTile(
                label = "预算原价合计",
                value = Fmt.money(totals.listTotal),
                accent = Ink.Blue,
                caption = "${totals.itemCount} 项物料 · ${totals.pendingCount} 项未买齐",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                // 两张合计卡没有可拆的数字，用留白补齐副行数，四张卡才会一样高
                footnotes = listOf(null, null),
            )
            StatTile(
                label = "日常价合计",
                value = Fmt.money(totals.discountTotal),
                accent = Ink.Indigo,
                caption = savingCaption(totals.listTotal, totals.discountTotal),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                footnotes = listOf(null, null),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatTile(
                label = "已付",
                value = Fmt.money(totals.paidTotal),
                accent = Ink.Mint,
                valueColor = Ink.Amber,
                caption = "占预算 ${Fmt.percentOf(totals.paidTotal, totals.listTotal)}",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                trailing = {
                    MiniBars(
                        values = listOf(
                            totals.countOf(STATUS_DONE).toFloat(),
                            totals.countOf(STATUS_PARTIAL).toFloat(),
                            totals.countOf(STATUS_UNBOUGHT).toFloat(),
                        ),
                        color = Ink.Mint,
                    )
                },
                footnotes = listOf(
                    StatFootnote(
                        label = "实际优惠",
                        value = Fmt.money(totals.actualDiscountTotal),
                        valueColor = savingColor(totals.actualDiscountTotal),
                    ),
                    StatFootnote(
                        label = "日常价优惠",
                        value = Fmt.money(totals.dailyDiscountTotal),
                        valueColor = savingColor(totals.dailyDiscountTotal),
                    ),
                ),
            )
            StatTile(
                label = "未付",
                value = Fmt.money(totals.unpaidTotal),
                accent = Ink.Amber,
                caption = "未买清单 ${totals.pendingCount} 项",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                footnotes = listOf(
                    StatFootnote(
                        label = "日常价未付",
                        value = Fmt.money(totals.dailyUnpaidTotal),
                        valueColor = Ink.Cyan,
                    ),
                    null,
                ),
            )
        }

        Spacer(Modifier.height(14.dp))
        GlassCard(corner = 18.dp, padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "采购进度",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    // 按项数：已买齐的项数占比。金额进度会被单件贵的带偏，
                    // 项数更贴近"还剩几件事要办"
                    text = Fmt.percent(totals.boughtRatioByCount),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink.Amber,
                )
            }
            Spacer(Modifier.height(10.dp))
            ThinProgressBar(ratio = totals.boughtRatioByCount, color = Ink.Mint)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "已买齐 ${totals.boughtCount} / ${totals.itemCount} 项",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextSecondary,
            )
        }

        /* ---------- 三个环形图 ---------- */
        Spacer(Modifier.height(22.dp))
        SectionTitle("实付构成", caption = "已付与未付（按原价口径）")
        Spacer(Modifier.height(12.dp))
        GlassCard {
            DonutWithLegend(
                slices = listOf(
                    DonutSlice("已付", totals.paidTotal, Ink.Mint),
                    DonutSlice("未付", (totals.listTotal - totals.paidTotal).coerceAtLeast(0.0), Ink.Amber),
                ),
                centerTop = Fmt.percentOf(totals.paidTotal, totals.listTotal),
                centerBottom = "已付占比",
            )
        }

        Spacer(Modifier.height(22.dp))
        SectionTitle("分类占比", caption = "按日常价合计")
        Spacer(Modifier.height(12.dp))
        GlassCard {
            val categorySlices = summary.byCategory
                .filter { it.discountTotal > 0 }
                .map { DonutSlice(it.name, it.discountTotal, categoryColor(it.name)) }
            if (categorySlices.isEmpty()) {
                EmptyState(title = "暂无分类金额", hint = "物料的日常价合计为 0 时不会出现在这里")
            } else {
                DonutWithLegend(
                    slices = categorySlices,
                    centerTop = Fmt.moneyCompact(categorySlices.sumOf { it.value }),
                    centerBottom = "日常价合计",
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionTitle("采购进度", caption = "按物料项数")
        Spacer(Modifier.height(12.dp))
        GlassCard {
            DonutWithLegend(
                slices = listOf(
                    DonutSlice("已买完", totals.countOf(STATUS_DONE).toDouble(), Ink.Mint),
                    DonutSlice("部分已买", totals.countOf(STATUS_PARTIAL).toDouble(), Ink.Amber),
                    DonutSlice("未买", totals.countOf(STATUS_UNBOUGHT).toDouble(), Ink.Blue),
                    DonutSlice("无需采购", totals.countOf(STATUS_NONE).toDouble(), Ink.TextMuted),
                ),
                centerTop = "${totals.countOf(STATUS_DONE)}/${totals.itemCount}",
                centerBottom = "已买完",
                valueFormatter = { Fmt.qty(it) + " 项" },
            )
        }

        /* ---------- 分类对比柱状图 ---------- */
        if (summary.byCategory.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionTitle("分类对比", caption = "原价 / 日常价 / 实付")
            Spacer(Modifier.height(12.dp))
            GlassCard {
                val cats = summary.byCategory.sortedByDescending { it.listTotal }.take(6)
                GroupedBarChart(
                    categories = cats.map { it.name },
                    series = listOf(
                        BarSeries("原价", Ink.Indigo, cats.map { it.listTotal }),
                        BarSeries("日常价", Ink.Cyan, cats.map { it.discountTotal }),
                        BarSeries("实付", Ink.Mint, cats.map { it.paidTotal }),
                    ),
                )
            }
        }

        /* ---------- 分组金额分布 ---------- */
        val roomBars = summary.byRoom
            .filter { it.listTotal > 0 }
            .sortedByDescending { it.listTotal }
            .take(8)
        if (roomBars.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionTitle("分组金额分布", caption = "按原价口径，最多显示 8 个分组")
            Spacer(Modifier.height(12.dp))
            GlassCard {
                HorizontalBarChart(
                    items = roomBars.map {
                        BarItem(
                            label = it.name,
                            value = it.listTotal,
                            caption = "${Fmt.qty(it.qty)} 件",
                            color = Ink.Indigo,
                        )
                    },
                )
            }
        }

        /* ---------- 未采购金额 Top ---------- */
        val unboughtTop = summary.unbought.take(6)
        if (unboughtTop.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionTitle("未采购金额 Top", caption = "优先处理金额大的")
            Spacer(Modifier.height(12.dp))
            GlassCard {
                HorizontalBarChart(
                    items = unboughtTop.map {
                        BarItem(
                            label = it.name,
                            value = it.discountTotal,
                            caption = "待买 ${Fmt.qtyUnit(it.unpaidQty, it.unit)}",
                            color = statusColor(it.status),
                        )
                    },
                )
            }
        }

        /* ---------- 未采购清单 ---------- */
        if (summary.unbought.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            SectionTitle(
                title = "未采购清单",
                caption = "共 ${summary.unbought.size} 项待买齐",
                trailing = {
                    TagPill(text = "去处理", color = Ink.Blue, filled = true, onClick = onGoItems)
                },
            )
            Spacer(Modifier.height(12.dp))
            GlassCard(padding = 0.dp) {
                summary.unbought.take(8).forEachIndexed { index, item ->
                    if (index > 0) {
                        GlassDivider(Modifier.padding(horizontal = 14.dp))
                    }
                    UnboughtRow(item)
                }
                if (summary.unbought.size > 8) {
                    GlassDivider(Modifier.padding(horizontal = 14.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TagPill(
                            text = "查看全部 ${summary.unbought.size} 项",
                            color = Ink.Indigo,
                            onClick = onGoItems,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UnboughtRow(item: ItemDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(8.dp))
                TagPill(text = statusLabel(item.status), color = statusColor(item.status))
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    append(item.categoryName ?: "未分类")
                    append(" · ")
                    if (item.status == STATUS_PARTIAL) {
                        append("差 ${Fmt.qty(item.unpaidQty)}${item.unit}")
                    } else {
                        append("${Fmt.qty(item.totalQty)}${item.unit}")
                    }
                    if (item.note.isNotBlank()) append(" · ${item.note}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextMuted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = Fmt.money(item.discountTotal),
            style = MaterialTheme.typography.bodyLarge,
            color = Ink.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun savingCaption(listTotal: Double, discountTotal: Double): String {
    val diff = listTotal - discountTotal
    return if (diff > 0.01) "比原价省 ${Fmt.money(diff)}" else "与预算持平"
}

/** 优惠是「省下的钱」，为负说明实付价高于原价/日常价，用警示色而非隐藏。 */
private fun savingColor(amount: Double): Color = if (amount < 0) Ink.Amber else Ink.Mint
