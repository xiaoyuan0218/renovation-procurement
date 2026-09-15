package com.xiaoyuan.renovation.ui.items

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoyuan.renovation.data.model.AllocInDto
import com.xiaoyuan.renovation.data.model.CategoryDto
import com.xiaoyuan.renovation.data.model.ItemInDto
import com.xiaoyuan.renovation.data.model.RecordInDto
import com.xiaoyuan.renovation.data.model.RoomDto
import com.xiaoyuan.renovation.data.repo.ApiResult
import com.xiaoyuan.renovation.data.repo.RenovationRepository
import com.xiaoyuan.renovation.data.repo.okData
import com.xiaoyuan.renovation.domain.Compute
import com.xiaoyuan.renovation.util.Fmt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 编辑中的一条采购记录（用字符串承接输入，保存时再解析）。 */
data class DraftRecord(
    val key: Long,
    val qty: String = "",
    val amount: String = "",
    val date: String = Fmt.today(),
    val note: String = "",
)

/** 编辑中的一条布点。 */
data class DraftAlloc(
    val key: Long,
    val roomId: Int? = null,
    val qty: String = "",
    val priceOverride: String = "",
    val note: String = "",
)

data class ItemForm(
    val id: Int = NEW_ITEM_ID,
    val name: String = "",
    val brand: String = "",
    val model: String = "",
    val categoryId: Int? = null,
    val unit: String = "个",
    val qtyTotal: String = "1",
    val price: String = "",
    val discountPrice: String = "",
    val note: String = "",
    val records: List<DraftRecord> = emptyList(),
    val allocations: List<DraftAlloc> = emptyList(),
) {
    val isNew: Boolean get() = id == NEW_ITEM_ID
    val hasAllocations: Boolean get() = allocations.any { it.roomId != null && Fmt.parseNumberOrZero(it.qty) > 0 }
}

/** 表单下方的实时口径预览，公式与后端 compute.py 一致。 */
data class ItemPreview(
    val totalQty: Double,
    val listTotal: Double,
    val discountTotal: Double,
    val paidQty: Double,
    val paid: Double,
    val unpaidQty: Double,
    val unpaid: Double,
    val status: String,
    val paidUnitPrice: Double?,
)

class ItemEditViewModel(private val repo: RenovationRepository) : ViewModel() {

    private val _form = MutableStateFlow(ItemForm())
    val form: StateFlow<ItemForm> = _form.asStateFlow()

    private val _rooms = MutableStateFlow<List<RoomDto>>(emptyList())
    val rooms: StateFlow<List<RoomDto>> = _rooms.asStateFlow()

    private val _categories = MutableStateFlow<List<CategoryDto>>(emptyList())
    val categories: StateFlow<List<CategoryDto>> = _categories.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var started = false
    private var nextKey = 1L

    fun edit(transform: (ItemForm) -> ItemForm) {
        _form.update(transform)
    }

    /** 首次进入时加载：物料本身（编辑模式）+ 类目 + 房间。 */
    fun start(itemId: Int) {
        if (started) return
        started = true

        viewModelScope.launch {
            _loading.value = true

            val categoriesResult = repo.categories()
            val roomsResult = repo.rooms()
            val failure = listOf(categoriesResult, roomsResult)
                .filterIsInstance<ApiResult.Err>()
                .firstOrNull()
            if (failure != null) {
                _loadError.value = failure.message
                _loading.value = false
                return@launch
            }
            _categories.value = categoriesResult.okData.orEmpty()
            _rooms.value = roomsResult.okData.orEmpty()

            if (itemId != NEW_ITEM_ID) {
                when (val itemResult = repo.item(itemId)) {
                    is ApiResult.Ok -> {
                        val item = itemResult.data
                        _form.value = ItemForm(
                            id = item.id,
                            name = item.name,
                            brand = item.brand,
                            model = item.model,
                            categoryId = item.categoryId,
                            unit = item.unit,
                            qtyTotal = Fmt.qty(item.qtyTotal),
                            price = if (item.price > 0) Fmt.qty(item.price) else "",
                            discountPrice = item.discountPrice?.let { Fmt.qty(it) } ?: "",
                            note = item.note,
                            records = item.records.map {
                                DraftRecord(
                                    key = nextKey++,
                                    qty = Fmt.qty(it.qty),
                                    amount = if (it.amount > 0) Fmt.qty(it.amount) else "",
                                    date = it.date,
                                    note = it.note,
                                )
                            },
                            allocations = item.allocations.map {
                                DraftAlloc(
                                    key = nextKey++,
                                    roomId = it.roomId,
                                    qty = Fmt.qty(it.qty),
                                    priceOverride = it.priceOverride?.let { p -> Fmt.qty(p) } ?: "",
                                    note = it.note,
                                )
                            },
                        )
                    }

                    is ApiResult.Err -> _loadError.value = itemResult.message
                }
            }
            _loading.value = false
        }
    }

