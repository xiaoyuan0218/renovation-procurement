/**
 * 登录态。这是应用里唯一一份全局状态——只有一个账号、三种界面状态，
 * 用 reactive 对象就够，不值得为它引入 pinia。
 *
 * status 取值：
 *   loading  启动时问后端要登录态，还不知道该显示什么
 *   setup    后端还没有账号，显示「创建管理员」
 *   login    有账号但没登录（或登录已过期）
 *   ready    已登录，显示主界面
 */

import { reactive } from 'vue'

import { api, setUnauthorizedHandler } from './api'

export const auth = reactive({
  status: 'loading',
  username: '',
})

export async function loadAuthState() {
  try {
    const state = await api.get('/api/auth/state')
    auth.username = state.username || ''
    if (!state.initialized) {
      auth.status = 'setup'
    } else {
      auth.status = state.authenticated ? 'ready' : 'login'
    }
  } catch {
    // 后端连不上时按未登录处理，否则会白屏；真正的错误在提交时提示
    auth.status = 'login'
  }
}

export async function login(username, password) {
  const res = await api.post('/api/auth/login', { username, password })
  auth.username = res.username
  auth.status = 'ready'
}

export async function setup(username, password) {
  const res = await api.post('/api/auth/setup', { username, password })
  auth.username = res.username
  auth.status = 'ready'
}

export async function logout() {
  try {
    await api.post('/api/auth/logout')
  } catch {
    /* 会话本来就过期了也算退出成功 */
  }
  auth.username = ''
  auth.status = 'login'
}

export async function changePassword(oldPassword, newPassword) {
  await api.post('/api/auth/password', {
    old_password: oldPassword,
    new_password: newPassword,
  })
}

// 任何业务请求收到 401（比如 cookie 过期、或在别处改了密码）都会走到这里
setUnauthorizedHandler(() => {
  auth.username = ''
  auth.status = 'login'
})
