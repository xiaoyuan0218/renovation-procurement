package com.xiaoyuan.renovation.mobile.ui.nav

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xiaoyuan.renovation.mobile.di.AppContainer
import com.xiaoyuan.renovation.mobile.ui.common.containerViewModel
import com.xiaoyuan.renovation.mobile.ui.common.sharedViewModel
import com.xiaoyuan.renovation.mobile.ui.dashboard.DashboardScreen
import com.xiaoyuan.renovation.mobile.ui.dashboard.DashboardViewModel
import com.xiaoyuan.renovation.mobile.ui.items.ItemEditScreen
import com.xiaoyuan.renovation.mobile.ui.items.ItemsScreen
import com.xiaoyuan.renovation.mobile.ui.items.ItemsViewModel
import com.xiaoyuan.renovation.mobile.ui.items.NEW_ITEM_ID
import com.xiaoyuan.renovation.mobile.ui.lists.ListsViewModel
import com.xiaoyuan.renovation.mobile.ui.matrix.MatrixScreen
import com.xiaoyuan.renovation.mobile.ui.matrix.MatrixViewModel
import com.xiaoyuan.renovation.mobile.ui.settings.AboutPage
import com.xiaoyuan.renovation.mobile.ui.settings.BackupPage
import com.xiaoyuan.renovation.mobile.ui.settings.ExpensesPage
import com.xiaoyuan.renovation.mobile.ui.settings.ListsPage
import com.xiaoyuan.renovation.mobile.ui.settings.RoomsPage
import com.xiaoyuan.renovation.mobile.ui.settings.SettingsScreen
import com.xiaoyuan.renovation.mobile.ui.settings.TrashPage
import com.xiaoyuan.renovation.mobile.ui.settings.SettingsViewModel
import com.xiaoyuan.renovation.mobile.ui.sync.ServerPage
import com.xiaoyuan.renovation.mobile.ui.sync.SyncViewModel
import com.xiaoyuan.renovation.mobile.ui.theme.Ink

private enum class ShellTab(val label: String, val icon: ImageVector) {
    Dashboard("总览", Icons.Filled.DonutLarge),
    Items("清单", Icons.AutoMirrored.Filled.FormatListBulleted),
    Matrix("矩阵", Icons.Filled.GridOn),
    Settings("设置", Icons.Filled.Tune),
}

/** 主壳内部的导航：主界面 / 服务器地址 / 物料编辑。 */
@Composable
fun AppShellHost(container: AppContainer) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    // 同步事件提示（主要是"服务器上那份被删了、已自动解绑"）挂在整个导航之上。
    // 手动点「立即同步」时用户正站在「设置 → 服务器」页，那里主界面没有组合 ——
    // 挂在主界面里的话，这条提示在最该出现的地方反而看不到。
    //
    // 用 LaunchedEffect(Unit) + collect，**不能**写成 LaunchedEffect(syncEvent)：那样
    // 在 let 里先 consumeEvent() 会把值清成 null，key 一变协程当场被取消，
    // showSnackbar 还没显示完就没了 —— 提示等于没弹。
    LaunchedEffect(Unit) {
        container.sync.events.collect { message ->
            if (message != null) {
                snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
                container.sync.consumeEvent()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "main",
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("main") {
                AppShell(
                    container = container,
                    snackbarHostState = snackbarHostState,
                    onEditItem = { id -> navController.navigate("item/$id") },
                    onOpenSettingsPage = { page -> navController.navigate("settings/$page") },
                )
            }
            // 设置里的各个子页面：设置页只做菜单，点开才进整页
            composable("settings/{page}") { entry ->
                val page = entry.arguments?.getString("page").orEmpty()
                val back = { navController.popBackStack(); Unit }
                when (page) {
                    "lists" -> ListsPage(
                        listsVm = sharedViewModel(container) {
                            ListsViewModel(it.repo, it.currentList, it::bumpDataVersion)
                        },
                        onBack = back,
                    )

                    "rooms" -> RoomsPage(
                        vm = sharedViewModel(container) { SettingsViewModel(it.repo) },
                        onBack = back,
                    )

                    "expenses" -> ExpensesPage(
                        vm = sharedViewModel(container) { SettingsViewModel(it.repo) },
                        onBack = back,
                    )

                    "trash" -> TrashPage(
                        vm = sharedViewModel(container) { SettingsViewModel(it.repo) },
                        onBack = back,
                    )

                    "backup" -> BackupPage(container = container, onBack = back)

                    "about" -> AboutPage(onBack = back)

                    "server" -> {
                        val pageListsVm: ListsViewModel = sharedViewModel(container) {
                            ListsViewModel(it.repo, it.currentList, it::bumpDataVersion)
                        }
                        // 拉取/上传/同步会 bumpDataVersion，这里跟着重读 —— 不跟的话
                        // 新拉下来的清单要退出 App 重进才出现
                        val refreshKey by container.dataVersion.collectAsStateWithLifecycle()
                        LaunchedEffect(refreshKey) { pageListsVm.loadIfStale(refreshKey) }
                        val lists by pageListsVm.lists.collectAsStateWithLifecycle()
                        val currentId by pageListsVm.currentId.collectAsStateWithLifecycle()
                        ServerPage(
                            vm = sharedViewModel(container) {
                                SyncViewModel(it.sync, it.repo, it.session, it.dataVersion)
                            },
                            lists = lists,
                            currentListId = currentId,
                            onChanged = container::bumpDataVersion,
                            onBack = back,
                        )
                    }
                }
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

        // 提示浮在最下面，任意页面都能看到。主界面底部有导航栏，得让开它的高度；
        // 设置子页面没有导航栏，直接贴底即可 —— 一律用 96dp 的话在子页面上会浮在半空
        val route = navController.currentBackStackEntryAsState().value?.destination?.route
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (route == "main") 96.dp else 16.dp),
        )
    }
}

