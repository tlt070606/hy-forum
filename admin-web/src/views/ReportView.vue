<template>
  <el-card>
    <template #header>
      <div class="head">
        <span>举报处理</span>
        <el-radio-group v-model="statusFilter" @change="reload(1)">
          <el-radio-button :value="-1">全部</el-radio-button>
          <el-radio-button :value="0">待处理</el-radio-button>
          <el-radio-button :value="1">已处理</el-radio-button>
          <el-radio-button :value="2">已驳回</el-radio-button>
        </el-radio-group>
      </div>
    </template>

    <el-alert type="info" :closable="false" class="tip"
      title="处置端点只登记结论；屏蔽/放行内容本身请到「帖子审核」「评论审核」页操作（单一写入口）。" />

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="对象" width="90">
        <template #default="{ row }">{{ targetTypeText(row.targetType) }} #{{ row.targetId }}</template>
      </el-table-column>
      <el-table-column prop="targetSummary" label="对象摘要" min-width="160" show-overflow-tooltip />
      <el-table-column label="举报理由" width="110">
        <template #default="{ row }">{{ reasonTypeText(row.reasonType) }}</template>
      </el-table-column>
      <el-table-column prop="reasonDetail" label="补充说明" min-width="120" show-overflow-tooltip />
      <el-table-column prop="reporterNickname" label="举报人" width="110" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'warning' : row.status === 1 ? 'success' : 'info'"
            disable-transitions>
            {{ row.status === 0 ? '待处理' : row.status === 1 ? '已处理' : '已驳回' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="举报时间" width="170" :formatter="(row: TimeRow) => fmtTime(row.createdAt)" />
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" :disabled="row.status !== 0"
            @click="dispose(row)">处置登记</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager" layout="prev, pager, next, total" :total="total"
      :page-size="size" :current-page="page" @current-change="reload" />

    <el-dialog v-model="dialogVisible" title="处置登记" width="480px">
      <p class="tip">
        对象：{{ targetTypeText(current?.targetType ?? 0) }} #{{ current?.targetId }}
        「{{ current?.targetSummary }}」
      </p>
      <el-form label-width="80px">
        <el-form-item label="结论">
          <el-radio-group v-model="outcome">
            <el-radio :value="1">已处理（内容已按违规处置）</el-radio>
            <el-radio :value="2">已驳回（内容未违规）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="note" type="textarea" :rows="3" maxlength="200" show-word-limit
            placeholder="处置说明必填（记入操作日志，合规可追溯）" data-test="note" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">登记</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { fmtTime, type TimeRow } from '../utils/time'
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { disposeReport, listReports } from '../api/admin'
import { ApiError } from '../api/client'
import type { AdminReportVO } from '../api/types'

const rows = ref<AdminReportVO[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const statusFilter = ref<number>(-1)
const dialogVisible = ref(false)
const saving = ref(false)
const current = ref<AdminReportVO | null>(null)
const outcome = ref<1 | 2>(1)
const note = ref('')

onMounted(() => reload(1))

async function reload(p = page.value): Promise<void> {
  loading.value = true
  try {
    const result = await listReports(statusFilter.value === -1 ? undefined : statusFilter.value, p, size)
    rows.value = result.list
    total.value = result.total
    page.value = result.page
  } finally {
    loading.value = false
  }
}

function dispose(row: AdminReportVO): void {
  current.value = row
  outcome.value = 1
  note.value = ''
  dialogVisible.value = true
}

async function onSubmit(): Promise<void> {
  if (!current.value || !note.value.trim()) {
    ElMessage.warning('处置说明必填（合规 C9：处置依据可追溯）')
    return
  }
  saving.value = true
  try {
    await disposeReport(current.value.id, outcome.value, note.value.trim())
    ElMessage.success('已登记')
    dialogVisible.value = false
    await reload()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '操作失败')
  } finally {
    saving.value = false
  }
}

function targetTypeText(t: number): string {
  return t === 1 ? '帖子' : t === 2 ? '评论' : t === 3 ? '用户' : `类型${t}`
}

function reasonTypeText(t: number): string {
  const map = ['违法违规', '色情低俗', '广告垃圾', '侵权', '其他']
  return map[t - 1] ?? `类型${t}`
}
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
.pager { margin-top: 14px; justify-content: flex-end; }
.tip { margin-bottom: 12px; }
.dialog-tip { margin: 0 0 12px; color: var(--el-text-color-secondary); font-size: 13px; }
</style>
