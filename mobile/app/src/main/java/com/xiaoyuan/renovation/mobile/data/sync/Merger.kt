package com.xiaoyuan.renovation.mobile.data.sync

/**
 * 三方合并：拿「上次同步时的基线」「本地现在的样子」「服务器现在的样子」算出该变成什么。
 *
 * 做得到的先说清楚：
 * - 只有一边改过的行 → 直接用改过的那边（这是绝大多数情况：手机上补一笔账、
 *   电脑上改了另一条物料的单价，两条改动都保住）
 * - 两边改成一样的 → 视为没冲突
 * - 一边删、另一边没动 → 删
 * - **同一行两边都改得不一样** → 报冲突，由用户在界面上裁决（以手机为准 / 以电脑为准）
 *
 * 两边都翻译到**本地 id 空间**再比对：本地主键是自增的，跟服务器的 id 没有
 * 对应关系，翻译靠上次同步存下来的映射（[SyncBaseline.localMap]）。服务器上
 * 新增的行在本地还没有 id，用负数占位 —— 反正推送后服务器会重新分配。
 */
object Merger {

    fun merge(
        base: SyncBaseline,
        mine: SyncPayload,
        theirs: SyncPayload,
        preferLocal: Boolean = false,
    ): MergeResult {
        val conflicts = mutableListOf<MergeConflict>()

        /** 服务器 id → 本地 id；没有映射说明是服务器新增的，用负数占位。 */
        fun localOf(kind: String, remoteId: Int?): Int =
            base.localMap["$kind:$remoteId"] ?: -((remoteId ?: 0).coerceAtLeast(1))

        /* ---------- 分组 ---------- */
        val rooms = mergeSimple(
            base = base.payload.rooms.associate { localOf("room", it.id) to SimpleRow(it.name, it.sort) },
            mine = mine.rooms.associate { (it.id ?: 0) to SimpleRow(it.name, it.sort) },
            theirs = theirs.rooms.associate { localOf("room", it.id) to SimpleRow(it.name, it.sort) },
            label = "分组",
            nameOf = { it.name },
            preferLocal = preferLocal,
            conflicts = conflicts,
            localTs = mine.rooms.associate { (it.id ?: 0) to it.updatedAt },
            remoteTs = theirs.rooms.associate { localOf("room", it.id) to it.updatedAt },
        ).map { (localId, row) ->
            val mineRow = mine.rooms.firstOrNull { (it.id ?: 0) == localId }
            val remoteRow = theirs.rooms.firstOrNull { localOf("room", it.id) == localId }
            SyncRoom(
                id = localId, name = row.name, sort = row.sort,
                createdAt = pickCreated(mineRow?.createdAt, remoteRow?.createdAt),
                updatedAt = pickUpdated(mineRow?.updatedAt, remoteRow?.updatedAt),
            )
        }

        /* ---------- 分类 ---------- */
        val categories = mergeSimple(
            base = base.payload.categories.associate { localOf("category", it.id) to SimpleRow(it.name, it.sort) },
            mine = mine.categories.associate { (it.id ?: 0) to SimpleRow(it.name, it.sort) },
            theirs = theirs.categories.associate { localOf("category", it.id) to SimpleRow(it.name, it.sort) },
            label = "分类",
            nameOf = { it.name },
            preferLocal = preferLocal,
            conflicts = conflicts,
            localTs = mine.categories.associate { (it.id ?: 0) to it.updatedAt },
            remoteTs = theirs.categories.associate { localOf("category", it.id) to it.updatedAt },
        ).map { (localId, row) ->
            val mineRow = mine.categories.firstOrNull { (it.id ?: 0) == localId }
            val remoteRow = theirs.categories.firstOrNull { localOf("category", it.id) == localId }
            SyncCategory(
                id = localId, name = row.name, sort = row.sort,
                createdAt = pickCreated(mineRow?.createdAt, remoteRow?.createdAt),
                updatedAt = pickUpdated(mineRow?.updatedAt, remoteRow?.updatedAt),
            )
        }

        /* ---------- 物料（连同它的分配与采购记录一起比：它们是一体的）---------- */
        fun mineItem(item: SyncItem): ItemRow = ItemRow(
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
            allocations = item.allocations
                .map { AllocRow(it.roomId, it.qty, it.priceOverride, it.note) }
                .sortedBy { it.toString() },
            records = item.records
                .map { RecordRow(it.qty, it.amount, it.date, it.note, it.vendor, it.orderNo, it.roomIds.sorted()) }
                .sortedBy { it.toString() },
        )

        fun remoteItem(item: SyncItem): ItemRow = ItemRow(
            name = item.name,
            categoryId = item.categoryId?.let { localOf("category", it) },
            unit = item.unit,
            brand = item.brand,
            model = item.model,
            qtyTotal = item.qtyTotal,
            price = item.price,
            discountPrice = item.discountPrice,
            note = item.note,
            sort = item.sort,
            deletedAt = item.deletedAt,
            allocations = item.allocations
                .map { AllocRow(it.roomId?.let { r -> localOf("room", r) }, it.qty, it.priceOverride, it.note) }
                .sortedBy { it.toString() },
            records = item.records
                .map {
                    RecordRow(it.qty, it.amount, it.date, it.note, it.vendor, it.orderNo,
                        it.roomIds.map { r -> localOf("room", r) }.sorted())
                }
                .sortedBy { it.toString() },
        )

        val items = mergeSimple(
            base = base.payload.items.associate { localOf("item", it.id) to remoteItem(it) },
            mine = mine.items.associate { (it.id ?: 0) to mineItem(it) },
            theirs = theirs.items.associate { localOf("item", it.id) to remoteItem(it) },
            label = "物料",
            nameOf = { it.name },
            preferLocal = preferLocal,
            conflicts = conflicts,
            localTs = mine.items.associate { (it.id ?: 0) to it.updatedAt },
            remoteTs = theirs.items.associate { localOf("item", it.id) to it.updatedAt },
        ).map { (localId, row) ->
            val mineItem = mine.items.firstOrNull { (it.id ?: 0) == localId }
            val remoteItem = theirs.items.firstOrNull { localOf("item", it.id) == localId }
            SyncItem(
                id = localId,
                name = row.name,
                categoryId = row.categoryId,
                unit = row.unit,
                brand = row.brand,
                model = row.model,
                qtyTotal = row.qtyTotal,
                price = row.price,
                discountPrice = row.discountPrice,
                note = row.note,
                sort = row.sort,
                deletedAt = row.deletedAt,
                createdAt = pickCreated(mineItem?.createdAt, remoteItem?.createdAt),
                updatedAt = pickUpdated(mineItem?.updatedAt, remoteItem?.updatedAt),
                allocations = row.allocations.map {
                    SyncAllocation(
                        roomId = it.roomId,
                        qty = it.qty,
                        priceOverride = it.priceOverride,
                        note = it.note,
                    )
                },
                records = row.records.map { r ->
                    // 按内容把这条记录在两边的原身找回来，取它的时间戳
                    val mRec = mineItem?.records?.firstOrNull { sameRecord(it, r) }
                    val tRec = remoteItem?.records?.firstOrNull { sameRecord(it, r) }
                    SyncRecord(
                        qty = r.qty,
                        amount = r.amount,
                        date = r.date,
                        note = r.note,
                        vendor = r.vendor,
                        orderNo = r.orderNo,
                        roomIds = r.roomIds,
                        createdAt = pickCreated(mRec?.createdAt, tRec?.createdAt),
                        updatedAt = pickUpdated(mRec?.updatedAt, tRec?.updatedAt),
                    )
                },
            )
        }

        /* ---------- 额外费用 ---------- */
        val expenses = mergeSimple(
            base = base.payload.expenses.associate {
                localOf("expense", it.id) to expenseRow(it, null)
            },
            mine = mine.expenses.associate { (it.id ?: 0) to expenseRow(it, null) },
            theirs = theirs.expenses.associate {
                localOf("expense", it.id) to expenseRow(it) { r -> localOf("item", r) }
            },
            label = "费用",
            nameOf = { "${it.kind} ${it.amount}" },
            preferLocal = preferLocal,
            conflicts = conflicts,
            localTs = mine.expenses.associate { (it.id ?: 0) to it.updatedAt },
            remoteTs = theirs.expenses.associate { localOf("expense", it.id) to it.updatedAt },
        ).map { (localId, row) ->
            val mineRow = mine.expenses.firstOrNull { (it.id ?: 0) == localId }
            val remoteRow = theirs.expenses.firstOrNull { localOf("expense", it.id) == localId }
            SyncExpense(
                id = localId,
                kind = row.kind,
                amount = row.amount,
                date = row.date,
                vendor = row.vendor,
                orderNo = row.orderNo,
                note = row.note,
                itemId = row.itemId,
                createdAt = pickCreated(mineRow?.createdAt, remoteRow?.createdAt),
                updatedAt = pickUpdated(mineRow?.updatedAt, remoteRow?.updatedAt),
            )
        }

        return MergeResult(
            payload = SyncPayload(
                list = mine.list,
                rooms = rooms.sortedBy { it.sort },
                categories = categories.sortedBy { it.sort },
                items = items.sortedBy { it.sort },
                expenses = expenses,
            ),
            conflicts = conflicts,
        )
    }

