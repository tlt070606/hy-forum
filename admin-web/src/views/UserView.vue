<template>
  <el-card>
    <template #header>
      <div class="head">
        <span>用户管理</span>
        <div class="search">
          <el-input v-model="keyword" placeholder="用户名 / 昵称" clearable style="width: 220px"
            @keyup.enter="reload(1)" />
          <el-button type="primary" @click="reload(1)">搜索</el-button>
        </div>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="用户名" width="160" />
      <el-table-column prop="nickname" label="昵称" min-width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" disable-transitions>
            {{ row.status === 1 ? '正常' : '已封禁' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="postCount" label="发帖数" width="90" />
      <el-table-column label="注册时间" width="170" :formatter="(row: TimeRow) => fmtTime(row.createdAt)" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" size="small" type="danger" @click="ban(row)">封禁</el-button>
          <el-button v-else size="small" type="success" @click="unban(row)">解封</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager" layout="prev, pager, next, total" :total="total"
      :page-size="size" :current-page="page" @current-change="reload" />

    <ReasonDialog ref="reasonDialog" />
  </el-card>
</template>

<script setup lang="ts">
import { fmtTime, type TimeRow } from '../utils/time'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ReasonDialog from '../components/ReasonDialog.vue'
import { banUser, listUsers, unbanUser } from '../api/admin'
import type { AdminUserVO } from '../api/types'

const rows = ref<AdminUserVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const keyword = ref('')
const reasonDialog = ref<InstanceType<typeof ReasonDialog>>()

onMounted(() => reload(1))

async function reload(p = page.value): Promise<void> {
  loading.value = true
  try {
    const result = await listUsers(keyword.value.trim() || undefined, p, size)
    rows.value = result.list
    total.value = result.total
    page.value = result.page
  } finally {
    loading.value = false
  }
}

function ban(row: AdminUserVO): void {
  reasonDialog.value?.open({
    title: `封禁用户「${row.nickname}」（${row.username}）`,
    description: '封禁立即生效：状态置 0 并踢掉其全部登录态，其下一次请求即被拒（1004）。理由必填。',
    confirmText: '封禁',
    onConfirm: async (reason) => {
      await banUser(row.id, reason)
      ElMessage.success('已封禁')
      await reload()
    },
  })
}

function unban(row: AdminUserVO): void {
  reasonDialog.value?.open({
    title: `解封用户「${row.nickname}」（${row.username}）`,
    description: '解封后用户可重新登录与发帖。理由可选。',
    confirmText: '解封',
    requireInput: false,
    onConfirm: async (reason) => {
      await unbanUser(row.id, reason || undefined)
      ElMessage.success('已解封')
      await reload()
    },
  })
}
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.search { display: flex; gap: 8px; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
