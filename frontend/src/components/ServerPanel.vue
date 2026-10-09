<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { api } from '../api'
import { lists } from '../lists'

/**
 * 「服务器」面板：把本机的清单同步到自己的服务端。
 *
 * 桌面端和手机端一样是客户端角色 —— 数据在本机，连上服务器才互相同步。
 * 交互与手机端「设置 / 服务器」页保持一致：绑定、立即同步、上传、拉取，
 * 以及两个绝不擅自替用户决定的弹窗（冲突裁决、两份撞上同一编号时的四选一）。
 */
const emit = defineEmits(['synced'])

const session = ref({ server_url: '', username: '', logged_in: false })
const bindings = ref({})
const remoteLists = ref([])
const busy = ref(false)
const form = ref({ url: '', username: '', password: '' })

// 提示条：成功 4 秒、失败 8 秒后自己消失（与手机端一致）
const notice = ref(null)
let noticeTimer = null
function showNotice(text, isError = false) {
  notice.value = { text, isError }
  clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => { notice.value = null }, isError ? 8000 : 4000)
}

/** 后端把可读的错误放在 detail 里，api.js 把它序列化成了 message，这里还原出来。 */
function readableError(error) {
  const raw = error?.message || '操作失败'
  try {
    const parsed = JSON.parse(raw)
    if (parsed && typeof parsed === 'object') {
      return parsed.hint ? `${parsed.message}（${parsed.hint}）` : (parsed.message || raw)
    }
  } catch { /* 不是 JSON 就原样用 */ }
  return raw
}

const addressHint = computed(() => {
  const raw = (form.value.url || '').trim()
  if (!raw) return '和网页版用的是同一个地址，例如 192.168.1.9:8000'
  const text = raw.includes('://') ? raw : `http://${raw}`
  try {
    const url = new URL(text)
    if (!url.hostname) return '这个地址看起来不对'
    return `将连接到 ${url.protocol}//${url.hostname}:${url.port || 8000}`
  } catch {
    return '这个地址看起来不对'
  }
})

const conflicts = ref(null)
const uploadDecision = ref(null)

function bindingOf(listId) {
  return bindings.value[String(listId)] || null
}

async function run(action) {
  busy.value = true
  try {
    return await action()
  } catch (error) {
    showNotice(readableError(error), true)
    return null
  } finally {
    busy.value = false
  }
}

async function loadState() {
  const data = await run(() => api.get('/api/desktop/state'))
  if (!data) return
  session.value = data.session
  bindings.value = data.bindings
  if (data.session.server_url && !form.value.url) form.value.url = data.session.server_url
}

async function loadRemoteLists() {
  const data = await run(() => api.get('/api/desktop/remote-lists'))
  if (data) remoteLists.value = data.lists
}

async function doLogin() {
  const result = await run(() => api.post('/api/desktop/login', {
    url: form.value.url,
    username: form.value.username,
    password: form.value.password,
  }))
  if (!result) return
  form.value.password = ''
  await loadState()
  await loadRemoteLists()
  showNotice(`已连接 ${result.server_url}`)
}

async function doLogout() {
  await run(() => api.post('/api/desktop/logout'))
  remoteLists.value = []
  await loadState()
  showNotice('已退出登录')
}

/** 同步/上传/拉取之后：本地内容可能被重建，通知外层刷新一遍。 */
async function afterChange() {
  await loadState()
  emit('synced')
}

async function upload(list) {
  const result = await run(() => api.post('/api/desktop/upload', { list_id: list.id }))
  if (!result) return
  if (result.needs_upload_decision) {
    uploadDecision.value = { ...result.needs_upload_decision, list_id: list.id, list_name: list.name }
    return
  }
  await afterChange()
}

async function resolveUpload(choice) {
  const pending = uploadDecision.value
  uploadDecision.value = null
  const result = await run(() => api.post('/api/desktop/resolve-upload', {
    list_id: pending.list_id,
    remote_list_id: pending.remote_list_id,
    choice,
  }))
  if (result) await afterChange()
}

