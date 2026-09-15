<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
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
  form.value.records.push({ qty: 0, amount: null, date: '', note: '' })
}

function removeRecord(idx) {
  form.value.records.splice(idx, 1)
}

function addAlloc() {
  const free = props.rooms.find((r) => !usedRoomIds.value.has(r.id))
  if (!free) { ElMessage.info('所有房间都已添加'); return }
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
      })),
    }
    const saved = props.item
      ? await api.put(`/api/items/${props.item.id}`, payload)
      : await api.post('/api/items', payload)
    ElMessage.success('已保存')
    emit('saved', saved)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" width="720px"
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
            <el-input v-model="form.name" placeholder="如：客厅吸顶灯" />
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="品牌">
            <el-input v-model="form.brand" placeholder="如：小米/易来" />
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="6">
          <el-form-item label="型号">
            <el-input v-model="form.model" placeholder="如：筒灯2Pro" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :xs="12" :sm="6">
          <el-form-item label="类目">
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
            <div v-if="allocationsLocked" class="field-hint">总量由布点合计决定</div>
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
        <el-table :data="form.records" size="small" style="min-width: 560px">
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
          <el-table-column label="备注" min-width="110">
            <template #default="{ row }">
              <el-input v-model="row.note" size="small" placeholder="如 订单号/店铺" />
            </template>
          </el-table-column>
          <el-table-column width="52">
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

      <el-divider content-position="left">按房间布点（选填，总量以布点合计为准）</el-divider>
      <div class="table-wrap">
        <el-table :data="form.allocations" size="small" style="min-width: 560px">
          <el-table-column label="房间" width="140">
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
          <el-table-column width="52">
            <template #default="{ $index }">
              <el-button link type="danger" size="small" @click="removeAlloc($index)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="alloc-footer">
        <el-button size="small" @click="addAlloc">+ 添加房间</el-button>
        <span v-if="form.allocations.length" class="alloc-hint">
          布点合计 {{ allocQtyTotal }}{{ form.unit || '' }} · 原价小计 ￥{{ money(allocListTotal) }}
        </span>
        <span v-else class="alloc-hint">不填布点则直接使用上方"数量"</span>
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
.auto-price { color: var(--ios-label-2); font-variant-numeric: tabular-nums; }
:global(.item-dialog) { max-width: 94vw; }
</style>
