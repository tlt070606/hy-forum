<template>
  <el-card>
    <template #header>
      <div class="head">
        <span>敏感词管理</span>
        <div class="add">
          <el-input v-model="word" placeholder="新词（≤100 字）" maxlength="100" clearable
            style="width: 240px" @keyup.enter="onAdd" />
          <el-button type="primary" :loading="adding" @click="onAdd">添加</el-button>
        </div>
      </div>
    </template>

    <el-alert type="info" :closable="false" class="tip"
      title="增删立即生效（事务提交后刷新内存快照，无需重启）。含词的帖子/评论会进入待审核队列。" />

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="word" label="词" min-width="200" />
      <el-table-column label="添加时间" width="180" :formatter="(row: TimeRow) => fmtTime(row.createdAt)" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-popconfirm title="确定删除该词？" @confirm="onRemove(row)">
            <template #reference>
              <el-button size="small" type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
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
import { addSensitiveWord, deleteSensitiveWord, listSensitiveWords } from '../api/admin'
import { ApiError } from '../api/client'
import type { AdminSensitiveWordVO } from '../api/types'

const rows = ref<AdminSensitiveWordVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const word = ref('')
const adding = ref(false)

onMounted(() => reload(1))

async function reload(p = page.value): Promise<void> {
  loading.value = true
  try {
    const result = await listSensitiveWords(p, size)
    rows.value = result.list
    total.value = result.total
    page.value = result.page
  } finally {
    loading.value = false
  }
}

async function onAdd(): Promise<void> {
  if (!word.value.trim()) {
    ElMessage.warning('请输入词')
    return
  }
  adding.value = true
  try {
    await addSensitiveWord(word.value.trim())
    ElMessage.success('已添加（立即生效）')
    word.value = ''
    await reload(1)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '添加失败')
  } finally {
    adding.value = false
  }
}

function onRemove(row: AdminSensitiveWordVO): void {
  deleteSensitiveWord(row.id).then(() => {
    ElMessage.success('已删除（立即生效）')
    void reload()
  })
}
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.add { display: flex; gap: 8px; }
.tip { margin-bottom: 12px; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
