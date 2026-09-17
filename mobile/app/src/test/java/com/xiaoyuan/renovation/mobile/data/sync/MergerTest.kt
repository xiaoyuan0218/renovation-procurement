package com.xiaoyuan.renovation.mobile.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三方合并的判定表：base（上次同步时）/ mine（手机）/ theirs（电脑）两两组合。
 *
 * 这套规则是同步的全部智慧所在 —— 记错一条就会静默丢数据，所以每一条都单独测。
 * 用负数 id 表示"还没映射到本地的服务器新增行"，与实现里的占位规则一致。
 */
class MergerTest {

    /* ---------------- 构造数据的小工具 ---------------- */

    private fun item(
        id: Int,
        name: String,
        price: Double = 10.0,
        model: String = "",
        allocations: List<SyncAllocation> = emptyList(),
        records: List<SyncRecord> = emptyList(),
    ) = SyncItem(
        id = id,
        name = name,
        price = price,
        model = model,
        qtyTotal = 1.0,
        allocations = allocations,
        records = records,
    )

    private fun payload(
        items: List<SyncItem> = emptyList(),
        rooms: List<SyncRoom> = emptyList(),
        expenses: List<SyncExpense> = emptyList(),
    ) = SyncPayload(list = SyncListMeta(name = "装修采购"), items = items, rooms = rooms, expenses = expenses)

    /** 服务器 id 101 → 本地 id 1，102 → 2。 */
    private fun baseline(
        items: List<SyncItem>,
        rooms: List<SyncRoom> = emptyList(),
        expenses: List<SyncExpense> = emptyList(),
    ) = SyncBaseline(
        fingerprint = "f",
        payload = payload(items = items, rooms = rooms, expenses = expenses),
        localMap = mapOf("item:101" to 1, "item:102" to 2, "room:201" to 1, "expense:301" to 1),
    )

    private fun MergeResult.itemNamed(name: String) =
        payload.items.firstOrNull { it.name == name }

    /* ---------------- 逐条判定 ---------------- */

