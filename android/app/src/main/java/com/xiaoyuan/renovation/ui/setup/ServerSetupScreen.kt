package com.xiaoyuan.renovation.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.di.AppContainer
import com.xiaoyuan.renovation.domain.AddressParse
import com.xiaoyuan.renovation.ui.common.containerViewModel
import com.xiaoyuan.renovation.ui.design.AppTextField
import com.xiaoyuan.renovation.ui.design.GhostButton
import com.xiaoyuan.renovation.ui.design.GlassCard
import com.xiaoyuan.renovation.ui.design.GlassIconButton
import com.xiaoyuan.renovation.ui.design.GlowBackground
import com.xiaoyuan.renovation.ui.design.HintText
import com.xiaoyuan.renovation.ui.design.InlineBanner
import com.xiaoyuan.renovation.ui.design.ConfirmDialog
import com.xiaoyuan.renovation.ui.design.NeonButton
import com.xiaoyuan.renovation.ui.theme.Ink

/**
 * 服务器配置页：首次启动的引导，也是设置页里"修改地址"的入口。
 * 地址规则对用户完全可见 —— 输入时实时回显最终会连接的地址。
 */
@Composable
fun ServerSetupScreen(
    container: AppContainer,
    currentAddress: String?,
    onSaved: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val vm = containerViewModel(container) { c -> ServerSetupViewModel(c.repo, c.settings) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(currentAddress) { vm.prefill(currentAddress) }

    GlowBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    GlassIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = onBack,
                        contentDescription = "返回",
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (onBack == null) "装修采购" else "服务器地址",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.BlueSoft,
                    )
                    Text(
                        text = if (onBack == null) "自托管 · 局域网内可用" else "改完立刻生效，无需重装",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextSecondary,
                    )
                }
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Ink.Blue, Ink.IndigoDeep))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("装", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(34.dp))

            if (onBack == null) {
                Text(
                    text = "把整张采购清单",
                    fontSize = 34.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink.TextPrimary,
                )
                Text(
                    text = "装进手机里",
                    fontSize = 34.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.displaySmall.copy(
                        brush = Brush.horizontalGradient(Ink.PrimaryGradient),
                    ),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "填一次服务器地址，之后随手改数量、记付款、看进度 —— " +
                        "数据还在你自己机器的 SQLite 里，手机只是换了个入口。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.TextSecondary,
                )
                Spacer(Modifier.height(28.dp))
            }

            GlassCard(corner = 22.dp, padding = 18.dp) {
                AppTextField(
                    value = state.input,
                    onValueChange = vm::onInputChange,
                    label = "后端地址",
                    placeholder = "192.168.1.9:8000",
                    leadingIcon = Icons.Filled.Dns,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                    isError = state.parse is AddressParse.Invalid,
                    supportingText = when (val p = state.parse) {
                        AddressParse.Empty ->
                            "省略 http:// 和端口都行，默认按 8000 处理"
                        is AddressParse.Invalid -> p.reason
                        is AddressParse.Ok -> "将连接到 ${p.normalized}"
                    },
                    accent = Ink.Blue,
                    onDone = { if (state.canSubmit) vm.test() },
                )

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(
                        text = if (state.testing) "连接中…" else "测试连接",
                        onClick = vm::test,
                        icon = Icons.Filled.MonitorHeart,
                        enabled = state.canSubmit,
                    )
                    NeonButton(
                        text = "保存并进入",
                        onClick = { vm.save { onSaved() } },
                        icon = Icons.Filled.Save,
                        enabled = state.canSubmit,
                        loading = state.saving,
                    )
                }

                val message = state.testMessage
                if (message != null) {
                    Spacer(Modifier.height(14.dp))
                    InlineBanner(
                        text = message,
                        accent = if (state.testOk) Ink.Mint else Ink.Danger,
                    )
                    if (!state.testOk) {
                        Spacer(Modifier.height(6.dp))
                        HintText("检查一下手机是否和服务器在同一个 WiFi，以及服务是否已启动。")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            GlassCard(corner = 20.dp, padding = 16.dp, accent = Ink.IndigoDeep) {
                Text(
                    text = "地址从哪来",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(10.dp))
                SetupTip("1", "在电脑上启动服务后，浏览器地址栏里那一串就是，例如 http://192.168.1.9:8000")
                SetupTip("2", "手机要和运行服务的电脑在同一个 WiFi 下")
                SetupTip("3", "模拟器里调试本机服务，用 10.0.2.2:8000")
            }
        }
    }

    if (state.askSaveAnyway) {
        ConfirmDialog(
            title = "还没测试通过",
            message = "当前地址没有连接成功，保存后可能打不开数据。仍要保存吗？",
            confirmText = "仍要保存",
            onConfirm = { vm.save { onSaved() } },
            onDismiss = vm::dismissSaveAnyway,
        )
    }
}

@Composable
private fun SetupTip(index: String, text: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Ink.Indigo.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(index, fontSize = 10.sp, color = Ink.Indigo, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Ink.TextSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}
