<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, money, qty as fmtQty } from '../api'
import ItemDialog from '../components/ItemDialog.vue'

const items = ref([])
const rooms = ref([])
const categories = ref([])
const q = ref('')
const categoryId = ref(null)
const brand = ref('')
const statusFilter = ref('all')

// 品牌下拉的选项从当前清单实际用到的品牌里取（去重、排序、忽略空值）——
// 写死一份品牌表没意义，家具家电和年货的品牌完全不是一回事
const brands = computed(() => {
  const seen = new Set(items.value.map((it) => (it.brand || '').trim()).filter(Boolean))
  return [...seen].sort((a, b) => a.localeCompare(b, 'zh'))
})

const STATUS = {
  done: { label: '已买完', type: 'success' },
  partial: { label: '部分已买', type: 'primary' },
  unbought: { label: '未买', type: 'info' },
  none: { label: '无需采购', type: 'info' },
}

const filtered = computed(() => items.value.filter((it) => {
  if (categoryId.value && it.category_id !== categoryId.value) return false
  if (brand.value && (it.brand || '').trim() !== brand.value) return false
  if (statusFilter.value !== 'all' && it.status !== statusFilter.value) return false
  if (q.value && !it.name.includes(q.value)) return false
  return true
}))

// 空清单和"筛不出结果"是两回事：前者要告诉人怎么开始
const emptyText = computed(() => {
  const untouched = !items.value.length && !q.value && !categoryId.value
    && !brand.value && statusFilter.value === 'all'
  return untouched
    ? '这个清单还是空的：点「新增物料」加第一条，分组可以在「设置 / 数据 → 分组」里建'
    : '没有符合条件的物料'
})

const today = new Date().toISOString().slice(0, 10)
const tableBoxRef = ref(null)
const tableHeight = ref(420)
let ro = null
function calcTableHeight() {
  const el = tableBoxRef.value
  if (!el) return
  const h = Math.max(160, Math.floor(el.clientHeight))
  if (Math.abs(h - tableHeight.value) > 1) tableHeight.value = h
}

// 分页与排序：每页条数记在 localStorage，下次打开还是这个数
const page = ref(1)
const SIZE_OPTIONS = [10, 20, 50, 100, 200]
const pageSize = ref(Number(localStorage.getItem('items.pageSize')) || 20)
const sortState = ref({ prop: 'discount_total', order: 'descending' })

watch(pageSize, (v) => {
  page.value = 1
  const n = Math.floor(Number(v))
  if (Number.isFinite(n) && n >= 1) {
    try { localStorage.setItem('items.pageSize', String(n)) } catch { /* 隐私模式禁写 */ }
  }
})

// 下拉里可以手输任意数字（allow-create 给的是字符串），这里统一规整：
// 不合法就退回 20，上限一万（再多浏览器也吃不消）
function onSizeChange(v) {
  const n = Math.floor(Number(v))
  pageSize.value = Number.isFinite(n) && n >= 1 ? Math.min(n, 10000) : 20
}
// 筛选一改就回第一页，否则可能停在一个空页上
watch([q, categoryId, brand, statusFilter], () => { page.value = 1 })

// 先排序、再切页：排序必须作用于**全量**筛选结果，
// 交给 el-table 自己排的话只会在当前页内排，翻到第二页数字就乱了
const sorted = computed(() => {
  const arr = [...filtered.value]
  const { prop, order } = sortState.value
  if (!prop || !order) return arr
  const dir = order === 'ascending' ? 1 : -1
  return arr.sort((a, b) => ((Number(a[prop]) || 0) - (Number(b[prop]) || 0)) * dir)
})

const paged = computed(() => {
  const size = Math.max(1, Math.floor(Number(pageSize.value) || 20))
  const start = (page.value - 1) * size
  return sorted.value.slice(start, start + size)
})

function onSortChange({ prop, order }) {
  sortState.value = { prop, order }
  page.value = 1
}

const dialogVisible = ref(false)
const editing = ref(null)
const loading = ref(false)
const selectedIds = ref([])
function onSelectionChange(rows) {
  selectedIds.value = rows.map(r => r.id)
}

const filteredTotals = computed(() => {
  let discount = 0; let paid = 0
  for (const it of filtered.value) { discount += it.discount_total; paid += it.paid }
  return { discount: Math.round(discount * 100) / 100, paid: Math.round(paid * 100) / 100 }
})

onMounted(async () => {
  await load()
  await nextTick()
  calcTableHeight()
  ro = new ResizeObserver(calcTableHeight)
  if (tableBoxRef.value) ro.observe(tableBoxRef.value)
  window.addEventListener('resize', calcTableHeight)
})

