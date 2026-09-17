package com.xiaoyuan.renovation.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.di.AppContainer
import com.xiaoyuan.renovation.ui.common.containerViewModel
import com.xiaoyuan.renovation.ui.common.dataOrNull
import com.xiaoyuan.renovation.ui.design.GlassCard
import com.xiaoyuan.renovation.ui.lists.ListsViewModel
import com.xiaoyuan.renovation.ui.theme.Ink
import com.xiaoyuan.renovation.util.Fmt

/**
 * 设置页：**只放菜单**。
 *
 * 原来把服务器、清单、分组、分类、费用、回收站、备份、账号全挤在一页里，
 * 往下翻半天才找得到，而且那么长的列表在真机上滑动是沉的。现在每一项都是
 * 一行带摘要的入口，点开才进各自整页 —— 顺带每个子页面都能只渲染可见的几项。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    listsVm: ListsViewModel,
    refreshKey: Int,
    onOpenPage: (String) -> Unit,
) {
    val vm = containerViewModel(container) { c ->
        SettingsViewModel(c.repo, c.settings, c.appContextRef)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val lists by listsVm.lists.collectAsStateWithLifecycle()
    val baseUrl by vm.baseUrl.collectAsStateWithLifecycle()
    val connectionOk by vm.connectionOk.collectAsStateWithLifecycle()
    val data = state.dataOrNull

    LaunchedEffect(refreshKey) {
        vm.load()
        listsVm.load()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "数据都在服务器上，这里管的是清单结构与备份",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                )
            }
        }

        item {
            MenuRow(
                icon = Icons.Filled.Inventory2,
                title = "清单",
                summary = if (lists.isEmpty()) "还没有清单" else "${lists.size} 份 · 点这里新建或切换",
                accent = Ink.Blue,
                onClick = { onOpenPage("lists") },
            )
        }

        item {
            MenuRow(
                icon = Icons.Filled.Category,
                title = "分组与分类",
                summary = data?.let { "${it.rooms.size} 个分组 · ${it.categories.size} 个分类" }
                    ?: "正在读取…",
                accent = Ink.Cyan,
                onClick = { onOpenPage("rooms") },
            )
        }

        item {
            MenuRow(
                icon = Icons.Filled.LocalShipping,
                title = "额外费用",
                summary = data?.let {
                    if (it.expenses.isEmpty()) {
                        "运费、安装费这类不进单价的支出"
                    } else {
                        "${it.expenses.size} 笔 · 合计 ${Fmt.money(it.expenses.sumOf { e -> e.amount })}"
                    }
                } ?: "正在读取…",
                accent = Ink.Amber,
                onClick = { onOpenPage("expenses") },
            )
        }

        item {
            MenuRow(
                icon = Icons.Filled.DeleteOutline,
                title = "回收站",
                summary = data?.let {
                    if (it.trash.isEmpty()) "空的 · 删掉的物料会先放这里" else "${it.trash.size} 条可以捞回来"
                } ?: "正在读取…",
                accent = Ink.TextSecondary,
                onClick = { onOpenPage("trash") },
            )
        }

        item {
            MenuRow(
                icon = Icons.Filled.CloudDownload,
                title = "数据备份",
                summary = "导出或导入表格文件，电脑上也读得进来",
                accent = Ink.Indigo,
                onClick = { onOpenPage("backup") },
            )
        }

        item {
            MenuRow(
                icon = Icons.Filled.Dns,
                title = "账号与服务器",
                summary = when {
                    baseUrl == null -> "还没配置服务器地址"
                    connectionOk == true -> "已连接 $baseUrl"
                    connectionOk == false -> "连不上 $baseUrl"
                    else -> baseUrl.orEmpty()
                },
                accent = Ink.Mint,
                onClick = { onOpenPage("account") },
            )
        }
    }
}

/** 菜单里的一行：图标 + 标题 + 一句摘要 + 箭头。摘要让人不用点进去就知道里面有多少东西。 */
@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    summary: String,
    accent: Color,
    onClick: () -> Unit,
) {
    val shape = remember { RoundedCornerShape(10.dp) }
    GlassCard(corner = 16.dp, padding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(shape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Ink.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
