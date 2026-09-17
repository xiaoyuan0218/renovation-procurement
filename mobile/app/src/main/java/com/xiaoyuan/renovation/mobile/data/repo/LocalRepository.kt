package com.xiaoyuan.renovation.mobile.data.repo

import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.CategoryEntity
import com.xiaoyuan.renovation.mobile.data.db.ExtraExpenseEntity
import com.xiaoyuan.renovation.mobile.data.db.ItemEntity
import com.xiaoyuan.renovation.mobile.data.db.PurchaseRecordEntity
import com.xiaoyuan.renovation.mobile.data.db.RecordRoomEntity
import com.xiaoyuan.renovation.mobile.data.db.RoomEntity
import com.xiaoyuan.renovation.mobile.data.db.nowStamp
import com.xiaoyuan.renovation.mobile.data.model.AllocationDto
import com.xiaoyuan.renovation.mobile.data.model.BatchDeleteResultDto
import com.xiaoyuan.renovation.mobile.data.model.CategoryDto
import com.xiaoyuan.renovation.mobile.data.model.CellSaveResultDto
import com.xiaoyuan.renovation.mobile.data.model.ExpenseDto
import com.xiaoyuan.renovation.mobile.data.model.ExpenseInDto
import com.xiaoyuan.renovation.mobile.data.model.ItemDto
import com.xiaoyuan.renovation.mobile.data.model.ItemInDto
import com.xiaoyuan.renovation.mobile.data.model.ItemListDto
import com.xiaoyuan.renovation.mobile.data.model.MatrixDto
import com.xiaoyuan.renovation.mobile.data.model.OkDto
import com.xiaoyuan.renovation.mobile.data.model.PurgeResultDto
import com.xiaoyuan.renovation.mobile.data.model.RecordInDto
import com.xiaoyuan.renovation.mobile.data.model.RecordPatchDto
import com.xiaoyuan.renovation.mobile.data.model.RoomDto
import com.xiaoyuan.renovation.mobile.data.model.SummaryDto
import com.xiaoyuan.renovation.mobile.data.model.TrashItemDto
import com.xiaoyuan.renovation.mobile.domain.ItemBundle
import com.xiaoyuan.renovation.mobile.domain.LocalCompute
import com.xiaoyuan.renovation.mobile.domain.SummaryCompute
import com.xiaoyuan.renovation.mobile.util.ListCodes
import com.xiaoyuan.renovation.mobile.domain.LocalCompute.toDto
import com.xiaoyuan.renovation.mobile.domain.LocalCompute.toMatrixItem
import com.xiaoyuan.renovation.mobile.data.db.AllocationEntity
import com.xiaoyuan.renovation.mobile.data.db.deleteCategoryChecked
import com.xiaoyuan.renovation.mobile.data.db.deleteRoomCascade
import com.xiaoyuan.renovation.mobile.data.db.stamped
import androidx.room.withTransaction

/**
 * 本地数据仓储：方法名与返回类型都和网络版的 `RenovationRepository` 一致，
 * 只是数据来自手机自己的库。界面层因此可以在两版之间照搬。
 *
 * 这里也是**本地写操作的唯一出口** —— 四期做同步时，「本地改过没有」的判断
 * 就挂在这些方法上，所以界面不要绕过它直接写 DAO。
 *
 * 约定：写操作会把物料的 `rev` +1（与后端一致，便于同步后两边的观感相同）。
 */
