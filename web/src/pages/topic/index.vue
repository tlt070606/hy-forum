<template>
  <PagePlaceholder title="话题" active-nav="topic" />
</template>

<script setup lang="ts">
/**
 * 话题页（薄壳）。
 *
 * ⚠️ 这一页**暂时只能是占位**，而且原因不是"来不及做"，是**数据模型里没有话题**：
 *    `docs/db/schema.sql` 的 16 张表里没有 `topic` / `tag` / hashtag 任何一张，
 *    契约（14 个路径）里也没有对应端点。
 *    要做成真的必须先加表 + 加接口 —— 那是契约变更（CR，L1 的事）。
 *
 *    参考图的左栏与右栏都有"话题"，本项目没有；这一条已作为提案登记进交付报告。
 *    路由先留在这里，等契约落地后只替换本文件内容。
 */
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>
