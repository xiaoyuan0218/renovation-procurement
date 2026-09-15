<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { auth, changePassword } from '../auth'

const visible = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['imported'])

const rooms = ref([])
const categories = ref([])
const newRoom = ref('')
const newCategory = ref('')

const importMode = ref('replace')
const importing = ref(false)
const importReport = ref(null)
const fileList = ref([])

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const changing = ref(false)

onMounted(async () => {
  ;[rooms.value, categories.value] = await Promise.all([
    api.get('/api/rooms'), api.get('/api/categories'),
  ])
})

async function renameRoom(row) {
  const { value } = await ElMessageBox.prompt('修改房间名', '重命名', {
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
    `删除房间「${row.name}」会同时删除该房间的全部布点数量，确定？`, '删除房间', { type: 'warning' })
  await api.del(`/api/rooms/${row.id}`)
  rooms.value = rooms.value.filter((r) => r.id !== row.id)
  ElMessage.success('已删除')
  emit('imported') // 布点变化，刷新其他页面
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
  const { value } = await ElMessageBox.prompt('修改类目名', '重命名', {
    inputValue: row.name, inputPattern: /\S+/, inputErrorMessage: '名称不能为空',
  })
  const updated = await api.put(`/api/categories/${row.id}`, { name: value.trim(), sort: row.sort })
  Object.assign(row, updated)
  ElMessage.success('已改名')
}

async function removeCategory(row) {
  await ElMessageBox.confirm(`确定删除类目「${row.name}」？`, '删除类目', { type: 'warning' })
  try {
    await api.del(`/api/categories/${row.id}`)
    categories.value = categories.value.filter((c) => c.id !== row.id)
    ElMessage.success('已删除')
  } catch (e) { ElMessage.error(e.message) }
}

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
  <el-dialog v-model="visible" width="640px" :close-on-click-modal="false">
    <template #header>
      <div class="dlg-title">
        <span class="dlg-chip dlg-indigo">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round"><path d="M4 6h16M4 12h16M4 18h16"/><circle cx="9" cy="6" r="2" fill="#fff" stroke="none"/><circle cx="15" cy="12" r="2" fill="#fff" stroke="none"/><circle cx="8" cy="18" r="2" fill="#fff" stroke="none"/></svg>
        </span>
        设置 / 数据
      </div>
    </template>
    <el-tabs>
      <el-tab-pane label="房间">
        <div class="add-row">
          <el-input v-model="newRoom" placeholder="新房间名，如：阳台" class="add-input"
                    @keyup.enter="addRoom" />
          <el-button type="primary" plain @click="addRoom">添加</el-button>
        </div>
        <el-table :data="rooms" size="small" max-height="320">
          <el-table-column prop="name" label="房间" min-width="120" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="renameRoom(row)">改名</el-button>
              <el-button link size="small" type="danger" @click="removeRoom(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="类目">
        <div class="add-row">
          <el-input v-model="newCategory" placeholder="新类目名，如：家具" class="add-input"
                    @keyup.enter="addCategory" />
          <el-button type="primary" plain @click="addCategory">添加</el-button>
        </div>
        <el-table :data="categories" size="small" max-height="320">
          <el-table-column prop="name" label="类目" min-width="120" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="renameCategory(row)">改名</el-button>
              <el-button link size="small" type="danger" @click="removeCategory(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="数据备份">
        <div class="data-cards">
          <a class="data-card" href="/api/export">
            <span class="dc-icon dc-blue">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M4 19h16"/></svg>
            </span>
            <span class="dc-text"><b>导出当前数据</b><i>xlsx 完整备份，含采购记录与布点</i></span>
          </a>
          <a class="data-card" href="/api/import/template">
            <span class="dc-icon dc-teal">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 15V3"/><path d="m7 8 5-5 5 5"/><path d="M4 19h16"/></svg>
            </span>
            <span class="dc-text"><b>下载导入模板</b><i>按模板填好后再导入</i></span>
          </a>
        </div>
        <el-alert type="info" :closable="false" class="mt12"
                  title="导入使用系统模板格式（先下载模板查看），支持覆盖导入或按名称合并导入" />
        <el-form label-width="90px" class="mt12">
          <el-form-item label="导入方式">
            <el-radio-group v-model="importMode">
              <el-radio value="replace">覆盖现有数据</el-radio>
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
            布点 {{ importReport.allocations }} 条 · 房间 +{{ importReport.rooms_created }} · 类目 +{{ importReport.categories_created }}
          </template>
          <div v-for="(w, i) in importReport.warnings" :key="i">⚠ {{ w }}</div>
        </el-alert>
      </el-tab-pane>

      <el-tab-pane label="账号">
        <el-form label-width="90px">
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
  </el-dialog>
</template>

<style scoped>
.add-row { display: flex; gap: 8px; margin-bottom: 10px; }
.add-input { width: 220px; }
.data-cards { display: flex; gap: 10px; flex-wrap: wrap; }
.data-card {
  flex: 1;
  min-width: 200px;
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
.dc-text { display: flex; flex-direction: column; line-height: 1.4; }
.dc-text b { font-size: 13px; font-weight: 600; }
.dc-text i { font-size: 11px; color: var(--ios-label-2); font-style: normal; }
.mt12 { margin-top: 12px; }
.account-name { font-weight: 600; }
.pwd-input { width: 220px; }
</style>
