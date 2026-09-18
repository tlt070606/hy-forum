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

    <!--
      封面。
      ⚠️ 需求方 2026-09-18：「这个图片能不能不要一下子加载全部？我想要跟九宫格一样，
         有多少个就加载多少个」—— 通栏大图一下占掉半屏，改成**九宫格里一格的大小**。

      ⚠️ 但**契约限制这里只能画一张**：`PostSummaryVO` 只有 `coverUrl` + `imageCount`，
         **没有图片列表** → 列表页**做不出真正的九宫格**（详情页有 `images[]` 才做得出来）。
         所以这里如实画一张 + 右下角标「N 张」说明不止一张，
         并把"列表需要图片列表"作为 CR 上报（见报告 §Q）。**不自己编造图片地址**。
    -->
    <view v-if="post.coverUrl" class="post-card__cover-box">
      <image
        class="post-card__cover"
        :src="post.coverUrl"
        mode="aspectFill"
        data-testid="post-card-cover"
      />
      <text
        v-if="post.imageCount > 1"
        class="post-card__cover-more"
        data-testid="post-card-cover-more"
      >
        {{ post.imageCount }} 张
      </text>
    </view>

    <!-- 版块标签：单独一行，放在互动行**上方** -->
    <view v-if="post.boardName" class="post-card__board-row">
      <view class="board-chip" data-testid="post-card-board" @click.stop="openBoard">
        <text class="board-chip__text">{{ post.boardName }}</text>
      </view>
    </view>

    <!--
      互动行：**铺在卡片底部一整行**（需求方 2026-09-17 定）。
      之前是「版块标签在左、互动挤在右下角」，看过去像把操作塞进角落；
      现在点赞/评论/收藏从左往右排开、浏览量靠右，与参考图一致。
    -->
    <view class="post-card__foot">
      <!--
        ⚠️ 图标尺寸（需求方 2026-09-18）：「我要的爱心变大是要在**我现在这个页面**啊，
        不要点进去再变大」—— 所以**列表卡片**这里也要大，不是只有详情页大。
        爱心用**线框**（heartOutline），未点赞时与参考站一致；点赞态在详情页才出现
        （列表项的契约字段里没有 isLiked，见报告 CR-K）。
      -->
      <view class="metric" data-testid="post-card-like">
        <HyIcon type="heartOutline" size="xl" />
        <text class="metric__text">{{ compactCount(post.likeCount) }}</text>
      </view>
      <view class="metric" data-testid="post-card-comment">
        <HyIcon type="comment" size="xl" />
        <text class="metric__text">{{ compactCount(post.commentCount) }}</text>
      </view>
      <view class="metric" data-testid="post-card-collect">
        <HyIcon type="bookmark" size="xl" />
        <text class="metric__text">{{ compactCount(post.collectCount) }}</text>
      </view>
      <!-- 浏览量属于"参考信息"，比三个操作小一档，不抢注意力 -->
      <view class="metric metric--trailing" data-testid="post-card-view">
        <HyIcon type="eye" size="lg" />
        <text class="metric__text metric__text--muted">{{ compactCount(post.viewCount) }}</text>
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

  &__cover-box {
    position: relative;
    /*
     * **九宫格里一格的大小**（三列中的一格），不再通栏。
     * 需求方 2026-09-18：通栏大图一下占掉半屏，"跟九宫格一样"更合适。
     */
    width: calc(33.33% - 8px);
    height: 92px;
    margin-top: 10px;
  }

  &__cover {
    width: 100%;
    height: 100%;
    border-radius: $hy-radius-sm;
    background-color: $hy-bg-hover;
  }

  /*
   * 「N 张」角标。
   * 为什么要有它：列表拿不到图片列表（契约只给 `coverUrl`），只画一张会让人以为
   * 这篇帖只有一张图 —— 这个角标是**如实说明**，不是装饰。
   */
  &__cover-more {
    position: absolute;
    right: 4px;
    bottom: 4px;
    padding: 0 6px;
    font-size: 10px;
    line-height: 16px;
    color: #ffffff;
    background-color: rgba(29, 33, 41, 0.55);
    border-radius: 3px;
  }

  &__board-row {
    margin-top: 10px;
    display: flex;
  }

  &__foot {
    margin-top: 12px;
    /* 与内容之间加一条分隔线：互动行是"卡片底部的一栏"，不是正文的延续 */
    padding-top: 10px;
    border-top: 1px solid $hy-border-color;
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

/* 互动行里的单项：从左往右排开，不再挤在角落 */
.metric {
  display: flex;
  align-items: center;
  /* 图标放大到 28px 之后，间距也要跟着放大，否则会挤成一团 */
  margin-right: 44px;

  /* 浏览量靠右：`margin-left: auto` 把它推到行尾，同时不影响左边三项的位置 */
  &--trailing {
    margin-left: auto;
    margin-right: 0;
  }

  &__text {
    margin-left: 8px;
    font-size: $hy-font-md;
    color: $hy-text-secondary;

    /* 浏览量那档小一点、浅一点 */
    &--muted {
      font-size: $hy-font-sm;
      color: $hy-text-placeholder;
    }
  }
}
</style>
