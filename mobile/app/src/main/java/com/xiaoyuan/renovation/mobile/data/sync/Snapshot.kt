package com.xiaoyuan.renovation.mobile.data.sync

import androidx.room.withTransaction
import com.xiaoyuan.renovation.mobile.data.db.AllocationEntity
import com.xiaoyuan.renovation.mobile.data.db.AppDatabase
import com.xiaoyuan.renovation.mobile.data.db.ExtraExpenseEntity
import com.xiaoyuan.renovation.mobile.data.db.ItemEntity
import com.xiaoyuan.renovation.mobile.data.db.ItemListEntity
import com.xiaoyuan.renovation.mobile.data.db.PurchaseRecordEntity
import com.xiaoyuan.renovation.mobile.data.db.RecordRoomEntity
import com.xiaoyuan.renovation.mobile.data.db.RoomEntity
import com.xiaoyuan.renovation.mobile.data.db.CategoryEntity
import com.xiaoyuan.renovation.mobile.data.db.adopted
import com.xiaoyuan.renovation.mobile.data.db.nowStamp
import com.xiaoyuan.renovation.mobile.util.ListCodes

/**
 * 本地数据与搬运 payload 之间的来回转换。
 *
 * 一份清单整份进出：不做行级增量（一份家用清单就几百行，全量最简单，也不会
 * 出现"少了某个字段两边悄悄不一致"）。回收站里的条目也一起走，否则两端
 * 回收站会各有一半。
 */
object Snapshot {

