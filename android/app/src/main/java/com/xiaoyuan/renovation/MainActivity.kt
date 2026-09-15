package com.xiaoyuan.renovation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xiaoyuan.renovation.ui.AppRoot
import com.xiaoyuan.renovation.ui.theme.RenovationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as RenovationApp).container
        setContent {
            RenovationTheme {
                AppRoot(container)
            }
        }
    }
}