onUnmounted(() => {
  if (ro) ro.disconnect()
  window.removeEventListener('resize', calcTableHeight)
})


async function load() {
  loading.value = true
  try {
    ;[items.value, rooms.value, categories.value] = await Promise.all([
      api.get('/api/items'), api.get('/api/rooms'), api.get('/api/categories'),
    ])
  } finally {
    loading.value = false
    selectedIds.value = []
  }
}

function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  dialogVisible.value = true
}

const payVisible = ref(false)
const payBusy = ref(false)
const payForm = ref({ id: null, name: '', unit: '', totalQty: 0, recordCount: 0,
                      paid: 0, qty: 0, amount: 0, date: '', vendor: '', order_no: '',
                      room_ids: [], row: null })

// 这笔钱涉及哪几间：只列这条物料分到的分组，不选就按老规矩推算
const payRooms = computed(() => {
  const row = payForm.value.row
  if (!row?.allocations?.length) return []
  const ids = new Set(row.allocations.map((a) => a.room_id))
  return rooms.value.filter((r) => ids.has(r.id))
})

function openPay(row) {
  payForm.value = {
    id: row.id, name: row.name, unit: row.unit || '',
    totalQty: row.total_qty || 0,
    recordCount: (row.records || []).length,
    paid: row.paid || 0,
    qty: Math.max(0, (row.total_qty || 0) - (row.paid_qty || 0)),
    amount: 0,
    date: today,
    vendor: '',
    order_no: '',
    room_ids: [],
    row,
  }
  payVisible.value = true
}

function _applyPay(updated) {
  if (payForm.value.row) Object.assign(payForm.value.row, updated)
  payVisible.value = false
}

async function addRecord() {
  const f = payForm.value
  if (!Number(f.qty) && !Number(f.amount)) {
    ElMessage.warning('请填写实付数量或实付金额')
    return
  }
  payBusy.value = true
  try {
    const updated = await api.post(`/api/items/${f.id}/records`, {
      qty: Number(f.qty) || 0,
      amount: Number(f.amount) || 0,
      date: f.date || today,
      vendor: f.vendor || '',
      order_no: f.order_no || '',
      room_ids: f.room_ids || [],
    })
    _applyPay(updated)
    ElMessage.success('已记录一笔采购')
  } catch (e) {
    ElMessage.error(e.message)
  } finally { payBusy.value = false }
}

async function quickPaid() {
  const f = payForm.value
  payBusy.value = true
  try {
    const updated = await api.post(`/api/items/${f.id}/records`, {
      qty: Number(f.totalQty) || 0,
      amount: Number(f.row?.discount_total) || 0,
      date: f.date || today,
      vendor: f.vendor || '',
      order_no: f.order_no || '',
      room_ids: f.room_ids || [],
    })
    _applyPay(updated)
    ElMessage.success('已按日常价付清')
  } catch (e) {
    ElMessage.error(e.message)
  } finally { payBusy.value = false }
}

async function clearRecords() {
  const f = payForm.value
  try {
    await ElMessageBox.confirm(`确定清零「${f.name}」的所有采购记录？清零后状态变为未买。`, '清零确认', { type: 'warning' })
  } catch { return }
  payBusy.value = true
  try {
    const updated = await api.del(`/api/items/${f.id}/records`)
    _applyPay(updated)
    ElMessage.success('已清零')
  } catch (e) {
    ElMessage.error(e.message)
  } finally { payBusy.value = false }
}

