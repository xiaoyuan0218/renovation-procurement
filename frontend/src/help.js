import { ref } from 'vue'

// 使用说明的开关：顶部栏有入口，「关于」页里也放了一个
export const helpVisible = ref(false)

export function openHelp() {
  helpVisible.value = true
}