    /** 把本地一份清单整份读成 payload。 */
    suspend fun capture(db: AppDatabase, listId: Int): SyncPayload {
        val list = db.lists().byId(listId) ?: error("清单不存在")
        val items = db.items().all(listId)
        val allocations = db.allocations().byList(listId).groupBy { it.itemId }
        val records = db.records().byList(listId).groupBy { it.itemId }
        val recordRooms = db.recordRooms().byList(listId).groupBy { it.recordId }

        return SyncPayload(
            list = SyncListMeta(
                name = list.name,
                note = list.note,
                sort = list.sort,
                code = list.code,
                createdAt = list.createdAt,
                updatedAt = list.updatedAt,
            ),
            rooms = db.rooms().byList(listId)
                .map { SyncRoom(id = it.id, name = it.name, sort = it.sort, createdAt = it.createdAt, updatedAt = it.updatedAt) },
            categories = db.categories().byList(listId)
                .map { SyncCategory(id = it.id, name = it.name, sort = it.sort, createdAt = it.createdAt, updatedAt = it.updatedAt) },
            items = items.map { item ->
                SyncItem(
                    id = item.id,
                    name = item.name,
                    categoryId = item.categoryId,
                    unit = item.unit,
                    brand = item.brand,
                    model = item.model,
                    qtyTotal = item.qtyTotal,
                    price = item.price,
                    discountPrice = item.discountPrice,
                    note = item.note,
                    sort = item.sort,
                    deletedAt = item.deletedAt,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                    allocations = allocations[item.id].orEmpty().map {
                        SyncAllocation(
                            id = it.id,
                            roomId = it.roomId,
                            qty = it.qty,
                            priceOverride = it.priceOverride,
                            note = it.note,
                        )
                    },
                    records = records[item.id].orEmpty().map { record ->
                        SyncRecord(
                            id = record.id,
                            qty = record.qty,
                            amount = record.amount,
                            date = record.date,
                            note = record.note,
                            vendor = record.vendor,
                            orderNo = record.orderNo,
                            roomIds = recordRooms[record.id].orEmpty().mapNotNull { it.roomId },
                            createdAt = record.createdAt,
                            updatedAt = record.updatedAt,
                        )
                    },
                )
            },
            expenses = db.expenses().byList(listId).map {
                SyncExpense(
                    id = it.id,
                    kind = it.kind,
                    amount = it.amount,
                    date = it.date,
                    vendor = it.vendor,
                    orderNo = it.orderNo,
                    note = it.note,
                    itemId = it.itemId,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
        )
    }

    /**
     * 用 payload 整份替换本地清单的内容，返回**服务器 id → 本地 id** 的映射。
     *
     * 映射必须留着：本地主键是自增的，跟服务器的 id 没有对应关系。下次同步
     * 要靠它认出"这两边哪一行是同一行"，才谈得上三方合并。
     *
     * 清单本身（名字、备注）不动 —— 覆盖的是内容，不是这份清单的归属。
     */
    suspend fun apply(
        db: AppDatabase,
        listId: Int,
        payload: SyncPayload,
    ): Map<String, Int> = db.withTransaction {
        // 重建之前，先把本地现有行的主键按名字收起来。
        //
        // 落地是「整份清掉再插」：每行都拿新主键的话，界面里缓存的旧 id 会当场
        // 失效 —— 同步刚跑完，列表点开物料就是「物料不存在」。所以同名行按出现
        // 顺序沿用原 id，只有服务器新带来的行才用自增主键。
        val spareRooms = spareIds(db.rooms().byList(listId)) { it.name to it.id }
        val spareCategories = spareIds(db.categories().byList(listId)) { it.name to it.id }
        val spareItems = spareIds(db.items().all(listId)) { it.name to it.id }
        val spareExpenses = spareIds(db.expenses().byList(listId)) {
            "${it.kind}|${it.amount}|${it.date}|${it.vendor}|${it.orderNo}" to it.id
        }

        clear(db, listId)

        // 编号跟着服务器那份走，两边显示同一个码，用户才对得上是同一份清单
        val incomingCode = ListCodes.normalize(payload.list.code)
        if (incomingCode.isNotEmpty()) {
            db.lists().byId(listId)?.let { db.lists().update(it.copy(code = incomingCode)) }
        }

        val map = mutableMapOf<String, Int>()

        val roomIds = mutableMapOf<Int, Int>()
        payload.rooms.forEach { room ->
            val newId = db.rooms().insert(
                RoomEntity(
                    id = spareRooms[room.name]?.removeFirstOrNull() ?: 0,
                    listId = listId, name = room.name, sort = room.sort,
                    createdAt = room.createdAt, updatedAt = room.updatedAt,
                ).adopted(),
            ).toInt()
            room.id?.let {
                roomIds[it] = newId
                map["room:$it"] = newId
            }
        }

        val categoryIds = mutableMapOf<Int, Int>()
        payload.categories.forEach { category ->
            val newId = db.categories().insert(
                CategoryEntity(
                    id = spareCategories[category.name]?.removeFirstOrNull() ?: 0,
                    listId = listId, name = category.name, sort = category.sort,
                    createdAt = category.createdAt, updatedAt = category.updatedAt,
                ).adopted(),
            ).toInt()
            category.id?.let {
                categoryIds[it] = newId
                map["category:$it"] = newId
            }
        }

        val itemIds = mutableMapOf<Int, Int>()
        payload.items.forEach { item ->
            val newId = db.items().insert(
                ItemEntity(
                    id = spareItems[item.name]?.removeFirstOrNull() ?: 0,
                    listId = listId,
                    name = item.name,
                    categoryId = item.categoryId?.let { categoryIds[it] },
                    unit = item.unit,
                    brand = item.brand,
                    model = item.model,
                    qtyTotal = item.qtyTotal,
                    price = item.price,
                    discountPrice = item.discountPrice,
                    note = item.note,
                    sort = item.sort,
                    deletedAt = item.deletedAt,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                ).adopted(),
            ).toInt()
            item.id?.let {
                itemIds[it] = newId
                map["item:$it"] = newId
            }

            item.allocations.forEach { alloc ->
                val roomId = alloc.roomId?.let { roomIds[it] } ?: return@forEach
                db.allocations().insert(
                    AllocationEntity(
                        itemId = newId,
                        roomId = roomId,
                        qty = alloc.qty,
                        priceOverride = alloc.priceOverride,
                        note = alloc.note,
                    ),
                )
            }

            item.records.forEach { record ->
                val newRecordId = db.records().insert(
                    PurchaseRecordEntity(
                        itemId = newId,
                        qty = record.qty,
                        amount = record.amount,
                        date = record.date,
                        note = record.note,
                        vendor = record.vendor,
                        orderNo = record.orderNo,
                        roomId = record.roomIds.firstOrNull()?.let { roomIds[it] },
                        createdAt = record.createdAt,
                        updatedAt = record.updatedAt,
                    ).adopted(),
                ).toInt()
                record.roomIds.forEach { remoteRoomId ->
                    roomIds[remoteRoomId]?.let { localRoomId ->
                        db.recordRooms().insert(
                            RecordRoomEntity(recordId = newRecordId, roomId = localRoomId),
                        )
                    }
                }
            }
        }

        payload.expenses.forEach { expense ->
            val key = "${expense.kind}|${expense.amount}|${expense.date}|${expense.vendor}|${expense.orderNo}"
            db.expenses().insert(
                ExtraExpenseEntity(
                    id = spareExpenses[key]?.removeFirstOrNull() ?: 0,
                    listId = listId,
                    kind = expense.kind,
                    amount = expense.amount,
                    date = expense.date,
                    vendor = expense.vendor,
                    orderNo = expense.orderNo,
                    note = expense.note,
                    itemId = expense.itemId?.let { itemIds[it] },
                    createdAt = expense.createdAt,
                    updatedAt = expense.updatedAt,
                ).adopted(),
            )
        }

        map
    }

    /**
     * 把现有行按配对键排成「主键队列」，供重建时按序沿用。
     *
     * 同键的多行必须各排各的：只认第一个的话，第二条仍会另拿新主键，界面里
     * 那一条的 id 照样失效。
     */
    private fun <T> spareIds(
        rows: List<T>,
        key: (T) -> Pair<String, Int>,
    ): Map<String, ArrayDeque<Int>> {
        val out = mutableMapOf<String, ArrayDeque<Int>>()
        rows.forEach { row ->
            val (name, id) = key(row)
            out.getOrPut(name) { ArrayDeque() }.addLast(id)
        }
        return out
    }

    /** 在本地新建一份清单并灌入内容，返回新清单 id。 */
    suspend fun createList(db: AppDatabase, payload: SyncPayload, name: String): Int {
        val listId = db.lists().insert(
            ItemListEntity(
                name = name,
                note = payload.list.note,
                sort = db.lists().nextSort(),
                // 从服务器拉下来的清单：时间戳跟着来源走（没有才用当下）
                createdAt = payload.list.createdAt.ifEmpty { nowStamp() },
                updatedAt = payload.list.updatedAt.ifEmpty { nowStamp() },
                // 服务器那份有编号就用它的：两边一致，一眼认得出是同一份
                code = ListCodes.normalize(payload.list.code).ifEmpty { ListCodes.new() },
            ),
        ).toInt()
        apply(db, listId, payload)
        return listId
    }

    /** 清空一份清单的内容（覆盖前的准备）。先删挂在下层的，再删主表。 */
    private suspend fun clear(db: AppDatabase, listId: Int) {
        db.expenses().byList(listId).forEach { db.expenses().delete(it) }
        db.items().all(listId).forEach { db.items().purgeCascade(it.id) }
        db.rooms().byList(listId).forEach { db.rooms().delete(it) }
        db.categories().byList(listId).forEach { db.categories().delete(it) }
    }
}
