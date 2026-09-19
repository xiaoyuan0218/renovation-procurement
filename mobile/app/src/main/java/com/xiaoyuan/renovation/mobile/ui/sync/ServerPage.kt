package com.xiaoyuan.renovation.mobile.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.mobile.data.model.ItemListDto
import kotlinx.coroutines.delay
import com.xiaoyuan.renovation.mobile.data.sync.SideSummary
import com.xiaoyuan.renovation.mobile.data.sync.UploadChoice
import com.xiaoyuan.renovation.mobile.data.db.toLocalStamp
import com.xiaoyuan.renovation.mobile.data.sync.UploadDecision
import com.xiaoyuan.renovation.mobile.ui.design.AppPasswordField
import com.xiaoyuan.renovation.mobile.ui.design.AppTextField
import com.xiaoyuan.renovation.mobile.ui.design.ConfirmDialog
import com.xiaoyuan.renovation.mobile.ui.design.GhostButton
import com.xiaoyuan.renovation.mobile.ui.design.GlassPanel
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.NeonButton
import com.xiaoyuan.renovation.mobile.ui.design.SectionTitle
import com.xiaoyuan.renovation.mobile.ui.design.TagPill
import com.xiaoyuan.renovation.mobile.ui.settings.SettingsPage
import com.xiaoyuan.renovation.mobile.ui.theme.Ink

/**
 * 「服务器」整页：登录、把本地清单传上去、把服务器上的清单拿下来、以及已绑定清单的双向同步。
 *
 * 冲突不擅自决定：两边都改过同一行时列出来，让用户选以哪边为准。
 */
