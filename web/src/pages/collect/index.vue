<template>
  <PagePlaceholder title="我的收藏" active-nav="collect" />
</template>

<script setup lang="ts">
/**
 * 收藏页（薄壳）。
 *
 * 数据表是有的（`post_collect`，见 `schema.sql` 第 15 张表），
 * 但**接口没有**：`GET /api/user/collections` 不在契约的 14 个路径里（属 M4）。
 * 所以这一页需要等 M4 的接口，不是等设计。
 */
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>
