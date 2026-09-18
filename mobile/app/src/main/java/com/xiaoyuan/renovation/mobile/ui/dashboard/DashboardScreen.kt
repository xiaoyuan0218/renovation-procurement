package com.xiaoyuan.renovation.mobile.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.xiaoyuan.renovation.mobile.data.model.ItemDto
import com.xiaoyuan.renovation.mobile.data.model.STATUS_DONE
import com.xiaoyuan.renovation.mobile.data.model.STATUS_NONE
import com.xiaoyuan.renovation.mobile.data.model.STATUS_PARTIAL
import com.xiaoyuan.renovation.mobile.data.model.STATUS_UNBOUGHT
import com.xiaoyuan.renovation.mobile.data.model.statusLabel
import com.xiaoyuan.renovation.mobile.ui.charts.BarItem
import com.xiaoyuan.renovation.mobile.ui.charts.BarSeries
import com.xiaoyuan.renovation.mobile.ui.charts.DonutSlice
import com.xiaoyuan.renovation.mobile.ui.charts.DonutWithLegend
import com.xiaoyuan.renovation.mobile.ui.charts.GroupedBarChart
import com.xiaoyuan.renovation.mobile.ui.charts.HorizontalBarChart
import com.xiaoyuan.renovation.mobile.ui.charts.MiniBars
import com.xiaoyuan.renovation.mobile.ui.charts.ThinProgressBar
import com.xiaoyuan.renovation.mobile.ui.common.LoadState
import com.xiaoyuan.renovation.mobile.ui.design.EmptyState
import com.xiaoyuan.renovation.mobile.ui.design.ErrorState
import com.xiaoyuan.renovation.mobile.ui.design.GlassCard
import com.xiaoyuan.renovation.mobile.ui.design.GlassPanel
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.LoadingState
import com.xiaoyuan.renovation.mobile.ui.design.NeonButton
import com.xiaoyuan.renovation.mobile.ui.design.SectionTitle
import com.xiaoyuan.renovation.mobile.ui.design.StatFootnote
import com.xiaoyuan.renovation.mobile.ui.design.StatTile
import com.xiaoyuan.renovation.mobile.ui.theme.Ink
import com.xiaoyuan.renovation.mobile.ui.theme.categoryColor
import com.xiaoyuan.renovation.mobile.ui.theme.statusColor
import com.xiaoyuan.renovation.mobile.util.Fmt

/**
 * 总览：和电脑端同一套数字、同一套排布 —— 四张统计卡、三个环形图、
 * 分类对比与分组分布，最后是还没买齐的清单。
 *
 * 数字来自 [com.xiaoyuan.renovation.mobile.domain.SummaryCompute]，
 * 它与后端 `summary.py` 用同一批对照数据比对过，逐字段相等。
 */
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    refreshKey: Int,
    onGoItems: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(refreshKey) { vm.loadIfStale(refreshKey) }

    when (val current = state) {
        is LoadState.Loading -> LoadingState(text = "正在汇总…")

        is LoadState.Failed -> ErrorState(
            message = current.message,
            hint = current.hint,
            onRetry = vm::load,
        )

        is LoadState.Ready -> DashboardBody(current.data, onGoItems)
    }
}

