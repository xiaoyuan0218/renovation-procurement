<script setup>
import { reactive } from 'vue'

/**
 * 微信与支付宝的收款码，两处共用（悬浮卡片、设置里的「关于」）。
 * 缺图时显示同尺寸的占位框，不会出破图、布局也不跳。
 */
const CODES = [
  { key: 'wechat', label: '微信', src: '/donate/wechat.png' },
  { key: 'alipay', label: '支付宝', src: '/donate/alipay.png' },
]
const missing = reactive({})
</script>

<template>
  <div class="codes">
    <div v-for="c in CODES" :key="c.key" class="code-cell">
      <img v-if="!missing[c.key]" class="code-img" :src="c.src"
           :alt="`${c.label}收款码`" @error="missing[c.key] = true" />
      <div v-else class="code-blank">还没放</div>
      <span class="code-label">{{ c.label }}</span>
    </div>
  </div>
  <p class="contact">联系作者：QQ 1755445756 · 微信 CixinYan</p>
  <p class="contact">官方只在这一处发布：github.com/xiaoyuan0218/renovation-procurement</p>
</template>

<style scoped>
.codes { display: flex; gap: 12px; justify-content: center; }
.code-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  flex: 1;
}
.code-img {
  width: 100%;
  aspect-ratio: 1;
  object-fit: contain;
  border-radius: 10px;
  border: 1px solid var(--glass-border);
  background: #fff;
}
/* 占位框与真图同尺寸，放上去前后布局不跳 */
.code-blank {
  width: 100%;
  aspect-ratio: 1;
  border-radius: 10px;
  border: 1px dashed var(--glass-border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--ios-label-3);
}
.code-label { font-size: 11px; color: var(--ios-label-2); }
.contact {
  margin: 10px 0 0;
  text-align: center;
  font-size: 11px;
  color: var(--ios-label-3);
}
</style>
