<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, money } from '../api'
import { PAGE_SIZE, pagedSlice } from '../paging'
import MiniPager from './MiniPager.vue'
import { auth, changePassword, loadAuthState } from '../auth'
import { deleteList, lists, updateList } from '../lists'
import { donateEnabled, setDonateEnabled } from '../donate'
import { openGuide } from '../guide'
import { openHelp } from '../help'
import NewListDialog from './NewListDialog.vue'
import DonateCodes from './DonateCodes.vue'
import ServerPanel from './ServerPanel.vue'

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
    loadApiKeys()
    loadLogs()
    loadVersion()
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
    gotoLast(rooms.value.length, roomPage)
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
    gotoLast(categories.value.length, categoryPage)
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
  if (!ok) ElMessage.error('请选择表格文件（.xlsx）')
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

// ---------- 列表分页 ----------
// 一页十条：设置里的这些列表都不长，分页后表格不必再靠内部滚动翻数据，
// 也就不会出现「内容没撑满就先滚起来」
const listPage = ref(1)
const roomPage = ref(1)
const categoryPage = ref(1)
const trashPage = ref(1)
const pagedLists = pagedSlice(computed(() => lists.all), listPage)
const pagedRooms = pagedSlice(rooms, roomPage)
const pagedCategories = pagedSlice(categories, categoryPage)
const pagedTrash = pagedSlice(trash, trashPage)
// 密钥列表的分页跟着 apiKeys 一起声明（它在下面才定义，不能提前引用）

// ---------- 关于 ----------
//
// 版本信息来自后端（建镜像时 CI 注入的构建时间）。检查更新直接问 GitHub：
// 滚动 release 的 tag 永远是 latest，比不了版本号，就比构建时间 —— 远端那批
// 资产的更新时间比本机新，就说明该 pull 了。

const version = ref({ version: '', built_at: '', repo: '' })
const checking = ref(false)
const updateState = ref('')   // '' | 'latest' | 'newer' | 'error'
const updateInfo = ref('')

/** GitHub 给的是 UTC，转成本地时间再看 */
function localTime(s) {
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}`
}

async function loadVersion() {
  try {
    version.value = await api.get('/api/version')
  } catch { /* 拿不到就显示未知，不影响别的功能 */ }
}

async function checkUpdate() {
  checking.value = true
  updateState.value = ''
  updateInfo.value = ''
  try {
    const repo = version.value.repo || 'xiaoyuan0218/renovation-procurement'
    const res = await fetch(
      `https://api.github.com/repos/${repo}/releases/tags/latest`)
    if (!res.ok) throw new Error(`GitHub 返回 ${res.status}`)
    const data = await res.json()
    const stamps = (data.assets || []).map((a) => a.updated_at).filter(Boolean).sort()
    const remote = stamps[stamps.length - 1] || data.published_at
    const local = version.value.built_at
    if (!remote) throw new Error('读不到远端的构建时间')
    if (!local) {
      updateState.value = 'latest'
      updateInfo.value = `本地是开发版（没有构建时间）。GitHub 上最新构建于 ${localTime(remote)}`
    } else if (new Date(remote) > new Date(local)) {
      updateState.value = 'newer'
      updateInfo.value = `有新版本：GitHub 上 ${localTime(remote)} 的构建比本机`
        + `（${localTime(local)}）新。在服务器上跑 docker compose pull && `
        + 'docker compose up -d 就能升级'
    } else {
      updateState.value = 'latest'
      updateInfo.value = `已是最新（本机构建于 ${localTime(local)}）`
    }
  } catch (e) {
    updateState.value = 'error'
    updateInfo.value = `检查失败：${e.message} —— 浏览器要能访问 GitHub 才查得到`
  } finally {
    checking.value = false
  }
}

/** 新增一条后跳到最后一页：否则刚加的东西藏在下一页，看着像没加成功 */
function gotoLast(count, pageRef) {
  pageRef.value = Math.max(1, Math.ceil(count / PAGE_SIZE))
}

// ---------- API 密钥 ----------
//
// 给脚本、手机快捷指令这类不方便走登录流程的调用方用。完整密钥只在下发
// 那一刻返回一次（库里只留 sha256），所以这里没有「查看」，只有生成和撤销。

