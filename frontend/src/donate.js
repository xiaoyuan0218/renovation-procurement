import { ref } from 'vue'

// 打赏入口的开关：默认开着。开着时每次打开网页会弹一次收款码当提示；
// 关掉后不再出现。状态记在这台设备的浏览器里 —— 这是访问者自己的偏好，
// 不需要惊动服务器。
//
// 「关掉」只对**当时那次部署**有效：换到新版本、或者服务端重新构建部署
// （哪怕版本号没变）之后，开关自动回到打开、再提示一次；在新部署里再关，
// 才算数。部署的"身份"由服务端给（/api/version 的 deploy_id），所以服务端
// 一更新，所有访问者的开关都会重新打开。
const KEY = 'donate.enabled'
const OFF_ID_KEY = 'donate.offDeployId'
const KNOWN_ID_KEY = 'donate.knownDeployId'

function readEnabled() {
  try {
    if (localStorage.getItem(KEY) !== '0') return true
    const offId = localStorage.getItem(OFF_ID_KEY)
    const knownId = localStorage.getItem(KNOWN_ID_KEY)
    // 没有部署标识的记录（1.2.4 及以前关的、或还没跟服务端对过）当作没关过
    if (!offId || !knownId) return true
    return offId !== knownId
  } catch {
    return true
  }
}

export const donateEnabled = ref(readEnabled())

export function setDonateEnabled(on) {
  donateEnabled.value = on
  try {
    localStorage.setItem(KEY, on ? '1' : '0')
    if (on) {
      localStorage.removeItem(OFF_ID_KEY)
    } else {
      // 关掉时记下当前部署标识：将来更新/重新部署后这条记录作废、自动重开。
      // 还没跟服务端对过（没有标识）时写空串，读取时按"没关过"处理
      localStorage.setItem(OFF_ID_KEY, localStorage.getItem(KNOWN_ID_KEY) || '')
    }
  } catch { /* 隐私模式禁写 */ }
}

/**
 * 跟服务端对一次部署标识。与上次不同 → 说明更新或重新部署过：关闭记录作废、
 * 开关回到打开，返回 true（调用方据此知道要不要提示）。
 *
 * 传空（服务端没给这个字段，比如很老的镜像）时什么都不做 —— 拿不到标识
 * 就把关掉的开关重开，会让"关不起作用"。
 */
export function syncDeployId(id) {
  if (!id) return false
  try {
    if (localStorage.getItem(KNOWN_ID_KEY) === id) return false
    localStorage.setItem(KNOWN_ID_KEY, id)
    localStorage.setItem(KEY, '1')
    localStorage.removeItem(OFF_ID_KEY)
    donateEnabled.value = true
    return true
  } catch {
    return false
  }
}
