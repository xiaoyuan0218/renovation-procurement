<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, money, shortStamp, toLocalStamp, qty as fmtQty } from '../api'

// 跨物料的采购流水：物料详情里只能一笔笔翻，大促对账、找某笔订单、按商家汇总
// 时需要一张统一台账。记录随 /api/items 一起回来（每条物料自带 records），
// 这里拼平即可，不需要单独的流水接口 —— 几百条记录的量级前端聚合毫无压力
const items = ref([])
const rooms = ref([])
const loading = ref(false)
const loadFailed = ref(false)

const q = ref('')
const vendor = ref('')
const orderNo = ref('')
const dateRange = ref(null)

// 日期读出口已归一化成 YYYY-MM-DD（认不出的原样保留、空的为 ''），
// 等宽格式的字典序就是时间序：'' 最小，没填日期的稳定排在最后
const rows = computed(() => {
  const roomName = new Map(rooms.value.map((r) => [r.id, r.name]))
  const list = []
  for (const it of items.value) {
    for (const r of it.records || []) {
      list.push({ ...r, item_name: it.name, unit: it.unit || '' })
    }
  }
  list.sort((a, b) => {
    if ((a.date || '') !== (b.date || '')) return (a.date || '') < (b.date || '') ? 1 : -1
    // 同一天内后记的排前面；created_at 可能没有（升级前的老数据），当 '' 处理
    return String(b.created_at || '').localeCompare(String(a.created_at || ''))
  })
  return list.map((r) => ({
    ...r,
    roomNames: (r.room_ids || []).map((id) => roomName.get(id)).filter(Boolean),
  }))
})

// 商家下拉的选项从流水里实际出现过的商家取（去重、排序、忽略空值）——
// 跟清单页品牌下拉同一个思路，写死一份商家表没有意义
const vendors = computed(() => {
  const seen = new Set(rows.value.map((r) => (r.vendor || '').trim()).filter(Boolean))
  return [...seen].sort((a, b) => a.localeCompare(b, 'zh'))
})

// 排序必须作用于**全量**筛选结果再切页：el-table 自带的排序只会在当前页内排，
// 翻到第二页顺序就乱了（清单页同款坑）。null 表示没点过列头，走默认的日期倒序
const sortState = ref(null)
const page = ref(1)
const PAGE_SIZE = 50
watch([q, vendor, orderNo, dateRange], () => { page.value = 1 })

const filtered = computed(() => {
  const arr = rows.value.filter((r) => {
    if (vendor.value && (r.vendor || '').trim() !== vendor.value) return false
    if (orderNo.value && !(r.order_no || '').includes(orderNo.value.trim())) return false
    if (dateRange.value?.length === 2) {
      // 没填日期的记录对不上范围：先不算进来，合计口径保持"看得见的都是范围内的"
      if (!r.date || r.date < dateRange.value[0] || r.date > dateRange.value[1]) return false
    }
    if (q.value) {
      const hay = `${r.item_name}\n${r.note || ''}\n${r.vendor || ''}\n${r.order_no || ''}`
      if (!hay.includes(q.value)) return false
    }
    return true
  })
  const { prop, order } = sortState.value || {}
  if (prop && order) {
    const dir = order === 'ascending' ? 1 : -1
    // 金额按数比；日期、时间戳是等宽格式的字符串，字典序就是时间序（zh 规则下同）
    const valueOf = (r) => (prop === 'amount' ? (Number(r.amount) || 0) : (r[prop] || ''))
    arr.sort((a, b) => {
      const x = valueOf(a)
      const y = valueOf(b)
      if (typeof x === 'number' && typeof y === 'number') return (x - y) * dir
      return String(x).localeCompare(String(y), 'zh') * dir
    })
  }
  return arr
})

// 合计是净额：退款记录的金额是负数，求和时自动抵回（与物料详情、看板同一口径）。
// 不给数量合计 —— 流水跨物料，单位各不相同（个/米/套），加起来没有意义
const totals = computed(() => {
  let amount = 0
  let refund = 0
  for (const r of filtered.value) {
    const a = Number(r.amount) || 0
    amount += a
    if (a < 0) refund -= a
  }
  return {
    count: filtered.value.length,
    amount: Math.round(amount * 100) / 100,
    refund: Math.round(refund * 100) / 100,
  }
})

const hasFilter = computed(() =>
  !!(q.value || vendor.value || orderNo.value || dateRange.value?.length))

function onSortChange({ prop, order }) {
  sortState.value = (!prop || !order) ? null : { prop, order }
  page.value = 1
}

const paged = computed(() =>
  filtered.value.slice((page.value - 1) * PAGE_SIZE, page.value * PAGE_SIZE))

const tableBoxRef = ref(null)
const tableHeight = ref(420)
let ro = null
function calcTableHeight() {
  const el = tableBoxRef.value
  if (!el) return
  const h = Math.max(160, Math.floor(el.clientHeight))
  if (Math.abs(h - tableHeight.value) > 1) tableHeight.value = h
}

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
  loadFailed.value = false
  try {
    ;[items.value, rooms.value] = await Promise.all([
      api.get('/api/items'), api.get('/api/rooms'),
    ])
  } catch (e) {
    // 401 已由全局跳登录处理，这里只提示其余失败
    if (e.status !== 401) {
      loadFailed.value = true
      ElMessage.error(`加载失败：${e.message}`)
    }
  } finally { loading.value = false }
}

const emptyText = computed(() => {
  if (loadFailed.value) {
    return '加载失败，可能是服务没启动或网络断了；切到别的页再切回来即可重试'
  }
  return hasFilter.value || rows.value.length
    ? '没有符合条件的记录'
    : '还没有采购记录：在物料详情里「记一笔」后，这里就是全部货款的流水台账'
})

