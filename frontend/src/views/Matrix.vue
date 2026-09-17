<script setup>
import { computed, h, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, money, qty as fmtQty } from '../api'

const data = ref({ rooms: [], items: [] })
const q = ref('')
const onlyUnbought = ref(false)
const loading = ref(false)

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

const cellDialog = ref(false)
const cellForm = ref({ item_id: null, room_id: null, itemName: '', roomName: '', qty: 0, price_override: null, note: '' })
const savingCell = ref(false)


async function load() {
  loading.value = true
  try {
    data.value = await api.get('/api/matrix')
  } finally {
    loading.value = false
  }
}

const filteredItems = computed(() => data.value.items.filter((it) => {
  if (onlyUnbought.value && it.bought) return false
  if (q.value && !it.name.includes(q.value)) return false
  return true
}))

function cellOf(item, room) {
  return item.cells[room.id] || null
}

function openCell(item, room) {
  const c = item.cells[room.id]
  cellForm.value = {
    item_id: item.id, room_id: room.id,
    itemName: item.name, roomName: room.name,
    qty: c?.qty ?? 0,
    price_override: c?.price_override ?? null,
    note: c?.note ?? '',
  }
  cellDialog.value = true
}

async function saveCell(clear = false) {
  savingCell.value = true
  try {
    const f = cellForm.value
    await api.put('/api/matrix/cell', {
      item_id: f.item_id, room_id: f.room_id,
      qty: clear ? 0 : Number(f.qty) || 0,
      price_override: clear || f.price_override === null || f.price_override === ''
        ? null : Number(f.price_override),
      note: clear ? '' : f.note || '',
    })
    cellDialog.value = false
    await load()
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    savingCell.value = false
  }
}

function summaryMethod({ columns }) {
  const gQty = filteredItems.value.reduce((s, it) => s + (it.total_qty || 0), 0)
  const gAmt = filteredItems.value.reduce((s, it) => s + (it.list_total || 0), 0)
  return columns.map((col) => {
    if (col.label === '合计') {
      return h('div', { class: 'grand' }, [
        h('div', { class: 'grand-qty' }, fmtQty(gQty)),
        h('div', { class: 'grand-amt' }, `￥${money(gAmt)}`),
      ])
    }
    if (!col.property || !col.property.startsWith('r')) return ''
    const roomId = Number(col.property.slice(1))
    const total = filteredItems.value.reduce((s, it) => s + (it.cells[roomId]?.qty || 0), 0)
    return total ? fmtQty(total) : ''
  })
}
</script>

