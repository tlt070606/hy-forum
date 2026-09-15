<template>
  <view class="doc">
    <view class="hy-card doc__card">
      <text class="doc__title">{{ title }}</text>
      <text class="doc__meta">版本：v0.1 · 更新日期：{{ updatedAt }}</text>

      <view v-for="(sec, i) in sections" :key="i" class="doc__section">
        <text class="doc__section-title">{{ i + 1 }}. {{ sec.heading }}</text>
        <text v-for="(p, j) in sec.paragraphs" :key="j" class="doc__paragraph">{{ p }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 静态文档页的共用组件（用户协议 / 隐私政策 / 免责声明）。
 *
 * 为什么抽成组件：三个页面除了文本内容完全一致。
 * 复制三份会造成「改样式要改三处」，且容易漂移。
 *
 * ⚠️ 页面的「尚未定稿」提示横幅已按需求方要求**移除**（保持界面干净）。
 *    但这不改变事实：这三份文本是**结构模板**，正式条文需需求方确认或经专业人士审阅。
 *    上线前务必替换 —— 论坛为公开 UGC，需履行内容审核与告知义务
 *    （见后端仓库《技术方案》§10）。
 *    修改方式：直接改各页面的 `sections` 数据，无需动样式。
 */
export interface DocSection {
  heading: string
  paragraphs: string[]
}

defineProps<{
  /** 文档标题 */
  title: string
  /** 更新日期（展示用） */
  updatedAt: string
  /** 章节内容 */
  sections: DocSection[]
}>()
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.doc {
  min-height: 100vh;
  padding: $hy-space-md;
  box-sizing: border-box;

  &__card {
    padding: $hy-space-lg;
  }

  &__title {
    display: block;
    font-size: 40rpx;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__meta {
    display: block;
    margin-top: $hy-space-xs;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__section {
    margin-top: $hy-space-lg;
  }

  &__section-title {
    display: block;
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
    margin-bottom: $hy-space-sm;
  }

  &__paragraph {
    display: block;
    font-size: $hy-font-sm;
    color: $hy-text-regular;
    line-height: 1.8;
    margin-bottom: $hy-space-sm;
  }
}
</style>
