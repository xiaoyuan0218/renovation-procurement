<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auth, loadAuthState, logout } from './auth'
import Dashboard from './views/Dashboard.vue'
import Items from './views/Items.vue'
import Matrix from './views/Matrix.vue'
import Login from './views/Login.vue'
import SettingsDialog from './components/SettingsDialog.vue'

const tabs = [
  { key: 'dashboard', label: '总览' },
  { key: 'items', label: '物料清单' },
  { key: 'matrix', label: '布点矩阵' },
]
const view = ref('dashboard')
const settingsVisible = ref(false)
const reloadKey = ref(0)

// 先问后端登录态，再决定显示登录页还是主界面
onMounted(loadAuthState)

function onImported() {
  reloadKey.value++
}

async function onLogout() {
  try {
    await ElMessageBox.confirm('退出后需要重新输入密码才能查看数据。', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  await logout()
  view.value = 'dashboard'
  ElMessage.success('已退出登录')
}
</script>

<template>
  <div v-if="auth.status === 'loading'" class="boot">
    <span class="spinner" aria-label="正在加载" />
  </div>

  <Login v-else-if="auth.status !== 'ready'" />

  <el-container v-else class="app">
    <el-header class="app-header" height="auto">
      <div class="header-row">
        <div class="brand">
          <span class="logo" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="#fff"
                 stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 10.5 12 3l9 7.5" />
              <path d="M5.5 9.5V20a1 1 0 0 0 1 1h11a1 1 0 0 0 1-1V9.5" />
            </svg>
          </span>
          <span class="brand-text">装修采购清单</span>
        </div>
        <nav class="seg" role="tablist">
          <button v-for="t in tabs" :key="t.key" class="seg-item"
                  :class="{ active: view === t.key }" role="tab"
                  :aria-selected="view === t.key" @click="view = t.key">
            {{ t.label }}
          </button>
        </nav>
        <el-button class="settings-btn" text bg @click="settingsVisible = true">设置 / 数据</el-button>
        <el-button class="logout-btn" text bg @click="onLogout">
          退出<span v-if="auth.username" class="who">（{{ auth.username }}）</span>
        </el-button>
      </div>
    </el-header>
    <el-main class="app-main">
      <Transition name="page" mode="out-in">
        <Dashboard v-if="view === 'dashboard'" :key="`d${reloadKey}`" @go-items="view = 'items'" />
        <Items v-else-if="view === 'items'" :key="`i${reloadKey}`" />
        <Matrix v-else :key="`m${reloadKey}`" />
      </Transition>
    </el-main>
    <SettingsDialog v-model="settingsVisible" @imported="onImported" />
  </el-container>
</template>

<style scoped>
.boot {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.spinner {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 2.5px solid rgba(0, 122, 255, 0.22);
  border-top-color: var(--ios-blue);
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
@media (prefers-reduced-motion: reduce) {
  .spinner { animation-duration: 2s; }
}

.app {
  height: 100vh;
}
.app-header {
  position: sticky;
  top: 0;
  z-index: 30;
  background: rgba(255, 255, 255, 0.55);
  -webkit-backdrop-filter: blur(24px) saturate(180%);
  backdrop-filter: blur(24px) saturate(180%);
  border-bottom: 0.5px solid rgba(60, 60, 67, 0.12);
  padding: 0 16px;
}
.header-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 0;
}
.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  white-space: nowrap;
}
.brand-text {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.logo {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: linear-gradient(135deg, #0a84ff, #5ac8fa);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(10, 132, 255, 0.35);
}
.seg {
  display: flex;
  gap: 2px;
  background: var(--ios-fill);
  border-radius: 10px;
  padding: 2px;
  margin: 0 auto;
}
.seg-item {
  border: 0;
  background: transparent;
  padding: 5px 18px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ios-label);
  cursor: pointer;
  font-family: inherit;
}
.seg-item.active {
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.14);
  font-weight: 600;
  color: var(--ios-blue);
}
.settings-btn {
  white-space: nowrap;
  color: var(--ios-blue);
  border: none !important;
  background: rgba(255, 255, 255, 0.6) !important;
  border-radius: 999px !important;
  font-weight: 600;
}
.settings-btn:hover {
  background: rgba(255, 255, 255, 0.92) !important;
}
.logout-btn {
  white-space: nowrap;
  color: var(--ios-label-2);
  border: none !important;
  background: rgba(255, 255, 255, 0.6) !important;
  border-radius: 999px !important;
  font-weight: 600;
}
.logout-btn:hover {
  background: rgba(255, 255, 255, 0.92) !important;
  color: var(--ios-red);
}
.who {
  font-weight: 500;
  color: var(--ios-label-3);
}
.app-main {
  width: 100%;
  box-sizing: border-box;
  padding: 16px 20px 24px;
  /* 内容区衬底提亮：卡片间隙露出的是衬底而非原样壁纸，
     否则缝隙底色比玻璃卡片暗一截，看起来像"留白与内容不同色" */
  background: rgba(255, 255, 255, 0.34);
}
</style>