<template>
  <div v-loading="loading" class="page-wrap">
    <div class="toolbar">
      <el-input v-model="q" placeholder="搜索物料" clearable class="search" />
      <el-checkbox v-model="onlyUnbought">只看未买</el-checkbox>
      <span class="legend">
        <i class="lg lg-blue" /> 未买齐
        <i class="lg lg-green" /> 已买齐
        <i class="lg lg-dot" /> 有备注/单独价
      </span>
      <span class="hint">点单元格填写该分组的数量</span>
    </div>

    <div class="glass panel matrix-panel">
      <div v-show="loading && !filteredItems.length" class="table-box skeleton-pad">
        <el-skeleton :rows="8" animated />
      </div>
      <div v-show="!loading || filteredItems.length" ref="tableBoxRef" class="table-box table-scroll">
        <el-table :data="filteredItems" size="small" show-summary :height="tableHeight"
                  :summary-method="summaryMethod" class="matrix-table">
        <el-table-column prop="name" label="物料" width="260">
          <template #default="{ row }">
            <div class="item-name" :class="{ bought: row.status === 'done' }">{{ row.name }}</div>
            <div class="item-sub">
              {{ row.category_name || '未分类' }}{{ row.model ? ` · ${row.model}` : '' }}{{ row.price ? ` · ￥${money(row.price)}` : '' }}
              <span v-if="row.status === 'partial'" class="partial-tag">
                实付 {{ fmtQty(row.paid_qty) }}/{{ fmtQty(row.total_qty) }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-for="room in data.rooms" :key="room.id"
                         :prop="`r${room.id}`" :label="room.name" min-width="62" align="center">
          <template #header>
            <div class="room-header">{{ room.name }}</div>
          </template>
          <template #default="{ row }">
            <button class="cell" :class="{
              filled: cellOf(row, room) && (cellOf(row, room).paid_qty || 0) < (cellOf(row, room).qty || 0),
              paid: cellOf(row, room) && (cellOf(row, room).paid_qty || 0) >= (cellOf(row, room).qty || 0),
            }" @click="openCell(row, room)">
              {{ cellOf(row, room) ? fmtQty(cellOf(row, room).qty) : '' }}
              <i v-if="cellOf(row, room) && (cellOf(row, room).note || cellOf(row, room).price_override != null)"
                 class="dot" />
            </button>
          </template>
        </el-table-column>
        <el-table-column label="合计" width="110" align="center">
          <template #default="{ row }">
            <div class="total-cell">
              <span class="total-qty" :class="{ bought: row.bought }">{{ fmtQty(row.total_qty) }}</span>
              <span class="total-amount">￥{{ money(row.list_total) }}</span>
            </div>
          </template>
        </el-table-column>
      </el-table>
      </div>
    </div>

    <el-dialog v-model="cellDialog" width="360px">
      <template #header>
        <div class="dlg-title">
          <span class="dlg-chip dlg-teal">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s-7-5.5-7-11a7 7 0 0 1 14 0c0 5.5-7 11-7 11z"/><circle cx="12" cy="10" r="2.5"/></svg>
          </span>
          {{ cellForm.itemName }} · {{ cellForm.roomName }}
        </div>
      </template>
      <el-form label-width="80px">
        <el-form-item label="数量">
          <el-input-number v-model="cellForm.qty" :min="0" style="width: 100%" />
        </el-form-item>
        <el-form-item label="覆盖单价">
          <el-input-number v-model="cellForm.price_override" :min="0" :precision="2"
                           placeholder="留空用物料单价" :value-on-clear="null" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="cellForm.note" placeholder="如：双口面板" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="danger" plain :disabled="savingCell" @click="saveCell(true)">清空</el-button>
        <el-button @click="cellDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingCell" @click="saveCell(false)">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-wrap { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  flex: none;
}
.search { width: 200px; }
.hint { color: var(--ios-label-2); font-size: 12px; }
.legend {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ios-label-2);
}
.lg { width: 10px; height: 10px; border-radius: 3px; display: inline-block; }
.lg-blue { background: rgba(0, 122, 255, 0.16); }
.lg-green { background: rgba(52, 199, 89, 0.22); }
.lg-dot { border-radius: 50%; background: var(--ios-orange); width: 7px; height: 7px; }
/* 表格视口：撑满面板剩余高度，横向/纵向滚动都交给表格内部 */
.glass.panel.matrix-panel {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  max-width: 100%;
  padding: 8px 6px;
  overflow: hidden;
}
.table-box { flex: 1 1 auto; min-height: 0; overflow: hidden; }
.skeleton-pad { padding: 10px 8px; }
.matrix-table { width: 100%; }
.room-header {
  white-space: normal;
  line-height: 1.2;
  font-size: 11px;
  padding: 2px 0;
}
.item-name { font-size: 13px; font-weight: 500; }
.item-name.bought { color: var(--ios-label-3); text-decoration: line-through; }
.item-sub { font-size: 11px; color: var(--ios-label-2); }
.partial-tag { color: var(--ios-orange); font-weight: 600; }
.total-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  line-height: 1.25;
}
.total-qty {
  font-weight: 600;
  font-size: 13px;
  color: var(--ios-label);
  font-variant-numeric: tabular-nums;
}
.total-qty.bought { color: var(--ios-label-3); text-decoration: line-through; }
.total-amount {
  font-size: 11px;
  color: var(--ios-label-2);
  font-variant-numeric: tabular-nums;
}
.grand {
  text-align: center;
  line-height: 1.3;
  font-variant-numeric: tabular-nums;
}
.grand-qty { font-weight: 700; font-size: 13px; color: var(--ios-blue); }
.grand-amt { font-size: 11px; color: var(--ios-label-2); }
.cell {
  position: relative;
  border: none;
  background: transparent;
  cursor: pointer;
  width: 100%;
  height: 24px;
  font-size: 13px;
  color: var(--ios-label);
  font-variant-numeric: tabular-nums;
  font-family: inherit;
}
.cell:hover { background: rgba(0, 122, 255, 0.08); border-radius: 6px; }
.cell.filled {
  background: rgba(0, 122, 255, 0.14);
  color: var(--ios-blue);
  font-weight: 600;
  border-radius: 6px;
}
.cell.paid {
  background: rgba(52, 199, 89, 0.16);
  color: #248a3d;
  font-weight: 600;
  border-radius: 6px;
}
.dot {
  position: absolute;
  top: 2px;
  right: 4px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ios-orange);
}
</style>
