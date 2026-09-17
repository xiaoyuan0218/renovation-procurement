<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createList, lists } from '../lists'

const visible = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['created'])

const name = ref('')
const mode = ref('blank')          // blank = 空白清单；copy = 照抄另一份的结构
const sourceId = ref(null)
const busy = ref(false)

// 每次打开都重置：默认空白，复制来源默认当前正在看的那份
watch(visible, (open) => {
  if (!open) return
  name.value = ''
  mode.value = 'blank'
  sourceId.value = lists.currentId ?? lists.all[0]?.id ?? null
})

async function submit() {
  const trimmed = name.value.trim()
  if (!trimmed) {
    ElMessage.warning('请填写清单名')
    return
  }
  busy.value = true
  try {
    const created = await createList(
      trimmed, '', mode.value === 'copy' ? sourceId.value : null)
    visible.value = false
    emit('created', created)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <!-- 会被设置面板套着打开，append-to-body 保证层级压在它上面 -->
  <el-dialog v-model="visible" title="新建清单" width="460px"
             :close-on-click-modal="false" append-to-body>
    <el-form label-width="82px">
      <el-form-item label="名称">
        <el-input v-model="name" placeholder="例如：年货采购" maxlength="50"
                  show-word-limit @keyup.enter="submit" />
      </el-form-item>
      <el-form-item label="起步方式">
        <el-radio-group v-model="mode">
          <el-radio value="blank">空白清单</el-radio>
          <el-radio value="copy">复制分组和分类</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="mode === 'copy'" label="复制自">
        <el-select v-model="sourceId" style="width: 100%">
          <el-option v-for="l in lists.all" :key="l.id" :value="l.id"
                     :label="`${l.name}（${l.room_count} 个分组 · ${l.category_count} 个分类）`" />
        </el-select>
      </el-form-item>
    </el-form>

    <div class="hint">
      <template v-if="mode === 'copy'">
        只照抄分组和分类的名字，物料、分配、采购记录都不会带过来。
      </template>
      <template v-else>
        新清单从零开始，之后可以在「设置 / 数据 → 分组」里添加分组。
      </template>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">创建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--ios-label-2);
  padding: 8px 10px;
  border-radius: 10px;
  background: rgba(10, 132, 255, 0.07);
}
</style>
