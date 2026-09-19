package com.xiaoyuan.renovation.mobile.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        updatedAt: String = "",
    ) = SyncItem(
        id = id,
        name = name,
        price = price,
        model = model,
        qtyTotal = 1.0,
        allocations = allocations,
        records = records,
        updatedAt = updatedAt,
    )

    private fun payload(
        items: List<SyncItem> = emptyList(),
        rooms: List<SyncRoom> = emptyList(),
        categories: List<SyncCategory> = emptyList(),
        expenses: List<SyncExpense> = emptyList(),
    ) = SyncPayload(
        list = SyncListMeta(name = "装修采购"),
        items = items,
        rooms = rooms,
        categories = categories,
        expenses = expenses,
    )

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
    fun `两边都改过时按时间戳定胜负，新的赢、不弹冲突`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(listOf(item(1, "筒灯", price = 12.0, updatedAt = "2026-09-18 10:00:00")))
        val theirs = payload(listOf(item(101, "筒灯", price = 20.0, updatedAt = "2026-09-18 11:00:00")))

        // 服务器那份 11:00 改的，比手机的 10:00 新 —— 直接听服务器的，不问用户
        val result = Merger.merge(base, mine, theirs)
        assertFalse(result.hasConflicts)
        assertEquals(20.0, result.itemNamed("筒灯")?.price)

        // 反过来也一样：手机的更新就听手机的
        val newer = Merger.merge(
            base,
            payload(listOf(item(1, "筒灯", price = 12.0, updatedAt = "2026-09-18 12:00:00"))),
            theirs,
        )
        assertFalse(newer.hasConflicts)
        assertEquals(12.0, newer.itemNamed("筒灯")?.price)
    }

    @Test
    fun `两边都改过但没时间戳可比时仍然报冲突`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(listOf(item(1, "筒灯", price = 12.0)))      // 老数据：没有时间戳
        val theirs = payload(listOf(item(101, "筒灯", price = 20.0)))

        val result = Merger.merge(base, mine, theirs)
        assertTrue(result.hasConflicts)
        assertEquals(20.0, result.itemNamed("筒灯")?.price)
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

    /* ---------------- 从没同步过的两边：按名字配对，不能各留一份 ---------------- */

    @Test
    fun `没有基线时同名分类只留一份`() {
        // 这条是"上传报 500"的根因：没有共同基线时，三方合并那套判定把两边
        // 每一行都当成新增，服务器 id 落进负数占位、本地 id 是正数，两个键
        // 永远对不上，于是同名分类被留成两份 —— 推上去撞服务端唯一约束，
        // 整份清单传不上去
        val mine = SyncPayload(
            categories = listOf(SyncCategory(id = 1234, name = "示例分类", sort = 0)),
            rooms = listOf(SyncRoom(id = 1234, name = "示例分组", sort = 0)),
            items = listOf(item(1234, "😄")),
        )
        val theirs = SyncPayload(
            categories = listOf(SyncCategory(id = 5, name = "示例分类", sort = 0)),
            rooms = listOf(SyncRoom(id = 5, name = "示例分组", sort = 0)),
            items = listOf(item(7, "😄")),
        )

        val merged = Merger.mergeWithoutBase(mine, theirs)

        assertEquals("同名分类只该留一份", 1, merged.categories.size)
        assertEquals("同名分组只该留一份", 1, merged.rooms.size)
        assertEquals("同名物料只该留一份", 1, merged.items.size)
    }

    @Test
    fun `没有基线时各自独有的都保留`() {
        val mine = SyncPayload(
            rooms = listOf(SyncRoom(id = 1, name = "客厅", sort = 0)),
            items = listOf(item(1, "筒灯")),
        )
        val theirs = SyncPayload(
            rooms = listOf(SyncRoom(id = 9, name = "书房", sort = 0)),
            items = listOf(item(9, "开关")),
        )

        val merged = Merger.mergeWithoutBase(mine, theirs)

        assertEquals(setOf("客厅", "书房"), merged.rooms.map { it.name }.toSet())
        assertEquals(setOf("筒灯", "开关"), merged.items.map { it.name }.toSet())
    }

    @Test
    fun `没有基线时同名取改动较新的那份`() {
        val mine = SyncPayload(
            items = listOf(item(1, "筒灯", price = 10.0, updatedAt = "2026-09-18 10:00:00")),
        )
        val theirs = SyncPayload(
            items = listOf(item(9, "筒灯", price = 20.0, updatedAt = "2026-09-18 11:00:00")),
        )

        val merged = Merger.mergeWithoutBase(mine, theirs)

        assertEquals(1, merged.items.size)
        assertEquals("电脑那份更新，听它的", 20.0, merged.items.first().price, 1e-9)

        // 反过来：手机更新就听手机的
        val flipped = Merger.mergeWithoutBase(
            SyncPayload(items = listOf(item(1, "筒灯", price = 10.0, updatedAt = "2026-09-18 12:00:00"))),
            theirs,
        )
        assertEquals(10.0, flipped.items.first().price, 1e-9)
    }

    @Test
    fun `没有基线时物料挂的分组跟着重新编号，不会指错`() {
        // 两边的分组 id 空间各自独立（本地 1、服务器 9），直接混用会让物料
        // 挂到错误的分组上。合并后必须重新编号，且引用指向正确的那一个
        val mine = SyncPayload(
            rooms = listOf(SyncRoom(id = 1, name = "客厅", sort = 0)),
            items = listOf(
                item(100, "筒灯", allocations = listOf(SyncAllocation(roomId = 1, qty = 6.0))),
            ),
        )
        val theirs = SyncPayload(
            rooms = listOf(SyncRoom(id = 9, name = "书房", sort = 0)),
            items = listOf(
                item(900, "开关", allocations = listOf(SyncAllocation(roomId = 9, qty = 3.0))),
            ),
        )

        val merged = Merger.mergeWithoutBase(mine, theirs)

        val roomIdByName = merged.rooms.associate { it.name to it.id }
        val lamp = merged.items.first { it.name == "筒灯" }
        val switch = merged.items.first { it.name == "开关" }
        assertEquals("筒灯的分配要指向客厅", roomIdByName["客厅"], lamp.allocations.first().roomId)
        assertEquals("开关的分配要指向书房", roomIdByName["书房"], switch.allocations.first().roomId)
        assertNotEquals(
            "两个分组不能是同一个 id",
            lamp.allocations.first().roomId,
            switch.allocations.first().roomId,
        )
    }

    @Test
    fun `没有基线时物料挂的分类也重新编号`() {
        val mine = SyncPayload(
            categories = listOf(SyncCategory(id = 7, name = "灯具", sort = 0)),
            items = listOf(item(1, "筒灯").copy(categoryId = 7)),
        )
        val theirs = SyncPayload(
            categories = listOf(SyncCategory(id = 88, name = "五金", sort = 0)),
            items = listOf(item(9, "开关").copy(categoryId = 88)),
        )

        val merged = Merger.mergeWithoutBase(mine, theirs)

        val categoryIdByName = merged.categories.associate { it.name to it.id }
        assertEquals(
            categoryIdByName["灯具"],
            merged.items.first { it.name == "筒灯" }.categoryId,
        )
        assertEquals(
            categoryIdByName["五金"],
            merged.items.first { it.name == "开关" }.categoryId,
        )
    }

    @Test
    fun `没有基线时合并结果里的 id 不重复`() {
        // 两边 id 都从 1 起，合并后如果不去重编号，同一份清单里会出现两个 id=1
        val mine = SyncPayload(
            rooms = listOf(SyncRoom(id = 1, name = "客厅", sort = 0)),
            categories = listOf(SyncCategory(id = 1, name = "灯具", sort = 0)),
            items = listOf(item(1, "筒灯"), item(2, "开关")),
        )
        val theirs = SyncPayload(
            rooms = listOf(SyncRoom(id = 1, name = "书房", sort = 0)),
            categories = listOf(SyncCategory(id = 1, name = "五金", sort = 0)),
            items = listOf(item(1, "插座"), item(2, "电线")),
        )

        val merged = Merger.mergeWithoutBase(mine, theirs)

        assertEquals(4, merged.items.size)
        assertEquals("物料 id 不能重复", 4, merged.items.mapNotNull { it.id }.toSet().size)
        assertEquals("分组 id 不能重复", 2, merged.rooms.mapNotNull { it.id }.toSet().size)
        assertEquals("分类 id 不能重复", 2, merged.categories.mapNotNull { it.id }.toSet().size)
    }

    @Test
    fun `没有基线时一边为空就照抄另一边`() {
        val mine = SyncPayload(
            rooms = listOf(SyncRoom(id = 1, name = "客厅", sort = 0)),
            categories = listOf(SyncCategory(id = 1, name = "灯具", sort = 0)),
            items = listOf(item(1, "筒灯")),
        )

        val merged = Merger.mergeWithoutBase(mine, SyncPayload())

        assertEquals(1, merged.items.size)
        assertEquals(1, merged.rooms.size)
        assertEquals(1, merged.categories.size)
        assertEquals("筒灯", merged.items.first().name)
    }

    /* ---------------- 时间戳精度：手机带微秒、服务器不带 ---------------- */

    @Test
    fun `同一秒内的微秒差不能决定胜负`() {
        // 手机写的是带微秒的格式，服务器存的是截到秒的。直接比字符串的话，
        // 同一秒内本地因为多一截小数而"更大" —— 判谁更新就会偏向本地
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(
            listOf(item(1, "筒灯", price = 12.0, updatedAt = "2026-09-18 10:00:00.500000")),
        )
        val theirs = payload(
            listOf(item(101, "筒灯", price = 20.0, updatedAt = "2026-09-18 10:00:00")),
        )

        val result = Merger.merge(base, mine, theirs)

        // 同一秒、判不出谁更新 —— 该报冲突让用户定，而不是悄悄让某一方赢
        assertTrue("同一秒应当判不出来、报冲突", result.hasConflicts)
    }

    @Test
    fun `手机的时间戳比服务器新时听手机的`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(
            listOf(item(1, "筒灯", price = 12.0, updatedAt = "2026-09-18 10:00:01.123456")),
        )
        val theirs = payload(
            listOf(item(101, "筒灯", price = 20.0, updatedAt = "2026-09-18 10:00:00")),
        )

        val result = Merger.merge(base, mine, theirs)

        assertFalse(result.hasConflicts)
        assertEquals("手机确实更新，听手机的", 12.0, result.itemNamed("筒灯")!!.price, 1e-9)
    }

    @Test
    fun `服务器的时间戳更新时听服务器的`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(
            listOf(item(1, "筒灯", price = 12.0, updatedAt = "2026-09-18 10:00:00.999999")),
        )
        val theirs = payload(
            listOf(item(101, "筒灯", price = 20.0, updatedAt = "2026-09-18 10:00:02")),
        )

        val result = Merger.merge(base, mine, theirs)

        assertFalse(result.hasConflicts)
        assertEquals("服务器确实更新，听服务器的", 20.0, result.itemNamed("筒灯")!!.price, 1e-9)
    }

    @Test
    fun `没有基线时同名取较新也按秒比`() {
        // 同一秒内的微秒差不该让本地赢；秒数不同才分胜负
        val sameSecond = Merger.mergeWithoutBase(
            SyncPayload(items = listOf(item(1, "筒灯", price = 12.0, updatedAt = "2026-09-18 10:00:00.500000"))),
            SyncPayload(items = listOf(item(9, "筒灯", price = 20.0, updatedAt = "2026-09-18 10:00:00"))),
        )
        assertEquals("同一秒判不出来，用手机这边", 12.0, sameSecond.items.first().price, 1e-9)

        val remoteNewer = Merger.mergeWithoutBase(
            SyncPayload(items = listOf(item(1, "筒灯", price = 12.0, updatedAt = "2026-09-18 10:00:00.500000"))),
            SyncPayload(items = listOf(item(9, "筒灯", price = 20.0, updatedAt = "2026-09-18 10:00:05"))),
        )
        assertEquals("服务器晚 5 秒，听服务器的", 20.0, remoteNewer.items.first().price, 1e-9)
    }

    /* ---------------- 没有基线时，费用与采购记录的引用也要跟着重编号 ---------------- */

    @Test
    fun `没有基线时费用的物料引用重新编号`() {
        val mine = SyncPayload(
            items = listOf(item(1, "筒灯")),
            expenses = listOf(SyncExpense(id = 1, kind = "运费", amount = 80.0, itemId = 1)),
        )
        val theirs = SyncPayload(
            items = listOf(item(9, "开关")),
            expenses = listOf(SyncExpense(id = 9, kind = "安装费", amount = 50.0, itemId = 9)),
        )

        val merged = Merger.mergeWithoutBase(mine, theirs)

        val itemIdByName = merged.items.associate { it.name to it.id }
        val freight = merged.expenses.first { it.kind == "运费" }
        val install = merged.expenses.first { it.kind == "安装费" }
        assertEquals("运费的物料要指向筒灯", itemIdByName["筒灯"], freight.itemId)
        assertEquals("安装费的物料要指向开关", itemIdByName["开关"], install.itemId)
    }

    @Test
    fun `没有基线时采购记录的分组引用重新编号`() {
        val mine = SyncPayload(
            rooms = listOf(SyncRoom(id = 1, name = "客厅", sort = 0)),
            items = listOf(
                item(
                    1, "筒灯",
                    records = listOf(SyncRecord(qty = 6.0, amount = 180.0, roomIds = listOf(1))),
                ),
            ),
        )
        val theirs = SyncPayload(
            rooms = listOf(SyncRoom(id = 9, name = "书房", sort = 0)),
            items = listOf(
                item(
                    9, "开关",
                    records = listOf(SyncRecord(qty = 3.0, amount = 40.0, roomIds = listOf(9))),
                ),
            ),
        )

        val merged = Merger.mergeWithoutBase(mine, theirs)

        val roomIdByName = merged.rooms.associate { it.name to it.id }
        val lamp = merged.items.first { it.name == "筒灯" }
        val switch = merged.items.first { it.name == "开关" }
        assertEquals(
            "筒灯那笔账要记在客厅",
            listOf(roomIdByName["客厅"]),
            lamp.records.first().roomIds,
        )
        assertEquals(
            "开关那笔账要记在书房",
            listOf(roomIdByName["书房"]),
            switch.records.first().roomIds,
        )
    }

    /* ---------------- 服务器重排过 id：localMap 失配按名字回退配对 ---------------- */

    @Test
    fun `服务器重排过id时按名字配对，不产生重复行`() {
        // 另一台设备覆盖过服务器（删了"乙"），行 id 重排：本地基线里"甲丙"的
        // 服务器 id 是 11/13，服务器现在的 id 是 21/23。localMap 查不到新 id，
        // 从前它们会被当成"服务器新增"，与本地同名行合并出重复
        val base = SyncBaseline(
            fingerprint = "old",
            payload = payload(
                rooms = listOf(SyncRoom(id = 11, name = "甲", sort = 0), SyncRoom(id = 12, name = "乙", sort = 1)),
                categories = listOf(SyncCategory(id = 31, name = "灯具", sort = 0)),
                items = listOf(item(41, "筒灯").copy(categoryId = 31)),
            ),
            localMap = mapOf("room:11" to 1, "room:12" to 2, "category:31" to 1, "item:41" to 1),
        )
        val mine = payload(
            rooms = listOf(SyncRoom(id = 1, name = "甲", sort = 0), SyncRoom(id = 2, name = "乙", sort = 1)),
            categories = listOf(SyncCategory(id = 1, name = "灯具", sort = 0)),
            items = listOf(item(1, "筒灯").copy(categoryId = 1)),
        )
        // 服务器重排后：甲=21、丙(新增)=23，物料与分类同样换了新 id
        val theirs = payload(
            rooms = listOf(SyncRoom(id = 21, name = "甲", sort = 0), SyncRoom(id = 23, name = "丙", sort = 1)),
            categories = listOf(SyncCategory(id = 33, name = "灯具", sort = 0)),
            items = listOf(item(43, "筒灯").copy(categoryId = 33)),
        )

        val result = Merger.merge(base, mine, theirs)

        assertFalse(result.hasConflicts)
        assertEquals(
            "甲保留、乙被服务器删掉、丙加入，不该有重复",
            setOf("甲", "丙"),
            result.payload.rooms.map { it.name }.toSet(),
        )
        // 配对成功的标志：沿用本地 id。配不上就会走"删本地 + 负数占位重插"，
        // 名字集合碰巧一样但行身份全重置（时间戳、绑定关系跟着丢）
        assertEquals("甲沿用本地 id", 1, result.payload.rooms.first { it.name == "甲" }.id)
        assertEquals("筒灯沿用本地 id", 1, result.payload.items.first().id)
        assertEquals("分类引用翻译到本地分类", 1, result.payload.items.first().categoryId)
        assertEquals("同名物料只该一条", 1, result.payload.items.size)
        assertEquals("灯具", result.payload.items.first().categoryId?.let {
            result.payload.categories.firstOrNull { c -> c.id == it }?.name
        })
    }

    @Test
    fun `服务器重排过id且两边都改了同一行——按名字配对后正常判冲突`() {
        // 配对成同一行之后，"两边都改"的判定才成立：同一秒判不出，弹冲突
        val base = SyncBaseline(
            fingerprint = "old",
            payload = payload(items = listOf(item(41, "筒灯", price = 10.0))),
            localMap = mapOf("item:41" to 1),
        )
        val mine = payload(items = listOf(item(1, "筒灯", price = 111.0, updatedAt = "2026-09-19 13:29:43")))
        val theirs = payload(items = listOf(item(43, "筒灯", price = 222.0, updatedAt = "2026-09-19 13:29:43")))

        val result = Merger.merge(base, mine, theirs)

        assertTrue("同名同行、同一秒：该弹冲突而不是悄悄选边", result.hasConflicts)
        assertEquals(1, result.payload.items.size)
        assertEquals("冲突裁决的是本地那一行，不是占位重插", 1, result.payload.items.first().id)
    }

    /* ---------------- 冲突的另外两种标签 ---------------- */

    @Test
    fun `分组两边都改得不一样要报分组冲突`() {
        val base = baseline(
            items = emptyList(),
            rooms = listOf(SyncRoom(id = 201, name = "客厅", sort = 0)),
        )
        val mine = payload(rooms = listOf(SyncRoom(id = 1, name = "客厅改A", sort = 0)))
        val theirs = payload(rooms = listOf(SyncRoom(id = 201, name = "客厅改B", sort = 0)))

        val result = Merger.merge(base, mine, theirs)

        assertTrue(result.hasConflicts)
        assertEquals("分组", result.conflicts.first().label)
    }

    @Test
    fun `分类两边都改得不一样要报分类冲突`() {
        val base = SyncBaseline(
            fingerprint = "f",
            payload = payload(categories = listOf(SyncCategory(id = 301, name = "灯具", sort = 0))),
            localMap = mapOf("category:301" to 1),
        )
        val mine = payload(categories = listOf(SyncCategory(id = 1, name = "灯具A", sort = 0)))
        val theirs = payload(categories = listOf(SyncCategory(id = 301, name = "灯具B", sort = 0)))

        val result = Merger.merge(base, mine, theirs)

        assertTrue(result.hasConflicts)
        assertEquals("分类", result.conflicts.first().label)
    }

    @Test
    fun `本地改过而服务器删了要报冲突`() {
        val base = baseline(listOf(item(101, "筒灯", price = 10.0)))
        val mine = payload(listOf(item(1, "筒灯", price = 30.0)))    // 手机上改了
        val theirs = payload(emptyList())                            // 电脑上删了

        val result = Merger.merge(base, mine, theirs)

        assertTrue(result.hasConflicts)
        assertEquals("物料", result.conflicts.first().label)
        // 默认听服务器的（删掉）；选了以手机为准就恢复
        assertEquals(emptyList<String>(), result.payload.items.map { it.name })
        assertEquals(
            listOf("筒灯"),
            Merger.merge(base, mine, theirs, preferLocal = true).payload.items.map { it.name },
        )
    }
}
