<template>
  <el-card>
    <template #header>
      <div class="head">
        <span>操作日志</span>
        <el-select v-model="actionFilter" clearable placeholder="按动作筛选" style="width: 220px"
          @change="reload(1)">
          <el-option v-for="a in ACTIONS" :key="a" :label="a" :value="a" />
        </el-select>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="时间" width="170" :formatter="(row: TimeRow) => fmtTime(row.createdAt)" />
      <el-table-column prop="adminUsername" label="操作人" width="120" />
      <el-table-column prop="action" label="动作" width="180" />
      <el-table-column label="对象" width="130">
        <template #default="{ row }">
          <span v-if="row.targetType != null">
            {{ targetTypeName(row.targetType) }} #{{ row.targetId }}
          </span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="理由" min-width="140" show-overflow-tooltip />
      <el-table-column prop="detail" label="变更摘要" min-width="160" show-overflow-tooltip />
      <el-table-column prop="ip" label="IP" width="130" />
    </el-table>

    <el-pagination
      class="pager" layout="prev, pager, next, total" :total="total"
      :page-size="size" :current-page="page" @current-change="reload" />
  </el-card>
</template>

<script setup lang="ts">
import { fmtTime, type TimeRow } from '../utils/time'
import { onMounted, ref } from 'vue'
import { listLogs } from '../api/admin'
import type { AdminLogVO } from '../api/types'

const ACTIONS = [
  'POST_STATUS', 'POST_TOP', 'POST_ESSENCE', 'POST_DELETE',
  'COMMENT_STATUS', 'REPORT_DISPOSE',
  'USER_BAN', 'USER_UNBAN',
  'BOARD_CREATE', 'BOARD_UPDATE',
  'SENSITIVE_WORD_CREATE', 'SENSITIVE_WORD_DELETE',
  'REGISTER_MODE', 'INVITE_CODE',
]

const rows = ref<AdminLogVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const actionFilter = ref<string | undefined>(undefined)

onMounted(() => reload(1))

async function reload(p = page.value): Promise<void> {
  loading.value = true
  try {
    const result = await listLogs(actionFilter.value || undefined, p, size)
    rows.value = result.list
    total.value = result.total
    page.value = result.page
  } finally {
    loading.value = false
  }
}

function targetTypeName(t: number): string {
  return ['帖子', '评论', '用户', '系统配置', '邀请码', '敏感词', '版块'][t - 1] ?? `类型${t}`
}
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
