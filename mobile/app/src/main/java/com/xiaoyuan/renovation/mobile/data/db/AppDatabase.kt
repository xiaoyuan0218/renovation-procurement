package com.xiaoyuan.renovation.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地 SQLite。库名与后端一致，方便「连服务器备份」时两边对照。
 *
 * 这里**不放**用户表：单机版没有账号，服务器上的鉴权只用在备份/恢复那两个请求上。
 */
@Database(
    entities = [
        ItemListEntity::class,
        CategoryEntity::class,
        RoomEntity::class,
        ItemEntity::class,
        PurchaseRecordEntity::class,
        RecordRoomEntity::class,
        AllocationEntity::class,
        ExtraExpenseEntity::class,
        // 本地专有：与服务器的绑定关系与上次同步的基线，不进服务端 schema
        SyncBindingEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lists(): ListDao
    abstract fun categories(): CategoryDao
    abstract fun rooms(): RoomDao
    abstract fun items(): ItemDao
    abstract fun records(): RecordDao
    abstract fun recordRooms(): RecordRoomDao
    abstract fun allocations(): AllocationDao
    abstract fun expenses(): ExpenseDao
    abstract fun sync(): SyncDao

    companion object {
        const val NAME = "renovation.db"

        /** v3 给清单加了编号（两端靠它对认是同一份清单）。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `lists` ADD COLUMN `code` TEXT NOT NULL DEFAULT ''")
                // 具体编号由 ListCodes 在启动时补齐（SQL 里生成随机码不好写）
            }
        }

        /**
         * v4 给各实体补创建/修改时间（同步判冲突与界面显示都用它）。
         *
         * 老行用**迁移时刻**回填：历史数据没有真实时间，回填值只表示
         * "从这一刻起开始记录"，之后每次写入都会覆盖成真实值。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = nowStamp()
                // lists / extra_expenses 原本就有 created_at，只补 updated_at
                val needCreated = listOf("categories", "rooms", "items", "purchase_records")
                val all = needCreated + listOf("lists", "extra_expenses")
                for (table in needCreated) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `created_at` TEXT NOT NULL DEFAULT ''")
                }
                for (table in all) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `updated_at` TEXT NOT NULL DEFAULT ''")
                }
                for (table in all) {
                    db.execSQL("UPDATE `$table` SET `created_at` = '$now' WHERE `created_at` = ''")
                    db.execSQL("UPDATE `$table` SET `updated_at` = '$now' WHERE `updated_at` = ''")
                }
            }
        }

        /** v2 加了 sync_bindings（绑定与同步基线）。老库升级时只多这一张表。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_bindings` (" +
                        "`list_id` INTEGER NOT NULL, " +
                        "`server_url` TEXT NOT NULL, " +
                        "`remote_list_id` INTEGER NOT NULL, " +
                        "`remote_name` TEXT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`baseline` TEXT NOT NULL, " +
                        "`last_synced_at` TEXT NOT NULL, " +
                        "`auto_sync` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`list_id`))",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // 外键靠 DDL 里的 ON DELETE 动作兜底，但删除一律走显式事务，
                        // 不依赖这个开关 —— 换库（ATTACH 搬运）时它可能是关的
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
    }
}
