package com.xiaoyuan.renovation.mobile.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地库的表结构逐列照抄后端 `app/models.py`（表名、列名、可空性都对齐）。
 *
 * 这么做是为了「连服务器备份」能简化成一次数据搬运：下载服务端的 .db，
 * 用 ATTACH 把每个表的数据拷进本地库；反过来把本地库清干净上传。
 * 两端列名一致，搬运就只是 `INSERT INTO t SELECT ... FROM src.t`，
 * 不需要任何字段映射表 —— 改了这里的列名就要连后端一起改。
 *
 * 类型映射（跟着 SQLite 的存储类别走，换库才不会读出错）：
 *   Integer → Int，Float → Double，String → String，Boolean → Boolean，
 *   DateTime → String（后端存的是 "YYYY-MM-DD HH:MM:SS.ffffff"）
 *
 * 索引也照后端的命名习惯（`ix_<表>_<列>`），便于两端对照排查。
 */

@Entity(tableName = "lists")
data class ItemListEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val note: String = "",
    val sort: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: String = "",
    /** 清单编号（见 ListCodes）：改名字、重名加后缀都不影响它，两端靠它对认 */
    val code: String = "",
)

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["list_id"], name = "ix_categories_list_id"),
        // 同名分类只在各自清单里唯一（后端是表内 UNIQUE 约束 uq_category_list_name）
        Index(value = ["list_id", "name"], unique = true, name = "uq_category_list_name"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ItemListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "list_id") val listId: Int? = null,
    val name: String,
    val sort: Int = 0,
)

@Entity(
    tableName = "rooms",
    indices = [Index(value = ["list_id"], name = "ix_rooms_list_id")],
    foreignKeys = [
        ForeignKey(
            entity = ItemListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "list_id") val listId: Int? = null,
    val name: String,
    val sort: Int = 0,
)

@Entity(
    tableName = "items",
    indices = [
        Index(value = ["list_id"], name = "ix_items_list_id"),
        Index(value = ["category_id"], name = "ix_items_category_id"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ItemListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        // 后端这一列没有 ondelete 动作（NO ACTION）：还在用的分类不允许删，
        // 由应用层先拦下来，所以这里也不让它顺手把物料的分类清空
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
        ),
    ],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "list_id") val listId: Int? = null,
    val name: String,
    @ColumnInfo(name = "category_id") val categoryId: Int? = null,
    val unit: String = "个",
    val brand: String = "",
    val model: String = "",
    @ColumnInfo(name = "qty_total") val qtyTotal: Double = 0.0,
    val price: Double = 0.0,
    @ColumnInfo(name = "discount_price") val discountPrice: Double? = null,
    // 下面三个是旧字段（已由采购记录取代），保留是为了换库时列不丢、老数据还能读
    @ColumnInfo(name = "paid_amount") val paidAmount: Double? = null,
    @ColumnInfo(name = "paid_qty") val paidQty: Double = 0.0,
    @ColumnInfo(name = "bought_qty") val boughtQty: Double = 0.0,
    val bought: Boolean = false,
    val note: String = "",
    val sort: Int = 0,
    val rev: Int = 1,
    @ColumnInfo(name = "deleted_at") val deletedAt: String? = null,
)

@Entity(
    tableName = "purchase_records",
    indices = [
        Index(value = ["item_id"], name = "ix_purchase_records_item_id"),
        Index(value = ["room_id"], name = "ix_purchase_records_room_id"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        // 删分组只置空、不删这笔钱（后端是 ON DELETE SET NULL）
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["room_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class PurchaseRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "item_id") val itemId: Int,
    val qty: Double = 0.0,
    val amount: Double = 0.0,
    val date: String = "",
    val note: String = "",
    val vendor: String = "",
    @ColumnInfo(name = "order_no") val orderNo: String = "",
    @ColumnInfo(name = "room_id") val roomId: Int? = null,
)

@Entity(
    tableName = "record_rooms",
    indices = [
        Index(value = ["record_id"], name = "ix_record_rooms_record_id"),
        Index(value = ["room_id"], name = "ix_record_rooms_room_id"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PurchaseRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["record_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["room_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class RecordRoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "record_id") val recordId: Int,
    @ColumnInfo(name = "room_id") val roomId: Int? = null,
)

@Entity(
    tableName = "allocations",
    indices = [
        Index(value = ["item_id"], name = "ix_allocations_item_id"),
        Index(value = ["room_id"], name = "ix_allocations_room_id"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["room_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "item_id") val itemId: Int,
    @ColumnInfo(name = "room_id") val roomId: Int,
    val qty: Double = 0.0,
    @ColumnInfo(name = "price_override") val priceOverride: Double? = null,
    @ColumnInfo(name = "paid_qty") val paidQty: Double = 0.0,
    val note: String = "",
)

@Entity(
    tableName = "extra_expenses",
    indices = [
        Index(value = ["list_id"], name = "ix_extra_expenses_list_id"),
        Index(value = ["item_id"], name = "ix_extra_expenses_item_id"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ItemListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        // 删物料只置空（钱记录不能因为整理物料就消失）
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class ExtraExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "list_id") val listId: Int? = null,
    val kind: String = "运费",
    val amount: Double = 0.0,
    val date: String = "",
    val vendor: String = "",
    @ColumnInfo(name = "order_no") val orderNo: String = "",
    val note: String = "",
    @ColumnInfo(name = "item_id") val itemId: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: String = "",
)

/**
 * 清单与服务器的绑定关系。**本地专有**，不在服务端 schema 里，搬运时也不涉及它。
 *
 * [baseline] 存的是上次同步完成时的快照（JSON，见 `data/sync/SyncModels.kt` 的
 * `SyncBaseline`）：里面有服务器当时的完整数据和"服务器 id → 本地 id"的映射。
 * 下次同步靠它判断"这行是谁改的" —— 本地主键是自增的，跟服务器的 id 没有对应
 * 关系，没有这份映射就认不出"两边哪一行是同一行"。
 */
@Entity(tableName = "sync_bindings")
data class SyncBindingEntity(
    @PrimaryKey @ColumnInfo(name = "list_id") val listId: Int,
    @ColumnInfo(name = "server_url") val serverUrl: String = "",
    @ColumnInfo(name = "remote_list_id") val remoteListId: Int = 0,
    @ColumnInfo(name = "remote_name") val remoteName: String = "",
    /** 上次同步完成时服务器那边的内容指纹 */
    @ColumnInfo(name = "fingerprint") val fingerprint: String = "",
    @ColumnInfo(name = "baseline") val baseline: String = "",
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: String = "",
    /** 进入已绑定的清单时是否自动对齐（关掉就只手动同步） */
    @ColumnInfo(name = "auto_sync") val autoSync: Boolean = true,
)
