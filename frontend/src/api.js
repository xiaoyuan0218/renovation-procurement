// 由 auth.js 反向注册，避免 api.js 与 auth.js 互相 import 形成循环依赖
let onUnauthorized = null

export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

// 当前清单 id，由 lists.js 反向注册（同样是为了避免循环 import）。
// 每个业务请求都带上它，后端才知道这次要动哪一份清单。
let currentListId = null

export function setCurrentListId(id) {
  currentListId = id
}

// 登录、查询登录态这些接口返回 401 是正常结果，不该被当成"会话失效"
const AUTH_ENDPOINTS = ['/api/auth/']

async function request(method, url, body, isForm = false) {
  // 凭证放在 httpOnly Cookie 里，同源请求自动带上；
  // 设置页的下载链接是 <a href> 直接导航（带不了自定义头），走 ?list_id= 参数。
  const opts = { method, headers: {}, credentials: 'same-origin' }
  if (currentListId != null) opts.headers['X-List-Id'] = String(currentListId)
  if (body !== undefined) {
    if (isForm) {
      opts.body = body
    } else {
      opts.headers['Content-Type'] = 'application/json'
      opts.body = JSON.stringify(body)
    }
  }
  const res = await fetch(url, opts)
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const j = await res.json()
      detail = typeof j.detail === 'string' ? j.detail : JSON.stringify(j.detail ?? j)
    } catch { /* keep statusText */ }
    if (res.status === 401 && onUnauthorized && !AUTH_ENDPOINTS.some((p) => url.startsWith(p))) {
      onUnauthorized()
    }
    // 带上状态码：调用方要能区分 409（别处已改）这类需要特殊处理的情况
    const err = new Error(detail)
    err.status = res.status
    throw err
  }
  const ct = res.headers.get('content-type') || ''
  return ct.includes('json') ? res.json() : res
}

export const api = {
  get: (u) => request('GET', u),
  post: (u, b) => request('POST', u, b),
  postForm: (u, fd) => request('POST', u, fd, true),
  put: (u, b) => request('PUT', u, b),
  patch: (u, b) => request('PATCH', u, b),
  del: (u) => request('DELETE', u),
}

export const money = (v) => {
  if (v === null || v === undefined || v === '') return '-'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export const qty = (v) => {
  if (v === null || v === undefined) return '-'
  return Number(v) % 1 === 0 ? String(Number(v)) : String(Number(v).toFixed(2))
}

// 服务端盖的时间戳是 UTC（'YYYY-MM-DD HH:MM:SS'）；转成本地时区再看，
// 否则时间与用户的钟对不上 —— 服务器容器常是 UTC，浏览器是本地时区
export const toLocalStamp = (s) => {
  if (!s) return ''
  const d = new Date(s.replace(' ', 'T') + 'Z')
  if (Number.isNaN(d.getTime())) return s
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

// 表格里空间紧，掐掉年份
export const shortStamp = (s) => (s && s.length >= 16 ? toLocalStamp(s).slice(5, 16) : '')
