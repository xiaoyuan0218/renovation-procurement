<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, money, qty as fmtQty, shortStamp, toLocalStamp } from '../api'

const props = defineProps({
  item: { type: Object, default: null },
  rooms: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] },
})
const visible = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['saved'])

const blank = () => ({
  name: '', category_id: null, brand: '', model: '', unit: '个', qty_total: null,
  price: null, discount_price: null, note: '', link: '',
  allocations: [], records: [],
})
const form = ref(blank())
const formRef = ref(null)
const saving = ref(false)

// 退款：往采购记录里追加一条负数记录（价保退差价、退货等）。统计、导出、同步
// 都沿用采购记录这一套口径，不需要再维护第二份「退款金额」。
const refundVisible = ref(false)
const refundIndex = ref(-1)
const refundMax = ref(0)
const refundForm = ref({ amount: 0, date: '', note: '价保退款' })

// 采购记录表最多这么高（超出在表内滚，表头钉住）。按屏幕高度算：大约占三分之一，
// 剩下的留给上面的信息与汇总、下面的分配表和底部按钮 —— 记录再多也不会把
// 「保存 / 取消」顶出屏幕。窗口大小变了要重算，所以监听 resize。
const recordMaxH = ref(260)
const allocMaxH = ref(200)
function calcRecordHeight() {
  const h = window.innerHeight || 800
  // 采购记录占约三分之一、分配表约四分之一，两张表各自滚，弹窗整体压得住
  recordMaxH.value = Math.max(170, Math.min(460, Math.round(h * 0.36)))
  allocMaxH.value = Math.max(120, Math.min(340, Math.round(h * 0.26)))
}
window.addEventListener('resize', calcRecordHeight)
calcRecordHeight()

