<template>
  <aside class="left-rail">
    <!-- ============ 主导航（参考图：图标 + 主标题 + 副标题） ============ -->
    <nav class="nav">
      <view
        v-for="item in NAV_ITEMS"
        :key="item.key"
        class="nav__item"
        :class="{ 'nav__item--active': item.key === active }"
        :data-testid="`nav-${item.key}`"
        @click="onNav(item)"
      >
        <HyIcon :type="item.icon" size="lg" class="nav__icon" />
        <view class="nav__text">
          <text class="nav__label">{{ item.label }}</text>
          <text class="nav__desc">{{ item.desc }}</text>
        </view>
      </view>
    </nav>

    <view class="divider" />

    <!-- ============ 热门话题（前 3 个） ============ -->
    <view class="block">
      <view class="block__head">
        <text class="block__title">热门话题</text>
        <text class="block__more" data-testid="left-topics-more" @click="goTopic">更多 ›</text>
      </view>
      <view
        v-for="(topic, index) in sidebarTopics"
        :key="topic.name"
        class="topic"
        :data-testid="`left-topic-${index}`"
        @click="goTopic"
      >
        <view class="topic__mark">
          <text class="topic__mark-text">#</text>
        </view>
        <view class="topic__text">
          <text class="topic__name">{{ topic.name }}</text>
          <text class="topic__meta">{{ topic.discussCount }} 讨论</text>
        </view>
      </view>
    </view>

    <view class="divider" />

    <!-- ============ 今日数据 ============ -->
    <!--
      真/假边界（需求方口径：真接口优先）：
      - 帖子总数 → **真**：来自 `GET /api/posts` 的 `total`，由父页面传进来
      - 活跃用户 → **假**：需要行为数据（登录/发帖/评论），契约里没有任何此类接口
    -->
    <view class="stats" data-testid="today-stats">
      <text class="stats__title">今日数据</text>
      <view class="stats__row">
        <view class="stats__item">
          <text class="stats__value" data-testid="stats-post-total">{{ postTotalText }}</text>
          <text class="stats__label">帖子总数</text>
        </view>
        <view class="stats__item">
          <text class="stats__value">{{ activeUsers }}</text>
          <text class="stats__label">活跃用户</text>
        </view>
      </view>
    </view>
  </aside>
</template>

<script setup lang="ts">
/**
 * 左侧栏（参考图左栏）。
 *
 * 三块内容：主导航 / 热门话题（前 3） / 今日数据。
 */
import { computed } from 'vue'
import HyIcon, { type IconType } from '@/components/HyIcon.vue'
import {
  HOT_TOPICS,
  MOCK_ACTIVE_USER_COUNT,
  SIDEBAR_TOPIC_COUNT,
} from '@/mock/hotContent'

const props = withDefaults(
  defineProps<{
    /**
     * 当前激活的导航项。
     * ⚠️ 含 `'me'`：它是**底部导航**才有的项（「我的」→ M2 的 pages/me/index）。
     *    左栏没有对应条目，因此传 `'me'` 时左栏不高亮任何一项 —— 这是正确行为，
     *    而不是类型不完备（共用外壳要能把同一个值转发给左栏和底部导航两处）。
     */
    active?: 'home' | 'topic' | 'collect' | 'me'
    /**
     * 帖子总数。
     * `null` = **还不知道**（请求中或失败），此时显示 `—` 而不是 `0` ——
     * 显示 0 会把"没请求到"伪装成"站点一篇帖子都没有"。
     */
    postTotal?: number | null
  }>(),
  { active: 'home', postTotal: null }
)

interface NavItem {
  key: 'home' | 'topic' | 'collect'
  label: string
  desc: string
  icon: IconType
  url: string | null
}

const NAV_ITEMS: readonly NavItem[] = [
  { key: 'home', label: '首页', desc: '全部最新帖子', icon: 'home', url: null },
  { key: 'topic', label: '话题', desc: '带话题标签的帖子', icon: 'hash', url: '/pages/topic/index' },
  { key: 'collect', label: '收藏', desc: '我的收藏', icon: 'bookmark', url: '/pages/collect/index' },
] as const

/** 左栏只取榜单前 N 个（不重排，顺序就是榜单顺序） */
const sidebarTopics = computed(() => HOT_TOPICS.slice(0, SIDEBAR_TOPIC_COUNT))

const activeUsers = MOCK_ACTIVE_USER_COUNT

const postTotalText = computed(() =>
  props.postTotal === null || props.postTotal === undefined ? '—' : String(props.postTotal)
)

function onNav(item: NavItem): void {
  // 当前页（`url === null`）不跳，避免 reLaunch 把页面栈清掉、丢失滚动位置
  if (!item.url) return
  uni.navigateTo({ url: item.url })
}

function goTopic(): void {
  uni.navigateTo({ url: '/pages/topic/index' })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.left-rail {
  width: $hy-shell-left-width;
  flex-shrink: 0;
}

/* ---------- 导航 ---------- */
.nav {
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  padding: 8px;
  box-shadow: $hy-shadow-card;

  &__item {
    display: flex;
    align-items: center;
    padding: 10px 12px;
    border-radius: $hy-radius-sm;

    &:active {
      background-color: $hy-bg-hover;
    }

    /* 激活态：浅紫底 + 主色文字/图标（参考图的做法） */
    &--active {
      background-color: $hy-color-primary-light;

      .nav__icon {
        color: $hy-color-primary;
      }

      .nav__label {
        color: $hy-color-primary;
        font-weight: 600;
      }
    }
  }

  &__icon {
    margin-right: 12px;
  }

  &__text {
    display: flex;
    flex-direction: column;
  }

  &__label {
    font-size: $hy-font-md;
    color: $hy-text-primary;
  }

  &__desc {
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

.divider {
  height: 1px;
  margin: 12px 0;
  background-color: $hy-border-color;
}

/* ---------- 热门话题 ---------- */
.block {
  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    padding: 0 4px 8px;
  }

  &__title {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__more {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

.topic {
  display: flex;
  align-items: center;
  padding: 6px 4px;
  border-radius: $hy-radius-sm;

  &:active {
    background-color: $hy-bg-hover;
  }

  &__mark {
    width: 28px;
    height: 28px;
    margin-right: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: $hy-color-primary-light;
    border-radius: $hy-radius-sm;
  }

  &__mark-text {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-color-primary;
  }

  &__text {
    display: flex;
    flex-direction: column;
  }

  &__name {
    font-size: $hy-font-sm;
    color: $hy-text-primary;
  }

  &__meta {
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 今日数据 ---------- */
.stats {
  padding: 14px 16px;
  background-color: $hy-color-primary-lighter;
  border-radius: $hy-radius-md;

  &__title {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__row {
    margin-top: 10px;
    display: flex;
  }

  &__item {
    flex: 1;
    display: flex;
    flex-direction: column;
  }

  &__value {
    font-size: $hy-font-xl;
    font-weight: 700;
    color: $hy-color-primary;
  }

  &__label {
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}
</style>
