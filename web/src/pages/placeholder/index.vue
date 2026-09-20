<template>
  <!--
    通用占位页：接受 `?title=xxx`。
    用于**临时入口**（顶栏菜单里的"设置"等），它们没有独立的路由价值。
    有独立路由的页面走各自的薄壳，理由见 PagePlaceholder 的说明。
  -->
  <PagePlaceholder :title="title" />
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import PagePlaceholder from '@/components/PagePlaceholder.vue'

const title = ref('这一')

onLoad((options) => {
  // 路由 query 一律是字符串；调用方做了 encodeURIComponent，这里解回来
  const t = options?.title
  if (t) title.value = decodeURIComponent(String(t))
})
</script>