const stampTitle = (row) => {
  if (!row.created_at) return '升级前的老数据，没有时间戳'
  const updated = row.updated_at && row.updated_at !== row.created_at
    ? `\n最后修改 ${toLocalStamp(row.updated_at)}` : ''
  return `记录于 ${toLocalStamp(row.created_at)}${updated}`
}
</script>

<template>
  <div v-loading="loading" class="page-wrap">
    <div class="toolbar">
      <el-input v-model="q" placeholder="搜物料名/备注/商家/订单号" clearable class="search" />
      <el-select v-model="vendor" placeholder="全部商家" clearable filterable
                 class="vendor-select">
        <el-option v-for="v in vendors" :key="v" :label="v" :value="v" />
      </el-select>
      <el-input v-model="orderNo" placeholder="按订单号找" clearable class="order-input" />
      <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
                      range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"
                      class="range-picker" />
      <span class="totals">
        共 <b>{{ totals.count }}</b> 笔
        · 合计 <b class="t-green">￥{{ money(totals.amount) }}</b>
        <template v-if="totals.refund"> · 其中退款 <b class="t-red">￥{{ money(totals.refund) }}</b>（已抵扣）</template>
      </span>
    </div>

    <div class="glass panel fill-panel">
      <div v-show="loading && !filtered.length" class="table-box skeleton-pad">
        <el-skeleton :rows="8" animated />
      </div>
      <el-empty v-show="!loading && !filtered.length" :description="emptyText" :image-size="80" />
      <div v-show="filtered.length" ref="tableBoxRef" class="table-box">
        <el-table :data="paged" size="default" :height="tableHeight" border
                  @sort-change="onSortChange">
          <el-table-column prop="date" label="日期" width="108" sortable="custom">
            <template #default="{ row }">
              <span v-if="row.date">{{ row.date }}</span>
              <span v-else class="dim">未填日期</span>
            </template>
          </el-table-column>
          <el-table-column prop="item_name" label="物料" min-width="150" sortable="custom"
                           show-overflow-tooltip>
            <template #default="{ row }">{{ row.item_name }}</template>
          </el-table-column>
          <el-table-column label="标记" width="74" align="center">
            <template #default="{ row }">
              <!-- 一笔既退定金又是负数时优先说"退款"：金额方向才是这笔钱的性质 -->
              <el-tag v-if="(Number(row.amount) || 0) < 0" type="danger" size="small"
                      effect="plain">退款</el-tag>
              <el-tag v-else-if="row.is_deposit" type="warning" size="small"
                      effect="plain">定金</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="数量" width="86" align="right">
            <template #default="{ row }">
              <!-- 定金与退款不推进到货数量（存的是 0），显示占位符更直观 -->
              <span v-if="row.qty">{{ fmtQty(row.qty) }}{{ row.unit ? ` ${row.unit}` : '' }}</span>
              <span v-else class="dim">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="110" align="right"
                           sortable="custom">
            <template #default="{ row }">
              <span :class="{ 'amt-refund': (Number(row.amount) || 0) < 0 }">
                ￥{{ money(row.amount) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="商家" width="90">
            <template #default="{ row }">
              <span v-if="row.vendor">{{ row.vendor }}</span>
              <span v-else class="dim">-</span>
            </template>
          </el-table-column>
          <el-table-column label="订单号" width="130" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.order_no">{{ row.order_no }}</span>
              <span v-else class="dim">-</span>
            </template>
          </el-table-column>
          <el-table-column label="涉及分组" min-width="110">
            <template #default="{ row }">
              <span v-if="row.roomNames.length">{{ row.roomNames.join('、') }}</span>
              <!-- 没勾分组的那部分按分配顺序推算（与"哪间买齐"同一套规则） -->
              <span v-else class="dim" title="没指定分组，按分配顺序推算">按分配推算</span>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="备注" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.note">{{ row.note }}</span>
              <span v-else class="dim">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="created_at" label="记录时间" width="102" sortable="custom">
            <template #default="{ row }">
              <span :title="stampTitle(row)" class="dim">
                {{ shortStamp(row.created_at) || '-' }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-if="filtered.length" class="pager">
        <span class="pager-total">共 {{ filtered.length }} 笔</span>
        <el-pagination v-model:current-page="page" :page-size="PAGE_SIZE"
                       :total="filtered.length"
                       layout="prev, pager, next, jumper" background />
      </div>
    </div>
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
.search { width: 220px; }
.vendor-select { width: 130px; }
.order-input { width: 140px; }
.totals { color: var(--ios-label-2); font-size: 13px; margin-right: auto; }
.t-green { color: var(--ios-green); font-weight: 700; font-style: normal; }
.t-red { color: var(--ios-red); font-weight: 700; font-style: normal; }
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
.table-box :deep(.el-table--border) {
  --el-table-border-color: rgba(120, 130, 150, 0.12);
}
.table-box :deep(.el-table--border)::before,
.table-box :deep(.el-table--border)::after,
.table-box :deep(.el-table--border .el-table__inner-wrapper)::after {
  display: none;
}
.pager {
  flex: none;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 2px 0;
}
.pager-total { margin-right: auto; font-size: 13px; color: var(--ios-label-2); }
.skeleton-pad { padding: 10px 8px; }
.dim { color: var(--ios-label-3); }
/* 退款金额红色：和普通付款不是一个方向的钱 */
.amt-refund { color: var(--ios-red); font-weight: 600; }
</style>
