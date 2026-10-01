<template>
  <el-card>
    <template #header>
      <div class="head">
        <span>版块管理</span>
        <el-button type="primary" @click="openCreate">新建版块</el-button>
      </div>
    </template>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" width="160" />
      <el-table-column prop="slug" label="slug" width="140" />
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column label="资源版" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.isResource === 1" size="small" type="warning">资源版</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="70" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" disable-transitions>
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="postCount" label="帖子数" width="90" />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" :type="row.status === 1 ? 'warning' : 'success'"
            @click="toggleStatus(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑版块' : '新建版块'" width="480px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="form.name" maxlength="30" />
        </el-form-item>
        <el-form-item v-if="!editing" label="slug">
          <el-input v-model="form.slug" placeholder="小写字母/数字/连字符，2–30，创建后不可改" />
        </el-form-item>
        <el-form-item v-else label="slug">
          <el-input :model-value="form.slug" disabled />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" maxlength="200" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
        <el-form-item label="资源版">
          <el-switch v-model="form.isResource" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createBoard, listBoards, updateBoard } from '../api/admin'
import { ApiError } from '../api/client'
import type { AdminBoardVO } from '../api/types'

const rows = ref<AdminBoardVO[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<AdminBoardVO | null>(null)
const form = reactive({
  name: '', slug: '', description: '', sort: 0, isResource: 0,
})

onMounted(() => reload())

async function reload(): Promise<void> {
  loading.value = true
  try {
    rows.value = await listBoards()
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  Object.assign(form, { name: '', slug: '', description: '', sort: 0, isResource: 0 })
  dialogVisible.value = true
}

function openEdit(row: AdminBoardVO): void {
  editing.value = row
  Object.assign(form, {
    name: row.name, slug: row.slug, description: row.description ?? '',
    sort: row.sort, isResource: row.isResource,
  })
  dialogVisible.value = true
}

async function onSubmit(): Promise<void> {
  if (!form.name.trim()) {
    ElMessage.warning('名称必填')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateBoard(editing.value.id, {
        name: form.name.trim(),
        description: form.description.trim(),
        sort: form.sort,
        isResource: form.isResource,
      })
      ElMessage.success('已保存')
    } else {
      await createBoard({
        name: form.name.trim(),
        slug: form.slug.trim(),
        description: form.description.trim() || undefined,
        sort: form.sort,
        isResource: form.isResource,
      })
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await reload()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function toggleStatus(row: AdminBoardVO): void {
  const target = row.status === 1 ? 0 : 1
  updateBoard(row.id, { status: target }).then(() => {
    ElMessage.success(target === 1 ? '已启用' : '已停用（前台立即隐藏）')
    void reload()
  })
}
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
</style>