async function pull(remote) {
  const result = await run(() => api.post('/api/desktop/pull', {
    remote_list_id: remote.id,
    name: remote.name,
  }))
  if (!result) return
  if (result.needs_upload_decision) {
    // 本地已经有同编号的一份：交给用户定怎么对齐（与上传撞车共用同一个选择框）
    const decision = result.needs_upload_decision
    const local = lists.all.find((l) => l.id === decision.list_id)
    uploadDecision.value = {
      ...decision,
      list_id: decision.list_id,
      list_name: local?.name || remote.name,
    }
    return
  }
  await afterChange()
}

async function syncNow(list) {
  const result = await run(() => api.post('/api/desktop/sync', { list_id: list.id }))
  if (!result) return
  if (result.conflicts?.length) {
    conflicts.value = { list_id: list.id, items: result.conflicts }
    return
  }
  await afterChange()
}

async function resolveConflicts(preferLocal) {
  const pending = conflicts.value
  conflicts.value = null
  const result = await run(() => api.post('/api/desktop/sync', {
    list_id: pending.list_id,
    prefer_local: preferLocal,
  }))
  if (result) await afterChange()
}

async function unbind(list) {
  try {
    await ElMessageBox.confirm(
      `「${list.name}」会变回纯本地清单，服务器上那一份原样留着、不受影响。`,
      '解除绑定', { confirmButtonText: '解除', cancelButtonText: '取消' })
  } catch {
    return
  }
  await run(() => api.post('/api/desktop/unbind', { list_id: list.id }))
  await loadState()
}

function sideText(side) {
  if (!side) return ''
  const total = side.items + side.rooms + side.categories
  return total === 0 ? '空的' : `${side.items} 条物料 · ${side.rooms} 个分组`
}

onMounted(loadState)
</script>

