package com.xiaoyuan.renovation.mobile.data.xlsx

import androidx.room.withTransaction
import com.xiaoyuan.renovation.mobile.data.db.AllocationEntity
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.CategoryEntity
import com.xiaoyuan.renovation.mobile.data.db.ExtraExpenseEntity
import com.xiaoyuan.renovation.mobile.data.db.ItemEntity
import com.xiaoyuan.renovation.mobile.data.db.PurchaseRecordEntity
import com.xiaoyuan.renovation.mobile.data.db.RecordRoomEntity
import com.xiaoyuan.renovation.mobile.data.db.RoomEntity
import com.xiaoyuan.renovation.mobile.data.db.nowStamp
import com.xiaoyuan.renovation.mobile.data.db.normalizeStamp
import com.xiaoyuan.renovation.mobile.data.db.toExportStamp
import com.xiaoyuan.renovation.mobile.domain.LocalCompute

/**
 * 表格与本地数据之间的来回转换。
 *
 * 列名与网页版/网络版**完全一致**，所以三个端的文件可以互相导入：
 * 手机上导出的表，电脑上能直接导进网页版；反过来也一样。
 *
 * 自己这套多认「采购记录」和「额外费用」两页 —— 网页版的导入模板只处理
 * 物料与布点（那两页的金额是它自己算出来的），而手机导出再导入应该**一分不差**，
 * 不然用户拿它当备份就会丢账。
 */

/** 导入结果，界面上报给用户看。 */
data class ImportReport(
    val mode: String,
    val itemsCreated: Int = 0,
    val itemsMatched: Int = 0,
    val allocations: Int = 0,
    val records: Int = 0,
    val expenses: Int = 0,
    val roomsCreated: Int = 0,
    val categoriesCreated: Int = 0,
    val warnings: List<String> = emptyList(),
)

object SheetMapping {

    const val SHEET_ITEMS = "物料汇总"
    const val SHEET_ALLOCS = "布点明细"
    const val SHEET_RECORDS = "采购记录"
    const val SHEET_EXPENSES = "额外费用"
    const val SHEET_ROOMS = "分组"
    const val SHEET_CATEGORIES = "分类"

    // 列名与顺序都和后端 excel_io.py 里的 ITEM_HEADER 等常量一一对应 ——
    // 三端互导靠的就是这套列名，改一边必须同时改另一边。
    // 「物料ID」在第一列：它才是物料的身份，对账时一眼能找到。
    private val ITEM_HEADERS = listOf(
        "物料ID", "类目", "物料名称", "品牌", "型号", "单位", "数量", "单价", "优惠单价",
        "日常价", "实付数量", "实付金额", "未付数量", "未付金额",
        "日常价未付", "实际优惠", "日常价优惠",
        "已购", "备注", "添加时间", "修改时间",
    )
    private val ALLOC_HEADERS = listOf("物料ID", "物料名称", "房间", "数量", "单价", "备注")
    private val RECORD_HEADERS = listOf(
        "物料ID", "物料名称", "实付数量", "实付金额", "付款日期", "分组", "商家",
        "订单号", "备注", "添加时间", "修改时间",
    )
    private val EXPENSE_HEADERS = listOf(
        "类型", "金额", "日期", "商家", "订单号", "备注", "添加时间", "修改时间",
    )
    private val ROOM_HEADERS = listOf("分组名称")
    private val CATEGORY_HEADERS = listOf("分类名称")

    /* ============================================================
       导出
       ============================================================ */

