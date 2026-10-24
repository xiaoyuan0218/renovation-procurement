package com.xiaoyuan.renovation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.data.model.LogDto
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import com.xiaoyuan.renovation.ui.common.LoadState
import com.xiaoyuan.renovation.ui.design.AppTextField
import com.xiaoyuan.renovation.ui.design.ConfirmDialog
import com.xiaoyuan.renovation.ui.design.EmptyState
import com.xiaoyuan.renovation.ui.design.ErrorState
import com.xiaoyuan.renovation.ui.design.GhostButton
import com.xiaoyuan.renovation.ui.design.GlassPanel
import com.xiaoyuan.renovation.ui.design.HintText
import com.xiaoyuan.renovation.ui.design.InlineBanner
import com.xiaoyuan.renovation.ui.design.LoadingState
import com.xiaoyuan.renovation.ui.design.TagPill
import com.xiaoyuan.renovation.ui.theme.Ink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LogsData(
    val total: Int = 0,
    val undoableId: Int? = null,
    val page: Int = 1,
    val pageSize: Int = 50,
    val items: List<LogDto> = emptyList(),
) {
    val pageCount: Int get() = if (total <= 0) 1 else (total + pageSize - 1) / pageSize
}

class LogsViewModel(private val repo: RenovationRepository) : ViewModel() {

    private val _state = MutableStateFlow<LoadState<LogsData>>(LoadState.Loading)
    val state: StateFlow<LoadState<LogsData>> = _state.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 已生效的搜索词：输入框变了不算数，点搜索/回车才算 */
    private var search = ""
    private var loading = false

    fun load(page: Int = 1) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            if (_state.value !is LoadState.Ready) _state.value = LoadState.Loading
            when (val result = repo.logs(page, query = search.ifBlank { null })) {
                is ApiResult.Ok -> _state.value = LoadState.Ready(
                    LogsData(
                        total = result.data.total,
                        undoableId = result.data.undoableId,
                        page = page,
                        items = result.data.items,
                    ),
                )

                is ApiResult.Err -> _state.value = LoadState.Failed(result.message, result.hint)
            }
            loading = false
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun applySearch() {
        search = _query.value.trim()
        reloadFrom(page = 1)
    }

    fun prevPage() {
        val data = (_state.value as? LoadState.Ready)?.data ?: return
        if (data.page > 1) reloadFrom(page = data.page - 1)
    }

    fun nextPage() {
        val data = (_state.value as? LoadState.Ready)?.data ?: return
        if (data.page < data.pageCount) reloadFrom(page = data.page + 1)
    }

    /** 回退会改数据：服务端把这条标记成已回退、可回退对象往前挪，当前页要重拉。 */
    fun undo(log: LogDto) {
        viewModelScope.launch {
            _busy.value = true
            when (val result = repo.undoLog(log.id)) {
                is ApiResult.Ok -> _message.value = result.data.message.ifBlank { "已回退" }
                is ApiResult.Err -> _message.value = result.message
            }
            val page = (_state.value as? LoadState.Ready)?.data?.page ?: 1
            reloadFrom(page = page)
            _busy.value = false
        }
    }

    /** 重拉指定页：load 的并发闸门会挡住紧接着的重拉，先把闸门放开（与 SettingsViewModel.reload 同理）。 */
    fun reloadFrom(page: Int) {
        loading = false
        load(page)
    }
}

/**
 * 操作日志：只读展示 + 对最新一条可回退的操作提供一键回退。
 * 「只撤最近一条」的约束在服务端把关，按钮该不该出现由接口的 can_undo 给出。
 */
@Composable
fun LogsPage(vm: LogsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    var pendingUndo by remember { mutableStateOf<LogDto?>(null) }

    LaunchedEffect(Unit) { vm.load() }

    SettingsPage(
        title = "操作日志",
        caption = "手机上记错的账当场就能撤，不用回家开电脑",
        onBack = onBack,
    ) {
        item {
            AppTextField(
                value = query,
                onValueChange = vm::setQuery,
                label = "搜索动作",
                placeholder = "输物料名翻出它的全部操作",
                leadingIcon = Icons.Filled.Search,
                imeAction = ImeAction.Search,
                onDone = vm::applySearch,
            )
        }

        message?.let { text ->
            item { InlineBanner(text = text, accent = Ink.Mint) }
        }

        when (val current = state) {
            is LoadState.Loading -> item { LoadingState(text = "正在读取日志…") }

            is LoadState.Failed -> item {
                ErrorState(
                    message = current.message,
                    hint = current.hint,
                    onRetry = { vm.reloadFrom(page = 1) },
                )
            }

            is LoadState.Ready -> {
                val data = current.data
                if (data.items.isEmpty()) {
                    item {
                        EmptyState(
                            title = "没有匹配的日志",
                            hint = if (query.isBlank()) null else "换个关键词试试",
                        )
                    }
                }
                items(data.items, key = { it.id }) { log ->
                    LogRow(log = log, onUndo = { pendingUndo = log })
                }
                if (data.total > data.pageSize) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GhostButton(
                                text = "上一页",
                                onClick = vm::prevPage,
                                enabled = data.page > 1 && !busy,
                            )
                            Text(
                                text = "第 ${data.page} / ${data.pageCount} 页 · 共 ${data.total} 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.TextSecondary,
                            )
                            GhostButton(
                                text = "下一页",
                                onClick = vm::nextPage,
                                enabled = data.page < data.pageCount && !busy,
                            )
                        }
                    }
                }
                item { HintText("回退只会出现在最新一条写操作上 —— 它之后又有改动时，服务端会拒绝以免把新改动一起覆盖。") }
            }
        }
    }

    pendingUndo?.let { log ->
        ConfirmDialog(
            title = "回退这条操作",
            message = "把数据放回「${log.action}」发生之前的样子。",
            confirmText = "回退",
            onConfirm = {
                pendingUndo = null
                vm.undo(log)
            },
            onDismiss = { pendingUndo = null },
        )
    }
}

@Composable
private fun LogRow(log: LogDto, onUndo: () -> Unit) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = log.action,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ink.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (log.statusCode >= 400) {
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        TagPill(text = "${log.statusCode}", color = Ink.DangerSoft)
                    }
                    if (log.undone) {
                        Spacer(Modifier.padding(horizontal = 3.dp))
                        TagPill(text = "已回退", color = Ink.TextMuted)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${log.at} · ${actorLabel(log)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (log.path.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${log.method} ${log.path}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (log.canUndo) {
                Spacer(Modifier.padding(horizontal = 3.dp))
                TagPill(text = "回退", color = Ink.Mint, onClick = onUndo)
            }
        }
    }
}

private fun actorLabel(log: LogDto): String = when {
    log.actorName.isBlank() -> log.actorKind
    log.actorKind == "api" -> "API · ${log.actorName}"
    else -> log.actorName
}
