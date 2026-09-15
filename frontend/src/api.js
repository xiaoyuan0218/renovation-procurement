// 由 auth.js 反向注册，避免 api.js 与 auth.js 互相 import 形成循环依赖
let onUnauthorized = null

export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

// 登录、查询登录态这些接口返回 401 是正常结果，不该被当成"会话失效"
const AUTH_ENDPOINTS = ['/api/auth/']

async function request(method, url, body, isForm = false) {
  // 凭证放在 httpOnly Cookie 里，同源请求自动带上；
  // 设置页那两个 <a href="/api/export"> 下载链接也因此无需改造。
  const opts = { method, headers: {}, credentials: 'same-origin' }
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
    throw new Error(detail)
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
