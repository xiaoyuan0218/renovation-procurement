<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, money } from '../api'
import { auth, changePassword, loadAuthState } from '../auth'
import { deleteList, lists, updateList } from '../lists'
import NewListDialog from './NewListDialog.vue'

const visible = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['imported', 'switched'])

const rooms = ref([])
const categories = ref([])
const newRoom = ref('')
const newCategory = ref('')
const newListVisible = ref(false)

// 回收站：删掉的物料先放这里，恢复时连分配和采购记录一起回来
const trash = ref([])
const trashing = ref(false)

async function loadTrash() {
  try {
    trash.value = await api.get('/api/trash')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// 每次打开面板都重拉一遍：
//   - 分组/分类属于当前清单，而清单可能在别处被切过（或这个面板关着的时候换的），
//     不重拉就会显示上一份清单的分组 —— 新建空白清单后"分组还在"就是这么来的；
//   - 回收站随时可能被别处改（比如手机上删了东西）。
watch(visible, (open) => {
  if (open) {
    loadRoomsAndCategories()
    loadTrash()
  }
})

async function restoreItem(row) {
  trashing.value = true
  try {
    await api.post(`/api/trash/${row.id}/restore`)
    ElMessage.success(`已恢复「${row.name}」`)
    await loadTrash()
    emit('imported')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    trashing.value = false
  }
}

async function purgeItem(row) {
  try {
    await ElMessageBox.confirm(
      `彻底删除「${row.name}」？它的分配与采购记录会一起消失，无法恢复。`,
      '彻底删除', { type: 'warning', confirmButtonText: '彻底删除' })
  } catch { return }
  trashing.value = true
  try {
    await api.del(`/api/trash/${row.id}`)
    ElMessage.success('已彻底删除')
    await loadTrash()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    trashing.value = false
  }
}

async function purgeAll() {
  try {
    await ElMessageBox.confirm(
      `清空回收站？里面的 ${trash.value.length} 条会永久消失，无法恢复。`,
      '清空回收站', { type: 'warning', confirmButtonText: '清空' })
  } catch { return }
  trashing.value = true
  try {
    const res = await api.del('/api/trash')
    ElMessage.success(`已清空（${res.deleted} 条）`)
    await loadTrash()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    trashing.value = false
  }
}

const importMode = ref('replace')
const importing = ref(false)
const importReport = ref(null)
const restoring = ref(false)
const fileList = ref([])

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const changing = ref(false)

// 导出走 <a href> 直接导航，带不了 X-List-Id 头，所以用查询参数说明导哪一份
const exportUrl = computed(() => `/api/export?list_id=${lists.currentId ?? ''}`)

async function loadRoomsAndCategories() {
  try {
    ;[rooms.value, categories.value] = await Promise.all([
      api.get('/api/rooms'), api.get('/api/categories'),
    ])
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(loadRoomsAndCategories)

// ---------- 清单 ----------

function onListCreated() {
  // 新清单建好并已切换过去：关掉设置面板，让用户直接看到它
  visible.value = false
  emit('imported')
}

async function renameListRow(row) {
  const { value } = await ElMessageBox.prompt('修改清单名', '重命名', {
    inputValue: row.name, inputPattern: /\S+/, inputErrorMessage: '名称不能为空',
  })
  try {
    await updateList(row.id, { name: value.trim(), note: row.note, sort: row.sort })
    ElMessage.success('已改名')
    emit('switched', lists.currentId) // 顶部的名字跟着更新
  } catch (e) { ElMessage.error(e.message) }
}

function switchTo(row) {
  if (row.id === lists.currentId) return
  visible.value = false // 切完清单，这个弹窗里的分组/分类已经不是新清单的了
  emit('switched', row.id)
}

async function removeList(row) {
  try {
    await ElMessageBox.confirm(
      `删除清单「${row.name}」会连它里面的 ${row.item_count} 个物料、` +
      `${row.room_count} 个分组、${row.category_count} 个分类一起删掉，无法撤销。确定？`,
      '删除清单', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await deleteList(row.id)
    ElMessage.success('已删除')
    emit('imported')
  } catch (e) { ElMessage.error(e.message) }
}

// ---------- 分组 / 分类 ----------

async function renameRoom(row) {
  const { value } = await ElMessageBox.prompt('修改分组名', '重命名', {
    inputValue: row.name, inputPattern: /\S+/, inputErrorMessage: '名称不能为空',
  })
  const updated = await api.put(`/api/rooms/${row.id}`, { name: value.trim(), sort: row.sort })
  Object.assign(row, updated)
  ElMessage.success('已改名')
}

async function addRoom() {
  if (!newRoom.value.trim()) return
  try {
    const r = await api.post('/api/rooms', { name: newRoom.value.trim(), sort: rooms.value.length })
    rooms.value.push(r)
    newRoom.value = ''
    ElMessage.success('已添加')
  } catch (e) { ElMessage.error(e.message) }
}

async function removeRoom(row) {
  await ElMessageBox.confirm(
    `删除分组「${row.name}」会同时删除该分组的全部数量分配，确定？`, '删除分组', { type: 'warning' })
  await api.del(`/api/rooms/${row.id}`)
  rooms.value = rooms.value.filter((r) => r.id !== row.id)
  ElMessage.success('已删除')
  emit('imported') // 分配变了，刷新其他页面
}

async function addCategory() {
  if (!newCategory.value.trim()) return
  try {
    const c = await api.post('/api/categories', { name: newCategory.value.trim(), sort: categories.value.length })
    categories.value.push(c)
    newCategory.value = ''
    ElMessage.success('已添加')
  } catch (e) { ElMessage.error(e.message) }
}

async function renameCategory(row) {
  const { value } = await ElMessageBox.prompt('修改分类名', '重命名', {
    inputValue: row.name, inputPattern: /\S+/, inputErrorMessage: '名称不能为空',
  })
  const updated = await api.put(`/api/categories/${row.id}`, { name: value.trim(), sort: row.sort })
  Object.assign(row, updated)
  ElMessage.success('已改名')
}

async function removeCategory(row) {
  await ElMessageBox.confirm(`确定删除分类「${row.name}」？`, '删除分类', { type: 'warning' })
  try {
    await api.del(`/api/categories/${row.id}`)
    categories.value = categories.value.filter((c) => c.id !== row.id)
    ElMessage.success('已删除')
  } catch (e) { ElMessage.error(e.message) }
}

// ---------- 导入导出 ----------

async function submitUpload({ file }) {
  importing.value = true
  importReport.value = null
  try {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('mode', importMode.value)
    importReport.value = await api.postForm('/api/import', fd)
    ElMessage.success('导入成功')
    emit('imported')
  } catch (e) {
    ElMessage.error(e.message, { duration: 6000 })
  } finally {
    importing.value = false
    fileList.value = []
  }
}

function beforeUpload(file) {
  const ok = /\.(xlsx)$/i.test(file.name)
  if (!ok) ElMessage.error('请上传 .xlsx 文件')
  return ok
}

async function restoreUpload({ file }) {
  try {
    await ElMessageBox.confirm(
      '恢复整库备份会用备份里的内容整体替换现在的数据（连账号一起）。' +
      '恢复前会把当前的库另存一份，传错了也能找回来。确定继续？',
      '恢复整库备份',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' })
  } catch { return }
  restoring.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const res = await api.postForm('/api/backup/restore', fd)
    const { current, previous_backup: saved } = res
    ElMessage.success(
      `已恢复：${current.lists} 份清单、${current.items} 个物料。恢复前的数据存为 ${saved}`,
      { duration: 10000 })
    // 账号也来自备份，当前登录态可能已失效 —— 重新问一次后端最稳
    await loadAuthState()
    emit('imported')
  } catch (e) {
    ElMessage.error(e.message, { duration: 8000 })
  } finally {
    restoring.value = false
  }
}

function beforeRestore(file) {
  const ok = /\.(db|sqlite|sqlite3)$/i.test(file.name)
  if (!ok) ElMessage.error('请选择本工具导出的 .db 备份文件')
  return ok
}

async function submitPassword() {
  if (!oldPassword.value) {
    ElMessage.warning('请输入原密码')
    return
  }
  if (newPassword.value.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changing.value = true
  try {
    await changePassword(oldPassword.value, newPassword.value)
    oldPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    ElMessage.success('密码已修改')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    changing.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" width="680px" :close-on-click-modal="false">
    <template #header>
      <div class="dlg-title">
        <span class="dlg-chip dlg-indigo">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round"><path d="M4 6h16M4 12h16M4 18h16"/><circle cx="9" cy="6" r="2" fill="#fff" stroke="none"/><circle cx="15" cy="12" r="2" fill="#fff" stroke="none"/><circle cx="8" cy="18" r="2" fill="#fff" stroke="none"/></svg>
        </span>
        设置 / 数据
      </div>
    </template>
    <el-tabs class="settings-tabs">
      <el-tab-pane label="清单">
        <div class="add-row">
          <el-button type="primary" plain @click="newListVisible = true">新建清单…</el-button>
          <span class="add-hint">可以建一张空白清单，也可以照抄某份现有清单的分组与分类</span>
        </div>
        <el-table :data="lists.all" size="small" max-height="300">
          <el-table-column label="清单" min-width="150">
            <template #default="{ row }">
              <span class="list-name">{{ row.name }}</span>
              <el-tag v-if="row.id === lists.currentId" size="small" type="primary"
                      effect="plain" class="cur-tag">当前</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="item_count" label="物料" width="66" />
          <el-table-column prop="room_count" label="分组" width="66" />
          <el-table-column label="操作" width="212">
            <template #default="{ row }">
              <el-button link size="small" type="primary"
                         :disabled="row.id === lists.currentId" @click="switchTo(row)">切换</el-button>
              <el-button link size="small" type="primary" @click="renameListRow(row)">改名</el-button>
              <el-button link size="small" type="danger"
                         :disabled="lists.all.length <= 1" @click="removeList(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="info" :closable="false" class="mt12"
                  title="每份清单的物料、分组、分类互相独立，互不影响；删除清单会连里面的内容一起删掉，最后一份不允许删除。" />
      </el-tab-pane>

      <el-tab-pane label="分组">
        <div class="add-row">
          <el-input v-model="newRoom" placeholder="新分组名，如：客厅 / 零食" class="add-input"
                    @keyup.enter="addRoom" />
          <el-button type="primary" plain @click="addRoom">添加</el-button>
        </div>
        <el-table :data="rooms" size="small" max-height="320">
          <el-table-column prop="name" label="分组" min-width="120" />
          <el-table-column label="操作" width="168">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="renameRoom(row)">改名</el-button>
              <el-button link size="small" type="danger" @click="removeRoom(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="分类">
        <div class="add-row">
          <el-input v-model="newCategory" placeholder="新分类名，如：照明 / 家具" class="add-input"
                    @keyup.enter="addCategory" />
          <el-button type="primary" plain @click="addCategory">添加</el-button>
        </div>
        <el-table :data="categories" size="small" max-height="320">
          <el-table-column prop="name" label="分类" min-width="120" />
          <el-table-column label="操作" width="168">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="renameCategory(row)">改名</el-button>
              <el-button link size="small" type="danger" @click="removeCategory(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="数据备份">
        <div class="data-cards">
          <a class="data-card" :href="exportUrl">
            <span class="dc-icon dc-blue">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M4 19h16"/></svg>
            </span>
            <span class="dc-text"><b>导出当前清单</b><i>xlsx，含记录与分组分配</i></span>
          </a>
          <a class="data-card" href="/api/import/template">
            <span class="dc-icon dc-teal">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 15V3"/><path d="m7 8 5-5 5 5"/><path d="M4 19h16"/></svg>
            </span>
            <span class="dc-text"><b>下载导入模板</b><i>按模板填好后再导入</i></span>
          </a>
          <a class="data-card" href="/api/backup">
            <span class="dc-icon dc-green">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="5.5" rx="7" ry="2.8"/><path d="M5 5.5v13c0 1.5 3.1 2.8 7 2.8s7-1.3 7-2.8v-13"/><path d="M5 12c0 1.5 3.1 2.8 7 2.8s7-1.3 7-2.8"/></svg>
            </span>
            <span class="dc-text"><b>下载整库备份</b><i>.db，所有清单与账号</i></span>
          </a>
        </div>
        <el-alert type="info" :closable="false" class="mt12"
                  title="导入使用系统模板格式（先下载模板查看）；导入导出都只作用于当前清单，不影响其它清单。" />
        <el-form label-width="90px" class="mt12">
          <el-form-item label="导入方式">
            <el-radio-group v-model="importMode">
              <el-radio value="replace">覆盖当前清单</el-radio>
              <el-radio value="merge">按名称合并</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="选择文件">
            <el-upload :auto-upload="true" :show-file-list="false" accept=".xlsx"
                       :http-request="submitUpload" :before-upload="beforeUpload"
                       v-loading="importing">
              <el-button type="primary" :loading="importing">选择 xlsx 并导入</el-button>
            </el-upload>
          </el-form-item>
        </el-form>
        <el-alert v-if="importReport" type="success" :closable="false" class="mt12">
          <template #title>
            新建 {{ importReport.items_created }} 个物料 · 匹配更新 {{ importReport.items_matched }} 个 ·
            分配 {{ importReport.allocations }} 条 · 分组 +{{ importReport.rooms_created }} · 分类 +{{ importReport.categories_created }}
          </template>
          <div v-for="(w, i) in importReport.warnings" :key="i">⚠ {{ w }}</div>
        </el-alert>

        <el-divider />
        <div class="restore-row">
          <el-upload :auto-upload="true" :show-file-list="false" accept=".db"
                     :http-request="restoreUpload" :before-upload="beforeRestore">
            <el-button type="warning" plain :loading="restoring">从 .db 备份恢复整库</el-button>
          </el-upload>
          <span class="hint">会用备份整体替换现在的数据；恢复前自动把现状另存一份</span>
        </div>
      </el-tab-pane>

      <el-tab-pane label="回收站">
        <div class="add-row">
          <span class="add-hint">删掉的物料先放在这里；恢复它，分配和采购记录也会一起回来</span>
          <el-button type="danger" plain :disabled="!trash.length || trashing"
                     @click="purgeAll">清空回收站</el-button>
        </div>
        <el-table v-loading="trashing" :data="trash" size="small" max-height="320">
          <el-table-column prop="name" label="物料" min-width="120" />
          <el-table-column label="原价小计" width="100">
            <template #default="{ row }">￥{{ money(row.list_total) }}</template>
          </el-table-column>
          <el-table-column label="已付" width="100">
            <template #default="{ row }">
              {{ row.paid ? `￥${money(row.paid)}` : '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="deleted_at" label="移入时间" width="130" />
          <el-table-column label="操作" width="164">
            <template #default="{ row }">
              <el-button link type="primary" size="small"
                         :disabled="trashing" @click="restoreItem(row)">恢复</el-button>
              <el-button link type="danger" size="small"
                         :disabled="trashing" @click="purgeItem(row)">彻底删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="回收站是空的" :image-size="70" />
          </template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="账号">
        <el-form label-width="90px" class="account-form">
          <el-form-item label="当前账号">
            <span class="account-name">{{ auth.username || '—' }}</span>
          </el-form-item>
          <el-form-item label="原密码">
            <el-input v-model="oldPassword" type="password" show-password
                      autocomplete="current-password" class="pwd-input" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="newPassword" type="password" show-password
                      placeholder="至少 6 位" autocomplete="new-password" class="pwd-input" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="confirmPassword" type="password" show-password
                      autocomplete="new-password" class="pwd-input"
                      @keyup.enter="submitPassword" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="changing" @click="submitPassword">
              修改密码
            </el-button>
          </el-form-item>
        </el-form>
        <el-alert type="info" :closable="false"
                  title="修改后，其它设备上已登录的会话会自动失效；当前这个会保持登录。" />
      </el-tab-pane>
    </el-tabs>

    <NewListDialog v-model="newListVisible" @created="onListCreated" />
  </el-dialog>
</template>

<style scoped>
/* 五个页签均分面板宽度：窄一点也放得下，所以左右滚动箭头是多余的
   （Element Plus 仍按它自己算出的内容宽度显示箭头，这里直接盖掉） */
.settings-tabs :deep(.el-tabs__nav) {
  display: flex;
  width: 100%;
}
.settings-tabs :deep(.el-tabs__item) {
  flex: 1 1 0;
  justify-content: center;
  padding: 0 6px;
}
.settings-tabs :deep(.el-tabs__nav-wrap.is-scrollable) {
  padding: 0;
}
.settings-tabs :deep(.el-tabs__nav-prev),
.settings-tabs :deep(.el-tabs__nav-next) {
  display: none;
}

.add-row { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.add-input { width: 220px; }
.add-hint { font-size: 12px; color: var(--ios-label-3); }
.list-name { font-weight: 600; }
.cur-tag { margin-left: 8px; }
.data-cards { display: flex; gap: 10px; flex-wrap: wrap; }
.data-card {
  flex: 1;
  min-width: 150px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--glass-border);
  color: var(--ios-label);
  text-decoration: none;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.data-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--glass-shadow);
  color: var(--ios-label);
}
.dc-icon {
  width: 34px; height: 34px; border-radius: 9px;
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.dc-blue { background: linear-gradient(135deg, #0a84ff, #5ac8fa); box-shadow: 0 3px 10px rgba(10,132,255,.3); }
.dc-teal { background: linear-gradient(135deg, #30b0c7, #64d2ff); box-shadow: 0 3px 10px rgba(48,176,199,.3); }
.dc-green { background: linear-gradient(135deg, #248a3d, #30d158); box-shadow: 0 3px 10px rgba(36,138,61,.3); }
.dc-text { display: flex; flex-direction: column; line-height: 1.4; }
.dc-text b { font-size: 13px; font-weight: 600; }
.dc-text i { font-size: 11px; color: var(--ios-label-2); font-style: normal; }
.mt12 { margin-top: 12px; }
.restore-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.hint { font-size: 11px; color: var(--ios-label-3); }
/* 账号表单是定宽的窄表单（标签 90 + 输入框 220），贴着左边看着空，整块居中 */
.account-form {
  max-width: 330px;
  margin: 0 auto;
}
.account-name { font-weight: 600; }
.pwd-input { width: 220px; }
</style>