    fun retryLoad(itemId: Int) {
        started = false
        _loadError.value = null
        start(itemId)
    }

    /* ---------- 采购记录行 ---------- */

    fun addRecordRow() {
        _form.update { it.copy(records = it.records + DraftRecord(key = nextKey++)) }
    }

    fun removeRecordRow(key: Long) {
        _form.update { form -> form.copy(records = form.records.filterNot { it.key == key }) }
    }

    fun updateRecordRow(key: Long, transform: (DraftRecord) -> DraftRecord) {
        _form.update { form ->
            form.copy(records = form.records.map { if (it.key == key) transform(it) else it })
        }
    }

    /* ---------- 布点行 ---------- */

    fun addAllocRow() {
        _form.update { it.copy(allocations = it.allocations + DraftAlloc(key = nextKey++)) }
    }

    fun removeAllocRow(key: Long) {
        _form.update { form -> form.copy(allocations = form.allocations.filterNot { it.key == key }) }
    }

    fun updateAllocRow(key: Long, transform: (DraftAlloc) -> DraftAlloc) {
        _form.update { form ->
            form.copy(allocations = form.allocations.map { if (it.key == key) transform(it) else it })
        }
    }

    /* ---------- 计算预览 ---------- */

    fun preview(form: ItemForm): ItemPreview {
        val price = Fmt.parseNumberOrZero(form.price)
        val allocs = toAllocInputs(form)
        val records = toRecordInputs(form)

        val totalQty = Compute.totalQty(allocs, Fmt.parseNumberOrZero(form.qtyTotal))
        val listTotal = Compute.listTotal(allocs, price, Fmt.parseNumberOrZero(form.qtyTotal))
        val discountTotal = Compute.discountTotal(totalQty, price, Fmt.parseNumber(form.discountPrice))
        val paidQty = Compute.paidQty(records)
        val paid = Compute.paidAmount(records)
        val unpaidQty = Compute.unpaidQty(totalQty, paidQty)

        return ItemPreview(
            totalQty = totalQty,
            listTotal = listTotal,
            discountTotal = discountTotal,
            paidQty = paidQty,
            paid = paid,
            unpaidQty = unpaidQty,
            unpaid = Compute.unpaidAmount(totalQty, paidQty, price),
            status = Compute.status(totalQty, paidQty),
            paidUnitPrice = Compute.paidUnitPrice(paidQty, paid),
        )
    }

    private fun toAllocInputs(form: ItemForm): List<AllocInDto> =
        form.allocations.mapNotNull { row ->
            val roomId = row.roomId ?: return@mapNotNull null
            val qty = Fmt.parseNumberOrZero(row.qty)
            if (qty <= 0) return@mapNotNull null
            AllocInDto(
                roomId = roomId,
                qty = qty,
                priceOverride = Fmt.parseNumber(row.priceOverride),
                note = row.note.trim(),
            )
        }

    private fun toRecordInputs(form: ItemForm): List<RecordInDto> =
        form.records.map { row ->
            RecordInDto(
                qty = Fmt.parseNumberOrZero(row.qty),
                amount = Fmt.parseNumberOrZero(row.amount),
                date = row.date.trim(),
                note = row.note.trim(),
            )
        }

    /* ---------- 保存 / 删除 ---------- */

    fun save(onSaved: () -> Unit) {
        val form = _form.value
        if (form.name.isBlank()) {
            _message.value = "物料名称不能为空"
            return
        }

        val allocs = toAllocInputs(form)
        val body = ItemInDto(
            name = form.name.trim(),
            categoryId = form.categoryId,
            brand = form.brand.trim(),
            model = form.model.trim(),
            unit = form.unit.trim().ifBlank { "个" },
            // 有布点时总量由布点决定，这里同步成布点合计，避免两处数字不一致
            qtyTotal = if (allocs.isNotEmpty()) {
                Compute.totalQty(allocs, 0.0)
            } else {
                Fmt.parseNumberOrZero(form.qtyTotal)
            },
            price = Fmt.parseNumberOrZero(form.price),
            discountPrice = Fmt.parseNumber(form.discountPrice),
            note = form.note.trim(),
            allocations = allocs,
            records = toRecordInputs(form),
        )

        viewModelScope.launch {
            _saving.value = true
            val result = if (form.isNew) repo.createItem(body) else repo.updateItem(form.id, body)
            when (result) {
                is ApiResult.Ok -> onSaved()
                is ApiResult.Err -> _message.value = result.message
            }
            _saving.value = false
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val form = _form.value
        if (form.isNew) {
            onDeleted()
            return
        }
        viewModelScope.launch {
            _saving.value = true
            when (val result = repo.deleteItem(form.id)) {
                is ApiResult.Ok -> onDeleted()
                is ApiResult.Err -> _message.value = result.message
            }
            _saving.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
