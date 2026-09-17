<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts'
import { api, money } from '../api'
import ExpensesDialog from '../components/ExpensesDialog.vue'

const emit = defineEmits(['go-items'])
const summary = ref(null)
const rootEl = ref(null)

const donutPaidEl = ref(null)
const pieEl = ref(null)
const donutStatusEl = ref(null)
const barCatEl = ref(null)
const barRoomEl = ref(null)
const barUnbEl = ref(null)
const barMonthEl = ref(null)

let charts = []
let ro = null

const fmtMoney = (v) => `￥${money(v)}`

/** 'YYYY-MM' 往前/往后挪几个月。 */
function shiftMonth(key, delta) {
  const [y, m] = key.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/**
 * 按月已付：以当前月（或最后一条有数据的月份）为终点往前取 6 个月，
 * 中间没付款的月份补 0 —— 柱子的疏密本身就是"哪几个月在花钱"的信息。
 */
const byMonthSeries = computed(() => {
  const raw = summary.value?.by_month || []
  const paid = new Map(raw.map((r) => [r.month, r.paid]))
  const now = new Date()
  const nowKey = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  const lastKey = raw.length ? raw[raw.length - 1].month : nowKey
  const endKey = lastKey > nowKey ? lastKey : nowKey
  return Array.from({ length: 6 }, (_, i) => {
    const key = shiftMonth(endKey, i - 5)
    return { key, label: `${Number(key.slice(5))}月`, paid: paid.get(key) || 0 }
  })
})

const hasMonthlyPaid = computed(() => (summary.value?.by_month?.length || 0) > 0)

const expensesVisible = ref(false)

/**
 * 额外费用改完只刷数字，不重画图表 —— 那些图都是货款口径，费用不参与，
 * 重画反而要拆掉再建一批 echarts 实例。
 */
async function refreshSummary() {
  summary.value = await api.get('/api/summary')
}

// 优惠按「省下的钱」理解，负数是实付价高于原价/日常价，改用警示色而不是隐藏
const discountClass = (v) => ((v ?? 0) < 0 ? 'saving warn' : 'saving')

function grad(top, bottom) {
  return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: top },
    { offset: 1, color: bottom },
  ])
}

onMounted(async () => {
  summary.value = await api.get('/api/summary')
  await nextTick()
  renderCharts()
  window.addEventListener('resize', onResize)
  if (rootEl.value && 'ResizeObserver' in window) {
    ro = new ResizeObserver(onResize)
    ro.observe(rootEl.value)
    // 底部卡片在 root 高度不变时也会变化，图表容器要单独观察
    if (barUnbEl.value) ro.observe(barUnbEl.value)
  }
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  ro?.disconnect()
  charts.forEach((c) => c.dispose())
  charts = []
})

function onResize() {
  calcUnboughtHeight()
  charts.forEach((c) => c.resize())
}

const unboughtBoxEl = ref(null)
const unboughtTableH = ref(280)
function calcUnboughtHeight() {
  const el = unboughtBoxEl.value
  if (!el) return
  const h = Math.max(120, Math.floor(el.clientHeight))
  if (Math.abs(h - unboughtTableH.value) > 1) unboughtTableH.value = h
}

function makeChart(el, option) {
  if (!el) return
  const c = echarts.init(el)
  c.setOption(option)
  charts.push(c)
}