    suspend fun toSheets(db: AppDatabase, listId: Int): List<Pair<String, List<List<Any?>>>> {
        val rooms = db.rooms().byList(listId).associateBy { it.id }
        val categories = db.categories().byList(listId).associateBy { it.id }
        val items = db.items().all(listId)
        val allocations = db.allocations().byList(listId).groupBy { it.itemId }
        val records = db.records().byList(listId).groupBy { it.itemId }
        val recordRooms = db.recordRooms().byList(listId).groupBy { it.recordId }

        val itemRows: MutableList<List<Any?>> = mutableListOf(ITEM_HEADERS)
        val allocRows: MutableList<List<Any?>> = mutableListOf(ALLOC_HEADERS)
        val recordRows: MutableList<List<Any?>> = mutableListOf(RECORD_HEADERS)

        items.forEach { item ->
            val mine = allocations[item.id].orEmpty()
            val bundle = com.xiaoyuan.renovation.mobile.domain.ItemBundle(
                item = item,
                categoryName = item.categoryId?.let { categories[it]?.name },
                allocations = mine,
                records = records[item.id].orEmpty(),
                recordRooms = recordRooms.mapValues { (_, rows) -> rows.mapNotNull { it.roomId } },
            )
            val dto = LocalCompute.toDto(bundle)

            itemRows += listOf(
                item.id,
                categories[item.categoryId]?.name ?: "",
                item.name,
                item.brand,
                item.model,
                item.unit,
                dto.totalQty,
                item.price,
                item.discountPrice,
                dto.discountTotal,
                dto.paidQty,
                dto.paid,
                dto.unpaidQty,
                dto.unpaid,
                LocalCompute.dailyUnpaid(
                    LocalCompute.unpaidQty(dto.totalQty, dto.paidQty),
                    item,
                ),
                LocalCompute.actualDiscount(item, mine, bundle.records),
                LocalCompute.dailyDiscount(item, mine, bundle.records),
                if (dto.bought) "是" else "否",
                item.note,
                // 时间跟着一起走（写成本地时间带偏移量）：不然导出再导回来，
                // 所有「修改时间」都变成刚刚，同步还会把每条都当成新改动推一遍
                toExportStamp(item.createdAt),
                toExportStamp(item.updatedAt),
            )

            mine.forEach { alloc ->
                // 数量为 0 的分配别处一律视为"没有这条"，导出时也不写，否则回灌会丢
                if (alloc.qty == 0.0) return@forEach
                allocRows += listOf(
                    item.id,
                    item.name,
                    rooms[alloc.roomId]?.name ?: "",
                    alloc.qty,
                    // 写原始的覆盖价：留空表示跟随物料单价，写死会变成固定价
                    alloc.priceOverride,
                    alloc.note,
                )
            }

            bundle.records.forEach { record ->
                val names = recordRooms[record.id].orEmpty()
                    .mapNotNull { it.roomId?.let { id -> rooms[id]?.name } }
                recordRows += listOf(
                    item.id,
                    item.name,
                    record.qty,
                    record.amount,
                    // 定金列与后端 excel_io 同位（实付金额之后）：是/否
                    if (record.isDeposit) "是" else "否",
                    record.date,
                    // 多选用顿号连起来，导入时按分隔符拆回
                    names.joinToString("、"),
                    record.vendor,
                    record.orderNo,
                    record.note,
                    toExportStamp(record.createdAt),
                    toExportStamp(record.updatedAt),
                )
            }
        }

        val expenseRows: MutableList<List<Any?>> = mutableListOf(EXPENSE_HEADERS)
        db.expenses().byList(listId).forEach { e ->
            expenseRows += listOf(e.kind, e.amount, e.date, e.vendor, e.orderNo, e.note,
                toExportStamp(e.createdAt), toExportStamp(e.updatedAt))
        }

        // 分组与分类单独成页：上面几页里它们只以「用到的名字」出现，没有任何物料
        // 的分组/分类会因此消失。全量列出来才搬得完整。
        val roomRows: MutableList<List<Any?>> = mutableListOf(ROOM_HEADERS)
        db.rooms().byList(listId).forEach { roomRows += listOf(it.name) }
        val categoryRows: MutableList<List<Any?>> = mutableListOf(CATEGORY_HEADERS)
        db.categories().byList(listId).forEach { categoryRows += listOf(it.name) }

        return listOf(
            "物料汇总" to itemRows,
            "布点明细" to allocRows,
            "采购记录" to recordRows,
            "额外费用" to expenseRows,
            "分组" to roomRows,
            "分类" to categoryRows,
        )   // 清单名不写进表里：导入是导进当前清单
    }

    /* ============================================================
       导入
       ============================================================ */

