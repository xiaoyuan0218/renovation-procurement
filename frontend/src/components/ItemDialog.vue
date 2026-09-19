<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, money, qty as fmtQty } from '../api'

const props = defineProps({
  item: { type: Object, default: null },
  rooms: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] },
})
const visible = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['saved'])

const blank = () => ({
  name: '', category_id: null, brand: '', model: '', unit: '个', qty_total: null,
  price: null, discount_price: null, note: '',
  allocations: [], records: [],
})
const form = ref(blank())
const formRef = ref(null)
const saving = ref(false)

watch(visible, (v) => {
  if (!v) return
  if (props.item) {
    form.value = {
      ...props.item,
      qty_total: props.item.qty_total ?? null,
      price: props.item.price ?? null,
      discount_price: props.item.discount_price,
      brand: props.item.brand || '',
      model: props.item.model || '',
      allocations: props.item.allocations.map((a) => ({ ...a })),
      records: (props.item.records || []).map((r) => ({
        qty: r.qty || 0, amount: r.amount || 0, date: r.date || '', note: r.note || '',
        vendor: r.vendor || '', order_no: r.order_no || '',
        room_ids: [...(r.room_ids || [])],
        // 服务端盖的时间戳：只读展示，保存时不回传（回传由服务端自己维护）
        created_at: r.created_at || '', updated_at: r.updated_at || '',
      })),
    }
  } else {
    form.value = blank()
  }
})

const rules = {
  name: [{ required: true, message: '请填写物料名称', trigger: 'blur' }],
}

const usedRoomIds = computed(() => new Set(form.value.allocations.map((a) => a.room_id)))
const allocQtyTotal = computed(() =>
  form.value.allocations.reduce((s, a) => s + (Number(a.qty) || 0), 0))
const allocListTotal = computed(() =>
  form.value.allocations.reduce((s, a) => {
    const unit = a.price_override ?? Number(form.value.price ?? 0)
    return s + (Number(a.qty) || 0) * (unit || 0)
  }, 0))
const allocationsLocked = computed(() => form.value.allocations.length > 0)

const maxBoughtQty = computed(() =>
  form.value.allocations.length ? allocQtyTotal.value : Number(form.value.qty_total || 0))