function renderCharts() {
  const t = summary.value.totals
  const byCat = summary.value.by_category.filter((c) => c.discount_total > 0)
  const byRoom = [...summary.value.by_room].sort((a, b) => a.list_total - b.list_total)
  const unbought = [...summary.value.unbought].slice(0, 6).reverse()
  const paidPct = t.list_total
    ? Math.round((t.paid_total / t.list_total) * 100)
    : 0
  const hairline = 'rgba(60,60,67,0.10)'
  const axisLabel = { color: 'rgba(60,60,67,0.6)', fontSize: 11 }

  // 1. 实付构成环形（未付 = 原价 − 已付）
  makeChart(donutPaidEl.value, {
    tooltip: { trigger: 'item', valueFormatter: fmtMoney },
    legend: { bottom: 0, left: 'center', itemWidth: 14, itemHeight: 10, textStyle: { fontSize: 12, color: '#3c3c43' } },
    color: ['#34c759', '#ff9f0a'],
    title: {
      text: `${paidPct}%`, subtext: '已付占比', left: 'center', top: '36%',
      textStyle: { fontSize: 22, fontWeight: 700, color: '#1c1c1e' },
      subtextStyle: { fontSize: 11, color: 'rgba(60,60,67,0.6)' },
    },
    series: [{
      type: 'pie', radius: ['54%', '72%'], center: ['50%', '44%'],
      label: { show: false },
      itemStyle: { borderColor: 'rgba(255,255,255,0.9)', borderWidth: 2 },
      data: [
        { name: '已付', value: t.paid_total },
        { name: '未付', value: Math.max(0, t.unpaid_total) },
      ],
    }],
  })

  // 2. 分类占比环形
  makeChart(pieEl.value, {
    tooltip: { trigger: 'item', valueFormatter: fmtMoney },
    legend: { bottom: 0, left: 'center', itemWidth: 14, itemHeight: 10, textStyle: { fontSize: 12, color: '#3c3c43' } },
    color: ['#0a84ff', '#34c759', '#ff9f0a', '#ff375f', '#8e8e93'],
    series: [{
      type: 'pie', radius: ['54%', '72%'], center: ['50%', '44%'],
      label: { formatter: '{b}\n{d}%', fontSize: 11, color: '#3c3c43' },
      itemStyle: { borderColor: 'rgba(255,255,255,0.9)', borderWidth: 2 },
      data: byCat.map((c) => ({ name: c.name, value: c.discount_total })),
    }],
  })

  // 3. 采购进度环形（按项数三态：已买完/部分已买/未买）
  const sc = t.status_count || { done: 0, partial: 0, unbought: 0 }
  makeChart(donutStatusEl.value, {
    tooltip: { trigger: 'item', valueFormatter: (v) => `${v} 项` },
    legend: { bottom: 0, left: 'center', itemWidth: 14, itemHeight: 10, textStyle: { fontSize: 12, color: '#3c3c43' } },
    color: ['#0a84ff', '#ff9f0a', '#8e8e93'],
    title: {
      text: `${sc.done}/${t.item_count}`, subtext: '已买完（项）', left: 'center', top: '36%',
      textStyle: { fontSize: 20, fontWeight: 700, color: '#1c1c1e' },
      subtextStyle: { fontSize: 11, color: 'rgba(60,60,67,0.6)' },
    },
    series: [{
      type: 'pie', radius: ['54%', '72%'], center: ['50%', '44%'],
      label: { show: false },
      itemStyle: { borderColor: 'rgba(255,255,255,0.9)', borderWidth: 2 },
      data: [
        { name: '已买完', value: sc.done },
        { name: '部分已买', value: sc.partial },
        { name: '未买', value: sc.unbought + (sc.none || 0) },
      ],
    }],
  })

  // 4. 分类对比柱状：原价/日常价/实付
  makeChart(barCatEl.value, {
    tooltip: { trigger: 'axis', valueFormatter: fmtMoney },
    legend: { top: 0, itemWidth: 14, itemHeight: 10, textStyle: { fontSize: 12, color: '#3c3c43' } },
    grid: { left: 8, right: 16, top: 34, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category', data: byCat.map((c) => c.name),
      axisTick: { show: false }, axisLine: { lineStyle: { color: hairline } },
      axisLabel: { color: '#3c3c43', fontSize: 12 },
    },
    yAxis: {
      type: 'value', splitLine: { lineStyle: { color: hairline } }, axisLabel,
    },
    series: [
      { name: '原价', type: 'bar', data: byCat.map((c) => c.list_total),
        itemStyle: { borderRadius: [4, 4, 0, 0], color: grad('#0a84ff', '#5ac8fa') }, barMaxWidth: 22 },
      { name: '日常价', type: 'bar', data: byCat.map((c) => c.discount_total),
        itemStyle: { borderRadius: [4, 4, 0, 0], color: grad('#34c759', '#7ce38b') }, barMaxWidth: 22 },
      { name: '实付', type: 'bar', data: byCat.map((c) => c.paid_total),
        itemStyle: { borderRadius: [4, 4, 0, 0], color: grad('#ff9f0a', '#ffd60a') }, barMaxWidth: 22 },
    ],
  })

  // 5. 分组金额分布横向条形
  makeChart(barRoomEl.value, {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = Array.isArray(params) ? params[0] : params
        const room = summary.value.by_room.find((r) => r.name === p.name)
        return `${p.name}<br/>￥${money(p.value)} · ${room ? room.qty : 0} 件`
      },
    },
    grid: { left: 8, right: 24, top: 8, bottom: 0, containLabel: true },
    xAxis: {
      type: 'value', splitLine: { lineStyle: { color: hairline } }, axisLabel,
    },
    yAxis: {
      type: 'category', data: byRoom.map((r) => r.name),
      axisTick: { show: false }, axisLine: { lineStyle: { color: hairline } },
      axisLabel: { color: '#3c3c43', fontSize: 12 },
    },
    series: [{
      type: 'bar', data: byRoom.map((r) => r.list_total),
      itemStyle: { color: '#5ac8fa', borderRadius: [0, 4, 4, 0] }, barMaxWidth: 14,
    }],
  })

  // 6. 按月已付：付款记录里的日期，终于能回答"这个月花了多少"
  const months = byMonthSeries.value
  makeChart(barMonthEl.value, {
    tooltip: { trigger: 'axis', valueFormatter: fmtMoney },
    grid: { left: 8, right: 12, top: 16, bottom: 0, containLabel: true },
    xAxis: {
      type: 'category', data: months.map((m) => m.label),
      axisTick: { show: false }, axisLine: { lineStyle: { color: hairline } },
      axisLabel: { color: '#3c3c43', fontSize: 12 },
    },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: hairline } }, axisLabel },
    series: [{
      type: 'bar', data: months.map((m) => m.paid), barMaxWidth: 26,
      itemStyle: { borderRadius: [4, 4, 0, 0], color: grad('#5856d6', '#af52de') },
    }],
  })

  // 7. 未采购金额 Top6 横向条形
  makeChart(barUnbEl.value, {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const p = Array.isArray(params) ? params[0] : params
        const it = summary.value.unbought.find((v) => v.name === p.name)
        return `${p.name}<br/>￥${money(p.value)} · 待买 ${it ? it.total_qty : '-'} ${it?.unit || ''}`
      },
    },
    grid: { left: 8, right: 24, top: 8, bottom: 0, containLabel: true },
    xAxis: {
      type: 'value', splitLine: { lineStyle: { color: hairline } }, axisLabel,
    },
    yAxis: {
      type: 'category', data: unbought.map((v) => v.name),
      axisTick: { show: false }, axisLine: { lineStyle: { color: hairline } },
      axisLabel: { color: '#3c3c43', fontSize: 11, width: 130, overflow: 'truncate' },
    },
    series: [{
      type: 'bar', data: unbought.map((v) => v.discount_total),
      itemStyle: { color: '#ff9f0a', borderRadius: [0, 4, 4, 0] }, barMaxWidth: 14,
    }],
  })
}

