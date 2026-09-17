package com.xiaoyuan.renovation.mobile.data.db

import androidx.room.withTransaction
import com.xiaoyuan.renovation.mobile.util.ListCodes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 默认清单名，与后端 `seed.DEFAULT_LIST_NAME` 保持一致。 */
const val DEFAULT_LIST_NAME = "采购清单"

// 默认只给一个示例：这工具是通用的（装修、年货、项目物料都能用），
// 一上来就摆十二个房间名会让人以为它只能干装修这一件事
private val DEFAULT_ROOMS = listOf("示例分组")

private val DEFAULT_CATEGORIES = listOf("示例分类")

private val STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")

fun nowStamp(): String = LocalDateTime.now().format(STAMP)

/**
 * 首次打开（或本地库被清空后）写入一份开箱可用的清单：
 * 一份「采购清单」+ 上面那批分组与分类，都在「设置」里能改。
 *
 * 只在一条清单都没有时才动手 —— 用户自己删空清单不该被判成"该重新播种"，
 * 所以调用方要保证这是启动时的兜底，而不是每次进主界面都跑。
 */
/** 给还没有编号的清单各发一个（老库升级上来、或迁移中途留下的空值）。 */
suspend fun ensureListCodes(db: AppDatabase) {
    val lists = db.lists().all()
    if (lists.none { it.code.isBlank() }) return
    db.withTransaction {
        val taken = lists.mapNotNull { it.code.takeIf { c -> c.isNotBlank() } }.toMutableSet()
        lists.filter { it.code.isBlank() }.forEach { list ->
            var code = ListCodes.new()
            while (code in taken) code = ListCodes.new()
            taken += code
            db.lists().update(list.copy(code = code))
        }
    }
}

suspend fun seedIfEmpty(db: AppDatabase) {
    if (db.lists().count() > 0) return
    db.withTransaction {
        val listId = db.lists()
            .insert(
                ItemListEntity(
                    name = DEFAULT_LIST_NAME,
                    note = "",
                    sort = 0,
                    createdAt = nowStamp(),
                    code = ListCodes.new(),
                ),
            )
            .toInt()
        DEFAULT_ROOMS.forEachIndexed { i, name ->
            db.rooms().insert(RoomEntity(listId = listId, name = name, sort = i))
        }
        DEFAULT_CATEGORIES.forEachIndexed { i, name ->
            db.categories().insert(CategoryEntity(listId = listId, name = name, sort = i))
        }
    }
}
