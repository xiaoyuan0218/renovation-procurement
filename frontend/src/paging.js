import { computed } from 'vue'

/**
 * 这类列表（设置里的分组分类、额外费用、总览的未采购清单）条数不多，
 * 一页十条就够铺满可视区，不需要物料清单那种可调条数。
 */
export const PAGE_SIZE = 10

/** 把一列数据按当前页切出这一页。传 ref，用起来和普通 computed 一样。 */
export function pagedSlice(items, page, size = PAGE_SIZE) {
  return computed(() => {
    const start = (page.value - 1) * size
    return items.value.slice(start, start + size)
  })
}
