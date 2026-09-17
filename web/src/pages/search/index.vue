<template>
  <PagePlaceholder title="搜索" />
</template>

<script setup lang="ts">
/**
 * 搜索页（薄壳）。
 *
 * ⚠️ 与话题/收藏不同，**搜索的接口是已交付的**（`GET /api/posts/search`，
 * 已在 `api/posts.ts` 里封装好 `searchPosts`）。这一页纯粹是**本轮没排上**，
 * 下一轮按参考图铺即可，不需要等任何契约。
 *
 * 下一轮实现时的口径提醒：契约里 `keyword` **必填**，缺了返回 `400`；
 * 「没输入就点搜索」是正常操作，应在发请求前拦掉，而不是让后端回一个 400。
 */
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>
