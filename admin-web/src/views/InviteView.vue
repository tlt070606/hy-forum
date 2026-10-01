<template>
  <el-card>
    <template #header>
      <span>邀请码</span>
    </template>

    <div class="gen">
      <el-input-number v-model="count" :min="1" :max="50" label="数量" />
      <el-input-number v-model="expireDays" :min="1" :max="365" label="有效天数" placeholder="留空=永久" />
      <el-checkbox v-model="neverExpire">永不过期</el-checkbox>
      <el-button type="primary" :loading="generating" @click="onGenerate">生成</el-button>
    </div>

    <el-alert v-if="generated.length" type="success" :closable="true" class="tip">
      <template #title>
        已生成 {{ generated.length }} 张（复制给受邀人，注册时填入邀请码）：
        <el-tag v-for="code in generated" :key="code" class="code-tag">{{ code }}</el-tag>
      </template>
    </el-alert>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="code" label="邀请码" min-width="160" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : row.status === 1 ? 'info' : 'danger'"
            disable-transitions>
            {{ row.status === 0 ? '未使用' : row.status === 1 ? '已使用' : '已失效' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="过期时间" width="170" :formatter="(row: TimeRow) => fmtTime(row.expireAt)" />
      <el-table-column label="使用时间" width="170" :formatter="(row: TimeRow) => fmtTime(row.usedAt)" />
      <el-table-column label="生成时间" width="170" :formatter="(row: TimeRow) => fmtTime(row.createdAt)" />
    </el-table>

    <el-pagination
      class="pager" layout="prev, pager, next, total" :total="total"
      :page-size="size" :current-page="page" @current-change="reload" />
  </el-card>
</template>

<script setup lang="ts">
import { fmtTime, type TimeRow } from '../utils/time'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { generateInviteCodes, listInviteCodes } from '../api/admin'
import { ApiError } from '../api/client'
import type { AdminInviteCodeVO } from '../api/types'

const rows = ref<AdminInviteCodeVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const count = ref(1)
const expireDays = ref(7)
const neverExpire = ref(false)
const generating = ref(false)
const generated = ref<string[]>([])

onMounted(() => reload(1))

async function reload(p = page.value): Promise<void> {
  loading.value = true
  try {
    const result = await listInviteCodes(undefined, p, size)
    rows.value = result.list
    total.value = result.total
    page.value = result.page
  } finally {
    loading.value = false
  }
}

async function onGenerate(): Promise<void> {
  generating.value = true
  try {
    const codes = await generateInviteCodes(count.value, neverExpire.value ? undefined : expireDays.value)
    generated.value = codes
    ElMessage.success(`已生成 ${codes.length} 张`)
    await reload(1)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '生成失败')
  } finally {
    generating.value = false
  }
}
</script>

<style scoped>
.gen { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.tip { margin-bottom: 14px; }
.code-tag { margin-left: 6px; font-family: monospace; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
