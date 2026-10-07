import { ref } from 'vue'

// 欢迎引导的显示状态：第一次打开自动弹一次，关掉后记在本机浏览器里。
// 两处要用同一个状态（应用根里显示、「关于」页里重新打开），所以放模块级。
const KEY = 'guide.seen'

function hasSeen() {
  try { return localStorage.getItem(KEY) === '1' } catch { return true }
}

export const guideVisible = ref(!hasSeen())

export function closeGuide() {
  guideVisible.value = false
  try { localStorage.setItem(KEY, '1') } catch { /* 隐私模式禁写 */ }
}

/** 「关于」页的「再看一次」：只是重新显示，不改变已看过的记录 */
export function openGuide() {
  guideVisible.value = true
}
