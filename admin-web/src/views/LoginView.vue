<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2 class="title">Hy论坛 · 管理后台</h2>
      <el-form :model="form" label-position="top" @keyup.enter="onSubmit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" data-test="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password" type="password" show-password
            autocomplete="current-password" data-test="password" />
        </el-form-item>
        <el-button
          type="primary" style="width: 100%" :loading="loading"
          data-test="submit" @click="onSubmit">
          登录
        </el-button>
        <p class="hint">管理员与前台账号完全隔离；登录有 IP 限流（10 次/分钟）。</p>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { adminLogin } from '../api/admin'
import { ApiError } from '../api/client'
import { saveAuth } from '../stores/auth'

const router = useRouter()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function onSubmit(): Promise<void> {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名与密码')
    return
  }
  loading.value = true
  try {
    const vo = await adminLogin(form.username, form.password)
    saveAuth(vo.token, { adminId: vo.adminId, nickname: vo.nickname, role: vo.role })
    ElMessage.success('登录成功')
    await router.replace({ path: '/' })
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '登录失败，请稍后再试')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  height: 100vh; display: flex; align-items: center; justify-content: center;
  background: var(--el-fill-color-light);
}
.login-card { width: 360px; }
.title { text-align: center; margin: 4px 0 18px; color: var(--el-color-primary); }
.hint { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 14px; }
</style>
