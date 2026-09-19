package com.xiaoyuan.renovation.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaoyuan.renovation.mobile.ui.design.FloatingNotice
import com.xiaoyuan.renovation.mobile.ui.design.GlassIconButton
import com.xiaoyuan.renovation.mobile.ui.theme.Ink

/**
 * 设置里那些子页面的统一外壳：顶部返回栏 + 一份可以懒加载的内容区。
 *
 * 内容用 [LazyColumn] 而不是整片滚动：分组、分类、费用这些列表在真机上
 * 一多就沉，只渲染看得见的那几项之后滑动才是稳的。
 */
@Composable
fun SettingsPage(
    title: String,
    caption: String? = null,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    /**
     * 页内提示条（成功薄荷绿 / 失败红）。写操作的反馈必须让用户看见 ——
     * 比如"分类下面还挂着物料，删不掉"这类拒绝，从前只在 ViewModel 里
     * 转一圈就没了，用户只看到点了没反应。
     */
    notice: String? = null,
    noticeIsError: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    // 全屏独立页面（不在 AppShell 的顶栏下面），要自己让开状态栏/刘海，
    // 否则标题和返回按钮会被顶到刘海底下 —— 点不到也看不清。
    // 页眉不画背景：直接浮在渐变上，与主界面顶部栏同一效果
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(
                icon = Icons.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
            )
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink.TextPrimary,
                )
                if (caption != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextSecondary,
                    )
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )

            // 提示条浮在列表之上：用 Box 叠上去而不是插进列表，出现与消失时
            // 下面的内容一动不动（插进布局流会把整页往下顶，看着就是抖一下）
            if (notice != null) {
                FloatingNotice(
                    text = notice,
                    accent = if (noticeIsError) Ink.DangerSoft else Ink.Mint,
                    isError = noticeIsError,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )
            }
        }
    }
}