@Composable
private fun AppShell(
    container: AppContainer,
    snackbarHostState: SnackbarHostState,
    onEditItem: (Int) -> Unit,
    onOpenSettingsPage: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ShellTab.Dashboard) }
    val dataVersion by container.dataVersion.collectAsStateWithLifecycle()

    // ViewModel 挂在 "main" 这个导航条目上，切 Tab 不会丢筛选条件与已加载数据
    val dashboardVm: DashboardViewModel = containerViewModel(container) { DashboardViewModel(it.repo) }
    val itemsVm: ItemsViewModel = containerViewModel(container) { ItemsViewModel(it.repo) }
    val matrixVm: MatrixViewModel = containerViewModel(container) { MatrixViewModel(it.repo) }
    // 这三份状态设置子页面也在用（服务器页要读登录态、分组页要读分组），
    // 得挂 Activity 级共享实例 —— 两个导航条目各拿一份的话，会出现
    // 「设置里显示未连接、点进去其实连着」这种状态对不上的问题
    val listsVm: ListsViewModel = sharedViewModel(container) {
        ListsViewModel(it.repo, it.currentList, it::bumpDataVersion)
    }
    val settingsVm: SettingsViewModel = sharedViewModel(container) { SettingsViewModel(it.repo) }
    val syncVm: SyncViewModel = sharedViewModel(container) {
        SyncViewModel(it.sync, it.repo, it.session, it.dataVersion)
    }

    Column(Modifier.fillMaxSize()) {
        // 跟着 dataVersion 刷新：加了几条物料之后，下拉里的「X 项」也是新的
        ListSwitcherBar(listsVm, snackbarHostState, dataVersion)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding(),
        ) {
            when (tab) {
                ShellTab.Dashboard -> DashboardScreen(
                    vm = dashboardVm,
                    refreshKey = dataVersion,
                    onGoItems = {
                        itemsVm.focusPending()
                        tab = ShellTab.Items
                    },
                )

                ShellTab.Items -> ItemsScreen(
                    vm = itemsVm,
                    refreshKey = dataVersion,
                    onEditItem = onEditItem,
                )

                ShellTab.Matrix -> MatrixScreen(
                    vm = matrixVm,
                    refreshKey = dataVersion,
                )

                ShellTab.Settings -> SettingsScreen(
                    listsVm = listsVm,
                    vm = settingsVm,
                    syncVm = syncVm,
                    refreshKey = dataVersion,
                    onOpenPage = onOpenSettingsPage,
                )
            }
        }

        GlassBottomBar(current = tab, onSelect = { tab = it })
    }
}

/**
 * 顶部清单切换栏。
 *
 * 清单是全局上下文：这里选哪一份，后面所有页面拉到的都是那一份的数据，
 * 所以它固定在最上面，切 Tab 也一直在。
 */
@Composable
private fun ListSwitcherBar(
    vm: ListsViewModel,
    snackbarHostState: SnackbarHostState,
    refreshKey: Int,
) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val current = lists.firstOrNull { it.id == currentId } ?: lists.firstOrNull()

    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) { vm.loadIfStale(refreshKey) }

    LaunchedEffect(message) {
        message?.let {
            vm.clearMessage()
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
        }
    }

    // 不画背景：直接浮在渐变上，和「设置」里那些详情页的页眉一个效果。
    // 画一条近不透明的色块会在顶部切出一道明显的分界，把整屏渐变割开
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Ink.GlassFill)
                    .border(1.dp, Ink.Blue.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                    .clickable { menuOpen = true }
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = current?.name ?: "清单",
                    color = Ink.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "切换清单",
                    tint = Ink.BlueSoft,
                    modifier = Modifier.size(20.dp),
                )
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Ink.BgMid,
            ) {
                lists.forEach { list ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(list.name, color = Ink.TextPrimary, fontSize = 14.sp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "${list.itemCount} 项",
                                    color = Ink.TextMuted,
                                    fontSize = 12.sp,
                                )
                            }
                        },
                        trailingIcon = {
                            if (list.id == currentId) {
                                Icon(Icons.Filled.Check, null, tint = Ink.Blue, modifier = Modifier.size(18.dp))
                            }
                        },
                        onClick = {
                            menuOpen = false
                            vm.select(list.id)
                        },
                    )
                }
            }
        }
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
