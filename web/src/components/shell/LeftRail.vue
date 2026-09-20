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

    <!-- ============ 今日数据 ============ -->
    <!--
      真/假边界（需求方 2026-09-18 裁定：**假数据一律去掉**）：
      - 帖子总数 → **真**：来自 `GET /api/posts` 的 `total`，由父页面传进来
      - 原来这里还有一个「活跃用户」→ **已删除**：它是写死的 8，而契约里
        **没有任何**行为/统计接口能把它做成真的（登录时间、发帖活跃度都没有接口）。
        假的就删掉，不做半真半假的卡片。

      ⚠️ 整块**只在知道帖子总数时渲染**。详情页不请求列表接口，拿不到 `total`，
         若照旧渲染就会出现「— / 帖子总数」这种看着像坏掉的东西。
         宁可少一块，也不要显示一个我们并不知道的数 —— 这与 M2 定下的
         「不把环境故障伪装成业务状态」是同一条口径。
    -->
    <view v-if="hasPostTotal" class="stats" data-testid="today-stats">
      <text class="stats__title">今日数据</text>
      <view class="stats__row">
        <view class="stats__item">
          <text class="stats__value" data-testid="stats-post-total">{{ postTotalText }}</text>
          <text class="stats__label">帖子总数</text>
        </view>
        <!--
          原来这里还有一个「活跃用户」。它是**假数据**（8），而且契约里**没有任何**
          行为/统计接口能把它做成真的（登录时间、发帖活跃度都没有接口）——
          按需求方"换成有后端接口的"这条口径，**假的就去掉**，不做半真半假的卡片。
          如果 L1 将来加了统计接口，这里可以再补回来。
        -->
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
import { num } from '@/utils/postView'

const props = withDefaults(
  defineProps<{
    /**
     * 当前激活的导航项。
     * ⚠️ 含 `'me'`：它是**底部导航**才有的项（「我的」→ M2 的 pages/me/index）。
     *    左栏没有对应条目，因此传 `'me'` 时左栏不高亮任何一项 —— 这是正确行为，
     *    而不是类型不完备（共用外壳要能把同一个值转发给左栏和底部导航两处）。
     * `''` = 不高亮任何一项（搜索页 / 版块页 / 详情页都用它）。
     */
    active?: 'home' | 'boards' | 'collect' | 'me' | 'notifications' | ''
    /**
     * 帖子总数（真实数据，来自 `GET /api/posts` 的 `total`）。
     * `null` = **还不知道**（请求中 / 失败 / 该页面根本不请求列表，例如详情页）
     * → 整块「今日数据」**不渲染**，而不是显示 `0` 或 `—`。
     */
    postTotal?: number | null
  }>(),
  { active: 'home', postTotal: null }
)

interface NavItem {
  key: 'home' | 'boards' | 'collect'
  label: string
  desc: string
  icon: IconType
  url: string | null
}

const NAV_ITEMS: readonly NavItem[] = [
  /*
   * ⚠️ 「首页」**必须有 url**（原来写的是 `null`）。
   * 旧逻辑是"`url === null` = 当前页，不跳"，而那条假设**只在首页成立**：
   * 左栏是全局外壳的一部分，**在版块页/详情页也显示**，而「首页」的 url 恒为 null
   * → 于是在任何非首页点「首页」都**毫无反应**，用户只能去点「返回」（需求方实测反馈）。
   * 正确做法：把它当成一个真实目的地，**是不是"当前页"由 `active` 判断**（见 `onNav`）。
   */
  { key: 'home', label: '首页', desc: '全部最新帖子', icon: 'home', url: '/pages/index/index' },
  /* 2026-09-18 需求方裁定：'话题' 去掉（占位页、无表无接口），换成有真接口的 '版块' */
  { key: 'boards', label: '版块', desc: '按版块浏览帖子', icon: 'hash', url: '/pages/boards/index' },
  { key: 'collect', label: '收藏', desc: '我的收藏', icon: 'bookmark', url: '/pages/collect/index' },
] as const




const postTotalText = computed(() => String(num(props.postTotal)))

/** 是否知道帖子总数。`false` 时整块「今日数据」不渲染（见模板注释） */
const hasPostTotal = computed(() => props.postTotal !== null && props.postTotal !== undefined)

/**
 * 点导航项。
 *
 * 三条规则，顺序不能反：
 * 1. **已经在本页 → 什么都不做**（判据是 `active`，**不是** `url === null`：
 *    后者只在首页成立，见 `NAV_ITEMS` 上的注释）。
 *    对「首页」额外把页面滚回顶部 —— 这是列表类首页的常规行为，成本一行。
 * 2. **「首页」用 `reLaunch`**：它是根页面，用 `navigateTo` 会把返回栈越堆越深
 *    （首页 → 版块 → 版块详情 → 首页 → …），用户要按好几次返回才能退出。
 *    `reLaunch` 清掉整个栈，**这才是"点首页快速回去"该有的语义**。
 * 3. 其余入口用 `navigateTo`，保留"从哪来回哪去"的返回栈。
 */
function onNav(item: NavItem): void {
  if (!item.url) return

  if (item.key === props.active) {
    if (item.key === 'home') uni.pageScrollTo({ scrollTop: 0, duration: 200 })
    return
  }

  if (item.key === 'home') {
    uni.reLaunch({ url: item.url })
    return
  }

  uni.navigateTo({ url: item.url })
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