const apiKeys = ref([])
const keyPage = ref(1)
const pagedKeys = pagedSlice(apiKeys, keyPage)
const newKeyName = ref('')
const creatingKey = ref(false)
const createdKey = ref('')
const showKeyVisible = ref(false)
const keyInputRef = ref(null)

async function loadApiKeys() {
  try {
    apiKeys.value = await api.get('/api/keys')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function createApiKey() {
  const name = newKeyName.value.trim()
  if (!name) {
    ElMessage.warning('先给它起个名字，方便以后认出是给谁用的')
    return
  }
  creatingKey.value = true
  try {
    const res = await api.post('/api/keys', { name })
    createdKey.value = res.key
    showKeyVisible.value = true
    newKeyName.value = ''
    await loadApiKeys()
    gotoLast(apiKeys.value.length, keyPage)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    creatingKey.value = false
  }
}

async function revokeApiKey(row) {
  try {
    await ElMessageBox.confirm(
      `撤销「${row.name}」？正在用它的一方会立刻连不上，而且找不回来 —— ` +
      '库里只存了哈希，就算你后悔也发不出同一把。确定？',
      '撤销密钥', { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' })
  } catch { return }
  try {
    await api.del(`/api/keys/${row.id}`)
    apiKeys.value = apiKeys.value.filter((k) => k.id !== row.id)
    ElMessage.success('已撤销')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function copyKey() {
  const text = createdKey.value
  try {
    if (!navigator.clipboard?.writeText) throw new Error('no clipboard api')
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    // 局域网是明文 HTTP，浏览器在这种页面里不给 clipboard API，
    // 退回老办法：选中输入框里的文字再发复制命令
    const el = keyInputRef.value?.input || keyInputRef.value?.$el?.querySelector('input')
    if (el) {
      el.select()
      if (document.execCommand('copy')) {
        ElMessage.success('已复制')
        return
      }
    }
    ElMessage.warning('这个浏览器不让自动复制，请手动选中上面的密钥复制')
  }
}

// ---------- 操作日志 ----------
//
// 只读展示 + 对最新一条可回退的操作提供一键回退。回退在服务端受「只撤
// 最近一条」约束，这里不用自己判，按钮该不该出现由接口的 can_undo 给出。

const logs = ref([])
const logsTotal = ref(0)
const logsLoading = ref(false)
const logPage = ref(1)
// 筛选：来源 / 类别 / 结果 / 关键词。类别值与接口前缀的对应关系在后端
// audit.CATEGORY_PREFIXES，这里只传值
const logSource = ref('all')
const logCategory = ref('all')
const logFailed = ref('all')
const logQuery = ref('')
// 服务端分页：每页条数记在 localStorage，跟物料清单同一套交互
const LOG_SIZE_OPTIONS = [20, 50, 100, 200]
const logPageSize = ref(Number(localStorage.getItem('logs.pageSize')) || 50)

const LOG_CATEGORIES = [
  { value: 'all', label: '全部类别' },
  { value: 'item', label: '物料与采购' },
  { value: 'expense', label: '额外费用' },
  { value: 'roomcat', label: '分组与分类' },
  { value: 'list', label: '清单' },
  { value: 'key', label: 'API 密钥' },
  { value: 'auth', label: '账号' },
  { value: 'log', label: '日志' },
]

function logsUrl(pageNum) {
  const size = Math.max(1, Math.floor(Number(logPageSize.value) || 50))
  const p = new URLSearchParams({ page: String(pageNum), page_size: String(size) })
  if (logSource.value !== 'all') p.set('source', logSource.value)
  if (logCategory.value !== 'all') p.set('category', logCategory.value)
  if (logFailed.value === 'failed') p.set('failed', '1')
  if (logQuery.value.trim()) p.set('q', logQuery.value.trim())
  return `/api/logs?${p}`
}

async function fetchLogs(pageNum) {
  logsLoading.value = true
  try {
    const d = await api.get(logsUrl(pageNum))
    logs.value = d.items
    logsTotal.value = d.total
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    logsLoading.value = false
  }
}

// 筛选一变就回到第一页重查
async function loadLogs() {
  logPage.value = 1
  await fetchLogs(1)
}

// 下拉的筛选一改就查；关键词等回车或查询按钮，免得打一半就发请求
watch([logSource, logCategory, logFailed], () => {
  logPage.value = 1
  fetchLogs(1)
})

function onLogPageChange(p) {
  logPage.value = p
  fetchLogs(p)
}

function onLogSizeChange(v) {
  const n = Math.floor(Number(v))
  logPageSize.value = Number.isFinite(n) && n >= 1 ? Math.min(n, 10000) : 50
  try { localStorage.setItem('logs.pageSize', String(logPageSize.value)) } catch { /* 隐私模式禁写 */ }
  logPage.value = 1
  fetchLogs(1)
}

async function undoLog(row) {
  try {
    await ElMessageBox.confirm(
      `回退「${row.action}」？数据会恢复到这次操作之前，布点与采购记录一起还原。`,
      '回退操作', { type: 'warning', confirmButtonText: '回退', cancelButtonText: '取消' })
  } catch { return }
  try {
    const r = await api.post(`/api/logs/${row.id}/undo`)
    ElMessage.success(r.message || '已回退', { duration: 6000 })
    await loadLogs()
    emit('imported')   // 数据变了，各页跟着重读
  } catch (e) {
    ElMessage.error(e.message, { duration: 8000 })
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
          <el-button type="primary" plain @click="newListVisible = true">新建清单</el-button>
          <span class="add-hint">空白起步，或照抄现有清单的分组与分类</span>
        </div>
        <el-table :data="pagedLists" size="small" height="100%" class="pane-table">
          <el-table-column label="清单" min-width="150">
            <template #default="{ row }">
              <span class="list-name">{{ row.name }}</span>
              <el-tag v-if="row.id === lists.currentId" size="small" type="primary"
                      effect="plain" class="cur-tag">当前</el-tag>
              <!-- 编号：改名字、重名都不变，靠它认出是哪一份 -->
              <div v-if="row.code" class="list-code">{{ row.code }}</div>
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
        <MiniPager v-model:page="listPage" :total="lists.all.length" />
        <el-alert type="info" :closable="false" class="mt12"
                  title="每份清单的物料、分组、分类互相独立，互不影响；删除清单会连里面的内容一起删掉，最后一份不允许删除。" />
      </el-tab-pane>

      <el-tab-pane label="分组">
        <div class="add-row">
          <el-input v-model="newRoom" placeholder="新分组名，如：日常 / 零食" class="add-input"
                    @keyup.enter="addRoom" />
          <el-button type="primary" plain @click="addRoom">添加</el-button>
        </div>
        <el-table :data="pagedRooms" size="small" height="100%" class="pane-table">
          <el-table-column prop="name" label="分组" min-width="120" />
          <el-table-column label="操作" width="168">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="renameRoom(row)">改名</el-button>
              <el-button link size="small" type="danger" @click="removeRoom(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <MiniPager v-model:page="roomPage" :total="rooms.length" />
      </el-tab-pane>

      <el-tab-pane label="分类">
        <div class="add-row">
          <el-input v-model="newCategory" placeholder="新分类名，如：耗材 / 配件" class="add-input"
                    @keyup.enter="addCategory" />
          <el-button type="primary" plain @click="addCategory">添加</el-button>
        </div>
        <el-table :data="pagedCategories" size="small" height="100%" class="pane-table">
          <el-table-column prop="name" label="分类" min-width="120" />
          <el-table-column label="操作" width="168">
            <template #default="{ row }">
              <el-button link size="small" type="primary" @click="renameCategory(row)">改名</el-button>
              <el-button link size="small" type="danger" @click="removeCategory(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <MiniPager v-model:page="categoryPage" :total="categories.length" />
      </el-tab-pane>

      <el-tab-pane label="备份">
        <div class="data-cards">
          <a class="data-card" :href="exportUrl">
            <span class="dc-icon dc-blue">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M4 19h16"/></svg>
            </span>
            <span class="dc-text"><b>导出当前清单</b><i>表格文件，含记录与分组分配</i></span>
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
            <span class="dc-text"><b>下载整库备份</b><i>整库文件，所有清单与账号</i></span>
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
              <el-button type="primary" :loading="importing">选择表格文件并导入</el-button>
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
        <el-table v-loading="trashing" :data="pagedTrash" size="small" height="100%" class="pane-table">
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
        <MiniPager v-model:page="trashPage" :total="trash.length" />
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

        <el-divider />

        <div class="add-row">
          <el-input v-model="newKeyName" placeholder="这把钥匙给谁用，如：手机快捷指令"
                    class="add-input" @keyup.enter="createApiKey" />
          <el-button type="primary" plain :loading="creatingKey" @click="createApiKey">
            生成密钥
          </el-button>
          <!-- 文档页要登录才开得动，同源导航会自动带上会话 Cookie -->
          <a class="add-hint doc-link" href="/api/docs" target="_blank" rel="noopener">
            打开接口文档 →
          </a>
        </div>
        <el-table :data="pagedKeys" size="small" class="pane-table">
          <el-table-column prop="name" label="用途" min-width="110" />
          <el-table-column label="密钥" width="126">
            <template #default="{ row }">
              <span class="list-code">{{ row.prefix }}…</span>
            </template>
          </el-table-column>
          <el-table-column prop="created_at" label="生成时间" width="142" />
          <el-table-column label="最后使用" width="142">
            <template #default="{ row }">
              {{ row.last_used_at || '还没用过' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="72">
            <template #default="{ row }">
              <el-button link size="small" type="danger" @click="revokeApiKey(row)">撤销</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有密钥" :image-size="70" />
          </template>
        </el-table>
        <MiniPager v-model:page="keyPage" :total="apiKeys.length" />
        <el-alert type="info" :closable="false" class="mt12"
                  title="密钥等同管理员权限：拿着它就能读写全部清单。只存在你要用的那台设备上；怀疑泄漏就立刻撤销。" />
      </el-tab-pane>

      <el-tab-pane label="日志">
        <div class="add-row">
          <el-select v-model="logSource" size="small" class="log-filter">
            <el-option label="全部来源" value="all" />
            <el-option label="本人" value="human" />
            <el-option label="API 密钥" value="api" />
          </el-select>
          <el-select v-model="logCategory" size="small" class="log-filter">
            <el-option v-for="cat in LOG_CATEGORIES" :key="cat.value"
                       :label="cat.label" :value="cat.value" />
          </el-select>
          <el-select v-model="logFailed" size="small" class="log-filter">
            <el-option label="全部结果" value="all" />
            <el-option label="仅未成功" value="failed" />
          </el-select>
          <el-input v-model="logQuery" size="small" clearable
                    placeholder="搜动作，如物料名" class="log-search"
                    @keyup.enter="loadLogs" @clear="loadLogs" />
          <el-button size="small" :loading="logsLoading" @click="loadLogs">查询</el-button>
        </div>
        <el-table :data="logs" size="small" height="100%" class="pane-table" v-loading="logsLoading">
          <el-table-column prop="at" label="时间" width="138" />
          <el-table-column label="来源" width="126" show-overflow-tooltip>
            <template #default="{ row }">{{ row.actor_name }}</template>
          </el-table-column>
          <el-table-column label="动作" min-width="210">
            <template #default="{ row }">
              {{ row.action }}
              <el-tag v-if="row.status_code >= 400" size="small" type="danger"
                      effect="plain" class="fail-tag">未成功</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="88">
            <template #default="{ row }">
              <el-button v-if="row.can_undo" link type="warning" size="small"
                         @click="undoLog(row)">回退</el-button>
              <span v-else-if="row.undone" class="hint">已回退</span>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有操作记录" :image-size="70" />
          </template>
        </el-table>
        <div v-if="logs.length" class="log-foot">
          <span class="pager-total">共 {{ logsTotal }} 条</span>
          <el-select v-model="logPageSize" class="pager-size" size="small"
                     filterable allow-create default-first-option
                     @change="onLogSizeChange">
            <el-option v-for="n in LOG_SIZE_OPTIONS" :key="n"
                       :label="`${n} 条/页`" :value="String(n)" />
          </el-select>
          <el-pagination :current-page="logPage"
                         :page-size="Math.max(1, Math.floor(Number(logPageSize) || 50))"
                         :total="logsTotal"
                         layout="prev, pager, next, jumper" background
                         @current-change="onLogPageChange" />
          <span class="hint">回退仅限最近一次操作</span>
        </div>
      </el-tab-pane>

      <!-- 连自己的服务端：同步之后本地内容会被重建，通知外层整体刷新 -->
      <el-tab-pane label="服务器">
        <ServerPanel @synced="emit('imported')" />
      </el-tab-pane>

      <el-tab-pane label="关于">
        <div class="about-block">
          <div class="about-line">
            <span class="about-label">当前版本</span>
            <span class="about-value">{{ version.version || '未知' }}</span>
          </div>
          <div class="about-line">
            <span class="about-label">构建时间</span>
            <span class="about-value">
              {{ version.built_at ? localTime(version.built_at) : '本地开发版（没有构建时间）' }}
            </span>
          </div>
          <div class="add-row">
            <el-button size="small" :loading="checking" @click="checkUpdate">
              检查更新
            </el-button>
            <el-button size="small" @click="openGuide">再看一次新手引导</el-button>
            <el-button size="small" @click="openHelp">使用说明</el-button>
          </div>
          <el-alert v-if="updateState" :closable="false" class="mt12"
                    :type="updateState === 'newer' ? 'warning'
                           : (updateState === 'error' ? 'error' : 'success')"
                    :title="updateInfo" />

          <el-divider />

          <div class="about-line">
            <span class="about-label">打赏入口</span>
            <el-switch :model-value="donateEnabled" size="small"
                       @change="setDonateEnabled" />
            <span class="hint">右下角那颗小圆钮；开着时每次打开都会弹一次</span>
          </div>
          <div class="donate-preview">
            <DonateCodes />
          </div>
          <p class="hint">这套东西是自部署的，数据都在你自己的机器上</p>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showKeyVisible" title="密钥已生成" width="520px"
               append-to-body :close-on-click-modal="false">
      <p class="key-tip">它只露面这一次，关掉之后就只剩前缀了。现在复制走，存到要用它的地方。</p>
      <el-input ref="keyInputRef" :model-value="createdKey" readonly class="key-value">
        <template #append>
          <el-button @click="copyKey">复制</el-button>
        </template>
      </el-input>
      <el-alert type="warning" :closable="false" class="mt12"
                title="调用时放在请求头里：X-API-Key: 密钥，或 Authorization: Bearer 密钥。" />
    </el-dialog>

    <NewListDialog v-model="newListVisible" @created="onListCreated" />
  </el-dialog>
</template>

<style scoped>
/* 七个页签均分面板宽度。名字必须短：nav 稍微宽过容器哪怕几像素，
   Element Plus 就会切到滚动模式把两端裁掉（is-scrollable + 位移） */
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
/* EP 对「放不放得下」的测量总比 flex 均分宽几个像素，七个页签必进滚动
   模式并给 nav 一个位移，把第一列裁掉一截。箭头已隐藏、内边距已归零，
   这里再把位移禁掉 —— 七个页签本来就都看得见，不需要它滚 */
.settings-tabs :deep(.el-tabs__nav) {
  transform: none !important;
}
/* 内容区高度固定：各页签长短不一，跟着内容伸缩的话弹窗会一跳一跳的 */
.settings-tabs :deep(.el-tabs__content) {
  height: min(560px, 62vh);
  overflow-y: auto;
}
/* 各页签纵向排满：表格吃掉添加行之外的剩余高度，行多时在表格内部滚
   （表头保持可见），而不是内容还没填满外层就先出现滚动条 */
.settings-tabs :deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.settings-tabs :deep(.el-tab-pane > .pane-table) {
  flex: 1 1 auto;
  min-height: 0;
}

.add-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.add-input { width: 220px; }
.add-hint { font-size: 12px; color: var(--ios-label-3); }
.list-name { font-weight: 600; }
.list-code {
  margin-top: 2px;
  font-size: 11px;
  letter-spacing: 0.4px;
  color: var(--ios-label-3);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
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
.doc-link { text-decoration: none; }
.doc-link:hover { color: var(--ios-blue); }
.fail-tag { margin-left: 6px; }
.about-block { display: flex; flex-direction: column; }
.donate-preview { max-width: 300px; margin: 12px 0 4px; }
.about-line { display: flex; gap: 10px; padding: 6px 0; font-size: 13px; }
.about-label { width: 72px; flex: none; color: var(--ios-label-2); }
.about-value { color: var(--ios-label); }

.log-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.pager-total { font-size: 13px; color: var(--ios-label-2); margin-right: auto; }
.pager-size { width: 112px; }
.log-filter { width: 116px; }
.log-search { width: 150px; }
.key-tip { margin: 0 0 10px; font-size: 13px; color: var(--ios-label-2); }
/* 密钥是一长串大写字母数字，等宽字体才好逐段核对 */
.key-value :deep(.el-input__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
</style>
