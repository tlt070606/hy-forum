<template>
  <el-dialog
    :model-value="visible"
    :title="title"
    width="460px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @closed="reset">
    <p v-if="description" class="desc">{{ description }}</p>
    <el-input
      v-model="reason" type="textarea" :rows="3" maxlength="200" show-word-limit
      :placeholder="placeholder" data-test="reason" />
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="loading" data-test="confirm" @click="onConfirm">
        {{ confirmText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * 处置理由弹窗：屏蔽/封禁/删除类动作的 reason 输入统一走这里
 * （后端合规 C9 要求这类动作 reason 必填，前端就地拦截，省一次 400 往返）。
 *
 * 用法：const dlg = ref<InstanceType<typeof ReasonDialog>>() → dlg.value?.open({...})
 */
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()
const visible = ref(false)
const title = ref('')
const description = ref('')
const placeholder = ref('')
const confirmText = ref('确定')
const requireInput = ref(true)
const reason = ref('')
const loading = ref(false)
let pending: ((reason: string) => Promise<void>) | null = null

function open(options: {
  title: string
  description?: string
  placeholder?: string
  confirmText?: string
  requireInput?: boolean
  onConfirm: (reason: string) => Promise<void>
}): void {
  title.value = options.title
  description.value = options.description ?? ''
  placeholder.value = options.placeholder ?? '请填写处置理由（将记入操作日志）'
  confirmText.value = options.confirmText ?? '确定'
  requireInput.value = options.requireInput ?? true
  pending = options.onConfirm
  reason.value = ''
  visible.value = true
}

function reset(): void {
  reason.value = ''
  loading.value = false
  pending = null
}

async function onConfirm(): Promise<void> {
  if (requireInput.value && !reason.value.trim()) {
    ElMessage.warning('请填写处置理由（合规要求可追溯）')
    return
  }
  loading.value = true
  try {
    await pending?.(reason.value.trim())
    visible.value = false
  } catch (e) {
    // 保持弹窗打开让用户重试；错误消息就地弹出（调用方无需再接 catch）
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    loading.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.desc { margin: 0 0 10px; color: var(--el-text-color-secondary); font-size: 13px; }
</style>