@Composable
fun ServerPage(
    vm: SyncViewModel,
    lists: List<ItemListDto>,
    currentListId: Int?,
    onChanged: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy = state.busy

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) vm.loadRemoteLists()
    }

    state.conflicts.takeIf { it.isNotEmpty() }?.let { conflicts ->
        ConflictDialog(
            count = conflicts.size,
            first = conflicts.first().description,
            onPreferLocal = { vm.resolveConflicts(preferLocal = true); onChanged() },
            onPreferRemote = { vm.resolveConflicts(preferLocal = false); onChanged() },
        )
    }

    // 上传撞上服务器上已有的同一份、且两边从没同步过：覆盖还是合并，让用户定
    state.uploadDecision?.let { decision ->
        val current = lists.firstOrNull { it.id == currentListId } ?: lists.firstOrNull()
        UploadDecisionDialog(
            listName = current?.name.orEmpty(),
            decision = decision,
            onChoose = { choice ->
                val listId = current?.id
                if (listId != null) {
                    vm.resolveUpload(listId, choice)
                    onChanged()
                }
            },
            onDismiss = vm::dismissUploadDecision,
        )
    }

    val listState = rememberLazyListState()
    // 提示停留几秒后自动清掉：不清的话"连不上服务器"这类旧提示会一直挂在
    // 页面顶部，用户早就连上了它还在，反而误导。
    // 不用再滚回顶部了 —— 提示条是浮在内容上的，用户停在哪一段都看得见。
    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            if (notice.error) {
                delay(8_000)
            } else {
                delay(4_000)
            }
            vm.consumeMessage()
        }
    }
    // 上传/拉取/同步成功 → 通知外层刷新：清单列表、看板、下拉里的条目数都要跟着变，
    // 不通知的话新拉下来的清单要重启 App 才看得见
    LaunchedEffect(state.changed) {
        if (state.changed > 0) onChanged()
    }

    SettingsPage(
        title = "服务器",
        caption = if (state.loggedIn) {
            "${state.url} · ${state.username}"
        } else {
            "可选：连上之后手机和电脑能互相同步；不连就一直当本地应用用"
        },
        onBack = onBack,
        listState = listState,
        // 提示条交给外壳做成浮层（叠在内容上），插进列表会把整页顶下去
        notice = state.notice?.text,
        noticeIsError = state.notice?.error == true,
    ) {
        if (!state.loggedIn) {
            item { LoginForm(busy = busy, initialUrl = state.url, onSubmit = vm::login) }
        } else {
            item {
                GlassPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = state.url.ifBlank { "（未填地址）" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink.TextPrimary,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(3.dp))
                            HintText("账号 ${state.username}")
                        }
                        TagPill("已连接", color = Ink.Mint)
                    }
                }
            }
            item {
                GhostButton(
                    text = "退出登录",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = vm::logout,
                )
            }
        }

        if (state.loggedIn && lists.isNotEmpty()) {
            item {
                SectionTitle("与服务器同步", caption = "「同步」是双向的：手机上的改动传上去，电脑上的改动拉下来")
            }
            items(lists, key = { "local-${it.id}" }) { list ->
                val binding = state.bindings[list.id]
                SyncListRow(
                    list = list,
                    bound = binding != null,
                    lastSyncedAt = binding?.lastSyncedAt.orEmpty(),
                    busy = busy,
                    onUpload = { vm.uploadRequested(list) },
                    onSync = { vm.syncNow(list.id) },
                    onUnbind = { vm.unbind(list.id) },
                )
            }
        }

        if (state.loggedIn && state.remoteLists.isNotEmpty()) {
            item {
                Spacer(Modifier.height(6.dp))
                SectionTitle(
                    "服务器上的清单",
                    caption = "共 ${state.remoteLists.size} 份 · 拉到本地之后就能双向同步",
                )
            }
            items(state.remoteLists, key = { "remote-${it.id}" }) { remote ->
                GlassPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = remote.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Ink.TextPrimary,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = remote.code.ifBlank { "" }
                                    .let { if (it.isEmpty()) "" else "$it · " } +
                                    "${remote.itemCount} 条物料 · ${remote.roomCount} 个分组",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.TextSecondary,
                                maxLines = 1,
                            )
                        }
                        // 忙碌时不要换成 TagPill：胶囊比 TextButton 矮一大截
                        // （约 22dp vs Material3 的 40dp 最小高度），卡片会跟着缩水，
                        // 整屏内容往上跳一下（实测按钮下移 133px、卡片矮 37px）。
                        // 保持同一个按钮、只换文字与禁用态，高度天然恒定。
                        TextButton(
                            onClick = { vm.pullAsNew(remote.id, remote.name) },
                            enabled = !busy,
                        ) {
                            Text(
                                text = if (busy) "处理中" else "拉到本地",
                                color = if (busy) Ink.TextSecondary else Ink.Cyan,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginForm(
    busy: Boolean,
    initialUrl: String,
    onSubmit: (String, String, String) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppTextField(
            value = url,
            onValueChange = { url = it },
            label = "服务器地址",
            placeholder = "192.168.1.9:8000",
            supportingText = "和网页版用的是同一个地址",
        )
        AppTextField(value = username, onValueChange = { username = it }, label = "账号")
        AppPasswordField(value = password, onValueChange = { password = it }, label = "密码")
        NeonButton(
            text = "连接",
            modifier = Modifier.fillMaxWidth(),
            enabled = url.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            loading = busy,
            onClick = { onSubmit(url, username, password) },
        )
        HintText("连上之后，这份清单可以传上去让电脑也能看；也可以把服务器上的清单拉到这台手机上。")
    }
}

@Composable
private fun SyncListRow(
    list: ItemListDto,
    bound: Boolean,
    lastSyncedAt: String,
    busy: Boolean,
    onUpload: () -> Unit,
    onSync: () -> Unit,
    onUnbind: () -> Unit,
) {
    var confirmUnbind by remember { mutableStateOf(false) }

    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = list.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink.TextPrimary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = list.code.ifBlank { "" }
                        .let { if (it.isEmpty()) "" else "$it · " } + if (bound) {
                        "已绑定" + toLocalStamp(lastSyncedAt).take(16).let { if (it.isBlank()) "" else " · 上次 $it" }
                    } else {
                        "纯本地 · 只在手机上"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                    maxLines = 1,
                )
            }
            TagPill(
                // 「同步中」容易被当成"正在同步"，实际表示这份清单两头都会同步
                text = if (bound) "双向同步" else "本地",
                color = if (bound) Ink.Mint else Ink.TextSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (bound) {
                TextButton(onClick = onSync, enabled = !busy) {
                    Text("立即同步", color = Ink.Cyan, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { confirmUnbind = true }, enabled = !busy) {
                    Text("解除绑定", color = Ink.TextSecondary)
                }
            } else {
                TextButton(onClick = onUpload, enabled = !busy) {
                    Text("上传到服务器", color = Ink.Cyan, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (confirmUnbind) {
        ConfirmDialog(
            title = "解除绑定",
            message = "「${list.name}」会变回纯本地清单，服务器上那一份原样留着、不受影响。",
            confirmText = "解除",
            onConfirm = {
                onUnbind()
                confirmUnbind = false
            },
            onDismiss = { confirmUnbind = false },
        )
    }
}

/** 两边都改过同一行：不擅自决定，问用户听谁的。 */
@Composable
private fun ConflictDialog(
    count: Int,
    first: String,
    onPreferLocal: () -> Unit,
    onPreferRemote: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { },
        containerColor = Ink.BgMid,
        shape = RoundedCornerShape(22.dp),
        title = { Text("有 $count 处两边都改了", color = Ink.TextPrimary) },
        text = {
            Column {
                Text(
                    text = "同步时发现手机和电脑改过同一处，没法自动合并。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                HintText("比如：$first")
                Spacer(Modifier.height(6.dp))
                HintText("选以哪边为准，另一边的这次改动会被覆盖。")
            }
        },
        confirmButton = {
            TextButton(onClick = onPreferLocal) {
                Text("以手机为准", color = Ink.Cyan, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onPreferRemote) {
                Text("以电脑为准", color = Ink.TextSecondary)
            }
        },
    )
}

/**
 * 上传时认出服务器上已有同一份、但两边从没同步过：该覆盖还是合并，得用户定。
 *
 * 这种情况没有"正确答案"：两边各自都可能是用户真实的数据，谁覆盖谁都会丢东西。
 * 所以把两边各有多少内容、最后什么时候动的摆出来，让用户自己看 —— 判不出来
 * 就不替用户猜（宁可不猜，见项目里一贯的口径）。
 */
@Composable
private fun UploadDecisionDialog(
    listName: String,
    decision: UploadDecision,
    onChoose: (UploadChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    // 内容里最新的那个修改时间更新的一边，标出来 —— 大多数时候用户就是照它选
    val remoteNewer = decision.remote.lastChangedAt.isNotBlank() &&
        decision.remote.lastChangedAt > decision.local.lastChangedAt

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.BgMid,
        shape = RoundedCornerShape(22.dp),
        title = { Text("服务器上已有「$listName」", color = Ink.TextPrimary) },
        text = {
            Column {
                Text(
                    text = "编号相同，说明是同一份清单，但两台设备从没同步过 —— 没法自动判断该留谁的。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(12.dp))

                SideRow(
                    title = "这台手机",
                    summary = decision.local,
                    highlight = !remoteNewer && decision.local.lastChangedAt.isNotBlank(),
                )
                Spacer(Modifier.height(6.dp))
                SideRow(
                    title = "服务器（电脑）",
                    summary = decision.remote,
                    highlight = remoteNewer,
                )

                Spacer(Modifier.height(12.dp))
                HintText("选一种处理方式：")
                Spacer(Modifier.height(8.dp))

                ChoiceRow(
                    title = "两边合并（推荐）",
                    caption = "都留着：同名的取较新的那份，各自独有的都保留",
                    onClick = { onChoose(UploadChoice.MergeBoth) },
                )
                Spacer(Modifier.height(6.dp))
                ChoiceRow(
                    title = "用手机上的覆盖",
                    caption = "服务器那份换成手机的，电脑上的改动会丢掉",
                    onClick = { onChoose(UploadChoice.OverwriteRemote) },
                )
                Spacer(Modifier.height(6.dp))
                ChoiceRow(
                    title = "用电脑上的覆盖",
                    caption = "手机这份换成服务器的，手机上的改动会丢掉",
                    onClick = { onChoose(UploadChoice.KeepRemote) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("先不处理", color = Ink.TextSecondary)
            }
        },
    )
}

/** 一边的规模：多少物料/分组/分类、最后什么时候动的。 */
@Composable
private fun SideRow(title: String, summary: SideSummary, highlight: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.GlassFill)
            .border(
                1.dp,
                if (highlight) Ink.Cyan.copy(alpha = 0.5f) else Ink.Divider,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Ink.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                if (highlight) {
                    Spacer(Modifier.width(8.dp))
                    TagPill("更新", color = Ink.Cyan)
                }
            }
            Spacer(Modifier.height(3.dp))
            HintText(
                buildString {
                    append("${summary.items} 条物料 · ${summary.rooms} 个分组")
                    if (summary.lastChangedAt.isNotBlank()) {
                        append(" · 最后改动 ${toLocalStamp(summary.lastChangedAt).take(16)}")
                    }
                },
            )
        }
    }
}

/** 一个可点的处理方式。 */
@Composable
private fun ChoiceRow(title: String, caption: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Ink.Blue.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(title, color = Ink.Cyan, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        HintText(caption)
    }
}
