<template>
  <PagePlaceholder title="帖子详情" />
</template>

<script setup lang="ts">
/**
 * 帖子详情页（薄壳）。
 *
 * 接口已交付（`GET /api/posts/{id}`，见 `api/posts.ts` 的 `fetchPostDetail`），
 * 本轮没排上。下一轮按参考图铺：正文 + 图片九宫格 + 网盘卡片 + 一键复制 + 评论区。
 *
 * ⚠️ 两条既有口径，下一轮不要忘：
 * 1. **404 要给友好页而不是白屏**；
 * 2. 图片**只展示后端返回的 `images`**，前端不得自己拼图片 URL（口径 7）。
 *
 * ⚠️ 路径**故意与 `utils/postView.ts` 里 `detailUrl` 的形态一致**
 *    （`/pages/post/detail?id=…`），这样下一轮只换本文件内容，卡片那边一行都不用改。
 */
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>
