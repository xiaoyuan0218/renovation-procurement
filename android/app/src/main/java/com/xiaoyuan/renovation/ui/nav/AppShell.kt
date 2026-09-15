package com.xiaoyuan.renovation.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xiaoyuan.renovation.di.AppContainer
import com.xiaoyuan.renovation.ui.common.containerViewModel
import com.xiaoyuan.renovation.ui.dashboard.DashboardScreen
import com.xiaoyuan.renovation.ui.dashboard.DashboardViewModel
import com.xiaoyuan.renovation.ui.items.ItemEditScreen
import com.xiaoyuan.renovation.ui.items.ItemsScreen
import com.xiaoyuan.renovation.ui.items.ItemsViewModel
import com.xiaoyuan.renovation.ui.items.NEW_ITEM_ID
import com.xiaoyuan.renovation.ui.matrix.MatrixScreen
import com.xiaoyuan.renovation.ui.matrix.MatrixViewModel
import com.xiaoyuan.renovation.ui.settings.SettingsScreen
import com.xiaoyuan.renovation.ui.setup.ServerSetupScreen
import com.xiaoyuan.renovation.ui.theme.Ink

private enum class ShellTab(val label: String, val icon: ImageVector) {
    Dashboard("总览", Icons.Filled.DonutLarge),
    Items("清单", Icons.AutoMirrored.Filled.FormatListBulleted),
    Matrix("矩阵", Icons.Filled.GridOn),
    Settings("设置", Icons.Filled.Tune),
}

/** 主壳内部的导航：主界面 / 服务器地址 / 物料编辑。 */
@Composable
fun AppShellHost(container: AppContainer, baseUrl: String) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main",
        modifier = Modifier.fillMaxSize(),
    ) {
        composable("main") {
            AppShell(
                container = container,
                baseUrl = baseUrl,
                onEditItem = { id -> navController.navigate("item/$id") },
                onEditServerAddress = { navController.navigate("server") },
            )
        }
        composable("server") {
            ServerSetupScreen(
                container = container,
                currentAddress = baseUrl,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "item/{id}",
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            ItemEditScreen(
                container = container,
                itemId = entry.arguments?.getInt("id") ?: NEW_ITEM_ID,
                onClose = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun AppShell(
    container: AppContainer,
    baseUrl: String,
    onEditItem: (Int) -> Unit,
    onEditServerAddress: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ShellTab.Dashboard) }
    val dataVersion by container.dataVersion.collectAsStateWithLifecycle()

    // ViewModel 挂在 "main" 这个导航条目上，切 Tab 不会丢筛选条件与已加载数据
    val dashboardVm: DashboardViewModel = containerViewModel(container) { DashboardViewModel(it.repo) }
    val itemsVm: ItemsViewModel = containerViewModel(container) { ItemsViewModel(it.repo) }
    val matrixVm: MatrixViewModel = containerViewModel(container) { MatrixViewModel(it.repo) }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .statusBarsPadding()
                .imePadding(),
        ) {
            when (tab) {
                ShellTab.Dashboard -> DashboardScreen(
                    vm = dashboardVm,
                    serverUrl = baseUrl,
                    refreshKey = dataVersion,
                    onGoItems = {
                        itemsVm.focusPending()
                        tab = ShellTab.Items
                    },
                    onOpenServerSettings = onEditServerAddress,
                )

                ShellTab.Items -> ItemsScreen(
                    vm = itemsVm,
                    serverUrl = baseUrl,
                    refreshKey = dataVersion,
                    onEditItem = onEditItem,
                    onOpenServerSettings = onEditServerAddress,
                )

                ShellTab.Matrix -> MatrixScreen(
                    vm = matrixVm,
                    serverUrl = baseUrl,
                    refreshKey = dataVersion,
                    onOpenServerSettings = onEditServerAddress,
                )

                ShellTab.Settings -> SettingsScreen(
                    container = container,
                    refreshKey = dataVersion,
                    onEditServerAddress = onEditServerAddress,
                )
            }
        }

        GlassBottomBar(current = tab, onSelect = { tab = it })
    }
}

@Composable
private fun GlassBottomBar(current: ShellTab, onSelect: (ShellTab) -> Unit) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Ink.Divider, Color.Transparent),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink.BgMid.copy(alpha = 0.92f))
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ShellTab.entries.forEach { item ->
                BottomBarItem(
                    tab = item,
                    selected = item == current,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(tab: ShellTab, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val tint = if (selected) Color.White else Ink.TextSecondary
    Column(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) {
                    Brush.horizontalGradient(Ink.PrimaryGradient)
                } else {
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                },
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, Ink.Blue.copy(alpha = 0.4f), shape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            fontSize = 11.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

