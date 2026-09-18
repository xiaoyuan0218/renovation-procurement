package com.xiaoyuan.renovation.mobile.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyuan.renovation.mobile.ui.theme.Ink

@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    text: String = "加载中…",
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            color = Ink.Blue,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(text, color = Ink.TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    icon: ImageVector = Icons.Filled.Inbox,
    accent: Color = Ink.Indigo,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            color = Ink.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
        )
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = hint,
                color = Ink.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 连不上后端时的统一界面：说清楚连的是哪、下一步该干什么。 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    serverUrl: String? = null,
    hint: String? = null,
    onRetry: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    settingsLabel: String = "修改地址",
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassCard(corner = 20.dp, accent = Ink.Danger, padding = 20.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Ink.Danger.copy(alpha = 0.16f))
                        .border(1.dp, Ink.Danger.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (serverUrl == null) Icons.Filled.ErrorOutline else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = Ink.DangerSoft,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = message,
                    color = Ink.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (serverUrl != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = serverUrl,
                        color = Ink.Blue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
                if (hint != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = hint,
                        color = Ink.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onRetry != null) {
                        NeonButton(text = "重试", onClick = onRetry)
                    }
                    if (onOpenSettings != null) {
                        GhostButton(text = settingsLabel, onClick = onOpenSettings)
                    }
                }
            }
        }
    }
}

/** 顶部细条提示（保存成功、导入完成等）。 */
@Composable
fun InlineBanner(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Ink.Mint,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            color = Ink.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 页面头部：标题 + 一行说明，与「设置」里那些详情页同一套字号（titleLarge）。 */
@Composable
fun BrandHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Ink.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Ink.TextSecondary,
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun GradientTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier,
        color = Ink.TextPrimary,
    )
}

/** 分割线：玻璃卡片内使用。 */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Ink.Divider, Color.Transparent))),
    )
}

@Composable
fun FullScreenLoading(text: String = "加载中…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingState(text = text)
    }
}