// 服务端盖的时间戳是 UTC（'YYYY-MM-DD HH:MM:SS'）；转成本地时区再看，
// 否则时间与用户的钟对不上（服务器容器常是 UTC，浏览器是本地时区）
const toLocalStamp = (s) => {
  if (!s) return ''
  const d = new Date(s.replace(' ', 'T') + 'Z')
  if (Number.isNaN(d.getTime())) return s
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
// 表格里空间紧，掐掉年份
const shortStamp = (s) => (s && s.length >= 16 ? toLocalStamp(s).slice(5, 16) : '')
const stampTitle = (row) => {
  if (!row.created_at) return '升级前的老数据，没有时间戳'
  const updated = row.updated_at && row.updated_at !== row.created_at
    ? `\n最后修改 ${toLocalStamp(row.updated_at)}` : ''
  return `记录于 ${toLocalStamp(row.created_at)}${updated}`
}

const recordsQtySum = computed(() =>
  form.value.records.reduce((s, r) => s + (Number(r.qty) || 0), 0))
const recordsAmountSum = computed(() =>
  form.value.records.reduce((s, r) => s + (Number(r.amount) || 0), 0))
const recordsPrice = computed(() => {
  const qty = recordsQtySum.value
  return qty > 0 && recordsAmountSum.value
    ? Math.round(recordsAmountSum.value / qty * 100) / 100
    : null
})

const unpaidQty = computed(() =>
  Math.max(0, maxBoughtQty.value - recordsQtySum.value))
const unpaidMoney = computed(() =>
  Math.round(unpaidQty.value * (Number(form.value.price) || 0) * 100) / 100)

const statusText = computed(() => {
  const b = recordsQtySum.value
  if (maxBoughtQty.value <= 0) return '无需采购'
  if (b >= maxBoughtQty.value) return '已买完'
  if (b > 0) return `部分已买 ${b}/${maxBoughtQty.value}`
  return '未买'
})

/* 价格汇总卡 */
const listTotal = computed(() => {
  if (form.value.allocations.length) return allocListTotal.value
  return (Number(form.value.qty_total) || 0) * (Number(form.value.price) || 0)
})
const discTotal = computed(() => {
  const qty = maxBoughtQty.value
  const unit = form.value.discount_price != null ? Number(form.value.discount_price) : (Number(form.value.price) || 0)
  return Math.round(qty * unit * 100) / 100
})

function addRecord() {
  form.value.records.push({ qty: 0, amount: null, date: '', note: '',
                            vendor: '', order_no: '', room_ids: [] })
}

function removeRecord(idx) {
  form.value.records.splice(idx, 1)
}

// 采购记录里的「归属分组」只列这条物料实际分到的分组 ——
// 归到一个它根本没分到的分组没有意义
const allocRooms = computed(() => {
  const ids = new Set(form.value.allocations.map((a) => a.room_id))
  return props.rooms.filter((r) => ids.has(r.id))
})

function addAlloc() {
  if (!props.rooms.length) {
    ElMessage.info('这个清单还没有分组，先去「设置 / 数据 → 分组」添加')
    return
  }
  const free = props.rooms.find((r) => !usedRoomIds.value.has(r.id))
  if (!free) { ElMessage.info('所有分组都已添加'); return }
  form.value.allocations.push({ room_id: free.id, qty: 1, price_override: null, note: '' })
}

function removeAlloc(idx) {
  form.value.allocations.splice(idx, 1)
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    const payload = {
      name: form.value.name,
      category_id: form.value.category_id,
      brand: form.value.brand || '',
      model: form.value.model || '',
      unit: form.value.unit || '个',
      qty_total: Number(form.value.qty_total) || 0,
      price: Number(form.value.price) || 0,
      discount_price: form.value.discount_price === null || form.value.discount_price === ''
        ? null : Number(form.value.discount_price),
      note: form.value.note || '',
      allocations: form.value.allocations.map((a) => ({
        room_id: a.room_id,
        qty: Number(a.qty) || 0,
        price_override: a.price_override === null || a.price_override === '' ? null : Number(a.price_override),
        note: a.note || '',
      })),
      records: form.value.records.map((r) => ({
        qty: Number(r.qty) || 0,
        amount: Number(r.amount) || 0,
        date: r.date || '',
        note: r.note || '',
        vendor: r.vendor || '',
        order_no: r.order_no || '',
        room_ids: r.room_ids || [],
      })),
    }
    const saved = props.item
      ? await api.put(`/api/items/${props.item.id}`, { ...payload, base_rev: props.item.rev })
      : await api.post('/api/items', payload)
    ElMessage.success('已保存')
    emit('saved', saved)
  } catch (e) {
    if (e.status === 409) {
      // 打开这个弹窗之后，别处（另一台设备/另一个标签页）改了这条。此时直接
      // 整条写回去会把对方的改动盖掉（比如刚记的那笔付款就没了），所以交给用户定。
      ElMessageBox.confirm(e.message, '这条已被别处修改', {
        confirmButtonText: '以我这边为准，覆盖',
        cancelButtonText: '取消，我先看看最新',
        type: 'warning',
        confirmButtonClass: 'el-button--danger',
      }).then(async () => {
        const fresh = await api.get(`/api/items/${props.item.id}`)
        const saved = await api.put(`/api/items/${props.item.id}`,
                                    { ...payload, base_rev: fresh.rev })
        ElMessage.success('已按你这边的版本覆盖保存')
        emit('saved', saved)
      }).catch(() => { /* 用户选择先刷新，什么都不做 */ })
    } else {
      ElMessage.error(e.message)
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <!-- 宽度要装得下「采购记录」那张表（数量/金额/单价/日期/分组/商家/订单号/备注/删除），
       否则最右边的备注和删除要横滑才看得到 -->
  <el-dialog v-model="visible" width="1060px"
             :close-on-click-modal="false" class="item-dialog">
    <template #header>
      <div class="dlg-title">
        <span class="dlg-chip dlg-blue">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>
        </span>
        {{ item ? '编辑物料' : '新增物料' }}
      </div>
    </template>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
      <!-- 基础信息 -->
      <el-row :gutter="12">
        <el-col :xs="24" :sm="12">
          <el-form-item label="名称" prop="name">
            <el-input v-model="form.name" placeholder="如：抽纸" />
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="品牌">
            <el-input v-model="form.brand" placeholder="如：小米" />
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="型号">
            <el-input v-model="form.model" placeholder="如：A200" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :xs="12" :sm="6">
          <el-form-item label="分类">
            <el-select v-model="form.category_id" placeholder="选择" clearable style="width: 100%">
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="单位">
            <el-input v-model="form.unit" placeholder="个/米/套" />
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="数量">
            <el-input-number :model-value="allocationsLocked ? allocQtyTotal : form.qty_total"
                             :min="0" :disabled="allocationsLocked"
                             controls-position="right" :value-on-clear="0" style="width: 100%"
                             @update:model-value="form.qty_total = $event" />
            <div v-if="allocationsLocked" class="field-hint">总量由分配合计决定</div>
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="备注">
            <el-input v-model="form.note" placeholder="选填" />
          </el-form-item>
        </el-col>
      </el-row>

      <!-- 价格信息：一行四列，明确标签 -->
      <el-row :gutter="12">
        <el-col :xs="12" :sm="6">
          <el-form-item label="单价（原价）">
            <el-input-number v-model="form.price" :min="0" :precision="2" controls-position="right"
                             :value-on-clear="0" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="日常单价">
            <el-input-number v-model="form.discount_price" :min="0" :precision="2"
                             controls-position="right" placeholder="留空按原价"
                             :value-on-clear="null" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="已付金额">
            <el-input-number :model-value="recordsAmountSum" :precision="2" disabled
                             controls-position="right" style="width: 100%" />
            <div class="field-hint">由下方采购记录汇总</div>
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="未付金额">
            <el-input-number :model-value="unpaidMoney" :precision="2" disabled
                             controls-position="right" style="width: 100%" />
            <div class="field-hint">{{ unpaidQty }}{{ form.unit }} × {{ money(form.price) }}</div>
          </el-form-item>
        </el-col>
      </el-row>

      <!-- 价格汇总卡 -->
      <div class="price-summary">
        <div class="ps-item">
          <span class="ps-label">原价小计</span>
          <span class="ps-value">￥{{ money(listTotal) }}</span>
        </div>
        <div class="ps-divider">|</div>
        <div class="ps-item">
          <span class="ps-label">日常价小计</span>
          <span class="ps-value">￥{{ money(discTotal) }}</span>
        </div>
        <div class="ps-divider">|</div>
        <div class="ps-item">
          <span class="ps-label">已付</span>
          <span class="ps-value paid">￥{{ money(recordsAmountSum) }}</span>
        </div>
        <div class="ps-divider">|</div>
        <div class="ps-item">
          <span class="ps-label">未付</span>
          <span class="ps-value unpaid">￥{{ money(unpaidMoney) }}</span>
        </div>
        <div class="ps-status" :class="{ done: recordsQtySum >= maxBoughtQty }">{{ statusText }}</div>
      </div>

      <el-divider content-position="left">采购记录（每笔付款一行，可多笔）</el-divider>
      <div class="table-wrap">
        <el-table :data="form.records" size="small" style="min-width: 1030px">
          <el-table-column label="实付数量" width="100">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0" size="small" controls-position="right"
                               style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="实付金额" width="110">
            <template #default="{ row }">
              <el-input-number v-model="row.amount" :min="0" :precision="2" size="small"
                               controls-position="right" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="实付单价" width="90" align="right">
            <template #default="{ row }">
              <span class="auto-price">{{ row.qty > 0 && row.amount ? money(Math.round(row.amount / row.qty * 100) / 100) : '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="日期" width="150">
            <template #default="{ row }">
              <el-date-picker v-model="row.date" type="date" value-format="YYYY-MM-DD"
                              placeholder="选择日期" size="small" style="width:100%" />
            </template>
          </el-table-column>
          <!-- 什么时候记的（服务端盖的时间戳，只读）；悬停能看到完整时间 -->
          <el-table-column label="记录于" width="96">
            <template #default="{ row }">
              <span class="rec-stamp" :title="stampTitle(row)">{{ shortStamp(row.created_at) }}</span>
            </template>
          </el-table-column>
          <!-- 涉及分组（可多选）：勾了谁，"这间买齐了没"就只往谁身上算 -->
          <el-table-column label="涉及分组" width="186">
            <template #default="{ row }">
              <el-select v-model="row.room_ids" multiple collapse-tags size="small"
                         placeholder="不指定" style="width: 100%">
                <el-option v-for="r in allocRooms" :key="r.id"
                           :label="r.name" :value="r.id" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="商家" width="100">
            <template #default="{ row }">
              <el-input v-model="row.vendor" size="small" placeholder="如 京东" />
            </template>
          </el-table-column>
          <el-table-column label="订单号" width="126">
            <template #default="{ row }">
              <el-input v-model="row.order_no" size="small" placeholder="选填" />
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="110">
            <template #default="{ row }">
              <el-input v-model="row.note" size="small" placeholder="如 定金/尾款" />
            </template>
          </el-table-column>
          <el-table-column width="66">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="removeRecord($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="alloc-footer">
        <el-button size="small" @click="addRecord">+ 添加采购记录</el-button>
        <span v-if="form.records.length" class="alloc-hint">
          合计 {{ recordsQtySum }}{{ form.unit || '' }} · 已付 ￥{{ money(recordsAmountSum) }}
          <template v-if="recordsPrice"> · 均价 ￥{{ money(recordsPrice) }}</template>
        </span>
        <span v-else class="alloc-hint">没填记录则视为未买</span>
      </div>

      <el-divider content-position="left">按分组分配（选填，总量以分配合计为准）</el-divider>
      <div class="table-wrap">
        <el-table :data="form.allocations" size="small" style="min-width: 560px">
          <el-table-column label="分组" width="140">
            <template #default="{ row }">
              <el-select v-model="row.room_id" style="width: 100%">
                <el-option v-for="r in rooms" :key="r.id" :label="r.name" :value="r.id"
                           :disabled="usedRoomIds.has(r.id) && r.id !== row.room_id" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="110">
            <template #default="{ row }">
              <el-input-number v-model="row.qty" :min="0" size="small" controls-position="right"
                               style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="覆盖单价" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.price_override" :min="0" :precision="2" size="small"
                               controls-position="right" placeholder="用物料单价"
                               :value-on-clear="null" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="120">
            <template #default="{ row }">
              <el-input v-model="row.note" size="small" placeholder="如：双口面板" />
            </template>
          </el-table-column>
          <el-table-column width="66">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="removeAlloc($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="alloc-footer">
        <el-button size="small" @click="addAlloc">+ 添加分组</el-button>
        <span v-if="form.allocations.length" class="alloc-hint">
          分配合计 {{ allocQtyTotal }}{{ form.unit || '' }} · 原价小计 ￥{{ money(allocListTotal) }}
        </span>
        <span v-else class="alloc-hint">不填分配则直接使用上方"数量"</span>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.price-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin: 4px 0 12px;
  background: rgba(0, 122, 255, 0.04);
  border: 1px solid rgba(0, 122, 255, 0.1);
  border-radius: 12px;
  font-size: 13px;
}
.ps-item { display: flex; align-items: baseline; gap: 4px; }
.ps-label { color: var(--ios-label-2); font-size: 12px; }
.ps-value { font-weight: 700; font-variant-numeric: tabular-nums; }
.ps-value.paid { color: var(--ios-green); }
.ps-value.unpaid { color: var(--ios-orange); }
.ps-divider { color: rgba(60, 60, 67, 0.2); font-size: 16px; }
.ps-status { margin-left: auto; font-size: 12px; color: var(--ios-label-2); }
.ps-status.done { color: var(--ios-green); }
.alloc-footer { display: flex; align-items: center; gap: 12px; margin-top: 8px; flex-wrap: wrap; }
.alloc-hint { color: var(--ios-label-2); font-size: 12px; }
.field-hint { font-size: 11px; color: var(--ios-label-2); line-height: 1.6; margin-top: 2px; }
.rec-stamp { font-size: 11px; color: var(--ios-label-3); font-variant-numeric: tabular-nums; }
.auto-price { color: var(--ios-label-2); font-variant-numeric: tabular-nums; }
:global(.item-dialog) { max-width: 94vw; }
</style>