    @Test
    fun `只有手机改过时用手机的`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(listOf(item(1, "筒灯", price = 12.0)))
        val theirs = payload(listOf(item(101, "筒灯", price = 10.0)))

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(12.0, result.itemNamed("筒灯")?.price)
    }

    @Test
    fun `只有电脑改过时用电脑的`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(listOf(item(1, "筒灯", price = 10.0)))
        val theirs = payload(listOf(item(101, "筒灯", price = 25.5)))

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(25.5, result.itemNamed("筒灯")?.price)
    }

    @Test
    fun `两边都改但改成一样不算冲突`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(listOf(item(1, "筒灯", price = 18.0)))
        val theirs = payload(listOf(item(101, "筒灯", price = 18.0)))

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(18.0, result.itemNamed("筒灯")?.price)
    }

    @Test
    fun `两边都改得不一样要报冲突`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(listOf(item(1, "筒灯", price = 12.0)))
        val theirs = payload(listOf(item(101, "筒灯", price = 20.0)))

        val result = Merger.merge(base, mine, theirs)
        assertTrue(result.hasConflicts)
        assertEquals(1, result.conflicts.size)
        assertEquals("物料", result.conflicts.first().label)
        assertEquals("筒灯", result.conflicts.first().name)
        // 默认听电脑的；用户选"以手机为准"时反过来
        assertEquals(20.0, result.itemNamed("筒灯")?.price)
        assertEquals(12.0, Merger.merge(base, mine, theirs, preferLocal = true).itemNamed("筒灯")?.price)
    }

    @Test
    fun `手机删了而电脑没动就删掉`() {
        val base = baseline(listOf(item(101, "筒灯"), item(102, "开关")))
        val mine = payload(listOf(item(2, "开关")))                       // 手机上删了筒灯
        val theirs = payload(listOf(item(101, "筒灯"), item(102, "开关")))

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(listOf("开关"), result.payload.items.map { it.name })
    }

    @Test
    fun `电脑删了而手机没动就跟着删`() {
        val base = baseline(listOf(item(101, "筒灯"), item(102, "开关")))
        val mine = payload(listOf(item(1, "筒灯"), item(2, "开关")))
        val theirs = payload(listOf(item(102, "开关")))                   // 电脑上删了筒灯

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(listOf("开关"), result.payload.items.map { it.name })
    }

    @Test
    fun `手机删了但电脑改过要报冲突`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(emptyList())                                   // 手机上删了
        val theirs = payload(listOf(item(101, "筒灯", price = 30.0)))     // 电脑上改了价

        val result = Merger.merge(base, mine, theirs)
        assertTrue(result.hasConflicts)
    }

    @Test
    fun `手机新增的保留、电脑新增的加入`() {
        val base = baseline(listOf(item(101, "筒灯")))
        val mine = payload(listOf(item(1, "筒灯"), item(50, "我加的开关")))
        val theirs = payload(listOf(item(101, "筒灯"), item(999, "电脑加的插座")))

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(
            setOf("筒灯", "我加的开关", "电脑加的插座"),
            result.payload.items.map { it.name }.toSet(),
        )
    }

    @Test
    fun `两边各改各的——两条改动都保住`() {
        // 这一条是"自动合并"存在的意义：手机上补了一笔账，电脑上改了另一条的价格
        val base = baseline(listOf(item(101, "筒灯", price = 10.0), item(102, "开关", price = 20.0)))
        val mine = payload(listOf(item(1, "筒灯", price = 12.0), item(2, "开关", price = 20.0)))
        val theirs = payload(listOf(item(101, "筒灯", price = 10.0), item(102, "开关", price = 25.0)))

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(12.0, result.itemNamed("筒灯")?.price, )
        assertEquals(25.0, result.itemNamed("开关")?.price)
    }

    /* ---------------- 聚合：物料的分配与记录跟着物料走 ---------------- */

    @Test
    fun `手机记一笔账、电脑改同一物料的分配 —— 算冲突`() {
        val base = baseline(listOf(item(101, "筒灯", records = emptyList())))
        val mine = payload(
            listOf(
                item(1, "筒灯", records = listOf(SyncRecord(qty = 6.0, amount = 180.0, date = "2026-09-01"))),
            ),
        )
        val theirs = payload(
            listOf(
                item(101, "筒灯", allocations = listOf(SyncAllocation(roomId = 201, qty = 6.0))),
            ),
        )

        val result = Merger.merge(base, mine, theirs)
        assertTrue("同一物料的记录与分配算作一个整体", result.hasConflicts)
    }

    @Test
    fun `手机记的账在电脑没动时保留下来`() {
        val base = baseline(listOf(item(101, "筒灯", records = emptyList())))
        val mine = payload(
            listOf(item(1, "筒灯", records = listOf(SyncRecord(qty = 6.0, amount = 180.0)))),
        )
        val theirs = payload(listOf(item(101, "筒灯", records = emptyList())))

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(1, result.itemNamed("筒灯")?.records?.size)
    }

    /* ---------------- 分组与费用 ---------------- */

    @Test
    fun `分组改名与新增各走各的`() {
        val base = baseline(
            items = emptyList(),
            rooms = listOf(SyncRoom(id = 201, name = "客厅", sort = 0)),
        )
        val mine = payload(rooms = listOf(SyncRoom(id = 1, name = "客厅改", sort = 0)))
        val theirs = payload(
            rooms = listOf(SyncRoom(id = 201, name = "客厅", sort = 0), SyncRoom(id = 202, name = "书房", sort = 1)),
        )

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(setOf("客厅改", "书房"), result.payload.rooms.map { it.name }.toSet())
    }

    @Test
    fun `费用金额两边改得不同要报冲突`() {
        val base = baseline(items = emptyList(), expenses = listOf(SyncExpense(id = 301, kind = "运费", amount = 80.0)))
        val mine = payload(expenses = listOf(SyncExpense(id = 1, kind = "运费", amount = 90.0)))
        val theirs = payload(expenses = listOf(SyncExpense(id = 301, kind = "运费", amount = 95.0)))

        val result = Merger.merge(base, mine, theirs)
        assertTrue(result.hasConflicts)
        assertEquals("费用", result.conflicts.first().label)
        assertEquals("两边都改过时默认听电脑的", 95.0, result.payload.expenses.first().amount, 1e-9)
        assertEquals(
            "选了以手机为准就用手机的",
            90.0,
            Merger.merge(base, mine, theirs, preferLocal = true).payload.expenses.first().amount,
            1e-9,
        )
    }

    @Test
    fun `什么都没变时结果与两边一致`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(listOf(item(1, "筒灯", price = 10.0)))
        val theirs = payload(listOf(item(101, "筒灯", price = 10.0)))

        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(1, result.payload.items.size)
        assertEquals(10.0, result.itemNamed("筒灯")?.price)
    }
}
