<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, money } from '../api'

const visible = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['changed'])

const KINDS = ['运费', '安装费', '搬运费', '辅料', '其他']
const rows = ref([])
const items = ref([])   // 用来把费用关联到具体物料的货（选填）

// 同名物料只靠名字分不清（真实数据里就有两条「易来灯带控制器」），
// 把型号缀在后面 —— 没填型号的保持原样，不显示多余的点号
const itemLabel = (i) => (i.model ? `${i.name} · ${i.model}` : i.name)
const loading = ref(false)
const busy = ref(false)

function blank(kind = '运费') {
  return { kind, amount: null, date: '', vendor: '', order_no: '', note: '',
           item_id: null }
}
const draft = ref(blank())

async function load() {
  loading.value = true
  try {
    rows.value = await api.get('/api/expenses')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => {
  if (open) {
    draft.value = blank()
    load()
    loadItems()
  }
})

async function loadItems() {
  try {
    items.value = await api.get('/api/items')
  } catch {
    /* 关联是选填的，拉不到就算了 */
  }
}

async function add() {
  const d = draft.value
  if (!Number(d.amount)) {
    ElMessage.warning('请填写金额')
    return
  }
  busy.value = true
  try {
    await api.post('/api/expenses', { ...d, amount: Number(d.amount) })
    draft.value = blank(d.kind)   // 类型留着，连着记好几笔时省事
    await load()
    emit('changed')
    ElMessage.success('已添加')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}

// 行内改动直接落库：这几行是流水账，没有"保存"按钮更顺手
async function save(row) {
  try {
    await api.put(`/api/expenses/${row.id}`, {
      kind: row.kind || '运费',
      amount: Number(row.amount) || 0,
      date: row.date || '',
      vendor: row.vendor || '',
      order_no: row.order_no || '',
      note: row.note || '',
      item_id: row.item_id ?? null,
    })
    emit('changed')
  } catch (e) {
    ElMessage.error(e.message)
    load()  // 改失败了把界面拉回服务端的值
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `删除这笔「${row.kind} ￥${money(row.amount)}」？`, '删除费用', { type: 'warning' })
  } catch { return }
  try {
    await api.del(`/api/expenses/${row.id}`)
    await load()
    emit('changed')
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

const total = computed(() =>
  Math.round(rows.value.reduce((s, r) => s + (Number(r.amount) || 0), 0) * 100) / 100)
</script>

<template>
  <el-dialog v-model="visible" title="额外费用" width="960px" :close-on-click-modal="false">
    <div class="hint">
      运费、安装费、辅料这类不进物料单价的支出记在这里。它们不参与「原价合计 / 日常价合计」
      的拆分，只在总览单独汇总 —— 所以不用再把运费摊进单价。
    </div>

    <div class="add-row">
      <el-select v-model="draft.kind" class="f-kind" filterable allow-create default-first-option>
        <el-option v-for="k in KINDS" :key="k" :label="k" :value="k" />
      </el-select>
      <el-input-number v-model="draft.amount" :min="0" :precision="2" :controls="false"
                       placeholder="金额" class="f-amount" />
      <el-date-picker v-model="draft.date" type="date" value-format="YYYY-MM-DD"
                      placeholder="日期" class="f-date" />
      <el-select v-model="draft.item_id" filterable clearable class="f-item"
                 placeholder="关联物料（选填）">
        <el-option v-for="i in items" :key="i.id" :label="itemLabel(i)" :value="i.id" />
      </el-select>
    </div>
    <div class="add-row">
      <el-input v-model="draft.vendor" placeholder="商家" maxlength="50" class="f-text" />
      <el-input v-model="draft.order_no" placeholder="订单号" maxlength="50" class="f-text" />
      <el-input v-model="draft.note" placeholder="备注" maxlength="200" class="f-note"
                @keyup.enter="add" />
      <el-button type="primary" :loading="busy" @click="add">添加</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" max-height="380">
      <el-table-column label="类型" width="96">
        <template #default="{ row }">
          <el-select v-model="row.kind" size="small" filterable allow-create
                     default-first-option @change="save(row)">
            <el-option v-for="k in KINDS" :key="k" :label="k" :value="k" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="金额" width="100" align="right">
        <template #default="{ row }">
          <el-input-number v-model="row.amount" :min="0" :precision="2" size="small"
                           :controls="false" style="width: 100%" @change="save(row)" />
        </template>
      </el-table-column>
      <el-table-column label="日期" width="132">
        <template #default="{ row }">
          <el-date-picker v-model="row.date" type="date" value-format="YYYY-MM-DD"
                          size="small" placeholder="选填" style="width: 100%"
                          @change="save(row)" />
        </template>
      </el-table-column>
      <el-table-column label="关联物料" width="172">
        <template #default="{ row }">
          <el-select v-model="row.item_id" size="small" filterable clearable
                     placeholder="选填" style="width: 100%" @change="save(row)">
            <el-option v-for="i in items" :key="i.id" :label="itemLabel(i)" :value="i.id" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="商家" width="92">
        <template #default="{ row }">
          <el-input v-model="row.vendor" size="small" placeholder="选填"
                    @change="save(row)" />
        </template>
      </el-table-column>
      <el-table-column label="订单号" width="112">
        <template #default="{ row }">
          <el-input v-model="row.order_no" size="small" placeholder="选填"
                    @change="save(row)" />
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="100">
        <template #default="{ row }">
          <el-input v-model="row.note" size="small" placeholder="选填" @change="save(row)" />
        </template>
      </el-table-column>
      <el-table-column width="64">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有记过额外费用" :image-size="70" />
      </template>
    </el-table>

    <div class="foot">
      共 {{ rows.length }} 笔 · 合计 <b>￥{{ money(total) }}</b>
    </div>
  </el-dialog>
</template>

<style scoped>
.hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--ios-label-2);
  padding: 8px 10px;
  border-radius: 10px;
  background: rgba(10, 132, 255, 0.07);
  margin-bottom: 12px;
}
.add-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
/* 宽度全部写死，不用 flex 吃满剩余 —— 否则日期选择器会按自己的内容撑到 220px，
   再把「关联物料」顶成整行宽度，第一行整体溢出弹窗。
   这几个数是配着弹窗内容区（约 900px）算的：一行总宽 700 出头，留足呼吸。 */
.f-kind { width: 118px; }
.f-amount { width: 128px; }
.f-date { width: 216px; }
.f-item { width: 216px; }
.f-text { width: 150px; }
.f-note { flex: 1; min-width: 160px; }
.foot {
  margin-top: 10px;
  text-align: right;
  font-size: 13px;
  color: var(--ios-label-2);
}
.foot b { color: var(--ios-label); font-size: 15px; }
</style>
