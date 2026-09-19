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

        /**
         * 把服务器的一批行对齐到本地 id 空间，返回"本地 id → 服务器行"。
         *
         * localMap 命中的直接用；**没命中的按名字回退配对** —— 服务器发生过
         * 覆盖重插时行 id 会重排（删过东西留下的空洞被填掉），另一台设备手里的
         * 旧映射就对不上了，这些行会被当成"服务器新增"跟本地同名行合并出
         * 重复（同一份清单里冒出两条「示例分组」，推上去还撞服务端唯一约束）。
         * 名字是这种清单里最自然的身份，按它配对最贴近事实。
         *
         * [mineIdByName] 的重名行只有一条能被配到（associate 语义），重名的
         * 其余 theirs 行落回负数占位。
         */
        fun <T> alignTheirs(
            rows: List<T>,
            idOf: (T) -> Int?,
            nameOf: (T) -> String,
            mineIdByName: Map<String, Int>,
            mappedId: (T) -> Int?,
        ): Aligned<T> {
            val used = HashSet<Int>()
            val byKey = HashMap<Int, T>()
            val remoteToLocal = HashMap<Int, Int>()
            rows.forEach { row ->
                val remoteId = idOf(row) ?: 0
                val key = mappedId(row)
                    ?: mineIdByName[nameOf(row)]?.takeIf { it !in used }
                if (key != null) {
                    used.add(key)
                    remoteToLocal[remoteId] = key
                    byKey[key] = row
                } else {
                    val placeholder = -((remoteId).coerceAtLeast(1))
                    remoteToLocal[remoteId] = placeholder
                    byKey[placeholder] = row
                }
            }
            return Aligned(byKey, remoteToLocal)
        }

        /* ---------- 分组 ---------- */
        val alignedTheirsRooms = alignTheirs(
            rows = theirs.rooms,
            idOf = { it.id },
            nameOf = { it.name },
            mineIdByName = mine.rooms.mapNotNull { r -> r.id?.let { id -> r.name to id } }.toMap(),
            mappedId = { base.localMap["room:${it.id}"] },
        )
        val rooms = mergeSimple(
            base = base.payload.rooms.associate { localOf("room", it.id) to SimpleRow(it.name, it.sort) },
            mine = mine.rooms.associate { (it.id ?: 0) to SimpleRow(it.name, it.sort) },
            theirs = alignedTheirsRooms.byKey.mapValues { (_, r) -> SimpleRow(r.name, r.sort) },
            label = "分组",
            nameOf = { it.name },
            preferLocal = preferLocal,
            conflicts = conflicts,
            localTs = mine.rooms.associate { (it.id ?: 0) to it.updatedAt },
            remoteTs = alignedTheirsRooms.byKey.mapValues { it.value.updatedAt },
        ).map { (localId, row) ->
            val mineRow = mine.rooms.firstOrNull { (it.id ?: 0) == localId }
            val remoteRow = alignedTheirsRooms.byKey[localId]
            SyncRoom(
                id = localId, name = row.name, sort = row.sort,
                createdAt = pickCreated(mineRow?.createdAt, remoteRow?.createdAt),
                updatedAt = pickUpdated(mineRow?.updatedAt, remoteRow?.updatedAt),
            )
        }

        /* ---------- 分类 ---------- */
        val alignedTheirsCategories = alignTheirs(
            rows = theirs.categories,
            idOf = { it.id },
            nameOf = { it.name },
            mineIdByName = mine.categories.mapNotNull { r -> r.id?.let { id -> r.name to id } }.toMap(),
            mappedId = { base.localMap["category:${it.id}"] },
        )
        val categories = mergeSimple(
            base = base.payload.categories.associate { localOf("category", it.id) to SimpleRow(it.name, it.sort) },
            mine = mine.categories.associate { (it.id ?: 0) to SimpleRow(it.name, it.sort) },
            theirs = alignedTheirsCategories.byKey.mapValues { (_, r) -> SimpleRow(r.name, r.sort) },
            label = "分类",
            nameOf = { it.name },
            preferLocal = preferLocal,
            conflicts = conflicts,
            localTs = mine.categories.associate { (it.id ?: 0) to it.updatedAt },
            remoteTs = alignedTheirsCategories.byKey.mapValues { it.value.updatedAt },
        ).map { (localId, row) ->
            val mineRow = mine.categories.firstOrNull { (it.id ?: 0) == localId }
            val remoteRow = alignedTheirsCategories.byKey[localId]
            SyncCategory(
                id = localId, name = row.name, sort = row.sort,
                createdAt = pickCreated(mineRow?.createdAt, remoteRow?.createdAt),
                updatedAt = pickUpdated(mineRow?.updatedAt, remoteRow?.updatedAt),
            )
        }

        /* ---------- 物料（连同它的分配与采购记录一起比：它们是一体的）---------- */
        val alignedTheirsItems = alignTheirs(
            rows = theirs.items,
            idOf = { it.id },
            nameOf = { it.name },
            mineIdByName = mine.items.mapNotNull { r -> r.id?.let { id -> r.name to id } }.toMap(),
            mappedId = { base.localMap["item:${it.id}"] },
        )
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

        fun remoteItem(item: SyncItem, translate: (String, Int) -> Int): ItemRow = ItemRow(
            name = item.name,
            categoryId = item.categoryId?.let { translate("category", it) },
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
                .map { AllocRow(it.roomId?.let { r -> translate("room", r) }, it.qty, it.priceOverride, it.note) }
                .sortedBy { it.toString() },
            records = item.records
                .map { rec ->
                    RecordRow(
                        qty = rec.qty,
                        amount = rec.amount,
                        date = rec.date,
                        note = rec.note,
                        vendor = rec.vendor,
                        orderNo = rec.orderNo,
                        roomIds = rec.roomIds.map { r -> translate("room", r) }.sorted(),
                    )
                }
                .sortedBy { it.toString() },
        )

        val items = mergeSimple(
            base = base.payload.items.associate {
                localOf("item", it.id) to remoteItem(it) { kind, id -> localOf(kind, id) }
            },
            mine = mine.items.associate { (it.id ?: 0) to mineItem(it) },
            theirs = alignedTheirsItems.byKey.mapValues { (_, r) ->
                remoteItem(r) { kind, id ->
                    when (kind) {
                        "room" -> alignedTheirsRooms.remoteToLocal[id]
                        "category" -> alignedTheirsCategories.remoteToLocal[id]
                        else -> null
                    } ?: localOf(kind, id)
                }
            },
            label = "物料",
            nameOf = { it.name },
            preferLocal = preferLocal,
            conflicts = conflicts,
            localTs = mine.items.associate { (it.id ?: 0) to it.updatedAt },
            remoteTs = alignedTheirsItems.byKey.mapValues { it.value.updatedAt },
        ).map { (localId, row) ->
            val mineItem = mine.items.firstOrNull { (it.id ?: 0) == localId }
            val remoteItem = alignedTheirsItems.byKey[localId]
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
        val alignedTheirsExpenses = alignTheirs(
            rows = theirs.expenses,
            idOf = { it.id },
            nameOf = { "${it.kind} ${it.amount}" },
            mineIdByName = mine.expenses.mapNotNull { r ->
                r.id?.let { id -> "${r.kind} ${r.amount}" to id }
            }.toMap(),
            mappedId = { base.localMap["expense:${it.id}"] },
        )
        val expenses = mergeSimple(
            base = base.payload.expenses.associate {
                localOf("expense", it.id) to expenseRow(it, null)
            },
            mine = mine.expenses.associate { (it.id ?: 0) to expenseRow(it, null) },
            theirs = alignedTheirsExpenses.byKey.mapValues { (_, r) ->
                // 服务器费用的 item_id 是服务器 id：按对齐表翻译成本地键
                expenseRow(r) { rowId ->
                    alignedTheirsItems.remoteToLocal[rowId] ?: localOf("item", rowId)
                }
            },
            label = "费用",
            nameOf = { "${it.kind} ${it.amount}" },
            preferLocal = preferLocal,
            conflicts = conflicts,
            localTs = mine.expenses.associate { (it.id ?: 0) to it.updatedAt },
            remoteTs = alignedTheirsExpenses.byKey.mapValues { it.value.updatedAt },
        ).map { (localId, row) ->
            val mineRow = mine.expenses.firstOrNull { (it.id ?: 0) == localId }
            val remoteRow = alignedTheirsExpenses.byKey[localId]
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
     * 两边从没同步过（没有共同基线）时的合并 —— 「两边的都留着」。
     *
     * 没有基线就分不清"谁改了什么"，三方合并那套判定完全用不上：它会把两边
     * 每一行都当成"新增"，同名行各留一份（服务器 id 落进负数占位、本地 id 是
     * 正数，两个键永远对不上），推上去还会撞服务端的同名唯一约束，整份传不上去。
     *
     * 这里只做**按名字配对**：同名的视为同一条、取改动较新的那份内容，各自独有
     * 的都留着。名字是这种清单里最自然的身份 —— 分组/分类在服务端本来就按名字
     * 唯一，物料用户也是按名字认的。
     *
     * 结果会**重新编号**（分组/分类/物料都从 1 起）：两边的 id 各自独立、互不
     * 对应，混着用会让物料挂到错误的分组/分类上。引用（物料的分类、分配的分组、
     * 采购记录的分组、费用的物料）跟着一起重写。
     */
    fun mergeWithoutBase(mine: SyncPayload, theirs: SyncPayload): SyncPayload {
        val rooms = pickByName(mine.rooms, theirs.rooms, { it.name }, { it.updatedAt })
        val categories = pickByName(mine.categories, theirs.categories, { it.name }, { it.updatedAt })

        val roomIdByName = HashMap<String, Int>()
        rooms.forEachIndexed { i, p -> roomIdByName.putIfAbsent(p.row.name, i + 1) }
        val categoryIdByName = HashMap<String, Int>()
        categories.forEachIndexed { i, p -> categoryIdByName.putIfAbsent(p.row.name, i + 1) }

        // 物料/费用上的引用是 id，得先按 id 找回名字，才能改到新编号上
        val localRoomName = mine.rooms.associate { (it.id ?: 0) to it.name }
        val remoteRoomName = theirs.rooms.associate { (it.id ?: 0) to it.name }
        val localCategoryName = mine.categories.associate { (it.id ?: 0) to it.name }
        val remoteCategoryName = theirs.categories.associate { (it.id ?: 0) to it.name }
        val localItemName = mine.items.associate { (it.id ?: 0) to it.name }
        val remoteItemName = theirs.items.associate { (it.id ?: 0) to it.name }

        fun roomIdOf(fromMine: Boolean, id: Int?): Int? {
            val name = (if (fromMine) localRoomName else remoteRoomName)[id ?: 0] ?: return null
            return roomIdByName[name]
        }

        fun categoryIdOf(fromMine: Boolean, id: Int?): Int? {
            val name = (if (fromMine) localCategoryName else remoteCategoryName)[id ?: 0] ?: return null
            return categoryIdByName[name]
        }

        val items = pickByName(mine.items, theirs.items, { it.name }, { it.updatedAt })
        val itemIdByName = HashMap<String, Int>()
        items.forEachIndexed { i, p -> itemIdByName.putIfAbsent(p.row.name, i + 1) }

        val mergedItems = items.mapIndexed { i, picked ->
            val row = picked.row
            SyncItem(
                id = i + 1,
                name = row.name,
                categoryId = categoryIdOf(picked.fromMine, row.categoryId),
                unit = row.unit,
                brand = row.brand,
                model = row.model,
                qtyTotal = row.qtyTotal,
                price = row.price,
                discountPrice = row.discountPrice,
                note = row.note,
                sort = row.sort,
                deletedAt = row.deletedAt,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                allocations = row.allocations.map { a ->
                    SyncAllocation(
                        roomId = roomIdOf(picked.fromMine, a.roomId),
                        qty = a.qty,
                        priceOverride = a.priceOverride,
                        note = a.note,
                    )
                },
                records = row.records.map { r ->
                    SyncRecord(
                        qty = r.qty,
                        amount = r.amount,
                        date = r.date,
                        note = r.note,
                        vendor = r.vendor,
                        orderNo = r.orderNo,
                        roomIds = r.roomIds.mapNotNull { roomIdOf(picked.fromMine, it) },
                        createdAt = r.createdAt,
                        updatedAt = r.updatedAt,
                    )
                },
            )
        }

        val expenses = pickByName(
            mine.expenses, theirs.expenses, { "${it.kind} ${it.amount}" }, { it.updatedAt },
        ).map { picked ->
            val row = picked.row
            SyncExpense(
                kind = row.kind,
                amount = row.amount,
                date = row.date,
                vendor = row.vendor,
                orderNo = row.orderNo,
                note = row.note,
                itemId = (if (picked.fromMine) localItemName else remoteItemName)[row.itemId ?: 0]
                    ?.let { itemIdByName[it] },
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
            )
        }

        return SyncPayload(
            list = mine.list.copy(
                updatedAt = listOf(mine.list.updatedAt, theirs.list.updatedAt)
                    .filter { it.isNotBlank() }.maxOrNull().orEmpty(),
            ),
            rooms = rooms.mapIndexed { i, p ->
                SyncRoom(
                    id = i + 1, name = p.row.name, sort = p.row.sort,
                    createdAt = p.row.createdAt, updatedAt = p.row.updatedAt,
                )
            },
            categories = categories.mapIndexed { i, p ->
                SyncCategory(
                    id = i + 1, name = p.row.name, sort = p.row.sort,
                    createdAt = p.row.createdAt, updatedAt = p.row.updatedAt,
                )
            },
            items = mergedItems,
            expenses = expenses,
        )
    }

    /** 对齐结果：键 → 服务器行，外加服务器原 id → 键的翻译表（引用翻译用）。 */
    private class Aligned<T>(val byKey: Map<Int, T>, val remoteToLocal: Map<Int, Int>)

    /** 配对结果：选中的那一行，以及它来自哪一边（引用翻译要用）。 */
    private class Picked<T>(val row: T, val fromMine: Boolean)

    /**
     * 按名字把两边配对：同名的算同一条、取改动较新的那份，各自独有的都留着。
     *
     * 同一名字在一侧出现多次时按出现顺序一一配，多出来的那些不会被丢掉。
     */
    private fun <T> pickByName(
        mine: List<T>,
        theirs: List<T>,
        nameOf: (T) -> String,
        updatedOf: (T) -> String,
    ): List<Picked<T>> {
        // 同名的可能不止一条（历史上就重过名），按出现顺序一一配
        val pending: MutableMap<String, MutableList<Int>> = HashMap()
        theirs.forEachIndexed { i, row ->
            pending.getOrPut(nameOf(row)) { mutableListOf() }.add(i)
        }
        val used = BooleanArray(theirs.size)
        val out = ArrayList<Picked<T>>(mine.size + theirs.size)

        mine.forEach { m ->
            val bucket = pending[nameOf(m)]
            val index = if (bucket.isNullOrEmpty()) null else bucket.removeAt(0)
            if (index == null) {
                out += Picked(m, fromMine = true)
            } else {
                used[index] = true
                val t = theirs[index]
                // 谁改得更近听谁的（时间戳先归一到秒再比，见 toSecond）；判不出来
                // （任一方没有时间戳）就用手机这边 —— 用户此刻正看着的是它
                val mTs = toSecond(updatedOf(m))
                val tTs = toSecond(updatedOf(t))
                val remoteNewer = mTs.isNotEmpty() && tTs.isNotEmpty() && tTs > mTs
                out += if (remoteNewer) Picked(t, fromMine = false) else Picked(m, fromMine = true)
            }
        }
        theirs.forEachIndexed { i, t -> if (!used[i]) out += Picked(t, fromMine = false) }
        return out
    }

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
     * 把时间戳归一到"秒"再比。
     *
     * 手机自己写的是 `yyyy-MM-dd HH:mm:ss.SSSSSS`（带微秒），服务器存的是
     * `YYYY-MM-DD HH:MM:SS`。直接比字符串的话，同一秒内手机的值因为多一截
     * 小数而"更大" —— 判谁更新就会偏向本地；而服务器把手机的值截断存下来后
     * 又反过来偏向服务器。先截到秒，两端才是同一把尺子。
     */
    private fun toSecond(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return ""
        // 秒之后的都砍掉：'.' 分隔的微秒、或 ISO 里的 'Z'/时区尾巴
        val dot = text.indexOf('.')
        val base = if (dot >= 0) text.substring(0, dot) else text
        return base.trimEnd('Z').take(19)
    }

    /**
     * 比两个时间戳谁新。等宽 `YYYY-MM-DD HH:MM:SS` 的字典序就是时间序。
     * 返回 1 本地新、-1 服务器新、0 判不出来（任一方没有时间戳、或两者相同）。
     */
    private fun newerSide(local: String?, remote: String?): Int {
        val l = toSecond(local)
        val r = toSecond(remote)
        if (l.isEmpty() || r.isEmpty()) return 0
        return when {
            l > r -> 1
            l < r -> -1
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
