package com.xiaoyuan.renovation.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.NeonButton
import com.xiaoyuan.renovation.mobile.ui.theme.Ink

/**
 * 第一次打开时的欢迎引导：这是什么、从哪三步上手。
 * 看过之后记在小本本里（AppPrefs.guideSeen），不再打扰。
 *
 * 底色实心、遮罩压暗：弹窗后面就是界面，半透明玻璃底会把底下的字透上来。
 */
@Composable
fun WelcomeDialog(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ink.BgMid)
                    .padding(20.dp)
                    // 小屏上内容放不下时在弹窗内部滚，别撑出屏幕
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "欢迎用采知道",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(10.dp))
                HintText(
                    "一套自己部署的装修采购账本：东西买没买齐、花了多少、还欠多少，" +
                        "都在这一个应用里。数据存在你自己的机器上，不经过任何第三方。",
                )

                Spacer(Modifier.height(16.dp))
                StepRow(1, "先把房间和类别列出来", "「设置 → 分组与分类」加客厅、主卧这些房间，以及灯具、五金这类归类。")
                Spacer(Modifier.height(12.dp))
                StepRow(2, "再往清单里加物料", "「清单」页右下角新增，填名称、总量、单价；要买在哪个房间就先写进分配。")
                Spacer(Modifier.height(12.dp))
                StepRow(3, "买完就记一笔", "点物料卡片上的状态标签，填数量金额和日期。状态会自动变成「部分已买」或「已买完」。")

                Spacer(Modifier.height(16.dp))
                HintText("底部三个页：总览看数、清单记明细、矩阵按房间排布点。「关于」和「使用说明」里还有更细的说明。")

                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeonButton(text = "开始用", onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun StepRow(index: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "$index",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.Cyan,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            HintText(body)
        }
    }
}
