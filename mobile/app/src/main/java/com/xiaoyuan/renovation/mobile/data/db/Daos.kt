package com.xiaoyuan.renovation.mobile.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 本地库的读写入口。
 *
 * 单机版没有服务端兜底，所以「删清单 / 删物料」的正确性得自己保证：
 * 表上都声明了外键，但删除一律走显式的事务方法（逐表删），不依赖
 * `PRAGMA foreign_keys` 开关的状态。
 */

@Dao
interface ListDao {
    @Query("SELECT * FROM lists ORDER BY sort, id")
    fun observeAll(): Flow<List<ItemListEntity>>

    @Query("SELECT * FROM lists ORDER BY sort, id")
    suspend fun all(): List<ItemListEntity>

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun byId(id: Int): ItemListEntity?

    @Query("SELECT * FROM lists WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): ItemListEntity?

    @Query("SELECT COUNT(*) FROM lists")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sort), -1) + 1 FROM lists")
    suspend fun nextSort(): Int

    @Insert
    suspend fun insert(list: ItemListEntity): Long

    @Update
    suspend fun update(list: ItemListEntity)

    /** 删清单：把它名下的数据逐表清掉，最后删清单本身。 */
    @Transaction
    suspend fun deleteCascade(id: Int) {
        deleteRecordRoomsOfList(id)
        deleteRecordsOfList(id)
        deleteAllocationsOfList(id)
        deleteExpensesOfList(id)
        deleteItemsOfList(id)
        deleteRoomsOfList(id)
        deleteCategoriesOfList(id)
        deleteByIdDirect(id)
    }

    @Query("DELETE FROM record_rooms WHERE record_id IN (SELECT r.id FROM purchase_records r JOIN items i ON i.id = r.item_id WHERE i.list_id = :id)")
    suspend fun deleteRecordRoomsOfList(id: Int)

    @Query("DELETE FROM purchase_records WHERE item_id IN (SELECT id FROM items WHERE list_id = :id)")
    suspend fun deleteRecordsOfList(id: Int)

    @Query("DELETE FROM allocations WHERE item_id IN (SELECT id FROM items WHERE list_id = :id)")
    suspend fun deleteAllocationsOfList(id: Int)

    @Query("DELETE FROM extra_expenses WHERE list_id = :id")
    suspend fun deleteExpensesOfList(id: Int)

    @Query("DELETE FROM items WHERE list_id = :id")
    suspend fun deleteItemsOfList(id: Int)

    @Query("DELETE FROM rooms WHERE list_id = :id")
    suspend fun deleteRoomsOfList(id: Int)

    @Query("DELETE FROM categories WHERE list_id = :id")
    suspend fun deleteCategoriesOfList(id: Int)

    @Query("DELETE FROM lists WHERE id = :id")
    suspend fun deleteByIdDirect(id: Int)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE list_id = :listId ORDER BY sort, id")
    fun observeByList(listId: Int): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE list_id = :listId ORDER BY sort, id")
    suspend fun byList(listId: Int): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: Int): CategoryEntity?

    @Query("SELECT * FROM categories WHERE list_id = :listId AND name = :name LIMIT 1")
    suspend fun byName(listId: Int, name: String): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sort), -1) + 1 FROM categories WHERE list_id = :listId")
    suspend fun nextSort(listId: Int): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)
}

@Dao
interface RoomDao {
    @Query("SELECT * FROM rooms WHERE list_id = :listId ORDER BY sort, id")
    fun observeByList(listId: Int): Flow<List<RoomEntity>>

    @Query("SELECT * FROM rooms WHERE list_id = :listId ORDER BY sort, id")
    suspend fun byList(listId: Int): List<RoomEntity>

    @Query("SELECT * FROM rooms WHERE id = :id")
    suspend fun byId(id: Int): RoomEntity?

    @Query("SELECT * FROM rooms WHERE list_id = :listId AND name = :name LIMIT 1")
    suspend fun byName(listId: Int, name: String): RoomEntity?

    @Query("SELECT COALESCE(MAX(sort), -1) + 1 FROM rooms WHERE list_id = :listId")
    suspend fun nextSort(listId: Int): Int

    @Insert
    suspend fun insert(room: RoomEntity): Long

    @Update
    suspend fun update(room: RoomEntity)

    @Delete
    suspend fun delete(room: RoomEntity)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE list_id = :listId AND deleted_at IS NULL ORDER BY sort, id")
    fun observeAlive(listId: Int): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE list_id = :listId AND deleted_at IS NULL ORDER BY sort, id")
    suspend fun alive(listId: Int): List<ItemEntity>