@Composable
private fun DashboardBody(data: DashboardData, onGoItems: () -> Unit) {
    val summary = data.summary
    val totals = summary.totals

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                text = data.listName.ifBlank { "总览" },
                style = MaterialTheme.typography.titleLarge,
                color = Ink.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${totals.itemCount} 项物料 · 已买完 ${totals.boughtCount} · 待办 ${totals.pendingCount}",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextSecondary,
            )
        }

        if (totals.itemCount == 0) {
            EmptyState(
                title = "还没有物料",
                hint = "到「清单」页新增第一条，金额和进度会自动汇总到这里",
            )
            NeonButton(
                text = "去添加物料",
                modifier = Modifier.fillMaxWidth(),
                onClick = onGoItems,
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
                // 这两张卡没有可拆的数字，用留白补齐副行数，四张卡才会一样高
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
        Spacer(Modifier.height(8.dp))
        SectionTitle("实付构成", caption = "已付与未付（按原价口径）")
        GlassCard {
            DonutWithLegend(
                slices = listOf(
                    DonutSlice("已付", totals.paidTotal, Ink.Mint),
                    DonutSlice(
                        "未付",
                        (totals.listTotal - totals.paidTotal).coerceAtLeast(0.0),
                        Ink.Amber,
                    ),
                ),
                centerTop = Fmt.percentOf(totals.paidTotal, totals.listTotal),
                centerBottom = "已付占比",
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionTitle("分类占比", caption = "按日常价合计")
        GlassCard {
            val slices = summary.byCategory
                .filter { it.discountTotal > 0 }
                .map { DonutSlice(it.name, it.discountTotal, categoryColor(it.name)) }
            if (slices.isEmpty()) {
                EmptyState(title = "暂无分类金额", hint = "物料的日常价合计为 0 时不会出现在这里")
            } else {
                DonutWithLegend(
                    slices = slices,
                    centerTop = Fmt.moneyCompact(slices.sumOf { it.value }),
                    centerBottom = "日常价合计",
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionTitle("采购进度", caption = "按物料项数")
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

        /* ---------- 分类对比 ---------- */
        if (summary.byCategory.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SectionTitle("分类对比", caption = "原价 / 日常价 / 实付")
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
            Spacer(Modifier.height(8.dp))
            SectionTitle("分组金额分布", caption = "按原价口径，最多显示 8 个分组")
            GlassCard {
                HorizontalBarChart(
                    items = roomBars.map { BarItem(it.name, it.listTotal, caption = "原价") },
                )
            }
        }

        /* ---------- 未采购金额 Top ---------- */
        val topPending = summary.unbought.take(8)
        if (topPending.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SectionTitle("未采购金额 Top", caption = "优先处理金额大的")
            GlassCard {
                HorizontalBarChart(
                    items = topPending.map { BarItem(it.name, it.discountTotal, caption = "日常价") },
                )
            }
        }

        /* ---------- 额外费用：不参与上面两个口径，单独看 ---------- */
        if (summary.expensesCount > 0) {
            Spacer(Modifier.height(8.dp))
            SectionTitle("额外费用", caption = "运费/安装费这类，独立于货款")
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "合计",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = Fmt.money(summary.expensesTotal),
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink.Cyan,
                    )
                }
                Spacer(Modifier.height(8.dp))
                summary.expensesByKind.forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = kind.kind,
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = Fmt.money(kind.amount),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.TextPrimary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                HintText("共 ${summary.expensesCount} 笔，不参与上面两个口径的合计")
            }
        }

        /* ---------- 还没买齐的 ---------- */
        if (summary.unbought.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SectionTitle("还没买齐", caption = "共 ${summary.unbought.size} 条，按日常价排序")
            summary.unbought.take(6).forEach { item -> PendingRow(item) }
            if (summary.unbought.size > 6) {
                HintText("还有 ${summary.unbought.size - 6} 条，去「清单」页看全部")
            }
            NeonButton(
                text = "去处理",
                modifier = Modifier.fillMaxWidth(),
                onClick = onGoItems,
            )
        }
    }
}

@Composable
private fun PendingRow(item: ItemDto) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.TextPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${statusLabel(item.status)} · 实付 ${Fmt.qty(item.paidQty)}/${Fmt.qty(item.totalQty)}${item.unit}" +
                        listOf(item.brand, item.model)
                            .filter { it.isNotBlank() }
                            .joinToString("", prefix = " · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                    maxLines = 1,
                )
            }
            Text(
                text = Fmt.money(item.discountTotal),
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor(item.status),
            )
        }
    }
}

/** 日常价比原价省了多少 —— 两套口径的差就是"如果都按日常价买"能省的钱。 */
private fun savingCaption(listTotal: Double, discountTotal: Double): String {
    val diff = listTotal - discountTotal
    return if (diff > 0.01) "比原价省 ${Fmt.money(diff)}" else "与预算持平"
}

/** 优惠是「省下的钱」，为负说明实付价高于原价/日常价，用警示色而非隐藏。 */
private fun savingColor(amount: Double): Color = if (amount < 0) Ink.Amber else Ink.Mint