class LocalRepository(
    private val db: AppDatabase,
    private val currentList: CurrentListHolder,
    /** 写成功后的回调：容器据此 +1 数据版本号，别的页面会自动重读。 */
    private val onDataChanged: () -> Unit = {},
) {

    /* ---------------- 清单 ---------------- */

    suspend fun lists(): ApiResult<List<ItemListDto>> = localCall {
        db.lists().all().map { l ->
            ItemListDto(
                id = l.id,
                name = l.name,
                note = l.note,
                sort = l.sort,
                code = l.code,
                itemCount = db.items().aliveCount(l.id),
                roomCount = db.rooms().byList(l.id).size,
                categoryCount = db.categories().byList(l.id).size,
            )
        }
    }

    /** 当前这份清单本身（页面标题、空状态提示用）。 */
    suspend fun currentList(): ApiResult<ItemListDto> = localCall { listDto(currentList.require()) }

    /** 新建清单。[copyFrom] 传另一份清单的 id 时只照抄它的分组与分类（不带物料）。 */
    suspend fun createList(name: String, note: String = "", copyFrom: Int? = null): ApiResult<ItemListDto> =
        mutate {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "清单名不能为空" }
            require(db.lists().byName(trimmed) == null) { "已经有一份叫「$trimmed」的清单了" }
            val id = db.withTransaction {
                val newId = db.lists().insert(
                    com.xiaoyuan.renovation.mobile.data.db.ItemListEntity(
                        name = trimmed,
                        note = note,
                        sort = db.lists().nextSort(),
                        // 本地新建时就发编号：之后传到服务器上，两边编号一致
                        code = ListCodes.new(),
                    ).stamped(),
                ).toInt()
                if (copyFrom != null) {
                    db.rooms().byList(copyFrom).forEach { r ->
                        db.rooms().insert(
                            RoomEntity(listId = newId, name = r.name, sort = r.sort).stamped(),
                        )
                    }
                    db.categories().byList(copyFrom).forEach { c ->
                        db.categories().insert(
                            CategoryEntity(listId = newId, name = c.name, sort = c.sort).stamped(),
                        )
                    }
                }
                newId
            }
            currentList.set(id)
            listDto(id)
        }

    suspend fun renameList(id: Int, name: String, note: String = "", sort: Int = 0): ApiResult<ItemListDto> =
        mutate {
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "清单名不能为空" }
            val dup = db.lists().byName(trimmed)
            require(dup == null || dup.id == id) { "已经有一份叫「$trimmed」的清单了" }
            val existing = db.lists().byId(id) ?: error("清单不存在")
            db.lists().update(existing.copy(name = trimmed, note = note, sort = sort).stamped())
            listDto(id)
        }

    /** 删清单会连它名下的物料、分组、分类、费用一起删掉；最后一份不允许删。 */
    suspend fun deleteList(id: Int): ApiResult<OkDto> = mutate {
        require(db.lists().count() > 1) { "至少要留一份清单" }
        db.lists().deleteCascade(id)
        db.lists().all().firstOrNull()?.let { currentList.set(it.id) }
        OkDto()
    }

    private suspend fun listDto(id: Int): ItemListDto {
        val l = db.lists().byId(id) ?: error("清单不存在")
        return ItemListDto(
            id = l.id,
            name = l.name,
            note = l.note,
            sort = l.sort,
            code = l.code,
            itemCount = db.items().aliveCount(l.id),
            roomCount = db.rooms().byList(l.id).size,
            categoryCount = db.categories().byList(l.id).size,
        )
    }

    /* ---------------- 物料 ---------------- */

    suspend fun items(): ApiResult<List<ItemDto>> = localCall {
        bundles(currentList.require()).map { LocalCompute.toDto(it) }
    }

    suspend fun item(id: Int): ApiResult<ItemDto> = localCall {
        val listId = currentList.require()
        val bundle = bundles(listId).firstOrNull { it.item.id == id } ?: error("物料不存在")
        LocalCompute.toDto(bundle)
    }

    suspend fun createItem(body: ItemInDto): ApiResult<ItemDto> = mutate {
        val listId = currentList.require()
        val name = body.name.trim()
        require(name.isNotEmpty()) { "物料名不能为空" }
        val id = db.withTransaction {
            val newId = db.items().insert(
                ItemEntity(
                    listId = listId,
                    name = name,
                    categoryId = body.categoryId,
                    unit = body.unit.ifBlank { "个" },
                    brand = body.brand,
                    model = body.model,
                    qtyTotal = body.qtyTotal,
                    price = body.price,
                    discountPrice = body.discountPrice,
                    note = body.note,
                    sort = db.items().nextSort(listId),
                ).stamped(),
            ).toInt()
            replaceAllocations(newId, body.allocations)
            replaceRecords(newId, body.records)
            newId
        }
        LocalCompute.toDto(bundleOf(id))
    }

    suspend fun updateItem(id: Int, body: ItemInDto): ApiResult<ItemDto> = mutate {
        val name = body.name.trim()
        require(name.isNotEmpty()) { "物料名不能为空" }
        db.withTransaction {
            val existing = db.items().byId(id) ?: error("物料不存在")
            db.items().update(
                existing.copy(
                    name = name,
                    categoryId = body.categoryId,
                    unit = body.unit.ifBlank { "个" },
                    brand = body.brand,
                    model = body.model,
                    qtyTotal = body.qtyTotal,
                    price = body.price,
                    discountPrice = body.discountPrice,
                    note = body.note,
                    rev = existing.rev + 1,
                ).stamped(),
            )
            // 传 null 表示这两个集合保持不动（与后端 ItemIn 语义一致）
            if (body.allocations != null) replaceAllocations(id, body.allocations)
            if (body.records != null) replaceRecords(id, body.records)
        }
        LocalCompute.toDto(bundleOf(id))
    }

    suspend fun deleteItem(id: Int): ApiResult<OkDto> = mutate {
        require(db.items().aliveById(id) != null) { "物料不存在" }
        db.items().softDelete(id, nowStamp())
        OkDto()
    }

    suspend fun batchDeleteItems(ids: List<Int>): ApiResult<BatchDeleteResultDto> = mutate {
        val stamp = nowStamp()
        var deleted = 0
        db.withTransaction {
            ids.forEach { id ->
                if (db.items().aliveById(id) == null) return@forEach
                db.items().softDelete(id, stamp)
                deleted++
            }
        }
        BatchDeleteResultDto(deleted = deleted)
    }

    /* ---------------- 采购记录 ---------------- */

    suspend fun addRecord(itemId: Int, body: RecordInDto): ApiResult<ItemDto> = mutate {
        require(db.items().aliveById(itemId) != null) { "物料不存在" }
        db.withTransaction {
            insertRecord(itemId, body)
            touch(itemId)
        }
        LocalCompute.toDto(bundleOf(itemId))
    }

    suspend fun clearRecords(itemId: Int): ApiResult<ItemDto> = mutate {
        require(db.items().aliveById(itemId) != null) { "物料不存在" }
        db.withTransaction {
            db.records().ofItem(itemId).forEach { db.recordRooms().deleteOfRecord(it.id) }
            db.items().deleteRecordsOfItem(itemId)
            touch(itemId)
        }
        LocalCompute.toDto(bundleOf(itemId))
    }

    /**
     * 改一笔记录。字段传 null 表示"这个字段不改" —— 与后端 patch 的语义一致，
     * 从别的客户端带过来的整个对象里那些没动过的 null 字段不会把数据抹掉。
     */
    suspend fun updateRecord(recordId: Int, body: RecordPatchDto): ApiResult<ItemDto> = mutate {
        val record = db.records().byId(recordId) ?: error("记录不存在")
        val itemId = record.itemId
        db.withTransaction {
            db.records().update(
                record.copy(
                    qty = body.qty ?: record.qty,
                    amount = body.amount ?: record.amount,
                    date = if (body.date != null) LocalCompute.cleanDate(body.date) else record.date,
                    note = body.note ?: record.note,
                    vendor = body.vendor ?: record.vendor,
                    orderNo = body.orderNo ?: record.orderNo,
                ).stamped(),
            )
            if (body.roomIds != null) {
                db.recordRooms().deleteOfRecord(recordId)
                body.roomIds.filter { it != 0 }.forEach {
                    db.recordRooms().insert(RecordRoomEntity(recordId = recordId, roomId = it))
                }
            }
            touch(itemId)
        }
        LocalCompute.toDto(bundleOf(itemId))
    }

    suspend fun deleteRecord(recordId: Int): ApiResult<ItemDto> = mutate {
        val record = db.records().byId(recordId) ?: error("记录不存在")
        val itemId = record.itemId
        db.withTransaction {
            db.recordRooms().deleteOfRecord(recordId)
            db.records().delete(record)
            touch(itemId)
        }
        LocalCompute.toDto(bundleOf(itemId))
    }

    /* ---------------- 分组 ---------------- */

    suspend fun rooms(): ApiResult<List<RoomDto>> = localCall {
        db.rooms().byList(currentList.require()).map { RoomDto(it.id, it.name, it.sort) }
    }

    suspend fun createRoom(name: String): ApiResult<RoomDto> = mutate {
        val listId = currentList.require()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "分组名不能为空" }
        val id = db.rooms().insert(
            RoomEntity(listId = listId, name = trimmed, sort = db.rooms().nextSort(listId)).stamped(),
        ).toInt()
        RoomDto(id, trimmed, 0)
    }

    suspend fun renameRoom(id: Int, name: String): ApiResult<RoomDto> = mutate {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "分组名不能为空" }
        val existing = db.rooms().byId(id) ?: error("分组不存在")
        db.rooms().update(existing.copy(name = trimmed).stamped())
        RoomDto(id, trimmed, existing.sort)
    }

    /** 删分组：它的分配跟着走，但采购记录只解除关联（那是钱，不能丢）。 */
    suspend fun deleteRoom(id: Int): ApiResult<OkDto> = mutate {
        val target = db.rooms().byId(id) ?: error("分组不存在")
        db.deleteRoomCascade(target)
        OkDto()
    }

    /* ---------------- 分类 ---------------- */

    suspend fun categories(): ApiResult<List<CategoryDto>> = localCall {
        db.categories().byList(currentList.require()).map { CategoryDto(it.id, it.name, it.sort) }
    }

    suspend fun createCategory(name: String): ApiResult<CategoryDto> = mutate {
        val listId = currentList.require()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "分类名不能为空" }
        require(db.categories().byName(listId, trimmed) == null) { "已经有「$trimmed」这个分类了" }
        val id = db.categories().insert(
            CategoryEntity(listId = listId, name = trimmed, sort = db.categories().nextSort(listId)).stamped(),
        ).toInt()
        CategoryDto(id, trimmed, 0)
    }

    suspend fun renameCategory(id: Int, name: String): ApiResult<CategoryDto> = mutate {
        val listId = currentList.require()
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "分类名不能为空" }
        val dup = db.categories().byName(listId, trimmed)
        require(dup == null || dup.id == id) { "已经有「$trimmed」这个分类了" }
        val existing = db.categories().byId(id) ?: error("分类不存在")
        db.categories().update(existing.copy(name = trimmed).stamped())
        CategoryDto(id, trimmed, existing.sort)
    }

    /** 删分类：该分类下还有物料时不允许删（与后端同口径）。 */
    suspend fun deleteCategory(id: Int): ApiResult<OkDto> = mutate {
        val target = db.categories().byId(id) ?: error("分类不存在")
        db.deleteCategoryChecked(target)
        OkDto()
    }

    /* ---------------- 分配矩阵 ---------------- */

    suspend fun matrix(): ApiResult<MatrixDto> = localCall {
        val listId = currentList.require()
        MatrixDto(
            rooms = db.rooms().byList(listId).map { RoomDto(it.id, it.name, it.sort) },
            items = bundles(listId).map { LocalCompute.toMatrixItem(it) },
        )
    }

    /** 存一格：[qty] 为 0 表示清掉这一格（与后端 PUT /api/matrix/cell 一致）。 */
    suspend fun saveCell(
        itemId: Int,
        roomId: Int,
        qty: Double,
        priceOverride: Double?,
        note: String,
    ): ApiResult<CellSaveResultDto> = mutate {
        require(db.items().aliveById(itemId) != null) { "物料不存在" }
        require(db.rooms().byId(roomId) != null) { "分组不存在" }
        var deleted = false
        db.withTransaction {
            val existing = db.allocations().ofItem(itemId).firstOrNull { it.roomId == roomId }
            if (qty == 0.0) {
                if (existing != null) {
                    db.allocations().delete(existing)
                    deleted = true
                }
            } else if (existing == null) {
                db.allocations().insert(
                    AllocationEntity(
                        itemId = itemId,
                        roomId = roomId,
                        qty = qty,
                        priceOverride = priceOverride,
                        note = note,
                    ),
                )
            } else {
                db.allocations().update(
                    existing.copy(qty = qty, priceOverride = priceOverride, note = note),
                )
            }
            if (deleted || qty != 0.0) touch(itemId)
        }
        CellSaveResultDto(ok = true, deleted = deleted)
    }

    /** 某个物料在某个分组的分配（给"分配数量"这类局部编辑用）。 */
    suspend fun allocationOf(itemId: Int, roomId: Int): AllocationDto? = localCall {
        db.allocations().ofItem(itemId).firstOrNull { it.roomId == roomId }?.let { a ->
            AllocationDto(a.id, a.itemId, a.roomId, a.qty, a.priceOverride, a.note)
        }
    }.okData

    /* ---------------- 总览 ---------------- */

    /** 汇总：合计、分类/分组分布、未买齐、按月已付、额外费用。口径对齐后端 summary.py。 */
    suspend fun summary(): ApiResult<SummaryDto> = localCall {
        val listId = currentList.require()
        SummaryCompute.summary(
            bundles = bundles(listId),
            categories = db.categories().byList(listId),
            rooms = db.rooms().byList(listId),
            expenses = db.expenses().byList(listId),
        )
    }

    /* ---------------- 额外费用（运费/安装费） ----------------
     *
     * 这类钱独立于物料：不参与原价/日常价的三段拆分，只在总览单独汇总。
     */

    suspend fun expenses(): ApiResult<List<ExpenseDto>> = localCall {
        val listId = currentList.require()
        db.expenses().byList(listId).map { LocalCompute.toDto(it, itemName(it.itemId)) }
    }

    suspend fun createExpense(body: ExpenseInDto): ApiResult<ExpenseDto> = mutate {
        val listId = currentList.require()
        require(body.amount > 0) { "金额要大于 0" }
        val id = db.expenses().insert(
            ExtraExpenseEntity(
                listId = listId,
                kind = body.kind.trim().ifEmpty { "其他" },
                amount = body.amount,
                date = LocalCompute.cleanDate(body.date),
                vendor = body.vendor,
                orderNo = body.orderNo,
                note = body.note,
                itemId = body.itemId,
            ).stamped(),
        ).toInt()
        val saved = db.expenses().byId(id) ?: error("保存失败")
        LocalCompute.toDto(saved, itemName(saved.itemId))
    }

    suspend fun updateExpense(id: Int, body: ExpenseInDto): ApiResult<ExpenseDto> = mutate {
        val existing = db.expenses().byId(id) ?: error("这笔费用不存在")
        require(body.amount > 0) { "金额要大于 0" }
        val next = existing.copy(
            kind = body.kind.trim().ifEmpty { "其他" },
            amount = body.amount,
            date = LocalCompute.cleanDate(body.date),
            vendor = body.vendor,
            orderNo = body.orderNo,
            note = body.note,
            itemId = body.itemId,
        ).stamped()
        db.expenses().update(next)
        LocalCompute.toDto(next, itemName(next.itemId))
    }

    suspend fun deleteExpense(id: Int): ApiResult<OkDto> = mutate {
        val existing = db.expenses().byId(id) ?: error("这笔费用不存在")
        db.expenses().delete(existing)
        OkDto()
    }

    /* ---------------- 回收站 ---------------- */

    suspend fun trash(): ApiResult<List<TrashItemDto>> = localCall {
        val listId = currentList.require()
        bundles(listId, includeDeleted = true).map { bundle ->
            val dto = LocalCompute.toDto(bundle)
            TrashItemDto(
                id = dto.id,
                name = dto.name,
                deletedAt = bundle.item.deletedAt.orEmpty(),
                listTotal = dto.listTotal,
                paid = dto.paid,
            )
        }
    }

    suspend fun restoreItem(id: Int): ApiResult<TrashItemDto> = mutate {
        require(db.items().byId(id)?.deletedAt != null) { "这条物料不在回收站里" }
        db.items().restore(id)
        val dto = LocalCompute.toDto(bundleOf(id))
        TrashItemDto(dto.id, dto.name, "", dto.listTotal, dto.paid)
    }

    /** 彻底删除：连它的分配与采购记录一起，不可恢复。 */
    suspend fun purgeItem(id: Int): ApiResult<OkDto> = mutate {
        db.items().purgeCascade(id)
        OkDto()
    }

    suspend fun purgeTrash(): ApiResult<PurgeResultDto> = mutate {
        val listId = currentList.require()
        val rows = db.items().trash(listId)
        rows.forEach { db.items().purgeCascade(it.id) }
        PurgeResultDto(deleted = rows.size, ok = true)
    }

    /* ---------------- 内部 ---------------- */

    /**
     * 写操作：成功后通知界面刷新。读操作用 localCall 即可 ——
     * 读也通知的话，「刷新 → 读 → 又通知刷新」会转成死循环。
     */
    private suspend fun <T> mutate(block: suspend () -> T): ApiResult<T> =
        localCall(block).onOk { onDataChanged() }

    private suspend fun itemName(itemId: Int?): String =
        itemId?.let { db.items().byId(it)?.name }.orEmpty()

    private suspend fun touch(itemId: Int) {
        val existing = db.items().byId(itemId) ?: return
        db.items().update(existing.copy(rev = existing.rev + 1).stamped())
    }

    private suspend fun replaceAllocations(itemId: Int, allocations: List<com.xiaoyuan.renovation.mobile.data.model.AllocInDto>?) {
        if (allocations == null) return
        db.allocations().deleteOfItem(itemId)
        allocations.filter { it.qty != 0.0 }.forEach { a ->
            db.allocations().insert(
                AllocationEntity(
                    itemId = itemId,
                    roomId = a.roomId,
                    qty = a.qty,
                    priceOverride = a.priceOverride,
                    note = a.note,
                ),
            )
        }
    }

    private suspend fun replaceRecords(itemId: Int, records: List<RecordInDto>?) {
        if (records == null) return
        db.records().ofItem(itemId).forEach { db.recordRooms().deleteOfRecord(it.id) }
        db.items().deleteRecordsOfItem(itemId)
        records.forEach { insertRecord(itemId, it) }
    }

    private suspend fun insertRecord(itemId: Int, body: RecordInDto) {
        val id = db.records().insert(
            PurchaseRecordEntity(
                itemId = itemId,
                qty = body.qty,
                amount = body.amount,
                date = LocalCompute.cleanDate(body.date),
                note = body.note,
                vendor = body.vendor,
                orderNo = body.orderNo,
                roomId = body.roomIds.firstOrNull(),
            ).stamped(),
        ).toInt()
        body.roomIds.filter { it != 0 }.forEach {
            db.recordRooms().insert(RecordRoomEntity(recordId = id, roomId = it))
        }
    }

    /** 一条物料连同它的分配、记录、记录涉及的分组。 */
    private suspend fun bundleOf(itemId: Int): ItemBundle {
        val item = db.items().byId(itemId) ?: error("物料不存在")
        val categoryName = item.categoryId?.let { db.categories().byId(it)?.name }
        val recordRooms = db.records().ofItem(itemId)
            .associate { r -> r.id to db.recordRooms().ofRecord(r.id).mapNotNull { it.roomId } }
        return ItemBundle(
            item = item,
            categoryName = categoryName,
            allocations = db.allocations().ofItem(itemId),
            records = db.records().ofItem(itemId),
            recordRooms = recordRooms,
        )
    }

    /** 整份清单的物料（含各自的分配与记录），一次读齐再组装。[includeDeleted] 为真时读回收站里的。 */
    private suspend fun bundles(listId: Int, includeDeleted: Boolean = false): List<ItemBundle> {
        val items = if (includeDeleted) db.items().trash(listId) else db.items().alive(listId)
        if (items.isEmpty()) return emptyList()
        val categories = db.categories().byList(listId).associateBy { it.id }
        val allocations = db.allocations().byList(listId).groupBy { it.itemId }
        val records = db.records().byList(listId).groupBy { it.itemId }
        val recordRooms = db.recordRooms().byList(listId).groupBy { it.recordId }
        return items.map { item ->
            ItemBundle(
                item = item,
                categoryName = item.categoryId?.let { categories[it]?.name },
                allocations = allocations[item.id].orEmpty(),
                records = records[item.id].orEmpty(),
                recordRooms = recordRooms.mapValues { (_, rows) -> rows.mapNotNull { it.roomId } },
            )
        }
    }
}
