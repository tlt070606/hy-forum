<template>
  <el-tag :type="tagType" disable-transitions>{{ text }}</el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 内容状态标签（帖子/评论同口径：0 待审 / 1 正常 / 2 已屏蔽）。
 */
const props = defineProps<{ status: number }>()

const tagType = computed(() =>
  props.status === 0 ? 'warning' : props.status === 1 ? 'success' : 'danger')
const text = computed(() =>
  props.status === 0 ? '待审核' : props.status === 1 ? '正常' : '已屏蔽')
</script>