    /** 含回收站的全部物料：搬运时回收站也要跟着走，两端才一致。 */
    @Query("SELECT * FROM items WHERE list_id = :listId ORDER BY sort, id")
    suspend fun all(listId: Int): List<ItemEntity>

    @Query("SELECT * FROM items WHERE list_id = :listId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeTrash(listId: Int): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE list_id = :listId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    suspend fun trash(listId: Int): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: Int): ItemEntity?

    @Query("SELECT * FROM items WHERE id = :id AND deleted_at IS NULL")
    suspend fun aliveById(id: Int): ItemEntity?

    @Query("SELECT * FROM items WHERE list_id = :listId AND deleted_at IS NULL AND name = :name LIMIT 1")
    suspend fun aliveByName(listId: Int, name: String): ItemEntity?

    @Query("SELECT COALESCE(MAX(sort), -1) + 1 FROM items WHERE list_id = :listId")
    suspend fun nextSort(listId: Int): Int

    @Query("SELECT COUNT(*) FROM items WHERE list_id = :listId AND deleted_at IS NULL")
    suspend fun aliveCount(listId: Int): Int

    /**
     * 用了这个分类的物料数。**含回收站里的** —— 与后端 `cat.items` 同口径
     * （那边的关系不过滤软删），两端行为要一致，要改就一起改。
     */
    @Query("SELECT COUNT(*) FROM items WHERE category_id = :categoryId")
    suspend fun countByCategory(categoryId: Int): Int

    @Insert
    suspend fun insert(item: ItemEntity): Long

    @Update
    suspend fun update(item: ItemEntity)

    /**
     * 移入回收站。rev 一起在这里推进 —— 不要改成"先软删、再 @Update 整行写回"：
     * 那样会把刚写上的 deleted_at 覆盖成读出来的 null，删除就静默失效了。
     */
    @Query("UPDATE items SET deleted_at = :at, rev = rev + 1 WHERE id = :id")
    suspend fun softDelete(id: Int, at: String)

    @Query("UPDATE items SET deleted_at = NULL WHERE id = :id")
    suspend fun restore(id: Int)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun purge(id: Int)

    /**
     * 删物料：记录和分配跟着走（它们只对这条物料有意义），
     * 但关联到它的额外费用只解除关联 —— 那是钱，留着。
     */
    @Transaction
    suspend fun purgeCascade(id: Int) {
        clearExpenseRefs(id)
        deleteRecordRoomsOfItem(id)
        deleteRecordsOfItem(id)
        deleteAllocationsOfItem(id)
        purge(id)
    }

    @Query("UPDATE extra_expenses SET item_id = NULL WHERE item_id = :id")
    suspend fun clearExpenseRefs(id: Int)

    @Query("DELETE FROM record_rooms WHERE record_id IN (SELECT id FROM purchase_records WHERE item_id = :id)")
    suspend fun deleteRecordRoomsOfItem(id: Int)

    @Query("DELETE FROM purchase_records WHERE item_id = :id")
    suspend fun deleteRecordsOfItem(id: Int)

    @Query("DELETE FROM allocations WHERE item_id = :id")
    suspend fun deleteAllocationsOfItem(id: Int)
}

@Dao
interface RecordDao {
    @Query(
        "SELECT r.* FROM purchase_records r JOIN items i ON i.id = r.item_id " +
            "WHERE i.list_id = :listId ORDER BY r.id",
    )
    fun observeByList(listId: Int): Flow<List<PurchaseRecordEntity>>

    @Query(
        "SELECT r.* FROM purchase_records r JOIN items i ON i.id = r.item_id " +
            "WHERE i.list_id = :listId ORDER BY r.id",
    )
    suspend fun byList(listId: Int): List<PurchaseRecordEntity>

    @Query("SELECT * FROM purchase_records WHERE item_id = :itemId ORDER BY id")
    suspend fun ofItem(itemId: Int): List<PurchaseRecordEntity>

    @Query("SELECT * FROM purchase_records WHERE id = :id")
    suspend fun byId(id: Int): PurchaseRecordEntity?

    @Insert
    suspend fun insert(record: PurchaseRecordEntity): Long

    @Update
    suspend fun update(record: PurchaseRecordEntity)

    @Delete
    suspend fun delete(record: PurchaseRecordEntity)

