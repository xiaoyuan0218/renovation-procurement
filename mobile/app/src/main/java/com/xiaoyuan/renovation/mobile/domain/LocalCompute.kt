package com.xiaoyuan.renovation.mobile.domain

import com.xiaoyuan.renovation.mobile.data.db.AllocationEntity
import com.xiaoyuan.renovation.mobile.data.db.ExtraExpenseEntity
import com.xiaoyuan.renovation.mobile.data.db.ItemEntity
import com.xiaoyuan.renovation.mobile.data.db.PurchaseRecordEntity
import com.xiaoyuan.renovation.mobile.data.model.AllocationDto
import com.xiaoyuan.renovation.mobile.data.model.ExpenseDto
import com.xiaoyuan.renovation.mobile.data.model.ItemDto
import com.xiaoyuan.renovation.mobile.data.model.MatrixCellDto
import com.xiaoyuan.renovation.mobile.data.model.MatrixItemDto
import com.xiaoyuan.renovation.mobile.data.model.RecordDto
import com.xiaoyuan.renovation.mobile.data.model.STATUS_DEPOSIT
import com.xiaoyuan.renovation.mobile.data.model.STATUS_DONE
import com.xiaoyuan.renovation.mobile.data.model.STATUS_NONE
import com.xiaoyuan.renovation.mobile.data.model.STATUS_PARTIAL
import com.xiaoyuan.renovation.mobile.data.model.STATUS_UNBOUGHT
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 本地版口径计算，逐条对齐后端 `app/services/compute.py` 与 `routers/matrix.py`。
 * 手机会把结果直接显示给用户、也会在同步时作为比对依据，所以口径必须与
 * 服务端完全一致 —— 改这里之前先去看后端那份。
 *
 * 两个口径各自拆成三段且严格闭合（已买部分省下的钱是**残差**，不是独立计算）：
 * - 已付 + 实际优惠 + 未付 = 原价小计
 * - 已付 + 日常价优惠 + 日常价未付 = 日常价小计
 *
 * 实付价高于原价时为负，不是错误值。
 */

/** 保留两位小数。用 HALF_EVEN 对齐 Python 的 round —— 不然手机和电脑会差一分。 */
fun r2(x: Double): Double =
    BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()

/** 一条物料连同它的分配、采购记录、记录涉及的分组 —— 算口径要的全部输入。 */
data class ItemBundle(
    val item: ItemEntity,
    val categoryName: String? = null,
    val allocations: List<AllocationEntity> = emptyList(),
    val records: List<PurchaseRecordEntity> = emptyList(),
    /** 采购记录 id → 涉及的分组 id（可多选） */
    val recordRooms: Map<Int, List<Int>> = emptyMap(),
)

object LocalCompute {

    /* ---------------- 日期：与后端 services/dates.py 同口径 ---------------- */

    private val LOOSE = Regex("""^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?:[ T].*)?$""")
    private val ISO = Regex("""\d{4}-\d{2}-\d{2}""")

