<template>
  <el-card>
    <template #header>
      <span>系统配置</span>
    </template>

    <el-form label-width="120px" style="max-width: 560px">
      <el-form-item label="注册模式">
        <el-radio-group v-model="mode">
          <el-radio value="open">开放注册</el-radio>
          <el-radio value="invite">邀请码注册</el-radio>
          <el-radio value="closed">关闭注册</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </el-form-item>
    </el-form>

    <el-alert type="info" :closable="false" style="max-width: 560px"
      title="保存立即生效（前台每次注册/查询都直读配置，无缓存窗口）。每次切换都会记入操作日志。" />
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { get } from '../api/client'
import { switchRegisterMode } from '../api/admin'
import { ApiError } from '../api/client'

interface RegisterModeVO {
  mode: 'open' | 'invite' | 'closed'
  inviteRequired: boolean
}

const mode = ref<RegisterModeVO['mode']>('open')
const saving = ref(false)

onMounted(() => {
  // 复用前台公开接口读取当前模式（GET /api/auth/register-mode，匿名可访问）
  get<RegisterModeVO>('/api/auth/register-mode').then((vo) => {
    mode.value = vo.mode
  })
})

async function onSave(): Promise<void> {
  saving.value = true
  try {
    await switchRegisterMode(mode.value)
    ElMessage.success('已切换（立即生效）')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>