    /** 解除这批记录与某个分组的关联（删分组时用，记录本身留着）。 */
    @Query("UPDATE purchase_records SET room_id = NULL WHERE room_id = :roomId")
    suspend fun detachRoom(roomId: Int)
}

@Dao
interface RecordRoomDao {
    @Query(
        "SELECT rr.* FROM record_rooms rr " +
            "JOIN purchase_records r ON r.id = rr.record_id " +
            "JOIN items i ON i.id = r.item_id WHERE i.list_id = :listId ORDER BY rr.id",
    )
    fun observeByList(listId: Int): Flow<List<RecordRoomEntity>>

    @Query(
        "SELECT rr.* FROM record_rooms rr " +
            "JOIN purchase_records r ON r.id = rr.record_id " +
            "JOIN items i ON i.id = r.item_id WHERE i.list_id = :listId ORDER BY rr.id",
    )
    suspend fun byList(listId: Int): List<RecordRoomEntity>

    @Query("SELECT * FROM record_rooms WHERE record_id = :recordId ORDER BY id")
    suspend fun ofRecord(recordId: Int): List<RecordRoomEntity>

    @Insert
    suspend fun insert(row: RecordRoomEntity): Long

    @Insert
    suspend fun insertAll(rows: List<RecordRoomEntity>)

    @Query("DELETE FROM record_rooms WHERE record_id = :recordId")
    suspend fun deleteOfRecord(recordId: Int)

    /** 解除与某个分组的关联（删分组时用）。 */
    @Query("UPDATE record_rooms SET room_id = NULL WHERE room_id = :roomId")
    suspend fun detachRoom(roomId: Int)
}

@Dao
interface AllocationDao {
    @Query(
        "SELECT a.* FROM allocations a JOIN items i ON i.id = a.item_id " +
            "WHERE i.list_id = :listId ORDER BY a.id",
    )
    fun observeByList(listId: Int): Flow<List<AllocationEntity>>

    @Query(
        "SELECT a.* FROM allocations a JOIN items i ON i.id = a.item_id " +
            "WHERE i.list_id = :listId ORDER BY a.id",
    )
    suspend fun byList(listId: Int): List<AllocationEntity>

    @Query("SELECT * FROM allocations WHERE item_id = :itemId ORDER BY id")
    suspend fun ofItem(itemId: Int): List<AllocationEntity>

    @Query("SELECT * FROM allocations WHERE room_id = :roomId")
    suspend fun ofRoom(roomId: Int): List<AllocationEntity>

    @Insert
    suspend fun insert(allocation: AllocationEntity): Long

    @Insert
    suspend fun insertAll(rows: List<AllocationEntity>)

    @Update
    suspend fun update(allocation: AllocationEntity)

    @Delete
    suspend fun delete(allocation: AllocationEntity)

    @Query("DELETE FROM allocations WHERE item_id = :itemId")
    suspend fun deleteOfItem(itemId: Int)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM extra_expenses WHERE list_id = :listId ORDER BY date DESC, id DESC")
    fun observeByList(listId: Int): Flow<List<ExtraExpenseEntity>>

    @Query("SELECT * FROM extra_expenses WHERE list_id = :listId ORDER BY date DESC, id DESC")
    suspend fun byList(listId: Int): List<ExtraExpenseEntity>

    @Query("SELECT * FROM extra_expenses WHERE id = :id")
    suspend fun byId(id: Int): ExtraExpenseEntity?

    @Insert
    suspend fun insert(expense: ExtraExpenseEntity): Long

    @Update
    suspend fun update(expense: ExtraExpenseEntity)

    @Delete
    suspend fun delete(expense: ExtraExpenseEntity)
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_bindings WHERE list_id = :listId")
    suspend fun byList(listId: Int): SyncBindingEntity?

    @Query("SELECT * FROM sync_bindings")
    suspend fun all(): List<SyncBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncBindingEntity)

    /** 同步成功后只更新这三样：指纹、基线、时间。 */
    @Query("UPDATE sync_bindings SET fingerprint = :fingerprint, baseline = :baseline, last_synced_at = :at WHERE list_id = :listId")
    suspend fun saveBaseline(listId: Int, fingerprint: String, baseline: String, at: String)

    @Query("UPDATE sync_bindings SET auto_sync = :enabled WHERE list_id = :listId")
    suspend fun setAutoSync(listId: Int, enabled: Boolean)

    @Query("DELETE FROM sync_bindings WHERE list_id = :listId")
    suspend fun delete(listId: Int)
}
