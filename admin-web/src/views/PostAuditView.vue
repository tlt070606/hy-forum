<template>
  <el-card>
    <template #header>
      <div class="head">
        <span>帖子审核</span>
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
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
      <el-table-column prop="authorNickname" label="作者" width="140" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <ContentStatusTag :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="标记" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.isTop === 1" size="small" type="warning">置顶</el-tag>
          <el-tag v-if="row.isEssence === 1" size="small" type="info">加精</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="发布时间" width="170" :formatter="(row: TimeRow) => fmtTime(row.createdAt)" />
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="success" :disabled="row.status === 1"
            @click="release(row)">放行</el-button>
          <el-button size="small" type="danger" :disabled="row.status === 2"
            @click="block(row)">屏蔽</el-button>
          <el-button size="small" @click="toggleTop(row)">
            {{ row.isTop === 1 ? '取消置顶' : '置顶' }}
          </el-button>
          <el-button size="small" @click="toggleEssence(row)">
            {{ row.isEssence === 1 ? '取消加精' : '加精' }}
          </el-button>
          <el-button size="small" type="warning" @click="remove(row)">删除</el-button>
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
import {
  deletePost, listAdminPosts, reviewPost, setPostEssence, setPostTop,
} from '../api/admin'
import type { AdminPostVO } from '../api/types'

const rows = ref<AdminPostVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
/** -1 表示"全部"（后端 status 参数不传即全部；radio 的值域不能含 undefined，用 -1 代指）。 */
const statusFilter = ref<number>(-1)
const reasonDialog = ref<InstanceType<typeof ReasonDialog>>()

onMounted(() => reload(1))

async function reload(p = page.value): Promise<void> {
  loading.value = true
  try {
    const result = await listAdminPosts(statusFilter.value === -1 ? undefined : statusFilter.value, p, size)
    rows.value = result.list
    total.value = result.total
    page.value = result.page
  } finally {
    loading.value = false
  }
}

function release(row: AdminPostVO): void {
  reviewPost(row.id, 1).then(() => {
    ElMessage.success('已放行')
    void reload()
  })
}

function block(row: AdminPostVO): void {
  reasonDialog.value?.open({
    title: `屏蔽帖子「${row.title}」`,
    confirmText: '屏蔽',
    onConfirm: async (reason) => {
      await reviewPost(row.id, 2, reason)
      ElMessage.success('已屏蔽')
      await reload()
    },
  })
}

function toggleTop(row: AdminPostVO): void {
  setPostTop(row.id, row.isTop !== 1).then(() => {
    ElMessage.success('已更新置顶状态')
    void reload()
  })
}

function toggleEssence(row: AdminPostVO): void {
  setPostEssence(row.id, row.isEssence !== 1).then(() => {
    ElMessage.success('已更新加精状态')
    void reload()
  })
}

function remove(row: AdminPostVO): void {
  reasonDialog.value?.open({
    title: `删除帖子「${row.title}」`,
    description: '逻辑删除：前台立即不可见；作者与版块的帖子计数会回退。理由必填。',
    confirmText: '删除',
    onConfirm: async (reason) => {
      await deletePost(row.id, reason)
      ElMessage.success('已删除')
      await reload()
    },
  })
}
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
