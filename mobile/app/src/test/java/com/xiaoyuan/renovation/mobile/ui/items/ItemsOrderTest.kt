package com.xiaoyuan.renovation.mobile.ui.items

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.model.ItemDto
import com.xiaoyuan.renovation.mobile.data.prefs.AppPrefs
import com.xiaoyuan.renovation.mobile.data.repo.CurrentListHolder
import com.xiaoyuan.renovation.mobile.data.repo.LocalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 清单页的默认顺序：按添加时间倒序，最近加的在最上面。
 *
 * 用户报过"移动端没有按时间默认排序" —— 原来按「日常价小计」从大到小排
 * （初版随手选的），跟网页版表格的默认排序（添加时间倒序）对不上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ItemsOrderTest {

    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var vm: ItemsViewModel

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // 排序只用到 filtered(data)：仓库在这里只是 ViewModel 的构造依赖
        val repo = LocalRepository(db, CurrentListHolder(AppPrefs(context), db, scope))
        vm = ItemsViewModel(repo)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** 日常价小计故意与时间顺序相反：排成时间序才说明不是按金额排的。 */
    private fun item(id: Int, createdAt: String, discountTotal: Double = 0.0) =
        ItemDto(id = id, name = "物料$id", createdAt = createdAt, discountTotal = discountTotal)

    @Test
    fun `默认按添加时间倒序——最近加的在最上面`() {
        val data = ItemsData(
            items = listOf(
                item(1, "2026-10-01 10:00:00", discountTotal = 999.0),
                item(3, "2026-10-03 10:00:00", discountTotal = 1.0),
                item(2, "2026-10-02 10:00:00", discountTotal = 500.0),
            ),
        )

        assertEquals(listOf(3, 2, 1), vm.filtered(data).map { it.id })
    }

    @Test
    fun `同一时间加的多条——后加的在前`() {
        val data = ItemsData(
            items = listOf(
                item(1, "2026-10-03 10:00:00"),
                item(2, "2026-10-03 10:00:00"),
                item(3, "2026-10-03 10:00:00"),
            ),
        )

        assertEquals(listOf(3, 2, 1), vm.filtered(data).map { it.id })
    }

    @Test
    fun `老数据没有时间戳时沉底，不挡在最新几条前面`() {
        val data = ItemsData(
            items = listOf(
                item(1, ""),
                item(2, "2026-10-03 10:00:00"),
                item(3, ""),
            ),
        )

        assertEquals(listOf(2, 3, 1), vm.filtered(data).map { it.id })
    }

    @Test
    fun `筛选之后仍然是时间倒序`() {
        val data = ItemsData(
            items = listOf(
                item(1, "2026-10-01 10:00:00").copy(name = "壁挂炉"),
                item(2, "2026-10-05 10:00:00").copy(name = "壁挂炉 备用"),
                item(3, "2026-10-03 10:00:00").copy(name = "筒灯"),
            ),
        )

        vm.setQuery("壁挂炉")

        assertEquals(listOf(2, 1), vm.filtered(data).map { it.id })
    }
}