    /**
     * 按表格重建当前清单的内容。
     *
     * [mode] 为 `replace` 时先清空现有物料（分组与分类留着，缺的会补）；
     * `merge` 则按物料名称匹配，已有的更新、新的追加。
     */
    suspend fun fromSheets(
        db: AppDatabase,
        listId: Int,
        sheets: Map<String, List<List<String>>>,
        mode: String,
    ): ImportReport = db.withTransaction {
        val itemSheet = sheets[SHEET_ITEMS] ?: throw IllegalArgumentException(
            "这个表格里没有「物料汇总」这一页，不是本工具导出的文件",
        )

        val warnings = mutableListOf<String>()
        val itemTable = Table(itemSheet)
        val allocTable = sheets[SHEET_ALLOCS]?.let { Table(it) } ?: Table(emptyList())
        val recordTable = sheets[SHEET_RECORDS]?.let { Table(it) } ?: Table(emptyList())
        val expenseTable = sheets[SHEET_EXPENSES]?.let { Table(it) } ?: Table(emptyList())

        var created = 0
        var matched = 0
        var allocCount = 0
        var recordCount = 0
        var roomsCreated = 0
        var categoriesCreated = 0

        // 名称 → 本地 id 的映射：分组合分类按名字自动补齐
        val roomIds = db.rooms().byList(listId).associateBy({ it.name }, { it.id }).toMutableMap()
        val categoryIds = db.categories().byList(listId)
            .associateBy({ it.name }, { it.id }).toMutableMap()

        suspend fun roomId(name: String): Int? {
            val key = name.trim()
            if (key.isEmpty()) return null
            roomIds[key]?.let { return it }
            val id = db.rooms().insert(
                RoomEntity(listId = listId, name = key, sort = db.rooms().nextSort(listId)),
            ).toInt()
            roomIds[key] = id
            roomsCreated++
            return id
        }

        suspend fun categoryId(name: String): Int? {
            val key = name.trim()
            if (key.isEmpty()) return null
            categoryIds[key]?.let { return it }
            val id = db.categories().insert(
                CategoryEntity(
                    listId = listId,
                    name = key,
                    sort = db.categories().nextSort(listId),
                ),
            ).toInt()
            categoryIds[key] = id
            categoriesCreated++
            return id
        }

        // 「分组」「分类」两页：没有物料的空分组/空分类只存在于此，先按它把名字
        // 建出来。也正因为先建好了，下面采购记录「分组」列的整串匹配才命中得了。
        sheets[SHEET_ROOMS]?.drop(1)?.forEach { row ->
            row.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { roomId(it) }
        }
        sheets[SHEET_CATEGORIES]?.drop(1)?.forEach { row ->
            row.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { categoryId(it) }
        }

        // 名称 → 未认领的现有物料队列。同名物料（真实数据里就有，比如两条
        // 「易来灯带控制器」）必须各归各的：用 associateBy 只留最后一条的话，
        // 布点与采购记录会全挂到那一条上，数量与金额凭空翻倍。
        val unclaimed = db.items().all(listId)
            .groupBy { it.name }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        // 本次导入后「名字 → 本地 id」与「导出文件里的物料ID → 本地 id」，
        // 布点与采购记录靠它们认物料（先认 ID，认不到再退名字）
        val nameToId = mutableMapOf<String, Int>()
        val idMap = mutableMapOf<String, Int>()

        fun itemFor(table: Table, row: List<String>): Int? {
            val sourceId = table.cell(row, "物料ID").trim()
            if (sourceId.isNotEmpty()) idMap[sourceId]?.let { return it }
            return nameToId[table.cell(row, "物料名称").trim()]
        }

        if (mode == "replace") {
            db.items().all(listId).forEach { db.items().purgeCascade(it.id) }
            unclaimed.clear()
        }
        // 额外费用是流水账，没有天然的匹配键：文件带了这一页就整体替换（与后端
        // 一致）。老文件没有这一页时不动 —— 否则拿一份旧备份导一次，现有费用
        // 就被清空了。
        if (sheets.containsKey(SHEET_EXPENSES)) {
            db.expenses().byList(listId).forEach { db.expenses().delete(it) }
        }

        // 已经清过旧分配的物料：布点明细里一条物料占多行，只清第一次
        val clearedAllocItems = mutableSetOf<Int>()

        // 已经清过旧记录的物料：同上，采购记录页是该物料的完整付款历史
        val clearedRecordItems = mutableSetOf<Int>()

        for (row in itemTable.rows) {
            val name = itemTable.cell(row, "物料名称").trim()
            if (name.isEmpty()) continue

            val existing = unclaimed[name]?.removeFirstOrNull()
            val itemId: Int
            if (existing != null) {
                itemId = existing.id
                matched++
            } else {
                itemId = db.items().insert(
                    ItemEntity(
                        listId = listId,
                        name = name,
                        sort = db.items().nextSort(listId),
                    ),
                ).toInt()
                created++
            }

            val current = db.items().byId(itemId) ?: continue
            db.items().update(
                current.copy(
                    categoryId = categoryId(itemTable.cell(row, "类目")) ?: current.categoryId,
                    brand = itemTable.cell(row, "品牌").ifBlank { current.brand },
                    model = itemTable.cell(row, "型号").ifBlank { current.model },
                    unit = itemTable.cell(row, "单位").ifBlank { current.unit },
                    qtyTotal = itemTable.number(row, "数量") ?: current.qtyTotal,
                    price = itemTable.number(row, "单价") ?: current.price,
                    discountPrice = itemTable.number(row, "优惠单价") ?: current.discountPrice,
                    note = itemTable.cell(row, "备注").ifBlank { current.note },
                    // 表里带了时间就照原样写回，导出再导入不改动任何时间；
                    // 没带的（老文件、或本次新建的行）补一个"现在"，免得界面
                    // 上「添加时间」是空的
                    createdAt = normalizeStamp(itemTable.cell(row, "添加时间"))
                        ?: current.createdAt.ifEmpty { nowStamp() },
                    updatedAt = normalizeStamp(itemTable.cell(row, "修改时间"))
                        ?: current.updatedAt.ifEmpty { nowStamp() },
                    rev = current.rev + 1,
                ),
            )
            nameToId.putIfAbsent(name, itemId)

            val remoteId = itemTable.cell(row, "物料ID").trim()
            if (remoteId.isNotEmpty()) idMap[remoteId] = itemId
        }

        for (row in allocTable.rows) {
            val itemName = allocTable.cell(row, "物料名称").trim()
            val itemId = itemFor(allocTable, row)
            if (itemId == null) {
                warnings += "布点明细里的物料「$itemName」在表格里找不到，已跳过"
                continue
            }
            // 表格是「这份物料分到哪些分组」的完整快照，所以重建前先清掉旧分配 ——
            // 否则按名称合并时，同一格会被插成两条（旧的一条 + 表格里的一条），
            // 数量凭空翻倍。replace 模式下物料本身已经重建，这里清不到东西。
            if (clearedAllocItems.add(itemId)) db.allocations().deleteOfItem(itemId)
            val qty = allocTable.number(row, "数量") ?: 0.0
            val room = roomId(allocTable.cell(row, "房间"))
            if (room == null) {
                warnings += "布点明细里「$itemName」有一行的房间是空的，已跳过"
                continue
            }
            if (qty == 0.0) continue
            db.allocations().insert(
                AllocationEntity(
                    itemId = itemId,
                    roomId = room,
                    qty = qty,
                    priceOverride = allocTable.number(row, "单价"),
                    note = allocTable.cell(row, "备注"),
                ),
            )
            allocCount++
        }

        for (row in recordTable.rows) {
            val itemName = recordTable.cell(row, "物料名称").trim()
            val itemId = itemFor(recordTable, row)
            if (itemId == null) {
                warnings += "采购记录里的物料「$itemName」在表格里找不到，已跳过"
                continue
            }
            // 表格是「这条物料的完整付款历史」，重建前先清旧记录 —— 否则同一份
            // 文件连导两次，采购记录会翻倍（后端也是这么做的）
            if (clearedRecordItems.add(itemId)) {
                db.records().deleteRecordRoomsOfItem(itemId)
                db.records().deleteOfItem(itemId)
            }
            // 定金列按「是/否」认（与后端 excel_io 同规则）；老文件没有这列时读空串，按否处理
            val isDeposit = recordTable.cell(row, "定金").trim() in
                setOf("是", "TRUE", "True", "1")
            val id = db.records().insert(
                PurchaseRecordEntity(
                    itemId = itemId,
                    // 定金不看数量：勾了就按 0 存（与网页端一致）
                    qty = if (isDeposit) 0.0 else (recordTable.number(row, "实付数量") ?: 0.0),
                    amount = recordTable.number(row, "实付金额") ?: 0.0,
                    isDeposit = isDeposit,
                    date = LocalCompute.forRead(recordTable.cell(row, "付款日期")),
                    note = recordTable.cell(row, "备注"),
                    vendor = recordTable.cell(row, "商家"),
                    orderNo = recordTable.cell(row, "订单号"),
                    createdAt = normalizeStamp(recordTable.cell(row, "添加时间")) ?: nowStamp(),
                    updatedAt = normalizeStamp(recordTable.cell(row, "修改时间")) ?: nowStamp(),
                ),
            ).toInt()
            // 先整体匹配已有分组名，匹配不到才按分隔符拆：分组名本身可能含顿号或
            // 斜杠（「客厅/餐厅」），无脑拆会把它拆成两个分组，来回导一次数据就散了。
            // 导出时这些名字原样写在「分组」页里，上面已按它建好，所以自己导出的
            // 文件这里必定命中。
            val rawRooms = recordTable.cell(row, "分组").trim()
            val roomNames = when {
                rawRooms.isEmpty() -> emptyList()
                roomIds.containsKey(rawRooms) -> listOf(rawRooms)
                else -> rawRooms.split("、", ",", "，", "/")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            roomNames.forEach { name ->
                // 用具名参数：RecordRoomEntity 的字段顺序是 (id, recordId, roomId)，
                // 按位置传会把记录 id 当主键、分组 id 当 record_id，roomId 永远为 null
                // ——「这笔钱花在哪几个分组」就整个丢了。
                roomId(name)?.let {
                    db.recordRooms().insert(RecordRoomEntity(recordId = id, roomId = it))
                }
            }
            recordCount++
        }

        var expenseCount = 0
        for (row in expenseTable.rows) {
            val amount = expenseTable.number(row, "金额") ?: continue
            db.expenses().insert(
                ExtraExpenseEntity(
                    listId = listId,
                    kind = expenseTable.cell(row, "类型").ifBlank { "其他" },
                    amount = amount,
                    date = LocalCompute.forRead(expenseTable.cell(row, "日期")),
                    vendor = expenseTable.cell(row, "商家"),
                    orderNo = expenseTable.cell(row, "订单号"),
                    note = expenseTable.cell(row, "备注"),
                    createdAt = normalizeStamp(expenseTable.cell(row, "添加时间")) ?: nowStamp(),
                    updatedAt = normalizeStamp(expenseTable.cell(row, "修改时间")) ?: nowStamp(),
                ),
            )
            expenseCount++
        }

        ImportReport(
            mode = mode,
            itemsCreated = created,
            itemsMatched = matched,
            allocations = allocCount,
            records = recordCount,
            expenses = expenseCount,
            roomsCreated = roomsCreated,
            categoriesCreated = categoriesCreated,
            warnings = warnings,
        )
    }

    /** 一张表：第一行是表头，按列名取单元格，列的顺序变了也能对上。 */
    private class Table(rows: List<List<String>>) {
        private val header: List<String> = rows.firstOrNull().orEmpty()
        val rows: List<List<String>> = rows.drop(1).filter { it.any { c -> c.isNotBlank() } }

        fun cell(row: List<String>, name: String): String {
            val index = header.indexOf(name)
            return if (index >= 0) row.getOrElse(index) { "" } else ""
        }

        fun number(row: List<String>, name: String): Double? =
            cell(row, name).trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    }
}
