<template>
  <PagePlaceholder title="版块" />
</template>

<script setup lang="ts">
/**
 * 版块页（薄壳）。
 *
 * 接口已交付（`GET /api/posts?boardId=…` + `GET /api/boards`），本轮没排上。
 *
 * ⚠️ 契约里**没有 `GET /api/boards/{id}`**，所以版块名要从 `GET /api/boards`
 *    的列表里按 id 查（见 `api/boards.ts` 的 `findBoard`）—— 不要编造一个不存在的端点。
 */
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>
