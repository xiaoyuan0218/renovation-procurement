package com.xiaoyuan.renovation.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xiaoyuan.renovation.mobile.ui.AppRoot
import com.xiaoyuan.renovation.mobile.ui.theme.RenovationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as MobileApp).container
        setContent {
            RenovationTheme {
                AppRoot(container)
            }
        }
    }
}