    private fun validIso(text: String): Boolean {
        if (!ISO.matches(text)) return false
        return try {
            LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 读出口径：老数据里混着 "2026-09-14 00:00:00" 这类脏值，读出来时归一化，
     * 认不出来就原样返回（不丢信息）。历史数据不做回写清理。
     */
    fun forRead(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (validIso(text)) return text
        // Excel 把日期存成「1900 起的天数」，用 Excel 编辑过日期列的文件读出来
        // 就是一串数字（如 45500）。后端用 openpyxl 能识别成真日期，手机这边是
        // 自己写的读取器、只拿得到文本，得在这里补上换算，否则同一份文件在两台
        // 设备上导入会得到不同的日期。
        fromExcelSerial(text)?.let { return it }
        val m = LOOSE.matchEntire(text) ?: return text
        val (y, mo, d) = m.destructured
        return try {
            LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).toString()
        } catch (_: Exception) {
            text
        }
    }

    /** Excel 日期序列号 → `YYYY-MM-DD`；不像序列号就返回 null。 */
    private fun fromExcelSerial(text: String): String? {
        val days = text.toDoubleOrNull() ?: return null
        // 合理区间大致是 1900-01-01 到 2447 年；这个范围外多半是数量之类的普通数字
        if (days < 1 || days > 200000) return null
        return try {
            // 基准取 1899-12-30：Excel 把 1900 当闰年，用 1900-01-01 当 1 会差一天
            LocalDate.of(1899, 12, 30).plusDays(days.toLong()).toString()
        } catch (_: Exception) {
            null
        }
    }

    /** 写入口径：空的或不合法就报错，避免把脏值写进库。 */
    fun cleanDate(value: String?): String {
        val text = forRead(value)
        require(text.isEmpty() || validIso(text)) { "付款日期要写成 2026-09-14 这样的格式（或留空）" }
        return text
    }

    /** 聚合用：取 YYYY-MM，认不出来返回 null。 */
    fun monthOf(value: String?): String? {
        val text = forRead(value)
        return if (validIso(text)) text.take(7) else null
    }

    /* ---------------- 数量 ---------------- */

    /** 总数量：有分配时 = Σ分配数量，否则用物料自身的总量。 */
    fun totalQty(item: ItemEntity, allocations: List<AllocationEntity>): Double =
        if (allocations.isNotEmpty()) r2(allocations.sumOf { it.qty })
        else r2(item.qtyTotal)

    /** 已到货数量 = 各笔记录的实付数量之和，**定金不算**（钱先付、货还没到）。 */
    fun paidQty(records: List<PurchaseRecordEntity>): Double =
        r2(records.filter { !it.isDeposit }.sumOf { it.qty })

    /** 已付的定金合计：用来抵扣未付，不参与"已到货"进度。 */
    fun depositPaid(records: List<PurchaseRecordEntity>): Double =
        r2(records.filter { it.isDeposit }.sumOf { it.amount })

    fun unpaidQty(totalQty: Double, paidQty: Double): Double = r2(maxOf(0.0, totalQty - paidQty))

    /* ---------------- 金额 ---------------- */

    /** 原价小计：Σ(分配数量 × (分组覆盖单价 ?? 物料单价))；无分配 = 总量 × 单价。 */
    fun listTotal(item: ItemEntity, allocations: List<AllocationEntity>): Double =
        if (allocations.isNotEmpty()) {
            r2(allocations.sumOf { it.qty * (it.priceOverride ?: item.price) })
        } else {
            r2(item.qtyTotal * item.price)
        }

    /** 日常单价：没填优惠价就按原价算。 */
    fun discountUnit(item: ItemEntity): Double = item.discountPrice ?: item.price

    fun discountTotal(item: ItemEntity, allocations: List<AllocationEntity>): Double =
        r2(totalQty(item, allocations) * discountUnit(item))

    fun paid(records: List<PurchaseRecordEntity>): Double = r2(records.sumOf { it.amount })

    /** 实付单价（均价）= 已付金额 ÷ 实付数量；数量为 0 或金额为 0 时没有意义。 */
    fun paidPrice(records: List<PurchaseRecordEntity>): Double? {
        val qty = paidQty(records)
        val amt = paid(records)
        return if (qty > 0 && amt != 0.0) r2(amt / qty) else null
    }

    /** 未付金额 = 未到货部分按原价算的钱 − 已付定金（不足 0 按 0）。 */
    fun unpaid(unpaidQty: Double, item: ItemEntity, deposit: Double = 0.0): Double =
        r2(maxOf(0.0, unpaidQty * item.price - deposit))

    /** 日常价未付 = 未到货部分按日常单价算的钱 − 已付定金，口径同上。 */
    fun dailyUnpaid(unpaidQty: Double, item: ItemEntity, deposit: Double = 0.0): Double =
        r2(maxOf(0.0, unpaidQty * discountUnit(item) - deposit))

    /** 实际优惠 = 原价小计 − 已付 − 未付（残差，保证三段闭合）。 */
    fun actualDiscount(
        item: ItemEntity,
        allocations: List<AllocationEntity>,
        records: List<PurchaseRecordEntity>,
    ): Double = r2(
        listTotal(item, allocations) - paid(records) -
            unpaid(unpaidQty(totalQty(item, allocations), paidQty(records)), item,
                   depositPaid(records)),
    )

    /** 日常价优惠 = 日常价小计 − 已付 − 日常价未付（残差）。 */
    fun dailyDiscount(
        item: ItemEntity,
        allocations: List<AllocationEntity>,
        records: List<PurchaseRecordEntity>,
    ): Double = r2(
        discountTotal(item, allocations) - paid(records) -
            dailyUnpaid(unpaidQty(totalQty(item, allocations), paidQty(records)), item,
                        depositPaid(records)),
    )

    fun status(totalQty: Double, paidQty: Double, deposit: Double = 0.0): String = when {
        totalQty <= 0 -> STATUS_NONE
        paidQty >= totalQty - 1e-9 -> STATUS_DONE
        paidQty > 0 -> STATUS_PARTIAL
        deposit > 0 -> STATUS_DEPOSIT   // 货一件没到，但定金付了
        else -> STATUS_UNBOUGHT
    }

    /* ---------------- 各分组的已付覆盖 ---------------- */

    /**
     * 各分配的已付覆盖 `{分配 id: 已付数量}`，对齐 `compute.allocation_paid_cover`。
     *
     * 优先认用户在记账时**写明的归属**（记录上的分组多选）；没写归属的仍按老规矩：
     * 单分组、或整条全部买齐时按分配顺序抵扣，多分组只买一部分时一个都不标 ——
     * "先买哪间"系统无从知晓，猜错比不显示更坏。
     */
    fun paidCover(bundle: ItemBundle): Map<Int, Double> {
        val allocations = bundle.allocations
        if (allocations.isEmpty()) return emptyMap()

        val covered = HashMap<Int, Double>()   // 分组 id → 已算在它头上的数量
        var unassigned = 0.0

        fun fill(roomIds: Set<Int>, qty: Double) {
            var remain = qty
            for (a in allocations) {
                if (remain <= 0) break
                if (a.roomId !in roomIds) continue
                val cap = a.qty - (covered[a.roomId] ?: 0.0)
                val take = minOf(maxOf(0.0, cap), remain)
                covered[a.roomId] = (covered[a.roomId] ?: 0.0) + take
                remain -= take
            }
        }

        for (r in bundle.records) {
            // 勾了分组就按勾的算；只写了单值 room_id 的老记录当成"只勾了那一间"
            var roomIds = (bundle.recordRooms[r.id] ?: emptyList()).filter { it != 0 }.toSet()
            if (roomIds.isEmpty() && r.roomId != null) roomIds = setOf(r.roomId)
            if (roomIds.isNotEmpty()) fill(roomIds, r.qty) else unassigned += r.qty
        }

        val total = totalQty(bundle.item, allocations)
        val paidQty = paidQty(bundle.records)
        val everythingDone = total > 0 && paidQty >= total - 1e-9
        // 没写分组的那部分能不能放心按顺序摊开：只有"不存在谁先买"的问题时才行
        val canSpread = allocations.size == 1 || everythingDone
        if (unassigned > 0 && canSpread) fill(allocations.map { it.roomId }.toSet(), unassigned)

        if (covered.isEmpty() && !canSpread) return emptyMap()
        return allocations.associate { a ->
            a.id to r2(minOf(a.qty, covered[a.roomId] ?: 0.0))
        }
    }

    /* ---------------- 视图 ---------------- */

    /* ---------------- 商品链接 ---------------- */

    // 候选串只认 RFC 3986 字符集：中文、中文标点、全角空格天然截断。
    // $ 用 ${'$'} 显式转义，避免双引号串里被当成字符串模板的引用。
    private val LINK_RE =
        Regex("https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!${'$'}&'()*+,;=%]+", RegexOption.IGNORE_CASE)

    // host 段（scheme:// 到第一个 / ? # 之间）至少要有一个字母或数字：
    // `https://...` 这类残值经尾剥剩 `https://`，只查 scheme 前缀是拦不住的
    private val LINK_HOST_RE = Regex("^https?://[^/?#]*[A-Za-z0-9]", RegexOption.IGNORE_CASE)

    /**
     * 从一段分享文案里提取第一个 http(s) 链接；没有就返回空串。
     *
     * 与后端 `services/link_text.py`、网页端、安卓端是同一套统一提取规范：
     * 尾部普通标点逐字符剥（句子结尾的句号逗号不属于 URL），收括号看配对 ——
     * 对应开括号出现得比它少，说明它是外面包裹的才剥，URL 自己的括号不动；
     * 结果超过 500 字符（库字段上限）整体放弃而不是截断，链接截断就打不开了。
     * 对已清洗过的纯 URL 幂等（提取两次结果一致），scheme 大小写保留原样。
     */
    fun extractLink(value: String?): String {
        val text = (value ?: "").trim()
        var candidate = LINK_RE.find(text)?.value ?: return ""
        while (candidate.isNotEmpty()) {
            when (val last = candidate.last()) {
                '.', ',', ';', ':', '!', '?', '\'', '"' -> candidate = candidate.dropLast(1)
                // '}' 连同开括号 '{' 都不在候选字符集里，进不了候选串；写上只为
                // 与规范剥除表逐字对齐，实际永远走不到
                ')', ']', '}' -> {
                    val open = when (last) {
                        ')' -> '('
                        ']' -> '['
                        else -> '{'
                    }
                    if (candidate.count { it == open } < candidate.count { it == last }) {
                        candidate = candidate.dropLast(1)
                    } else {
                        break
                    }
                }
                else -> break
            }
        }
        if (!(candidate.startsWith("http://", ignoreCase = true) ||
                candidate.startsWith("https://", ignoreCase = true))
        ) {
            return ""
        }
        // containsMatchIn 而不是 matches：host 正则没有 $，语义是「串首匹配」
        // （对齐 Python 的 re.match / JS 的 test），matches 会要求把整条 URL 消费完
        if (!LINK_HOST_RE.containsMatchIn(candidate)) return ""
        return if (candidate.length > 500) "" else candidate
    }

    /**
     * 存库前的收口：现在语义是「从输入里提取链接」而不是「原值是不是 http(s)」，
     * 整段分享文案也能拿到链接，伪协议与裸网址照旧清空。对已清洗过的库值恒等，
     * 读回路径不受影响。点开时还会再校验一次 —— 这个值最终会被渲染成可点的
     * 链接，伪协议在浏览器里能执行脚本。与后端 `_clean_link` 是同一套规则。
     */
    fun cleanLink(value: String?): String = extractLink(value)

    fun toDto(b: ItemBundle): ItemDto {
        val item = b.item
        val qty = totalQty(item, b.allocations)
        val paidQty = paidQty(b.records)
        val unpaidQty = unpaidQty(qty, paidQty)
        val deposit = depositPaid(b.records)
        val st = status(qty, paidQty, deposit)
        return ItemDto(
            id = item.id,
            name = item.name,
            categoryId = item.categoryId,
            categoryName = b.categoryName,
            brand = item.brand,
            model = item.model,
            unit = item.unit,
            qtyTotal = item.qtyTotal,
            price = item.price,
            discountPrice = item.discountPrice,
            bought = st == STATUS_DONE,
            note = item.note,
            link = item.link,
            createdAt = item.createdAt,
            totalQty = qty,
            listTotal = listTotal(item, b.allocations),
            discountTotal = discountTotal(item, b.allocations),
            paidQty = paidQty,
            paidPrice = paidPrice(b.records),
            paid = paid(b.records),
            unpaidQty = unpaidQty,
            unpaid = unpaid(unpaidQty, item, deposit),
            status = st,
            records = b.records.map { toRecordDto(it, b.recordRooms[it.id] ?: emptyList()) },
            allocations = b.allocations.map { toDto(it) },
        )
    }

    fun toRecordDto(r: PurchaseRecordEntity, roomIds: List<Int>): RecordDto = RecordDto(
        id = r.id,
        itemId = r.itemId,
        qty = r.qty,
        amount = r.amount,
        // 定金字必须跟着回读：漏了它界面永远读回"未勾选"，用户再保存一次
        // （整批替换）就把库里真实的 true 抹成 false —— 看起来就是"勾了没用"
        isDeposit = r.isDeposit,
        unitPrice = if (r.qty > 0 && r.amount != 0.0) r2(r.amount / r.qty) else null,
        date = forRead(r.date),
        note = r.note,
        vendor = r.vendor,
        orderNo = r.orderNo,
        roomIds = roomIds.filter { it != 0 },
        createdAt = r.createdAt,
        updatedAt = r.updatedAt,
    )

    fun toDto(a: AllocationEntity): AllocationDto = AllocationDto(
        id = a.id,
        itemId = a.itemId,
        roomId = a.roomId,
        qty = a.qty,
        priceOverride = a.priceOverride,
        note = a.note,
    )

    /** 额外费用视图。[itemName] 由调用方查好（物料可能已进回收站，名字仍要能显示）。 */
    fun toDto(e: ExtraExpenseEntity, itemName: String): ExpenseDto = ExpenseDto(
        id = e.id,
        kind = e.kind,
        amount = e.amount,
        date = forRead(e.date),
        vendor = e.vendor,
        orderNo = e.orderNo,
        note = e.note,
        itemId = e.itemId,
        itemName = itemName,
    )

    fun toMatrixItem(b: ItemBundle): MatrixItemDto {
        val cover = paidCover(b)
        val qty = totalQty(b.item, b.allocations)
        val paidQty = paidQty(b.records)
        val st = status(qty, paidQty)
        return MatrixItemDto(
            id = b.item.id,
            name = b.item.name,
            model = b.item.model,
            categoryName = b.categoryName,
            unit = b.item.unit,
            price = b.item.price,
            discountPrice = b.item.discountPrice,
            bought = st == STATUS_DONE,
            status = st,
            paidQty = paidQty,
            totalQty = qty,
            totalQtySource = if (b.allocations.isNotEmpty()) "allocations" else "item",
            listTotal = listTotal(b.item, b.allocations),
            cells = b.allocations.associate { a ->
                a.roomId.toString() to MatrixCellDto(
                    qty = a.qty,
                    priceOverride = a.priceOverride,
                    paidQty = cover[a.id] ?: 0.0,
                    note = a.note,
                )
            },
        )
    }
}
