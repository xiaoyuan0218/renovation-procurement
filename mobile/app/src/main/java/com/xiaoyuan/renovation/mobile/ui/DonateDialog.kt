package com.xiaoyuan.renovation.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaoyuan.renovation.mobile.R
import com.xiaoyuan.renovation.mobile.ui.design.HintText
import com.xiaoyuan.renovation.mobile.ui.design.NeonButton
import com.xiaoyuan.renovation.mobile.ui.theme.Ink

/** 联系作者的方式，和收款码一起摆出来 —— 有问题也能直接找过来。 */
const val CONTACT_LINE = "联系作者：QQ 1755445756 · 微信 CixinYan"

/** 官方来源：从别处拿到的安装包，可以照这行核对一下 */
const val OFFICIAL_LINE = "官方只在这一处发布：github.com/xiaoyuan0218/renovation-procurement"

/**
 * 每次打开时的打赏提醒：两张收款码摆在中间，左下角留一个「不再提示」。
 * 不想要就在这儿点掉，或者去「设置 → 关于」把开关关掉 —— 关掉后不再出现。
 *
 * 两处刻意不跟主界面一个风格：底色实心而不是玻璃（弹窗后面就是界面，
 * 半透明会把底下的字透上来），遮罩也压得比默认暗一些，弹窗才立得住。
 */
@Composable
fun DonateDialog(onClose: () -> Unit, onNever: () -> Unit) {
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
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ink.BgMid)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "请作者喝杯咖啡",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                HintText("如果这东西帮你省了点事，可以扫码支持一下")

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CodeCell(R.drawable.donate_wechat, "微信", Modifier.weight(1f))
                    CodeCell(R.drawable.donate_alipay, "支付宝", Modifier.weight(1f))
                }

                Spacer(Modifier.height(12.dp))
                HintText(CONTACT_LINE)
                Spacer(Modifier.height(4.dp))
                HintText(OFFICIAL_LINE)

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onNever) {
                        Text("不再提示", color = Ink.TextSecondary, fontSize = 13.sp)
                    }
                    NeonButton(text = "知道了", onClick = onClose)
                }
            }
        }
    }
}

/** 一张收款码：图 + 名字，两张并排等宽 */
@Composable
private fun CodeCell(resId: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(resId),
            contentDescription = "$label 收款码",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(text = label, fontSize = 12.sp, color = Ink.TextSecondary)
    }
}