    /* ---------------- 内部：按行合并（分组/分类/物料/费用 走同一套判定） ---------------- */

    /**
     * 是不是同一条采购记录（Payload 里的一条 vs 合并后的一行）。记录跟着物料
     * 整条合并，合并后要按内容把原身找回来才拿得到时间戳 —— 按 roomIds 比会
     * 失配：两端的分组 id 空间不同。
     */
    private fun sameRecord(original: SyncRecord, row: RecordRow): Boolean =
        original.qty == row.qty && original.amount == row.amount &&
            original.date == row.date && original.note == row.note &&
            original.vendor == row.vendor && original.orderNo == row.orderNo

    /**
     * 合并结果的时间戳：创建取较早、修改取较新 —— 两边都可能动过这行。
     *
     * 结果 payload 要带上时间戳推给服务器；缺了的话服务端只能用"当下"兜底，
     * 于是没改过的行也会显得刚改过（判冲突就成了本地永远赢）。
     */
    private fun pickCreated(vararg values: String?): String =
        values.filterNotNull().filter { it.isNotBlank() }.minOrNull().orEmpty()

    private fun pickUpdated(vararg values: String?): String =
        values.filterNotNull().filter { it.isNotBlank() }.maxOrNull().orEmpty()

    /**
     * 比两个时间戳谁新。等宽 `YYYY-MM-DD HH:MM:SS` 的字典序就是时间序。
     * 返回 1 本地新、-1 服务器新、0 判不出来（任一方没有时间戳、或两者相同）。
     */
    private fun newerSide(local: String?, remote: String?): Int {
        if (local.isNullOrBlank() || remote.isNullOrBlank()) return 0
        return when {
            local > remote -> 1
            local < remote -> -1
            else -> 0
        }
    }

