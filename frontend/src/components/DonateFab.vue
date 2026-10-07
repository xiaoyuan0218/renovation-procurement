<script setup>
import { onMounted, ref } from 'vue'
import { donateEnabled } from '../donate'
import DonateCodes from './DonateCodes.vue'

/**
 * 打赏入口：右下角一个悬浮小圆钮，平时不占地方也不抢眼；点开是收款码
 * （微信与支付宝各一张）。开着开关（默认）时，每次打开页面自动弹一次 ——
 * 这就是「提示」。不想看到就去「设置 / 数据 → 关于」里关掉。
 */
const open = ref(false)

onMounted(() => {
  // 晚一点再弹：一进页面就盖住内容很烦
  if (donateEnabled.value) {
    setTimeout(() => { open.value = true }, 1200)
  }
})
</script>

<template>
  <div v-if="donateEnabled" class="donate-fab">
    <Transition name="donate-pop">
      <div v-if="open" class="donate-card">
        <button class="donate-close" title="收起" @click="open = false">×</button>
        <p class="donate-title">请作者喝杯咖啡</p>
        <DonateCodes />
        <p class="donate-hint">
          不想看到它？「设置 / 数据 → 关于」里可以关掉
        </p>
      </div>
    </Transition>
    <button class="donate-btn" :title="open ? '收起' : '支持作者'"
            @click="open = !open">
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none"
           stroke="currentColor" stroke-width="1.9" stroke-linecap="round">
        <path d="M4 8h13v7a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8Z" />
        <path d="M17 9h2a2 2 0 0 1 0 4h-2" />
        <path d="M8 4.5v2M12 4.5v2" />
      </svg>
    </button>
  </div>
</template>

<style scoped>
/* 右下角悬浮：显眼但压得住 —— 平时只是一颗小圆钮，展开才占地 */
.donate-fab {
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: 2000;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}
.donate-btn {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  border: 1px solid var(--glass-border);
  background: var(--glass-bg-strong);
  backdrop-filter: blur(var(--glass-blur));
  box-shadow: var(--glass-shadow);
  color: var(--ios-orange);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.15s ease;
}
.donate-btn:hover { transform: translateY(-2px); }
.donate-card {
  position: relative;
  width: 264px;
  padding: 16px 14px 12px;
  border-radius: 14px;
  border: 1px solid var(--glass-border);
  background: var(--glass-bg-strong);
  backdrop-filter: blur(var(--glass-blur));
  box-shadow: var(--glass-shadow);
  text-align: center;
}
.donate-close {
  position: absolute;
  top: 4px;
  right: 8px;
  border: none;
  background: none;
  font-size: 18px;
  line-height: 1;
  color: var(--ios-label-3);
  cursor: pointer;
}
.donate-title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ios-label);
}
.donate-hint {
  margin: 12px 0 0;
  font-size: 10px;
  color: var(--ios-label-3);
}
.donate-pop-enter-active { transition: opacity 0.18s ease, transform 0.18s ease; }
.donate-pop-enter-from { opacity: 0; transform: translateY(6px); }
</style>
