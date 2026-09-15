<template>
  <view class="doc">
    <!--
      合规提示横幅。
      为什么要有它：这三份文本是**法律文书**，其内容应由需求方确认或经专业人士审阅。
      我没有资格替项目编造用户协议/隐私政策条文，所以这里放的是**结构完整的模板**，
      并用醒目的横幅标明「尚未定稿」——避免上线时把模板当成正式文本发出去。
    -->
    <view class="doc__banner">
      <text class="doc__banner-title">⚠️ 文本尚未定稿</text>
      <text class="doc__banner-text">
        本页为结构模板，正式文本需由需求方确认（论坛为公开 UGC，需履行内容审核与告知义务，
        见《技术方案》§10）。确认后直接修改本页的 sections 数据即可，无需改样式。
      </text>
    </view>

    <view class="hy-card doc__card">
      <text class="doc__title">{{ title }}</text>
      <text class="doc__meta">版本：v0.1（模板） · 更新日期：{{ updatedAt }}</text>

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

  &__banner {
    background-color: #fff8e6;
    border: 1rpx solid #ffe1a8;
    border-radius: $hy-radius-md;
    padding: $hy-space-md;
    margin-bottom: $hy-space-md;
  }

  &__banner-title {
    display: block;
    font-size: $hy-font-sm;
    font-weight: 600;
    color: #8a6100;
    margin-bottom: 8rpx;
  }

  &__banner-text {
    display: block;
    font-size: $hy-font-xs;
    color: #8a6100;
    line-height: 1.7;
  }

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