    private inline fun <T> mergeSimple(
        base: Map<Int, T>,
        mine: Map<Int, T>,
        theirs: Map<Int, T>,
        label: String,
        nameOf: (T) -> String,
        preferLocal: Boolean,
        conflicts: MutableList<MergeConflict>,
        /** 两边都改过时用它们判谁新；不给就表示这类行没有时间戳可比 */
        localTs: Map<Int, String> = emptyMap(),
        remoteTs: Map<Int, String> = emptyMap(),
    ): Map<Int, T> {
        val result = mutableMapOf<Int, T>()
        val keys = base.keys + mine.keys + theirs.keys

        keys.forEach { key ->
            val b = base[key]
            val m = mine[key]
            val t = theirs[key]

            when {
                m != null && t != null -> when {
                    m == b -> result[key] = t                       // 我没动
                    t == b -> result[key] = m                       // 他没动
                    m == t -> result[key] = m                       // 都改成一样
                    else -> when (newerSide(localTs[key], remoteTs[key])) {
                        // 两边都改过：谁的时间新听谁的。时间戳是写入方自己盖的，
                        // 比"谁点了保存"更接近事实；判不出来（老数据没时间戳、
                        // 或两端同秒）才弹窗让用户拍板
                        1 -> result[key] = m
                        -1 -> result[key] = t
                        else -> {
                            conflicts += MergeConflict(label, nameOf(m))
                            result[key] = if (preferLocal) m else t
                        }
                    }
                }

                m != null -> when {                                 // 服务器那边没有
                    b == null -> result[key] = m                    // 我新增的
                    m == b -> Unit                                  // 我没动、那边删了 → 跟着删
                    else -> {
                        conflicts += MergeConflict(label, nameOf(m))
                        if (preferLocal) result[key] = m
                    }
                }

                t != null -> when {                                 // 本地这边没有
                    b == null -> result[key] = t                    // 服务器新增的
                    t == b -> Unit                                  // 他没动、我删了 → 保持删除
                    else -> {
                        conflicts += MergeConflict(label, nameOf(t))
                        if (!preferLocal) result[key] = t
                    }
                }
            }
        }
        return result
    }

    private fun expenseRow(e: SyncExpense, translate: ((Int) -> Int)? = null) = ExpenseRow(
        kind = e.kind,
        amount = e.amount,
        date = e.date,
        vendor = e.vendor,
        orderNo = e.orderNo,
        note = e.note,
        itemId = e.itemId?.let { translate?.invoke(it) ?: it },
    )

    /* ---------------- 内部：比对用的中间结构 ---------------- */

    private data class SimpleRow(val name: String, val sort: Int)

    private data class AllocRow(
        val roomId: Int?,
        val qty: Double,
        val priceOverride: Double?,
        val note: String,
    )

    private data class RecordRow(
        val qty: Double,
        val amount: Double,
        val date: String,
        val note: String,
        val vendor: String,
        val orderNo: String,
        val roomIds: List<Int>,
    )

    private data class ItemRow(
        val name: String,
        val categoryId: Int?,
        val unit: String,
        val brand: String,
        val model: String,
        val qtyTotal: Double,
        val price: Double,
        val discountPrice: Double?,
        val note: String,
        val sort: Int,
        val deletedAt: String?,
        val allocations: List<AllocRow>,
        val records: List<RecordRow>,
    )

    private data class ExpenseRow(
        val kind: String,
        val amount: Double,
        val date: String,
        val vendor: String,
        val orderNo: String,
        val note: String,
        val itemId: Int?,
    )
}

/** 合并结果：可推送的内容 + 需要用户裁决的项。 */
data class MergeResult(
    val payload: SyncPayload,
    val conflicts: List<MergeConflict>,
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

/** 一条两边都改过、且改得不一样的行。[label] 是"物料/分组/分类/费用"。 */
data class MergeConflict(
    val label: String,
    val name: String,
) {
    val description: String get() = "$label「$name」两边都改过，改得还不一样"
}