async function batchDelete() {
  if (!selectedIds.value.length) return
  try {
    await ElMessageBox.confirm(
      `把选中的 ${selectedIds.value.length} 项移入回收站？之后可以恢复。`,
      '移入回收站', { type: 'warning', confirmButtonText: '移入回收站' })
  } catch { return }
  try {
    const res = await api.post('/api/items/batch/delete', { ids: selectedIds.value })
    ElMessage.success(`已移入回收站 ${res.deleted} 项`)
    selectedIds.value = []
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

async function removeItem(row) {
  try {
    await ElMessageBox.confirm(
      `把「${row.name}」移入回收站？它的分配合采购记录都会留着，之后可以恢复。`,
      '移入回收站', { type: 'warning', confirmButtonText: '移入回收站' })
  } catch { return }
  try {
    await api.del(`/api/items/${row.id}`)
    ElMessage.success('已移入回收站（设置 / 数据 → 回收站 里可以恢复）')
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

// 分类色：按名字算一个稳定的颜色，而不是写死一张表。
// 写死表的后果就是新建的分类（不在表里）圆点全变灰 —— 真实数据里的
// 「灯具照明 / 网络面板 / 家电家装」就一个都匹配不上。
// 用 hash 取色：同一个名字永远同一个色，以后再加分类也自动有颜色。
const CAT_PALETTE = ['#0a84ff', '#34c759', '#ff9f0a', '#ff375f',
                     '#5e5ce6', '#30b0c7', '#af52de', '#ff6482']
function catColor(name) {
  if (!name) return '#8e8e93'
  let h = 0
  for (const ch of name) h = (h * 31 + ch.codePointAt(0)) % 9973
  return CAT_PALETTE[h % CAT_PALETTE.length]
}

function onSaved() {
  dialogVisible.value = false
  load()
}
</script>

<template>
  <div v-loading="loading" class="page-wrap">
    <div class="toolbar">
      <el-input v-model="q" placeholder="搜索物料名称" clearable class="search" />
      <el-select v-model="categoryId" placeholder="全部分类" clearable class="cat-select">
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="brand" placeholder="全部品牌" clearable filterable
                 class="brand-select">
        <el-option v-for="b in brands" :key="b" :label="b" :value="b" />
      </el-select>
      <el-radio-group v-model="statusFilter">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="unbought">未买</el-radio-button>
        <el-radio-button value="partial">部分已买</el-radio-button>
        <el-radio-button value="done">已买完</el-radio-button>
      </el-radio-group>
      <span class="totals">
        日常价 <b class="t-blue">￥{{ money(filteredTotals.discount) }}</b>
        · 实付 <b class="t-green">￥{{ money(filteredTotals.paid) }}</b>
      </span>
      <el-button type="danger" plain :disabled="!selectedIds.length" @click="batchDelete">
        删除所选 {{ selectedIds.length ? `(${selectedIds.length})` : '' }}
      </el-button>
      <el-button type="primary" @click="openCreate">新增物料</el-button>
    </div>

    <div class="glass panel fill-panel">
      <div v-show="loading && !filtered.length" class="table-box skeleton-pad">
        <el-skeleton :rows="8" animated />
      </div>
      <el-empty v-show="!loading && !filtered.length" :description="emptyText" :image-size="80" />
      <div v-show="filtered.length" ref="tableBoxRef" class="table-box">
      <el-table :data="paged" size="default" row-key="id" :height="tableHeight"
                :default-sort="{ prop: 'discount_total', order: 'descending' }"
                @sort-change="onSortChange"
                @selection-change="onSelectionChange">
        <!-- reserve-selection：翻页勾选的也留着，不然跨页批量删会漏 -->
        <el-table-column type="selection" width="36" reserve-selection />
        <el-table-column prop="name" label="物料" min-width="150" />
        <el-table-column prop="brand" label="品牌" width="80" show-overflow-tooltip>
          <template #default="{ row }">{{ row.brand || '-' }}</template>
        </el-table-column>
        <el-table-column prop="model" label="型号" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.model || '-' }}</template>
        </el-table-column>
        <el-table-column prop="category_name" label="分类" width="96">
          <template #default="{ row }">
            <span v-if="row.category_name" class="cat-cell">
              <i class="cat-dot" :style="{ background: catColor(row.category_name) }" />
              {{ row.category_name }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="80" align="right">
          <template #default="{ row }">{{ fmtQty(row.total_qty) }} {{ row.unit }}</template>
        </el-table-column>
        <el-table-column prop="price" label="单价" width="90" align="right"
                         sortable="custom">
          <template #default="{ row }">{{ row.price ? money(row.price) : '-' }}</template>
        </el-table-column>
        <el-table-column label="日常价" width="100" align="right"
                         sortable="custom" prop="discount_total">
          <template #default="{ row }">￥{{ money(row.discount_total) }}</template>
        </el-table-column>
        <el-table-column label="已付 / 未付" width="118" align="right">
          <template #default="{ row }">
            <div>{{ row.paid ? `￥${money(row.paid)}` : '-' }}</div>
            <div class="unpaid-cell" :class="{ cleared: row.paid && row.unpaid <= 0 }">
              {{ row.paid ? (row.unpaid > 0 ? `未付 ￥${money(row.unpaid)}` : '已付清') : '-' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="采购状态" width="124">
          <template #default="{ row }">
            <el-tag :type="STATUS[row.status]?.type || 'info'" effect="light"
                    class="status-tag" @click="openPay(row)">
              {{ STATUS[row.status]?.label || '未买' }}
              <span v-if="row.status === 'partial'" class="tag-ratio">
                {{ fmtQty(row.paid_qty) }}/{{ fmtQty(row.total_qty) }}
              </span>
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="note" label="备注" min-width="110" show-overflow-tooltip />
        <!-- 宽度按胶囊按钮算：两个按钮 + 间距 + 单元格内边距 -->
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeItem(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      </div>

      <div v-if="filtered.length" class="pager">
        <span class="pager-total">共 {{ filtered.length }} 条</span>
        <el-select v-model="pageSize" class="pager-size" size="small"
                   filterable allow-create default-first-option
                   @change="onSizeChange">
          <el-option v-for="n in SIZE_OPTIONS" :key="n" :label="`${n} 条/页`"
                     :value="String(n)" />
        </el-select>
        <el-pagination v-model:current-page="page"
                       :page-size="Math.max(1, Math.floor(Number(pageSize) || 20))"
                       :total="filtered.length"
                       layout="prev, pager, next, jumper" background />
      </div>
    </div>

    <ItemDialog v-model="dialogVisible" :item="editing" :rooms="rooms"
                :categories="categories" @saved="onSaved" />

    <el-dialog v-model="payVisible" width="420px" append-to-body>
      <template #header>
        <div class="dlg-title">
          <span class="dlg-chip dlg-green">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" stroke-width="2.2"
                 stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 6h18l-1.6 10.2A2 2 0 0 1 17.4 18H6.6a2 2 0 0 1-2-1.8L3 6z" />
              <path d="M8 6 9.5 3h5L16 6" />
            </svg>
          </span>
          记一笔采购 · {{ payForm.name }}
        </div>
      </template>

      <div class="pay-sum">
        <span>共 <b>{{ fmtQty(payForm.totalQty) }}{{ payForm.unit }}</b></span>
        <span>已付 <b class="t-green">￥{{ money(payForm.paid) }}</b></span>
        <span>已记 {{ payForm.recordCount }} 笔</span>
      </div>

      <el-form label-width="76px">
        <el-form-item label="实付数量">
          <el-input-number v-model="payForm.qty" :min="0" :max="payForm.totalQty"
                           controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="实付金额">
          <el-input-number v-model="payForm.amount" :min="0" :precision="2"
                           controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="付款日期">
          <el-date-picker v-model="payForm.date" type="date" value-format="YYYY-MM-DD"
                          :default-value="today" placeholder="付款日期" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="payRooms.length" label="涉及分组">
          <el-select v-model="payForm.room_ids" multiple clearable collapse-tags
                     placeholder="不选则按分配顺序推算" style="width: 100%">
            <el-option v-for="r in payRooms" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="商家">
          <el-input v-model="payForm.vendor" placeholder="选填，如 京东" maxlength="50" />
        </el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="payForm.order_no" placeholder="选填，售后与对账用"
                    maxlength="50" @keyup.enter="addRecord" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button size="small" type="danger" plain :disabled="payBusy" @click="clearRecords">清零</el-button>
        <el-button size="small" type="primary" plain :disabled="payBusy" @click="quickPaid">按日常价付清</el-button>
        <el-button size="small" @click="payVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="payBusy" @click="addRecord">记一笔</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-wrap { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  flex: none;
}
.search { width: 200px; }
.cat-select { width: 130px; }
.brand-select { width: 130px; }
.totals { color: var(--ios-label-2); font-size: 13px; margin-right: auto; }
.t-blue { color: var(--ios-blue); font-weight: 700; font-style: normal; }
.t-green { color: var(--ios-green); font-weight: 700; font-style: normal; }
/* 表格视口：撑满面板剩余高度，滚动只发生在表格内部 */
.glass.panel.fill-panel {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  padding: 10px 8px;
  overflow: hidden;
}
.fill-panel > .el-empty { flex: 1 1 auto; min-height: 0; }
.table-box { flex: 1 1 auto; min-height: 0; }
.pager {
  flex: none;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 2px 0;
}
/* 总数靠左，条数下拉与翻页靠右 */
.pager-total { margin-right: auto; font-size: 13px; color: var(--ios-label-2); }
.pager-size { width: 112px; }
.skeleton-pad { padding: 10px 8px; }
.cat-cell { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; }
.cat-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.unpaid-cell { font-size: 11px; color: var(--ios-orange); }
.unpaid-cell.cleared { color: var(--ios-green); }
.status-tag { cursor: pointer; font-weight: 600; }
.tag-ratio { font-weight: 500; }
.pay-sum {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 12px;
  color: var(--ios-label-2);
  margin-bottom: 14px;
}
.pay-sum b { color: var(--ios-label); font-weight: 600; }
</style>