<template>
  <div class="server-panel">
    <div v-if="notice" class="server-notice" :class="{ 'is-error': notice.isError }">
      {{ notice.text }}
    </div>

    <!-- 未登录：填地址与账号 -->
    <template v-if="!session.logged_in">
      <el-alert type="info" :closable="false" class="mb12"
                title="可选：连上服务器之后，这份清单可以和手机、网页版互相同步；不连就一直当本地应用用。" />
      <el-form label-width="90px" class="account-form">
        <el-form-item label="服务器地址">
          <el-input v-model="form.url" placeholder="192.168.1.9:8000" />
          <div class="addr-hint">{{ addressHint }}</div>
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="和网页版登录用同一个" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password
                    @keyup.enter="doLogin" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy"
                     :disabled="!form.url || !form.username || !form.password"
                     @click="doLogin">连接</el-button>
        </el-form-item>
      </el-form>
    </template>

    <!-- 已登录 -->
    <template v-else>
      <div class="server-account">
        <div>
          <div class="account-name">{{ session.username }}</div>
          <div class="account-url">{{ session.server_url }}</div>
        </div>
        <el-button link size="small" @click="doLogout">退出登录</el-button>
      </div>

      <div class="section-title">与服务器同步</div>
      <div class="section-caption">同步是双向的：本机上的改动传上去，服务器上的改动拉下来</div>
      <el-table :data="lists.all" size="small" class="pane-table">
        <el-table-column label="清单" min-width="150">
          <template #default="{ row }">
            <span class="list-name">{{ row.name }}</span>
            <div v-if="bindingOf(row.id)" class="list-code">
              已绑定 · 上次 {{ bindingOf(row.id).last_synced_at || '—' }}
            </div>
            <div v-else class="list-code">纯本地 · 只在这台电脑上</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="196">
          <template #default="{ row }">
            <template v-if="bindingOf(row.id)">
              <el-button link size="small" type="primary" :loading="busy"
                         @click="syncNow(row)">立即同步</el-button>
              <el-button link size="small" type="danger" @click="unbind(row)">解除绑定</el-button>
            </template>
            <el-button v-else link size="small" type="primary" :loading="busy"
                       @click="upload(row)">上传到服务器</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="section-title">
        服务器上的清单
        <el-button link size="small" class="refresh" @click="loadRemoteLists">刷新</el-button>
      </div>
      <div class="section-caption">共 {{ remoteLists.length }} 份 · 拉到本地之后就能双向同步</div>
      <el-table :data="remoteLists" size="small" class="pane-table">
        <el-table-column prop="name" label="清单" min-width="150" />
        <el-table-column prop="item_count" label="物料" width="70" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button link size="small" type="primary" :loading="busy"
                       @click="pull(row)">拉到本地</el-button>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 冲突裁决：绝不擅自决定 -->
    <el-dialog :model-value="!!conflicts" width="470px" :show-close="false"
               :close-on-click-modal="false" title="有改动需要你定夺">
      <p>同步时发现本机和服务器改过同一处，没法自动合并：</p>
      <ul class="conflict-list">
        <li v-for="(item, index) in (conflicts?.items || [])" :key="index">
          {{ item.description }}
        </li>
      </ul>
      <p class="dim">选以哪边为准，另一边的这次改动会被覆盖。</p>
      <template #footer>
        <el-button @click="resolveConflicts(false)">以服务器为准</el-button>
        <el-button type="primary" @click="resolveConflicts(true)">以本机为准</el-button>
      </template>
    </el-dialog>

    <!-- 两份撞上同一编号（上传时服务器已有，或拉取时本地已有）：问用户怎么对齐 -->
    <el-dialog :model-value="!!uploadDecision" width="520px" :show-close="false"
               :close-on-click-modal="false"
               :title="`两边都有「${uploadDecision?.list_name || ''}」`">
      <p>编号相同，说明是同一份清单；但两边没有共同的同步记录，没法自动判断该留谁的。</p>
      <div class="side-row">
        <span>本机</span><span>{{ sideText(uploadDecision?.local) }}</span>
      </div>
      <div class="side-row">
        <span>服务器</span><span>{{ sideText(uploadDecision?.remote) }}</span>
      </div>
      <p class="dim">选一种处理方式：</p>
      <div class="choice-row" @click="resolveUpload('merge_both')">
        <strong>两边合并（推荐）</strong>
        <span>都留着：同名的取较新的那份，各自独有的都保留</span>
      </div>
      <div class="choice-row" @click="resolveUpload('overwrite_remote')">
        <strong>用本机上的覆盖</strong>
        <span>服务器那份换成这台电脑的，服务器上的改动会丢掉</span>
      </div>
      <div class="choice-row" @click="resolveUpload('keep_remote')">
        <strong>用服务器上的覆盖</strong>
        <span>这台电脑这份换成服务器的，本机上的改动会丢掉</span>
      </div>
      <div class="choice-row" @click="resolveUpload('create_new')">
        <strong>另存一份（两份都留）</strong>
        <span>本机这份作为一份新清单传到服务器，两边内容都不动</span>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.server-panel { position: relative; }
.server-notice {
  padding: 8px 12px; border-radius: 8px; margin-bottom: 10px; font-size: 13px;
  background: rgba(52, 199, 89, 0.12); color: #1f7a3f;
}
.server-notice.is-error { background: rgba(255, 59, 48, 0.12); color: #b3261e; }
.mb12 { margin-bottom: 12px; }
.addr-hint { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 4px; }
.server-account {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 12px; border-radius: 10px; margin-bottom: 14px;
  background: var(--el-fill-color-light);
}
.account-name { font-weight: 600; }
.account-url { font-size: 12px; color: var(--el-text-color-secondary); }
.section-title {
  font-size: 13px; font-weight: 600; margin: 14px 0 2px;
  display: flex; align-items: center; gap: 8px;
}
.section-caption { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 8px; }
.refresh { margin-left: auto; }
.conflict-list { margin: 8px 0; padding-left: 18px; font-size: 13px; }
.dim { color: var(--el-text-color-secondary); font-size: 12px; }
.side-row {
  display: flex; justify-content: space-between; font-size: 13px;
  padding: 6px 0; border-bottom: 1px dashed var(--el-border-color-lighter);
}
.choice-row {
  padding: 8px 10px; border-radius: 8px; cursor: pointer; margin-bottom: 6px;
  border: 1px solid var(--el-border-color-lighter);
}
.choice-row:hover { background: var(--el-fill-color-light); }
.choice-row strong { display: block; font-size: 13px; }
.choice-row span { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
