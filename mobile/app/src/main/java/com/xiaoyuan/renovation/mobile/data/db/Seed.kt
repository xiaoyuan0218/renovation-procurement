package com.xiaoyuan.renovation.mobile.data.db

import androidx.room.withTransaction
import com.xiaoyuan.renovation.mobile.util.ListCodes
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 默认清单名，与后端 `seed.DEFAULT_LIST_NAME` 保持一致。 */
const val DEFAULT_LIST_NAME = "采购清单"

// 默认只给一个示例：这工具是通用的（装修、年货、项目物料都能用），
// 一上来就摆十二个房间名会让人以为它只能干装修这一件事
private val DEFAULT_ROOMS = listOf("示例分组")

private val DEFAULT_CATEGORIES = listOf("示例分类")

internal val STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")

/**
 * 全端统一的时间戳：**UTC**。
 *
 * 手机与服务器（NAS 容器）的时区设置可能不同 —— 从前各自用设备本地时间，
 * 两端时区不同时同一时刻写出的时间戳能差出好几个小时，同步判"谁改得更近"
 * 就会失真，较新的改动反而被当成旧的覆盖。存储与比较一律 UTC，
 * 显示时由 `toLocalStamp` 转回设备时区。
 */
fun nowStamp(): String = LocalDateTime.now(ZoneOffset.UTC).format(STAMP)

/**
 * 把 `nowStamp()` 写下的时间戳读回成时刻（UTC 语义）。
 *
 * 空串、老数据里格式对不上的值一律返回 null —— 调用方自己决定拿什么当兜底，
 * 别在这里抛异常（顶栏要显示"上次同步多久之前"，读不出来顶多不显示）。
 */
fun parseStamp(text: String): LocalDateTime? =
    if (text.isBlank()) null else runCatching { LocalDateTime.parse(text, STAMP) }.getOrNull()

/**
 * UTC 时间戳转**设备本地时区**，给界面显示用。
 *
 * 存的是 UTC、给用户看的是本地时间 —— 直接拿 UTC 字符串显示的话，
 * 时间会差出时区偏移量，用户对不上自己的钟。读不出来原样返回。
 */
fun toLocalStamp(text: String): String {
    val utc = parseStamp(text) ?: return text
    return utc.atOffset(ZoneOffset.UTC)
        .atZoneSameInstant(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(STAMP)
}

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