watch(visible, (v) => {
  if (!v) return
  calcRecordHeight()   // 弹窗打开时按当前窗口再算一次
  if (props.item) {
    form.value = {
      ...props.item,
      qty_total: props.item.qty_total ?? null,
      price: props.item.price ?? null,
      discount_price: props.item.discount_price,
      brand: props.item.brand || '',
      model: props.item.model || '',
      link: props.item.link || '',
      allocations: props.item.allocations.map((a) => ({ ...a })),
      records: (props.item.records || []).map((r) => ({
        qty: r.qty || 0, amount: r.amount || 0, date: r.date || '', note: r.note || '',
        vendor: r.vendor || '', order_no: r.order_no || '',
        room_ids: [...(r.room_ids || [])],
        is_deposit: !!r.is_deposit,
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

const stampTitle = (row) => {
  if (!row.created_at) return '升级前的老数据，没有时间戳'
  const updated = row.updated_at && row.updated_at !== row.created_at
    ? `\n最后修改 ${toLocalStamp(row.updated_at)}` : ''
  return `记录于 ${toLocalStamp(row.created_at)}${updated}`
}

/** 每一笔的实付单价（金额 ÷ 数量），鼠标停在金额上才显示；定金没有数量，不显示 */
const unitTip = (row) => {
  const qty = Number(row.qty) || 0
  const amount = Number(row.amount) || 0
  if (row.is_deposit || qty <= 0 || !amount) return ''
  return `单价 ￥${money(Math.round(amount / qty * 100) / 100)}`
}

// 到货数量：定金是"钱先付、货没到"，不能算进来 —— 否则一填定金就显示买完
const recordsQtySum = computed(() =>
  form.value.records.reduce(
    (s, r) => s + (r.is_deposit ? 0 : (Number(r.qty) || 0)), 0))
// 已付金额：定金也算付出去的钱；退款（负数）自动扣回，得到的是净额
const recordsAmountSum = computed(() =>
  form.value.records.reduce((s, r) => s + (Number(r.amount) || 0), 0))
// 退款合计（只统计负数记录），用于在汇总里单独提示
const refundSum = computed(() => Math.round(
  form.value.records.reduce(
    (s, r) => s + (Number(r.amount) < 0 ? -Number(r.amount) : 0), 0) * 100) / 100)
// 已付的定金：用来抵扣未付（和后端口径一致）
const depositSum = computed(() =>
  form.value.records.reduce(
    (s, r) => s + (r.is_deposit ? (Number(r.amount) || 0) : 0), 0))
const recordsPrice = computed(() => {
  const qty = recordsQtySum.value
  return qty > 0 && recordsAmountSum.value
    ? Math.round(recordsAmountSum.value / qty * 100) / 100
    : null
})

const unpaidQty = computed(() =>
  Math.max(0, maxBoughtQty.value - recordsQtySum.value))
const unpaidMoney = computed(() =>
  Math.round(Math.max(0, unpaidQty.value * (Number(form.value.price) || 0)
    - depositSum.value) * 100) / 100)

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
                            vendor: '', order_no: '', room_ids: [], is_deposit: false })
}

function removeRecord(idx) {
  form.value.records.splice(idx, 1)
}

/** 金额为负的记录就是退款，界面上给个醒目的标记，别和普通付款混在一起看。 */
const isRefund = (row) => (Number(row.amount) || 0) < 0

function todayStr() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function openRefund(row, index) {
  const amount = Number(row.amount) || 0
  if (amount <= 0) return
  refundIndex.value = index
  refundMax.value = amount
  // 默认整单退回这笔；改小就是部分退款（价保退差价通常属于这种）
  refundForm.value = { amount, date: todayStr(), note: '价保退款' }
  refundVisible.value = true
}

function confirmRefund() {
  const row = form.value.records[refundIndex.value]
  if (!row) { refundVisible.value = false; return }
  const amount = Math.round((Number(refundForm.value.amount) || 0) * 100) / 100
  if (amount <= 0) {
    ElMessage.warning('退款金额要大于 0')
    return
  }
  if (amount > refundMax.value + 1e-9) {
    ElMessage.warning('退款金额不能超过这笔的实付金额')
    return
  }
  form.value.records.push({
    qty: 0,
    amount: -amount,
    // 退的是定金就沿用定金标记，否则「未付」里那份定金抵扣不会跟着退回
    is_deposit: !!row.is_deposit,
    date: refundForm.value.date || todayStr(),
    note: refundForm.value.note || '退款',
    vendor: row.vendor || '',
    order_no: row.order_no || '',
    room_ids: [...(row.room_ids || [])],
    created_at: '',
    updated_at: '',
  })
  refundVisible.value = false
  ElMessage.success('退款已记下，点「保存」后生效')
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
      link: form.value.link || '',
      allocations: form.value.allocations.map((a) => ({
        room_id: a.room_id,
        qty: Number(a.qty) || 0,
        price_override: a.price_override === null || a.price_override === '' ? null : Number(a.price_override),
        note: a.note || '',
      })),
      records: form.value.records.map((r) => ({
        // 定金不看数量：勾了定金就按 0 存，免得导出的表里挂着一个不参与计算的数量
        qty: r.is_deposit ? 0 : (Number(r.qty) || 0),
        amount: Number(r.amount) || 0,
        is_deposit: !!r.is_deposit,
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
        <el-col :xs="12" :sm="8">
          <el-form-item label="分类">
            <el-select v-model="form.category_id" placeholder="选择" clearable style="width: 100%">
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="8">
          <el-form-item label="单位">
            <el-input v-model="form.unit" placeholder="个/米/套" />
          </el-form-item>
        </el-col>
        <el-col :xs="12" :sm="8">
          <el-form-item label="数量">
            <el-input-number :model-value="allocationsLocked ? allocQtyTotal : form.qty_total"
                             :min="0" :disabled="allocationsLocked"
                             controls-position="right" :value-on-clear="0" style="width: 100%"
                             @update:model-value="form.qty_total = $event" />
            <div v-if="allocationsLocked" class="field-hint">总量由分配合计决定</div>
          </el-form-item>
        </el-col>
      </el-row>
      <!-- 备注与商品链接并排：两样都是选填的副信息，各占一半 -->
      <el-row :gutter="12">
        <el-col :xs="24" :sm="12">
          <el-form-item label="备注">
            <el-input v-model="form.note" placeholder="选填" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="商品链接">
            <el-input v-model="form.link" placeholder="https://…（选填，之后点开就能看商品页）" />
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
        <template v-if="refundSum">
          <div class="ps-divider">|</div>
          <div class="ps-item">
            <span class="ps-label">已退</span>
            <span class="ps-value refund">￥{{ money(refundSum) }}</span>
          </div>
        </template>
        <div class="ps-divider">|</div>
        <div class="ps-item">
          <span class="ps-label">未付</span>
          <span class="ps-value unpaid">￥{{ money(unpaidMoney) }}</span>
        </div>
        <div class="ps-status" :class="{ done: recordsQtySum >= maxBoughtQty }">{{ statusText }}</div>
      </div>

      <el-divider content-position="left">采购记录（每笔付款一行，可多笔）</el-divider>
      <div class="table-wrap">
        <!-- 条目多了只让表体自己滚（表头钉住）：不然弹窗被越撑越高，
             底部「保存 / 取消」直接被顶出屏幕 -->
        <el-table :data="form.records" size="small" :max-height="recordMaxH"
                  style="min-width: 1000px">
          <el-table-column label="实付数量" width="112">
            <template #default="{ row }">
              <!-- 退款没有到货数量，用标签替掉输入框，一眼和普通付款区分开 -->
              <el-tag v-if="isRefund(row)" type="danger" size="small" effect="plain">退款</el-tag>
              <!-- 定金不看数量：灰掉它，省得填了数字却发现进度不动 -->
              <el-input-number v-else v-model="row.qty" :min="0" size="small"
                               :disabled="!!row.is_deposit"
                               controls-position="right" style="width: 100%" />
            </template>
          </el-table-column>
          <el-table-column label="实付金额" width="124">
            <template #default="{ row }">
              <!-- 单价收进悬停：行内再挂一行小字会把格子撑高，两个数字框就不在同一
                   水平线上了。单价本来也是顺带看一眼的数，不值得占行高。 -->
              <span :title="unitTip(row)" class="cell-tip"
                    :class="{ 'refund-amount': isRefund(row) }">
                <el-input-number v-model="row.amount" :min="isRefund(row) ? -1000000000 : 0"
                                 :precision="2" size="small"
                                 controls-position="right" style="width: 100%" />
              </span>
            </template>
          </el-table-column>
          <!-- 定金：钱先付、货还没到。只记钱、不推进「已买数量」，并从「未付」里扣掉 -->
          <el-table-column width="66" align="center">
            <template #header>
              <el-tooltip placement="top" content="勾上＝定金：钱先付、货还没到。只算已付金额，不推进「已买数量」（状态不会变成「已买完」，而是「已付定」），并从「未付」里扣掉；等货到了再记一笔尾款就结清">
                <span class="th-tip">定金</span>
              </el-tooltip>
            </template>
            <template #default="{ row }">
              <el-checkbox v-model="row.is_deposit" />
            </template>
          </el-table-column>
          <!-- 记录于收进悬停：它是只读的辅助信息，不值得占一列 -->
          <el-table-column label="日期" width="132">
            <template #default="{ row }">
              <span :title="stampTitle(row)" class="cell-tip">
                <el-date-picker v-model="row.date" type="date" value-format="YYYY-MM-DD"
                                placeholder="选择日期" size="small" style="width:100%" />
              </span>
            </template>
          </el-table-column>
          <!-- 涉及分组（可多选）：勾了谁，"这间买齐了没"就只往谁身上算 -->
          <el-table-column label="涉及分组" width="150">
            <template #default="{ row }">
              <el-select v-model="row.room_ids" multiple collapse-tags size="small"
                         placeholder="不指定" style="width: 100%">
                <el-option v-for="r in allocRooms" :key="r.id"
                           :label="r.name" :value="r.id" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="商家" width="84">
            <template #default="{ row }">
              <el-input v-model="row.vendor" size="small" placeholder="如 京东" />
            </template>
          </el-table-column>
          <el-table-column label="订单号" width="104">
            <template #default="{ row }">
              <el-input v-model="row.order_no" size="small" placeholder="选填" />
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="160">
            <template #default="{ row }">
              <el-input v-model="row.note" size="small" placeholder="如 尾款/批次" />
            </template>
          </el-table-column>
          <el-table-column width="112">
            <template #default="{ row, $index }">
              <!-- 两个操作固定横排：窄屏/大字号下也不能上下错开 -->
              <div class="record-actions">
                <!-- 退款只有对已经付出去的钱才有意义；负数行不再显示，点错了也没处退 -->
                <el-button v-if="Number(row.amount) > 0" link type="warning" size="small"
                           @click="openRefund(row, $index)">退款</el-button>
                <el-button link type="danger" size="small" @click="removeRecord($index)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="alloc-footer">
        <el-button size="small" @click="addRecord">+ 添加采购记录</el-button>
        <span v-if="form.records.length" class="alloc-hint">
          合计 {{ recordsQtySum }}{{ form.unit || '' }} · 已付 ￥{{ money(recordsAmountSum) }}
          <template v-if="depositSum"> （含定金 ￥{{ money(depositSum) }}）</template>
          <template v-if="refundSum"> · <span class="refund-text">已退 ￥{{ money(refundSum) }}</span></template>
          <template v-if="recordsPrice"> · 均价 ￥{{ money(recordsPrice) }}</template>
        </span>
        <span v-else class="alloc-hint">没填记录则视为未买</span>
      </div>

      <el-divider content-position="left">按分组分配（选填，总量以分配合计为准）</el-divider>
      <div class="table-wrap">
        <!-- 同理：分组多的物料，这张表也在自己区域内滚，不把弹窗撑高 -->
        <el-table :data="form.allocations" size="small" :max-height="allocMaxH"
                  style="min-width: 560px">
          <el-table-column label="分组" width="132">
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

  <!-- 退款弹窗：默认整单退回这笔，改小就是部分退款（价保退差价通常选这个）。
       生成一条负数采购记录，和其它记录一起保存，统计、导出、同步沿用同一套口径。 -->
  <el-dialog v-model="refundVisible" width="440px" append-to-body
             :close-on-click-modal="false" class="refund-dialog" title="记一笔退款">
    <div v-if="form.records[refundIndex]" class="refund-source">
      原记录：{{ form.records[refundIndex].date || '未填日期' }}
      · ￥{{ money(form.records[refundIndex].amount) }}
      <template v-if="form.records[refundIndex].vendor"> · {{ form.records[refundIndex].vendor }}</template>
      <template v-if="form.records[refundIndex].order_no"> · 订单 {{ form.records[refundIndex].order_no }}</template>
    </div>
    <el-form label-position="top">
      <el-form-item label="退款金额">
        <el-input-number v-model="refundForm.amount" :min="0.01" :max="refundMax"
                         :precision="2" controls-position="right" style="width: 100%" />
        <div class="field-hint">默认退回这笔的全部金额（整单退款）；改小就是部分退款，价保退差价选这个。</div>
      </el-form-item>
      <el-form-item label="退款原因">
        <div class="refund-presets">
          <el-button v-for="r in ['价保退款', '退货退款', '其他']" :key="r" size="small"
                     :type="refundForm.note === r ? 'primary' : ''"
                     @click="refundForm.note = r">{{ r }}</el-button>
        </div>
        <el-input v-model="refundForm.note" placeholder="会写进这笔记录的备注"
                  style="margin-top: 8px" />
      </el-form-item>
      <el-form-item label="退款日期">
        <el-date-picker v-model="refundForm.date" type="date" value-format="YYYY-MM-DD"
                        placeholder="选择日期" style="width: 100%" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="refundVisible = false">取消</el-button>
      <el-button type="primary" @click="confirmRefund">记下退款</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* 这两张表列多，窄屏上可能塞不下：留个横向滚动兜底，
   不然右边几列会被弹窗直接裁掉、连滚都滚不到 */
.table-wrap { overflow-x: auto; }
/* 内容再多也不让弹窗高过屏幕：正文区自己滚，底部「保存 / 取消」永远在视野里 */
.item-dialog :deep(.el-dialog__body) { max-height: 76vh; overflow-y: auto; }
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
/* 悬停才显示的辅助信息（比如「记录于 …」「单价 ￥x」）：给个提示，不然没人知道能停上去 */
.cell-tip { display: block; cursor: help; }
/* 表头带说明的列：虚线下划线提示"这里能悬停看解释"，不然没人知道能停上去 */
.th-tip { border-bottom: 1px dashed var(--ios-label-3); cursor: help; }
/* 记录行的操作按钮：横排、靠右、不换行 */
.record-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  white-space: nowrap;
}
.record-actions :deep(.el-button + .el-button) { margin-left: 0; }
/* 退款记录用红色区分：金额是负的，和普通付款不是一个方向的钱 */
.refund-amount :deep(.el-input__inner) { color: var(--ios-red); font-weight: 600; }
.refund-text { color: var(--ios-red); }
.ps-value.refund { color: var(--ios-red); }
.refund-source {
  font-size: 12px;
  color: var(--ios-label-2);
  background: rgba(255, 55, 95, 0.05);
  border: 1px solid rgba(255, 55, 95, 0.12);
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 12px;
}
.refund-presets { display: flex; gap: 8px; flex-wrap: wrap; }
:global(.item-dialog) { max-width: 94vw; }
:global(.refund-dialog) { max-width: 94vw; }
</style>
