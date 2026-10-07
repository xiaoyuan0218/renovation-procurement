import { ref } from 'vue'

// 打赏入口的开关：默认开着。开着时每次打开网页会弹一次收款码当提示；
// 关掉后不再出现。状态记在这台设备的浏览器里 —— 这是访问者自己的偏好，
// 不需要惊动服务器。
const KEY = 'donate.enabled'

function readEnabled() {
  try { return localStorage.getItem(KEY) !== '0' } catch { return true }
}

export const donateEnabled = ref(readEnabled())

export function setDonateEnabled(on) {
  donateEnabled.value = on
  try { localStorage.setItem(KEY, on ? '1' : '0') } catch { /* 隐私模式禁写 */ }
}
