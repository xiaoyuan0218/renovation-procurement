package com.xiaoyuan.renovation.mobile.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

    // 服务器上已有同名清单时，先问用户是新建还是覆盖
    var uploadChoice by remember { mutableStateOf<Pair<ItemListDto, ItemListDto>?>(null) }

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

    if (state.remoteMissing && currentListId != null) {
        val name = lists.firstOrNull { it.id == currentListId }?.name.orEmpty()
        RemoteGoneDialog(
            name = name,
            onReupload = { vm.resolveRemoteMissing(true, currentListId, name); onChanged() },
            onUnbind = { vm.resolveRemoteMissing(false, currentListId, name) },
        )
    }

    val listState = rememberLazyListState()
    // 提示一出现就滚回顶部 —— 用户常停在表单或清单列表中间，提示条在屏幕外等于没提示
    LaunchedEffect(state.notice) {
        if (state.notice != null) listState.animateScrollToItem(0)
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
    ) {
        state.notice?.let { notice -> item(key = "notice") { NoticeBar(notice) } }

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
                SectionTitle("与服务器同步", caption = "每份清单各自决定要不同步")
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
                        if (busy) {
                            TagPill("处理中", color = Ink.TextSecondary)
                        } else {
                            TextButton(onClick = { vm.pullAsNew(remote.id, remote.name) }) {
                                Text("拉到本地", color = Ink.Cyan, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    uploadChoice?.let { (local, remote) ->
        UploadChoiceDialog(
            localItems = local.itemCount,
            localRooms = local.roomCount,
            remoteName = remote.name,
            remoteItems = remote.itemCount,
            remoteRooms = remote.roomCount,
            onNew = {
                vm.clearUploadChoice()
                vm.uploadAsNew(local.id, local.name)
                uploadChoice = null
            },
            onOverwrite = {
                vm.clearUploadChoice()
                vm.uploadOverwrite(local.id, remote.id, local.name)
                uploadChoice = null
            },
            onDismiss = {
                vm.clearUploadChoice()
                uploadChoice = null
            },
        )
    }

    // 状态里带着待选目标时也把它弹出来（比如同名判断是在 ViewModel 里做的）
    state.uploadChoice?.takeIf { uploadChoice == null }?.let { (local, remote) ->
        UploadChoiceDialog(
            localItems = local.itemCount,
            localRooms = local.roomCount,
            remoteName = remote.name,
            remoteItems = remote.itemCount,
            remoteRooms = remote.roomCount,
            onNew = {
                vm.clearUploadChoice()
                vm.uploadAsNew(local.id, local.name)
            },
            onOverwrite = {
                vm.clearUploadChoice()
                vm.uploadOverwrite(local.id, remote.id, local.name)
            },
            onDismiss = { vm.clearUploadChoice() },
        )
    }
}

/** 一条提示：失败红底、成功薄荷绿 —— 得让人一眼看到，不能是和说明文字同色的灰字。 */
@Composable
private fun NoticeBar(notice: SyncNotice) {
    val accent = if (notice.error) Ink.Danger else Ink.Mint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (notice.error) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = notice.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (notice.error) Ink.DangerSoft else Ink.TextPrimary,
        )
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
                        "已绑定" + lastSyncedAt.take(16).let { if (it.isBlank()) "" else " · 上次 $it" }
                    } else {
                        "纯本地 · 只在手机上"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                    maxLines = 1,
                )
            }
            TagPill(
                text = if (bound) "同步中" else "本地",
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

/** 服务器上那份清单没了（多半是在电脑上删的）。 */
@Composable
private fun RemoteGoneDialog(name: String, onReupload: () -> Unit, onUnbind: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        containerColor = Ink.BgMid,
        shape = RoundedCornerShape(22.dp),
        title = { Text("服务器上的清单不在了", color = Ink.TextPrimary) },
        text = {
            Column {
                Text(
                    text = "服务器上那份「$name」已经被删掉了（可能是在电脑上删的）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                HintText("手机上的这份还在，你想怎么办？")
            }
        },
        confirmButton = {
            TextButton(onClick = onReupload) {
                Text("重新传上去", color = Ink.Cyan, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onUnbind) {
                Text("留作纯本地", color = Ink.TextSecondary)
            }
        },
    )
}

/**
 * 服务器上已经有同名清单：把两边的规模摆出来，让用户决定是新建一份还是覆盖那一份。
 * 覆盖前服务器会自动留一份整库备份，所以选错了也救得回来。
 */
@Composable
private fun UploadChoiceDialog(
    localItems: Int,
    localRooms: Int,
    remoteName: String,
    remoteItems: Int,
    remoteRooms: Int,
    onNew: () -> Unit,
    onOverwrite: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.BgMid,
        shape = RoundedCornerShape(22.dp),
        title = { Text("服务器上已有「$remoteName」", color = Ink.TextPrimary) },
        text = {
            Column {
                Text(
                    text = "手机这份： $localItems 条物料 · $localRooms 个分组",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "服务器那份：$remoteItems 条物料 · $remoteRooms 个分组",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.TextSecondary,
                )
                Spacer(Modifier.height(14.dp))
                NeonButton(text = "新建一份", modifier = Modifier.fillMaxWidth(), onClick = onNew)
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    text = "覆盖服务器那一份",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOverwrite,
                )
                Spacer(Modifier.height(8.dp))
                HintText("新建不会动服务器上原有的数据；覆盖会丢掉上面列出的服务器数据，但服务器会自动留一份备份。")
            }
        },
        confirmButton = { },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Ink.TextSecondary)
            }
        },
    )
}
