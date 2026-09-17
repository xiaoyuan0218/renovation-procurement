<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { auth, login, setup } from '../auth'

const username = ref('')
const password = ref('')
const confirm = ref('')
const busy = ref(false)
const usernameInput = ref(null)

// 后端还没有账号时，同一个页面变成「创建管理员」
const isSetup = computed(() => auth.status === 'setup')

const title = computed(() => (isSetup.value ? '创建管理员账号' : '采知道'))
const subtitle = computed(() => (isSetup.value
  ? '第一次使用，先设置一个账号。之后打开这个页面都需要登录。'
  : '登录后才能查看和修改采购数据。'))
const submitLabel = computed(() => (isSetup.value ? '创建并进入' : '登录'))

onMounted(() => usernameInput.value?.focus())

async function submit() {
  const name = username.value.trim()
  if (!name) {
    ElMessage.warning('请填写用户名')
    return
  }
  if (password.value.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (isSetup.value && password.value !== confirm.value) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }

  busy.value = true
  try {
    if (isSetup.value) {
      await setup(name, password.value)
      ElMessage.success('账号已创建')
    } else {
      await login(name, password.value)
    }
  } catch (e) {
    ElMessage.error(e.message)
    password.value = ''
    confirm.value = ''
    nextTick(() => usernameInput.value?.focus())
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card glass">
      <div class="brand">
        <span class="logo" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#fff"
               stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 10.5 12 3l9 7.5" />
            <path d="M5.5 9.5V20a1 1 0 0 0 1 1h11a1 1 0 0 0 1-1V9.5" />
          </svg>
        </span>
        <h1 class="title">{{ title }}</h1>
      </div>
      <p class="subtitle">{{ subtitle }}</p>

      <form class="fields" @submit.prevent="submit">
        <el-input
          ref="usernameInput"
          v-model="username"
          placeholder="用户名"
          autocomplete="username"
          size="large"
        />
        <el-input
          v-model="password"
          type="password"
          placeholder="密码"
          show-password
          :autocomplete="isSetup ? 'new-password' : 'current-password'"
          size="large"
        />
        <el-input
          v-if="isSetup"
          v-model="confirm"
          type="password"
          placeholder="再输一次密码"
          show-password
          autocomplete="new-password"
          size="large"
        />
        <el-button
          type="primary"
          size="large"
          class="submit"
          :loading="busy"
          native-type="submit"
        >
          {{ submitLabel }}
        </el-button>
      </form>

      <p v-if="isSetup" class="hint">密码至少 6 位，之后可以在「设置 / 数据」里修改。</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  box-sizing: border-box;
}
.login-card {
  width: 360px;
  max-width: 100%;
  padding: 28px 26px 24px;
  box-sizing: border-box;
}
.brand {
  display: flex;
  align-items: center;
  gap: 11px;
}
.logo {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  background: linear-gradient(135deg, #0a84ff, #5ac8fa);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(10, 132, 255, 0.35);
  flex-shrink: 0;
}
.title {
  margin: 0;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.subtitle {
  margin: 14px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ios-label-2);
}
.fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 20px;
}
.submit {
  width: 100%;
  margin-top: 4px;
}
.hint {
  margin: 14px 0 0;
  font-size: 12px;
  color: var(--ios-label-3);
  line-height: 1.5;
}
</style>
