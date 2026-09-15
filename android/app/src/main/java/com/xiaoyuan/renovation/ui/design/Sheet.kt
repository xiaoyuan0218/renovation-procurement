package com.xiaoyuan.renovation.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoyuan.renovation.ui.theme.Ink

/** 统一的底部弹层：记一笔、改布点、导入选项都用它。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.BgMid,
        contentColor = Ink.TextPrimary,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(Ink.PrimaryGradient)),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = title,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.TextPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.TextSecondary,
                )
            }
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}
