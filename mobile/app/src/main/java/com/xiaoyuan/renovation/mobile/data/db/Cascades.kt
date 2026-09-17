package com.xiaoyuan.renovation.mobile.data.db

import androidx.room.withTransaction

/**
 * 跨表的删除动作，语义对齐后端 `routers/base_data.py`。
 *
 * 删除一律走这些函数：光靠数据库的外键动作会漏掉"记录只解绑不删"这类细分要求。
 */

/** 删分组：布点明细跟着删，但采购记录只解除关联 —— 那是钱，不能因为整理分组就消失。 */
suspend fun AppDatabase.deleteRoomCascade(room: RoomEntity) = withTransaction {
    allocations().ofRoom(room.id).forEach { allocations().delete(it) }
    records().detachRoom(room.id)
    recordRooms().detachRoom(room.id)
    rooms().delete(room)
}

/** 删分类：该分类下还有物料时直接拒绝（与后端同口径，避免物料莫名失去分类）。 */
suspend fun AppDatabase.deleteCategoryChecked(category: CategoryEntity) = withTransaction {
    require(items().countByCategory(category.id) == 0) { "该类目下仍有物料，请先移动物料再删除" }
    categories().delete(category)
}
