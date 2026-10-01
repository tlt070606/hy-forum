<template>
  <el-card>
    <template #header>
      <div class="head">
        <span>评论审核</span>
        <el-radio-group v-model="statusFilter" @change="reload(1)">
          <el-radio-button :value="-1">全部</el-radio-button>
          <el-radio-button :value="0">待审核</el-radio-button>
          <el-radio-button :value="1">正常</el-radio-button>
          <el-radio-button :value="2">已屏蔽</el-radio-button>
        </el-radio-group>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="postTitle" label="所属帖子" min-width="160" show-overflow-tooltip />
      <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
      <el-table-column prop="authorNickname" label="作者" width="130" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <ContentStatusTag :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170" :formatter="(row: TimeRow) => fmtTime(row.createdAt)" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="success" :disabled="row.status === 1"
            @click="release(row)">放行</el-button>
          <el-button size="small" type="danger" :disabled="row.status === 2"
            @click="block(row)">屏蔽</el-button>
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
import ContentStatusTag from '../components/ContentStatusTag.vue'
import ReasonDialog from '../components/ReasonDialog.vue'
import { listAdminComments, reviewComment } from '../api/admin'
import type { AdminCommentVO } from '../api/types'

const rows = ref<AdminCommentVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const statusFilter = ref<number>(-1)
const reasonDialog = ref<InstanceType<typeof ReasonDialog>>()

onMounted(() => reload(1))

async function reload(p = page.value): Promise<void> {
  loading.value = true
  try {
    const result = await listAdminComments(statusFilter.value === -1 ? undefined : statusFilter.value, p, size)
    rows.value = result.list
    total.value = result.total
    page.value = result.page
  } finally {
    loading.value = false
  }
}

function release(row: AdminCommentVO): void {
  reviewComment(row.id, 1).then(() => {
    ElMessage.success('已放行（评论数会同步 +1）')
    void reload()
  })
}

function block(row: AdminCommentVO): void {
  reasonDialog.value?.open({
    title: `屏蔽评论（${row.authorNickname}）`,
    description: `「${row.content.slice(0, 60)}${row.content.length > 60 ? '…' : ''}」`,
    confirmText: '屏蔽',
    onConfirm: async (reason) => {
      await reviewComment(row.id, 2, reason)
      ElMessage.success('已屏蔽（评论数会同步 -1）')
      await reload()
    },
  })
}
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
