<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, money, qty as fmtQty } from '../api'
import ItemDialog from '../components/ItemDialog.vue'

const items = ref([])
const rooms = ref([])
const categories = ref([])
const q = ref('')
const categoryId = ref(null)
const statusFilter = ref('all')

const STATUS = {
  done: { label: '已买完', type: 'success' },
  partial: { label: '部分已买', type: 'primary' },
  unbought: { label: '未买', type: 'info' },
  none: { label: '无需采购', type: 'info' },
}

const filtered = computed(() => items.value.filter((it) => {
  if (categoryId.value && it.category_id !== categoryId.value) return false
  if (statusFilter.value !== 'all' && it.status !== statusFilter.value) return false
  if (q.value && !it.name.includes(q.value)) return false
  return true
}))

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
                      paid: 0, qty: 0, amount: 0, date: '', row: null })

function openPay(row) {
  payForm.value = {
    id: row.id, name: row.name, unit: row.unit || '',
    totalQty: row.total_qty || 0,
    recordCount: (row.records || []).length,
    paid: row.paid || 0,
    qty: Math.max(0, (row.total_qty || 0) - (row.paid_qty || 0)),
    amount: 0,
    date: today,
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
    await ElMessageBox.confirm(`确定删除选中的 ${selectedIds.value.length} 项物料？相关布点和采购记录也会一并删除。`, '批量删除', { type: 'warning' })
  } catch { return }
  try {
    const res = await api.post('/api/items/batch/delete', { ids: selectedIds.value })
    ElMessage.success(`已删除 ${res.deleted} 项`)
    selectedIds.value = []
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

async function removeItem(row) {
  try {
    await ElMessageBox.confirm(`确定删除物料「${row.name}」？其布点明细会一并删除。`, '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await api.del(`/api/items/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (e) { ElMessage.error(e.message) }
}

const CAT_COLORS = { 照明: '#0a84ff', 开关插座: '#34c759', 网络: '#ff9f0a', 家装: '#ff375f' }
function catColor(name) {
  return CAT_COLORS[name] || '#8e8e93'
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
      <el-select v-model="categoryId" placeholder="全部类目" clearable class="cat-select">
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
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
      <el-empty v-show="!loading && !filtered.length" description="没有符合条件的物料" :image-size="80" />
      <div v-show="filtered.length" ref="tableBoxRef" class="table-box">
      <el-table :data="filtered" size="default" row-key="id" :height="tableHeight"
                :default-sort="{ prop: 'discount_total', order: 'descending' }"
                @selection-change="onSelectionChange">
        <el-table-column type="selection" width="36" />
        <el-table-column prop="name" label="物料" min-width="150" />
        <el-table-column prop="brand" label="品牌" width="80" show-overflow-tooltip>
          <template #default="{ row }">{{ row.brand || '-' }}</template>
        </el-table-column>
        <el-table-column prop="model" label="型号" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.model || '-' }}</template>
        </el-table-column>
        <el-table-column prop="category_name" label="类目" width="96">
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
        <el-table-column label="单价" width="90" align="right">
          <template #default="{ row }">{{ row.price ? money(row.price) : '-' }}</template>
        </el-table-column>
        <el-table-column label="日常价" width="100" align="right" sortable prop="discount_total">
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
        <el-table-column label="操作" width="112">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeItem(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
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
