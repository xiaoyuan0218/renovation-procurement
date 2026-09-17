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

    private val ITEM_HEADERS = listOf(
        "类目", "物料名称", "品牌", "型号", "单位", "数量", "单价", "优惠单价",
        "日常价", "实付数量", "实付金额", "未付数量", "未付金额",
        "日常价未付", "实际优惠", "日常价优惠",
        "已购", "备注", "物料ID",
    )
    private val ALLOC_HEADERS = listOf("物料名称", "房间", "数量", "单价", "备注", "物料ID")
    private val RECORD_HEADERS = listOf(
        "物料名称", "实付数量", "实付金额", "付款日期", "分组", "商家", "订单号", "备注", "物料ID",
    )
    private val EXPENSE_HEADERS = listOf("类型", "金额", "日期", "商家", "订单号", "备注")

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
                item.id,
            )

            mine.forEach { alloc ->
                // 数量为 0 的分配别处一律视为"没有这条"，导出时也不写，否则回灌会丢
                if (alloc.qty == 0.0) return@forEach
                allocRows += listOf(
                    item.name,
                    rooms[alloc.roomId]?.name ?: "",
                    alloc.qty,
                    // 写原始的覆盖价：留空表示跟随物料单价，写死会变成固定价
                    alloc.priceOverride,
                    alloc.note,
                    item.id,
                )
            }

            bundle.records.forEach { record ->
                val names = recordRooms[record.id].orEmpty()
                    .mapNotNull { it.roomId?.let { id -> rooms[id]?.name } }
                recordRows += listOf(
                    item.name,
                    record.qty,
                    record.amount,
                    record.date,
                    // 多选用顿号连起来，导入时按分隔符拆回
                    names.joinToString("、"),
                    record.vendor,
                    record.orderNo,
                    record.note,
                    item.id,
                )
            }
        }

        val expenseRows: MutableList<List<Any?>> = mutableListOf(EXPENSE_HEADERS)
        db.expenses().byList(listId).forEach { e ->
            expenseRows += listOf(e.kind, e.amount, e.date, e.vendor, e.orderNo, e.note)
        }

        return listOf(
            "物料汇总" to itemRows,
            "布点明细" to allocRows,
            "采购记录" to recordRows,
            "额外费用" to expenseRows,
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

        // 按名字找到的现有物料（merge 时用）
        val byName = db.items().all(listId)
            .associateBy({ it.name }, { it })
            .toMutableMap()

        if (mode == "replace") {
            db.items().all(listId).forEach { db.items().purgeCascade(it.id) }
            byName.clear()
            db.expenses().byList(listId).forEach { db.expenses().delete(it) }
        }

        // 物料 ID 列 → 本地 id，供布点与采购记录对上号
        val idMap = mutableMapOf<String, Int>()

        for (row in itemTable.rows) {
            val name = itemTable.cell(row, "物料名称").trim()
            if (name.isEmpty()) continue

            val existing = byName[name]
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
                    rev = current.rev + 1,
                ),
            )
            byName[name] = db.items().byId(itemId)!!

            val remoteId = itemTable.cell(row, "物料ID").trim()
            if (remoteId.isNotEmpty()) idMap[remoteId] = itemId
        }

        for (row in allocTable.rows) {
            val itemName = allocTable.cell(row, "物料名称").trim()
            val itemId = byName[itemName]?.id
            if (itemId == null) {
                warnings += "布点明细里的物料「$itemName」在表格里找不到，已跳过"
                continue
            }
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
            val itemId = byName[itemName]?.id
            if (itemId == null) {
                warnings += "采购记录里的物料「$itemName」在表格里找不到，已跳过"
                continue
            }
            val id = db.records().insert(
                PurchaseRecordEntity(
                    itemId = itemId,
                    qty = recordTable.number(row, "实付数量") ?: 0.0,
                    amount = recordTable.number(row, "实付金额") ?: 0.0,
                    date = recordTable.cell(row, "付款日期"),
                    note = recordTable.cell(row, "备注"),
                    vendor = recordTable.cell(row, "商家"),
                    orderNo = recordTable.cell(row, "订单号"),
                ),
            ).toInt()
            recordTable.cell(row, "分组")
                .split("、", ",", "，")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { name -> roomId(name)?.let { db.recordRooms().insert(RecordRoomEntity(id, it)) } }
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
                    date = expenseTable.cell(row, "日期"),
                    vendor = expenseTable.cell(row, "商家"),
                    orderNo = expenseTable.cell(row, "订单号"),
                    note = expenseTable.cell(row, "备注"),
                    createdAt = nowStamp(),
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
