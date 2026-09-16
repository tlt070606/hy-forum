<template>
  <!--
    帖子列表项（首页「最新」流 / 版块页 / 搜索结果共用）。

    布局口径（需求方 2026-09-16 定）：**左文字 + 右单张缩略图**；
    无图时右侧**不占位**（不留白块），让无图帖的标题能撑满整行。

    ⚠️ 图片字段用的是契约 `PostSummaryVO.coverUrl`，**不是** `thumbUrl` ——
       契约里列表项没有 `thumbUrl`（只有详情用的 `PostImageVO` 有）。
       详见 `utils/format.ts` 里 `toListItem()` 的说明与交付报告中的口径差登记。
  -->
  <view class="post-item" data-testid="post-item" @click="onTap">
    <view class="post-item__main">
      <!-- 标题行：置顶/精华角标与标题同一行，角标不换行挤压标题 -->
      <view class="post-item__title-row">
        <text v-if="item.isTop" class="post-item__tag post-item__tag--top">置顶</text>
        <text v-if="item.isEssence" class="post-item__tag post-item__tag--essence">精华</text>
        <text class="post-item__title" data-testid="post-item-title">{{ item.title }}</text>
      </view>

      <!-- 元信息行：版块 · 作者 · 时间 -->
      <view class="post-item__meta">
        <text v-if="item.boardName" class="post-item__board" data-testid="post-item-board">
          {{ item.boardName }}
        </text>
        <text v-if="item.boardName" class="post-item__sep">·</text>
        <text class="post-item__author">{{ item.authorName }}</text>
        <text v-if="item.createdAtText" class="post-item__sep">·</text>
        <text class="post-item__time">{{ item.createdAtText }}</text>
      </view>

      <!-- 计数行：浏览 / 评论 / 点赞。刻意不显示收藏数 —— 列表里它最不重要 -->
      <view class="post-item__stats">
        <text class="post-item__stat">浏览 {{ compactCount(item.viewCount) }}</text>
        <text class="post-item__stat">评论 {{ compactCount(item.commentCount) }}</text>
        <text class="post-item__stat">点赞 {{ compactCount(item.likeCount) }}</text>
        <!--
          图片数量：只在**多于 1 张**时显示。
          单图帖右边已经有了缩略图，再标一个「1 图」是冗余信息。
        -->
        <text v-if="item.imageCount > 1" class="post-item__stat">
          {{ item.imageCount }} 图
        </text>
      </view>
    </view>

    <!-- 缩略图。`v-if` 而非空 src：空 src 在小程序端会产生一次无意义请求并报错 -->
    <image
      v-if="item.coverUrl"
      class="post-item__cover"
      :src="item.coverUrl"
      mode="aspectFill"
      data-testid="post-item-cover"
    />
  </view>
</template>

<script setup lang="ts">
/**
 * 帖子列表项。
 *
 * 为什么让它自己负责跳转，而不是 emit 事件交给父页面：
 * 三个列表页（首页 / 版块页 / 搜索页）的行为**完全一致**，跳转地址又完全由
 * `PostListItemView.detailUrl` 推导得出。让父页面各写一遍等于把同一段逻辑抄三次，
 * 抄错一次就是"某个页面点不动"这种只在特定路径下复现的 bug。
 * 需要自定义跳转时再引入 emit（目前没有这个需求，不提前抽象）。
 */
import { compactCount, type PostListItemView } from '@/utils/format'

const props = defineProps<{
  /** 已归一化的列表项（可选字段已在 `utils/format.ts` 里收敛） */
  item: PostListItemView
}>()

function onTap(): void {
  uni.navigateTo({ url: props.item.detailUrl })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.post-item {
  display: flex;
  align-items: flex-start;
  padding: $hy-space-md;
  background-color: $hy-bg-card;
  border-bottom: 1rpx solid $hy-border-color;

  /* 点击反馈：小程序端没有 :active 的悬停态，用 hover-class 更一致，
     这里保留 :active 供 H5/App，够用且不额外引入平台分支 */
  &:active {
    background-color: $hy-bg-hover;
  }

  &__main {
    /* min-width: 0 是必须的：flex 子项默认 min-width:auto，
       长标题不会触发省略号而是把整个盒子撑宽，导致缩略图被挤出屏幕 */
    min-width: 0;
    flex: 1;
  }

  &__title-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
  }

  &__tag {
    flex-shrink: 0;
    margin-right: $hy-space-xs;
    padding: 2rpx 10rpx;
    border-radius: $hy-radius-sm;
    font-size: $hy-font-xs;
    line-height: 1.6;

    &--top {
      color: $hy-text-inverse;
      background-color: $hy-color-danger;
    }

    &--essence {
      color: $hy-text-inverse;
      background-color: $hy-color-warning;
    }
  }

  &__title {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
    line-height: 1.45;

    /* 最多两行，超出省略。三行会让列表项高度参差，滚动时观感很差 */
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }

  &__meta {
    margin-top: $hy-space-xs;
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__board {
    color: $hy-color-primary;
  }

  &__sep {
    margin: 0 8rpx;
  }

  &__stats {
    margin-top: 6rpx;
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__stat {
    margin-right: $hy-space-sm;
  }

  &__cover {
    flex-shrink: 0;
    margin-left: $hy-space-md;
    width: 200rpx;
    height: 150rpx;
    border-radius: $hy-radius-sm;
    background-color: $hy-bg-hover;
  }
}
</style>
