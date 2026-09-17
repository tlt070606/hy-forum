<template>
  <!--
    信息流卡片。

    布局对照参考图：作者行（头像 / 昵称 / 日期 / 更多）→ 正文区 → 底栏（互动图标）。
    我们的数据模型与参考图有两处**不得不不同**的地方，都是契约决定的：

    1. **参考图的卡片直接显示正文**，而我们的 `PostSummaryVO`（列表项）**不含正文**
       —— 口径 6：「列表只返回摘要（无正文）」。因此卡片显示的是 **`title`**。
    2. **参考图有话题标签 chips**（`#工具箱` `#资源分享`），而本项目**没有话题表**
       （schema 16 张表里没有 topic/tag）。因此这里放的是**版块名** ——
       它是真实存在的分类维度，且点击能进版块页，不是拿假标签充数。
  -->
  <view class="post-card" data-testid="post-card" @click="openDetail">
    <view class="post-card__head">
      <Avatar :url="post.authorAvatarUrl" :nickname="post.authorName" :size="40" />
      <view class="post-card__author">
        <view class="post-card__name-row">
          <text class="post-card__name" data-testid="post-card-author">{{ post.authorName }}</text>
          <text v-if="post.isTop" class="tag tag--top">置顶</text>
          <text v-if="post.isEssence" class="tag tag--essence">精选</text>
        </view>
        <text class="post-card__time">{{ post.timeText }}</text>
      </view>
      <!-- 「更多」按钮：帖子级操作（举报/屏蔽）属 M5，这里明确提示未交付 -->
      <view class="post-card__more" data-testid="post-card-more" @click.stop="onMore">
        <HyIcon type="moreH" size="md" />
      </view>
    </view>

    <text class="post-card__title" data-testid="post-card-title">{{ post.title }}</text>

    <!-- 封面：契约列表项给的是 `coverUrl`（**不是** thumbUrl，见 postView.ts 的说明） -->
    <image
      v-if="post.coverUrl"
      class="post-card__cover"
      :src="post.coverUrl"
      mode="aspectFill"
      data-testid="post-card-cover"
    />

    <view class="post-card__foot">
      <view class="board-chip" data-testid="post-card-board" @click.stop="openBoard">
        <text class="board-chip__text">{{ post.boardName }}</text>
      </view>

      <view class="metrics">
        <view class="metric" data-testid="post-card-like">
          <HyIcon type="heart" size="sm" />
          <text class="metric__text">{{ compactCount(post.likeCount) }}</text>
        </view>
        <view class="metric" data-testid="post-card-comment">
          <HyIcon type="comment" size="sm" />
          <text class="metric__text">{{ compactCount(post.commentCount) }}</text>
        </view>
        <view class="metric" data-testid="post-card-collect">
          <HyIcon type="bookmark" size="sm" />
          <text class="metric__text">{{ compactCount(post.collectCount) }}</text>
        </view>
        <view class="metric metric--last" data-testid="post-card-view">
          <HyIcon type="eye" size="sm" />
          <text class="metric__text">{{ compactCount(post.viewCount) }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 帖子卡片。
 *
 * 为什么互动按钮**只展示数字、不可点击**：
 * 点赞/收藏接口（`POST /api/posts/{id}/like` 等）在本期契约的 14 个路径里**没有**
 * （见《技术方案》§6.5，属 M4）。做成可点的假按钮会让用户以为功能存在，
 * 点一下没反应又是更坏的体验。所以这里**只渲染计数**，等 M4 接口到位再接。
 * 「更多」按钮同理，点了会明确说明未交付。
 */
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import { compactCount, type PostCardView } from '@/utils/postView'

const props = defineProps<{
  /** 已归一化的卡片数据（可选字段已在 `utils/postView.ts` 收敛） */
  post: PostCardView
}>()

function openDetail(): void {
  uni.navigateTo({ url: props.post.detailUrl })
}

function openBoard(): void {
  // 版块 id 为 0 说明契约字段缺失，此时不跳（避免跳到 `?id=0` 的错误页）
  if (!props.post.boardId) return
  uni.navigateTo({ url: props.post.boardUrl })
}

function onMore(): void {
  uni.showToast({ title: '举报 / 屏蔽将在 M5 交付', icon: 'none', duration: 2000 })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.post-card {
  padding: 16px;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;
  margin-bottom: $hy-shell-gap;

  &:active {
    /* 整卡可点：给一点按压反馈，但不要变太多色（会像选中态） */
    background-color: #fcfcfd;
  }

  &__head {
    display: flex;
    align-items: center;
  }

  &__author {
    flex: 1;
    min-width: 0;
    margin-left: 10px;
  }

  &__name-row {
    display: flex;
    align-items: center;
  }

  &__name {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__time {
    display: block;
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__more {
    width: 28px;
    height: 28px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 50%;

    &:active {
      background-color: $hy-bg-hover;
    }
  }

  &__title {
    display: block;
    margin-top: 10px;
    font-size: $hy-font-lg;
    font-weight: 600;
    line-height: 1.5;
    color: $hy-text-primary;
    /* 最多两行：三行会让卡片高度参差，滚动观感差 */
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }

  &__cover {
    display: block;
    width: 100%;
    height: 180px;
    margin-top: 10px;
    border-radius: $hy-radius-sm;
    background-color: $hy-bg-hover;
  }

  &__foot {
    margin-top: 12px;
    display: flex;
    align-items: center;
  }
}

/* 置顶 / 精选 小角标 */
.tag {
  flex-shrink: 0;
  margin-left: 6px;
  padding: 0 5px;
  font-size: 10px;
  line-height: 16px;
  color: $hy-text-inverse;
  border-radius: 3px;

  &--top {
    background-color: $hy-color-danger;
  }

  &--essence {
    background-color: $hy-color-warning;
  }
}

/* 版块标签（真实分类维度，点击进版块页） */
.board-chip {
  padding: 3px 10px;
  background-color: $hy-color-primary-light;
  border-radius: $hy-radius-pill;

  &__text {
    font-size: $hy-font-xs;
    color: $hy-color-primary;
  }
}

.metrics {
  margin-left: auto;
  display: flex;
  align-items: center;
}

.metric {
  display: flex;
  align-items: center;
  margin-right: 18px;

  &--last {
    margin-right: 0;
  }

  &__text {
    margin-left: 4px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}
</style>
