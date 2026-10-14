import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'node:fs'

/**
 * 版本号统一在仓库根的 VERSION 里（后端与安卓端也读它）。
 *
 * 前端拿它判断"是不是换了新版本"：打赏开关的关闭只对当时那个版本有效，
 * 更新（或重新部署）之后自动回到打开 —— 详见 src/donate.js。
 * 读不到就退回 dev：开发环境里等价于"版本一直没变过"。
 */
function appVersion() {
  try {
    return readFileSync(new URL('../VERSION', import.meta.url), 'utf8').trim() || 'dev'
  } catch {
    return 'dev'
  }
}

export default defineConfig({
  plugins: [vue()],
  define: { __APP_VERSION__: JSON.stringify(appVersion()) },
  server: {
    port: 5173,
    proxy: { '/api': 'http://127.0.0.1:8000' },
  },
})
