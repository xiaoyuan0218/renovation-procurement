import { ref } from 'vue'

// 打赏入口的开关：默认开着。开着时每次打开网页会弹一次收款码当提示；
// 关掉后不再出现。状态记在这台设备的浏览器里 —— 这是访问者自己的偏好，
// 不需要惊动服务器。
//
// 「关掉」只对**当时那个版本**有效：软件更新（或重新部署，前端换了新版本）
// 之后开关自动回到打开、再提示一次；用户在那个新版本里再关，才算数。
// 所以关闭记录要连版本号一起存，读取时版本对不上就当作还开着。
const KEY = 'donate.enabled'
const OFF_VERSION_KEY = 'donate.offVersion'

/** 当前版本：构建时从仓库根 VERSION 注入（见 vite.config.js） */
const VERSION = typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : 'dev'

function readEnabled() {
  try {
    if (localStorage.getItem(KEY) !== '0') return true
    // 老记录（1.2.4 及以前）没有版本号：那是在更早的版本里关的 ——
    // 版本已经换了，按规则重新打开
    return localStorage.getItem(OFF_VERSION_KEY) !== VERSION
  } catch {
    return true
  }
}

export const donateEnabled = ref(readEnabled())

export function setDonateEnabled(on) {
  donateEnabled.value = on
  try {
    localStorage.setItem(KEY, on ? '1' : '0')
    // 关掉时记下版本：将来换了新版本，这条记录就作废、自动重新打开
    if (on) localStorage.removeItem(OFF_VERSION_KEY)
    else localStorage.setItem(OFF_VERSION_KEY, VERSION)
  } catch { /* 隐私模式禁写 */ }
}
