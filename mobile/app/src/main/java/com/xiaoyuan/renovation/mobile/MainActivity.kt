package com.xiaoyuan.renovation.mobile

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xiaoyuan.renovation.mobile.ui.AppRoot
import com.xiaoyuan.renovation.mobile.ui.theme.RenovationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App 永远是深色背景，状态栏图标固定用浅色。不指定的话系统会跟随系统主题：
        // 系统是浅色模式时图标变深，落在深色背景上几乎看不见
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val container = (application as MobileApp).container
        setContent {
            RenovationTheme {
                AppRoot(container)
            }
        }
    }
}
