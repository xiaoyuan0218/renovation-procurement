package com.xiaoyuan.renovation.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoyuan.renovation.di.AppContainer
import com.xiaoyuan.renovation.ui.common.containerViewModel
import com.xiaoyuan.renovation.ui.design.AppPasswordField
import com.xiaoyuan.renovation.ui.design.AppTextField
import com.xiaoyuan.renovation.ui.design.GlassCard
import com.xiaoyuan.renovation.ui.design.GlowBackground
import com.xiaoyuan.renovation.ui.design.HintText
import com.xiaoyuan.renovation.ui.design.InlineBanner
import com.xiaoyuan.renovation.ui.design.NeonButton
import com.xiaoyuan.renovation.ui.theme.Ink

/**
 * 登录页。后端还没有账号时同一个界面变成"创建管理员"。
 * 登录成功后 token 落盘，顶层路由会自动切到主界面 —— 这里不需要回调。
 */
@Composable
fun LoginScreen(container: AppContainer) {
    val vm = containerViewModel(container) { c -> LoginViewModel(c.repo, c.settings) }
    val state by vm.state.collectAsStateWithLifecycle()

    // 每次回到这一页都重新问一次：别处可能已经建过账号了
    LaunchedEffect(Unit) { vm.load() }

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
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "装修采购",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.BlueSoft,
                    )
                    Text(
                        text = "登录后才能查看和修改数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(34.dp))

            Text(
                text = if (state.isSetup) "先创建一个" else "欢迎回来",
                fontSize = 34.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.TextPrimary,
            )
            Text(
                text = if (state.isSetup) "管理员账号" else "装修采购清单",
                fontSize = 34.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.displaySmall.copy(
                    brush = Brush.horizontalGradient(Ink.PrimaryGradient),
                ),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = if (state.isSetup) {
                    "整个应用只有一个账号，数据都在这台服务器上。设好之后，网页端和这台手机都用它登录。"
                } else {
                    "用管理员账号登录。连续输错 5 次会暂时锁定一分钟。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.TextSecondary,
            )

            Spacer(Modifier.height(28.dp))

            GlassCard(corner = 22.dp, padding = 18.dp) {
                AppTextField(
                    value = state.username,
                    onValueChange = vm::onUsernameChange,
                    label = "用户名",
                    leadingIcon = Icons.Filled.Person,
                    imeAction = ImeAction.Next,
                    accent = Ink.Blue,
                )

                Spacer(Modifier.height(12.dp))

                AppPasswordField(
                    value = state.password,
                    onValueChange = vm::onPasswordChange,
                    label = "密码",
                    imeAction = if (state.isSetup) ImeAction.Next else ImeAction.Done,
                    supportingText = if (state.isSetup) "至少 6 位" else null,
                    onDone = { if (!state.isSetup) vm.submit() },
                )

                if (state.isSetup) {
                    Spacer(Modifier.height(12.dp))
                    AppPasswordField(
                        value = state.confirm,
                        onValueChange = vm::onConfirmChange,
                        label = "再输一次密码",
                        imeAction = ImeAction.Done,
                        onDone = vm::submit,
                    )
                }

                Spacer(Modifier.height(18.dp))

                NeonButton(
                    text = if (state.isSetup) "创建并进入" else "登录",
                    onClick = vm::submit,
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    enabled = state.canSubmit,
                    loading = state.loading,
                    fillWidth = true,
                )

                val error = state.error
                if (error != null) {
                    Spacer(Modifier.height(14.dp))
                    InlineBanner(text = error, accent = Ink.Danger)
                }
            }

            if (state.isSetup) {
                Spacer(Modifier.height(16.dp))
                HintText("账号未创建之前，局域网内先打开这个页面的人就能把它建走 —— 建议现在就把服务器地址收好。")
            }
        }
    }
}
