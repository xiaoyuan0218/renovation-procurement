/**
 * 清单：互相隔离的多个项目，每份有自己的条目、分组、分类。
 *
 * 当前清单记在 localStorage 里，业务请求统一由 api.js 带上 X-List-Id 头。
 * 这里只管三件事：拉清单列表、记住选了哪一份、切换后通知外面重新加载。
 * 和登录态一样，全局状态就这一份，不值得引入 pinia。
 */

import { reactive } from 'vue'

import { api, setCurrentListId } from './api'

const STORAGE_KEY = 'renovation.currentListId'

export const lists = reactive({
  all: [],
  currentId: null,
})

export function currentList() {
  return lists.all.find((l) => l.id === lists.currentId) || null
}

function readSaved() {
  try {
    return Number(localStorage.getItem(STORAGE_KEY) || '')
  } catch {
    return NaN // 隐私模式禁写存储，当作没记过
  }
}

function remember(id) {
  lists.currentId = id
  setCurrentListId(id)
  try {
    localStorage.setItem(STORAGE_KEY, String(id))
  } catch {
    /* 记不住就算了，刷新后退回第一份清单 */
  }
}

export async function loadLists() {
  lists.all = await api.get('/api/lists')
  const saved = readSaved()
  // 记住的清单可能已经不在了（在别的设备上删过），那就退回第一份
  const pick = lists.all.some((l) => l.id === saved) ? saved : (lists.all[0]?.id ?? null)
  remember(pick)
  return lists.all
}

export function setCurrentList(id) {
  remember(id)
}

/**
 * 新建清单。[copyFrom] 传另一份清单的 id 时，只把它的分组与分类结构照抄过来
 * （不带物料），省去重新建十几个分组的功夫；不传就是一张空白清单。
 */
export async function createList(name, note = '', copyFrom = null) {
  const created = await api.post('/api/lists', {
    name, note, sort: lists.all.length, copy_from: copyFrom,
  })
  lists.all.push(created)
  remember(created.id)
  return created
}

export async function updateList(id, { name, note, sort }) {
  const updated = await api.put(`/api/lists/${id}`, {
    name, note: note || '', sort: sort || 0,
  })
  const i = lists.all.findIndex((l) => l.id === id)
  if (i >= 0) lists.all[i] = updated
  return updated
}

export async function deleteList(id) {
  await api.del(`/api/lists/${id}`)
  lists.all = lists.all.filter((l) => l.id !== id)
  if (lists.currentId === id && lists.all.length) remember(lists.all[0].id)
}