</script>

<template>
  <div v-if="!summary" class="dash-board dash-skeleton">
    <el-skeleton :rows="10" animated />
  </div>
  <div v-else ref="rootEl" class="dash-board">
    <el-row :gutter="12" class="stats-row">
      <el-col :span="6">
        <el-card shadow="never" class="stat">
          <div class="stat-head">
            <span class="stat-chip chip-blue">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round"><path d="M4 6h16M4 12h10M4 18h7"/></svg>
            </span>
            <span class="stat-label">预算原价合计</span>
          </div>
          <div class="stat-value">￥{{ money(summary.totals.list_total) }}</div>
          <div class="stat-sub">{{ summary.totals.item_count }} 项物料 · {{ summary.totals.status_count.unbought + summary.totals.status_count.partial }} 项未买齐</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat">
          <div class="stat-head">
            <span class="stat-chip chip-teal">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.6 13.4 12.4 21.6a2 2 0 0 1-2.8 0l-7-7a2 2 0 0 1 0-2.8L10.8 3.6H19a2 2 0 0 1 2 2v7.8z"/><circle cx="16" cy="8" r="1.4"/></svg>
            </span>
            <span class="stat-label">日常价合计</span>
          </div>
          <div class="stat-value">￥{{ money(summary.totals.discount_total) }}</div>
          <div class="stat-sub">已买完 {{ summary.totals.status_count.done }} 项 · 部分已买 {{ summary.totals.status_count.partial }} 项</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat">
          <div class="stat-head">
            <span class="stat-chip chip-green">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M4 12.5 9.5 18 20 6.5"/></svg>
            </span>
            <span class="stat-label">已付</span>
          </div>
          <div class="stat-value paid">￥{{ money(summary.totals.paid_total) }}</div>
          <div class="stat-foot-row">
            <div class="stat-foot">
              <span class="foot-label">实际优惠</span>
              <span :class="discountClass(summary.totals.actual_discount_total)">
                ￥{{ money(summary.totals.actual_discount_total) }}
              </span>
            </div>
            <div class="stat-foot">
              <span class="foot-label">日常价优惠</span>
              <span :class="discountClass(summary.totals.daily_discount_total)">
                ￥{{ money(summary.totals.daily_discount_total) }}
              </span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat">
          <div class="stat-head">
            <span class="stat-chip chip-orange">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></svg>
            </span>
            <span class="stat-label">未付</span>
          </div>
          <div class="stat-value remain">￥{{ money(summary.totals.unpaid_total) }}</div>
          <div class="stat-foot-row">
            <div class="stat-foot">
              <span class="foot-label">未买清单</span>
              <span>{{ summary.unbought.length }} 项</span>
            </div>
            <div class="stat-foot">
              <span class="foot-label">日常价未付</span>
              <span class="saving daily">￥{{ money(summary.totals.daily_unpaid_total) }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 额外费用：运费/安装费这类不进单价的支出，单独一条，不掺进上面的口径 -->
    <div class="glass panel expense-bar">
      <span class="eb-title">额外费用</span>
      <span class="eb-total">￥{{ money(summary.expenses_total || 0) }}</span>
      <span v-if="summary.expenses_by_kind?.length" class="eb-kinds">
        <span v-for="k in summary.expenses_by_kind" :key="k.kind" class="eb-kind">
          {{ k.kind }} ￥{{ money(k.amount) }}
        </span>
      </span>
      <span v-else class="eb-empty">运费、安装费这类不进单价的支出记在这里，不用摊进单价</span>
      <el-button size="small" class="eb-btn" @click="expensesVisible = true">
        管理（{{ summary.expenses_count || 0 }}）
      </el-button>
    </div>

    <el-row :gutter="12" class="donuts-row">
      <el-col :span="8">
        <el-card shadow="never" class="donut-card">
          <template #header>实付构成</template>
          <div ref="donutPaidEl" class="donut"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="donut-card">
          <template #header>分类占比（日常价口径）</template>
          <div ref="pieEl" class="donut"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>采购进度（按金额）</template>
          <div ref="donutStatusEl" class="donut"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="bars-row">
      <el-col :span="8">
        <el-card shadow="never" class="bar-card">
          <template #header>分类对比</template>
          <div ref="barCatEl" class="bar"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="bar-card">
          <template #header>分组金额分布</template>
          <div ref="barRoomEl" class="bar"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="bar-card">
          <template #header>
            <div class="unbought-header">
              <span>按月已付</span>
              <span v-if="summary.by_month_undated" class="undated-hint">
                ￥{{ money(summary.by_month_undated) }} 未填日期
              </span>
            </div>
          </template>
          <el-empty v-if="!hasMonthlyPaid" description="还没有带日期的付款" :image-size="58" />
          <div v-else ref="barMonthEl" class="bar"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" class="bottom-row">
      <el-col :span="10">
        <el-card shadow="never" class="bottom-card">
          <template #header>
            <div class="unbought-header">
              <span>未采购金额 Top 6</span>
              <el-button size="small" @click="emit('go-items')">去处理</el-button>
            </div>
          </template>
          <div ref="barUnbEl" class="chart-fill"></div>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never" class="bottom-card">
          <template #header>未采购清单（按金额排序，共 {{ summary.unbought.length }} 项）</template>
          <el-empty v-if="!summary.unbought.length" description="全部买完啦" :image-size="72" />
          <div v-else ref="unboughtBoxEl" class="table-box">
            <el-table :data="summary.unbought" size="small" :height="unboughtTableH">
              <el-table-column prop="name" label="物料" min-width="150" />
              <el-table-column label="状态" width="84">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.status === 'partial' ? 'primary' : 'info'" effect="light">
                    {{ row.status === 'partial' ? '部分' : '未买' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="category_name" label="分类" width="90" />
              <el-table-column label="数量" width="80" align="right">
                <template #default="{ row }">
                  {{ row.total_qty }}
                  <span v-if="row.status === 'partial'" class="partial-hint">(差{{ row.unpaid_qty }})</span>
                </template>
              </el-table-column>
              <el-table-column label="日常价" width="110" align="right">
                <template #default="{ row }">￥{{ money(row.discount_total) }}</template>
              </el-table-column>
              <el-table-column prop="note" label="备注" min-width="120" show-overflow-tooltip />
            </el-table>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <ExpensesDialog v-model="expensesVisible" @changed="refreshSummary" />
  </div>
</template>

<style scoped>
/* 根容器：flex column 撑满 el-main，禁止页面滚动 */
.dash-board {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: calc(100vh - 93px);
  overflow: hidden;
}

/* 首屏加载占位：避免切换页面时内容区空一下 */
.dash-skeleton {
  background: var(--glass-bg);
  border-radius: 18px;
  padding: 22px 20px;
}

/* 统计卡 */
.stats-row { flex: 0 0 auto; }.stat { text-align: left; }
.stat :deep(.el-card__body) { padding: 10px 14px; }
.stat-head { display: flex; align-items: center; gap: 8px; }
.stat-label { font-size: 12px; color: var(--ios-label-2); }
.stat-chip {
  width: 24px; height: 24px; border-radius: 7px;
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.chip-blue { background: linear-gradient(135deg, #0a84ff, #5ac8fa); box-shadow: 0 3px 10px rgba(10,132,255,.3); }
.chip-teal { background: linear-gradient(135deg, #30b0c7, #64d2ff); box-shadow: 0 3px 10px rgba(48,176,199,.3); }
.chip-green { background: linear-gradient(135deg, #34c759, #7ce38b); box-shadow: 0 3px 10px rgba(52,199,89,.3); }
.chip-orange { background: linear-gradient(135deg, #ff9f0a, #ffd60a); box-shadow: 0 3px 10px rgba(255,159,10,.3); }
.stat-sub { font-size: 10px; color: var(--ios-label-3); margin-top: 4px; }
/* 卡片副行：把「已付 / 未付」按两个口径拆开，与上方 stat-sub 同号数 */
.stat-foot {
  display: flex; align-items: baseline; justify-content: space-between; gap: 8px;
  font-size: 10px; margin-top: 3px; font-variant-numeric: tabular-nums;
}
/* 卡片副信息并排一行、各占一半：四张卡都是「标题 + 大数 + 一行副信息」，
   行数一样高就自然一样高（margin-top 与 stat-sub 对齐，高度也一致） */
.stat-foot-row { display: flex; gap: 14px; margin-top: 4px; }
.stat-foot-row .stat-foot { flex: 1 1 0; min-width: 0; margin-top: 0; }
.foot-label { color: var(--ios-label-3); }
.saving { color: var(--ios-green); }
.saving.warn { color: var(--ios-orange); }
.saving.daily { color: #30b0c7; }
.stat-value { font-size: 20px; font-weight: 700; letter-spacing: -0.03em; margin-top: 2px; font-variant-numeric: tabular-nums; }
.stat-value.paid { color: var(--ios-green); }
.stat-value.remain { color: var(--ios-orange); }

/* 图表行：随视口弹性伸缩，图表高度由卡片决定，窗口变矮也不会把内容挤没 */
.donuts-row { flex: 3 1 0; min-height: 0; }
.bars-row { flex: 3 1 0; min-height: 0; }
.donut, .bar { flex: 1 1 auto; min-height: 0; }
.donuts-row :deep(.el-card__body),
.bars-row :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 6px 14px 8px;
}
/* el-row 是 flex-wrap 多行容器，列的交叉轴尺寸会取内容高度 → 必须显式约束，否则卡片溢出被裁 */
.donuts-row > .el-col,
.bars-row > .el-col,
.bottom-row > .el-col { height: 100%; }
.donuts-row .el-card,
.bars-row .el-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.chart-fill { flex: 1 1 auto; min-height: 0; padding: 6px 0; }
.table-box { flex: 1 1 auto; min-height: 0; overflow: hidden; }

/* 末行：弹性填满剩余空间，卡片等高 */
.bottom-row {
  flex: 4 1 0;
  min-height: 0;
}
.bottom-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}
.bottom-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.bottom-table { flex: 1; min-height: 0; overflow-y: auto; }

.unbought-header { display: flex; justify-content: flex-end; align-items: center; gap: 8px; }
.partial-hint { color: var(--ios-orange); font-size: 11px; }
/* 「按月已付」表头右侧的小字：提醒还有多少钱没记日期（当月卡片里也要占位） */
.undated-hint { color: var(--ios-label-3); font-size: 11px; font-weight: 400; }

/* 额外费用细条：只占一行高度，给个数字和入口 */
.expense-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 14px;
  margin-bottom: 12px;
}
.eb-title { font-size: 12px; color: var(--ios-label-2); }
.eb-total { font-size: 15px; font-weight: 700; color: #5856d6; font-variant-numeric: tabular-nums; }
.eb-kinds { display: flex; gap: 12px; font-size: 12px; color: var(--ios-label-2); flex: 1; }
.eb-empty { flex: 1; font-size: 12px; color: var(--ios-label-3); }
.eb-btn { flex: none; }
</style>
