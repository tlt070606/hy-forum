<template>
  <PagePlaceholder title="发帖" />
</template>

<script setup lang="ts">
/**
 * 发帖 / 编辑页（薄壳）。
 *
 * 接口已交付（`POST /api/posts`、`PUT /api/posts/{id}`），本轮没排上。
 *
 * ⚠️ 下一轮实现时必须遵守的几条（都是已定口径或已踩过的坑）：
 * 1. **资源版块用 `isResource` 决定是否显示网盘字段**（口径 9，字段名逐字是 `isResource`）；
 * 2. **`PUT` 是覆盖语义** —— `images` 不传 = **清空图片**，所以编辑时必须把
 *    已有图片 URL 原样回传，否则改一次标题就把图删光了；
 * 3. **图片上传仍被卡住**：`OssSignatureVO.accessKeyId` 已经在契约里了（L1 已重导契约），
 *    但"上传成功后前端从哪里拿到图片 URL"这件事**契约里没有答案**
 *    —— `POST /api/oss/callback` 按 CR-007 只回 `{code:0,message}`，没有 `data`。
 *    见交付报告的 **CR-G**。
 */
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>
