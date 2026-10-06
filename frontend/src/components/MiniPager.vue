<script setup>
import { computed, watch } from 'vue'

/**
 * 列表底部的迷你分页。条数不够一页时整块不渲染 —— 只有几行数据的地方
 * 摆一排页码只是噪声。
 *
 * 用法：<MiniPager v-model:page="roomPage" :total="rooms.length" />
 */
const props = defineProps({
  total: { type: Number, required: true },
  page: { type: Number, required: true },
  size: { type: Number, default: 10 },
})
const emit = defineEmits(['update:page'])

const maxPage = computed(() => Math.max(1, Math.ceil(props.total / props.size)))

// 数据变少（筛选、删除）后当前页可能落到空页，自动退到最后一页
watch(maxPage, () => {
  if (props.page > maxPage.value) emit('update:page', maxPage.value)
})
</script>

<template>
  <div v-if="total > size" class="mini-pager">
    <el-pagination small background layout="prev, pager, next"
                   :total="total" :page-size="size" :current-page="page"
                   @current-change="(p) => emit('update:page', p)" />
  </div>
</template>

<style scoped>
.mini-pager {
  display: flex;
  justify-content: center;
  padding-top: 8px;
  flex: none;
}
</style>
