<template>
  <AppShell active-nav="home" :post-total="postTotal">
    <!-- ==================== 发帖入口 ==================== -->
    <!--
      参考图首屏第一块是"分享你的想法..."。它**不是输入框，是发帖入口**（点了进发帖页）。
      做成真输入框会让人在这里写半天、然后发现发不出去。
    -->
    <view class="composer" data-testid="home-composer" @click="goCompose">
      <!--
        ⚠️ 未登录时**传空昵称**，让 Avatar 走"游客图标"分支。
           不能传 `auth.displayName` —— 它的兜底值是「未登录」，会被字母头像取首字
           渲染成一个孤零零的「未」字，看起来像数据错了。（本轮截图自检时抓到。）
      -->
      <Avatar
        :url="auth.user?.avatarUrl || ''"
        :nickname="auth.isLoggedIn ? auth.displayName : ''"
        :size="40"
      />
      <view class="composer__box">
        <text class="composer__placeholder">分享你的想法...</text>
      </view>
    </view>

    <!-- ==================== 排序 ==================== -->
    <!--
      排序口径：契约 `GET /api/posts` 的 `sort`，取值 `latest | hot | essence`
      （《技术方案》§6.5）。⚠️ 契约里 `sort` **没有 enum**，这组取值只能来自文档
      —— 已作为契约改进建议登记进交付报告。
      参考图只有"最新/精选"两档；我们给三档，因为契约支持三档，藏起"热门"没有理由。
    -->
    <view class="sortbar" data-testid="home-sort">
      <view
        v-for="opt in POST_SORT_OPTIONS"
        :key="opt.value"
        class="sortbar__item"
        :class="{ 'sortbar__item--active': sort === opt.value }"
        :data-testid="`home-sort-${opt.value}`"
        @click="changeSort(opt.value)"
      >
        <text class="sortbar__text">{{ opt.label }}</text>
      </view>
    </view>

    <!-- ==================== 状态占位 ==================== -->
    <!--
      ⚠️ `loading` 传的是 `loading && posts.length === 0`，不是裸的 `loading`：
      刷新时旧数据还在，若让 ListState 显示"正在加载…"，它会与下面仍在渲染的列表**同时出现**。
      只在"没有任何内容可显示"时才让它接管版面。
    -->
    <ListState
      :loading="loading && posts.length === 0"
      :error="error"
      :empty="posts.length === 0"
      empty-text="这里还没有帖子"
      loading-text="正在加载…"
      testid-base="home-state"
      @retry="load"
    />

    <!-- ==================== 信息流 ==================== -->
    <view v-if="posts.length" data-testid="home-feed">
      <PostCard v-for="post in posts" :key="post.id" :post="post" />
    </view>

    <!--
      分页。`hasMore` 用**满页判定**而不是 `total`：
      `shape.ts` 的 `expectPage` 在 `total` 缺失时会退化成"本页条数"，
      此时 `list.length < total` 恒为 false → 永远不显示"加载更多"。
    -->
    <view v-if="posts.length" class="more">
      <view v-if="hasMore" class="more__btn" data-testid="home-load-more" @click="loadMore">
        <text class="more__btn-text">{{ loadingMore ? '加载中…' : '加载更多' }}</text>
      </view>
      <text v-else class="more__end" data-testid="home-list-end">没有更多了</text>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 首页：发帖入口 + 排序 + 全站信息流（三栏外壳）。
 *
 * ==========================================================================
 * 真 / 假数据的边界（需求方 2026-09-16 定的口径：真接口优先，无契约才用假数据）
 * ==========================================================================
 * **真**（走契约里真实存在的端点）：
 * - 信息流 → `GET /api/posts`（不传 `boardId` = 全站），带 `sort`
 * - 左栏「今日数据」的帖子总数 → 同一响应的 `total`
 * - 登录态 / 头像 / 昵称 → `stores/auth`（底层 `GET /api/user/me`）
 *
 * **假**（无表、无端点，见 `mock/hotContent.ts`）：
 * - 右栏：热门话题榜 / 推荐关注 / 热门活动
 * - 左栏：活跃用户数；顶栏：通知未读数（通知接口属 M5）
 *
 * **未交付并明确提示**（不做假成功）：
 * - 点赞 / 收藏 / 举报 → 接口属 M4/M5，卡片上只显示计数，不做成可点的假按钮
 */
import { ref } from 'vue'
import { onReachBottom, onShow } from '@dcloudio/uni-app'
import AppShell from '@/components/shell/AppShell.vue'
import Avatar from '@/components/Avatar.vue'
import PostCard from '@/components/PostCard.vue'
import ListState from '@/components/ListState.vue'
import { fetchPosts } from '@/api/posts'
import { POST_SORT_OPTIONS, type PostSort } from '@/api/types'
import { ApiError, PAGE_SIZE_MAX } from '@/utils/request'
import { toPostCard, type PostCardView } from '@/utils/postView'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const posts = ref<PostCardView[]>([])
/**
 * 帖子总数（左栏「今日数据」用）。
 * `null` = **还不知道**（加载中或失败）→ 界面显示 `—`。
 * 不用 `0` 兜底：那会把"没请求到"伪装成"站点一篇帖子都没有"。
 */
const postTotal = ref<number | null>(null)

const sort = ref<PostSort>('latest')
const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const hasMore = ref(false)
const page = ref(1)

/**
 * 用 `onShow` 而不是 `onLoad`：从详情页返回时 `onLoad` **不会**再触发，
 * 于是刚发完帖回来看到的还是旧列表。`onShow` 每次回到本页都刷新 ——
 * 这是列表页的正确语义，也让"发帖成功 → 返回首页能看到"这条路径成立。
 */
onShow(() => {
  void load()
})

/** 重新加载第 1 页（进入页面、切换排序、点重试都走这里） */
async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const res = await fetchPosts({ sort: sort.value, page: 1 })
    posts.value = res.list.map(toPostCard)
    postTotal.value = res.total
    hasMore.value = res.list.length >= PAGE_SIZE_MAX
    page.value = 1
  } catch (e) {
    /*
     * 错误文案一律取自 `ApiError.message`（`request` 层已按错误码表映射过，
     * 见 `utils/error-code.ts`），**不在这里重写一套** ——
     * 两处文案迟早不一致，而错误码表是契约的一部分（口径 2：必须按码分支）。
     */
    error.value = e instanceof ApiError ? e.message : '帖子加载失败，请稍后重试'
    posts.value = []
    hasMore.value = false
    // 失败时把总数置为"未知"而不是沿用上一次的值：
    // 列表已清空，还留着一个和空列表矛盾的"共 N 条"更让人困惑
    postTotal.value = null
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const next = page.value + 1
    const res = await fetchPosts({ sort: sort.value, page: next })
    posts.value = posts.value.concat(res.list.map(toPostCard))
    hasMore.value = res.list.length >= PAGE_SIZE_MAX
    page.value = next
  } catch (e) {
    /*
     * 加载更多失败**不覆盖已加载的内容**，只 toast。
     * 走 error 分支会把整页换成错误态、用户已看到的内容全没了 ——
     * 对"翻页失败"这种局部失败是过度惩罚。
     */
    uni.showToast({
      title: e instanceof ApiError ? e.message : '加载失败，请稍后重试',
      icon: 'none',
      duration: 2000,
    })
  } finally {
    loadingMore.value = false
  }
}

function changeSort(next: PostSort): void {
  if (sort.value === next) return
  sort.value = next
  void load()
}

/**
 * 发帖入口。
 *
 * 未登录 → **跳登录页**（口径 4 / §8.5「未登录时引导登录」同源）。
 * 不在这里弹 toast：能跳转就不要只提示 —— 用户的目标是发帖，
 * 直接把他送到能完成目标的页面。
 */
function goCompose(): void {
  if (!auth.isLoggedIn) {
    uni.navigateTo({ url: '/pages/auth/index?mode=login' })
    return
  }
  uni.navigateTo({ url: '/pages/post/edit' })
}

/** 触底自动加载（与「加载更多」按钮并存：按钮保证可发现性，触底保证连贯性） */
onReachBottom(() => {
  void loadMore()
})
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

/* ---------- 发帖入口 ---------- */
.composer {
  display: flex;
  align-items: center;
  padding: 16px;
  margin-bottom: $hy-shell-gap;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;

  &__box {
    flex: 1;
    min-width: 0;
    margin-left: 12px;
    height: 40px;
    padding: 0 16px;
    display: flex;
    align-items: center;
    background-color: $hy-bg-page;
    border-radius: $hy-radius-pill;
  }

  &__placeholder {
    font-size: $hy-font-md;
    color: $hy-text-placeholder;
  }
}

/* ---------- 排序条 ---------- */
.sortbar {
  display: flex;
  align-items: center;
  padding: 0 8px;
  margin-bottom: $hy-shell-gap;
  height: 46px;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;

  &__item {
    flex: 1;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    /* 选中态用下划线而不是背景块：三档并排时背景块会把版面压得很重 */
    border-bottom: 2px solid transparent;
    box-sizing: border-box;

    &--active {
      border-bottom-color: $hy-color-primary;

      .sortbar__text {
        color: $hy-color-primary;
        font-weight: 600;
      }
    }
  }

  &__text {
    font-size: $hy-font-md;
    color: $hy-text-regular;
  }
}

/* ---------- 分页 ---------- */
.more {
  padding: 4px 0 16px;
  display: flex;
  justify-content: center;

  &__btn {
    height: 36px;
    padding: 0 28px;
    display: flex;
    align-items: center;
    background-color: $hy-bg-card;
    border-radius: $hy-radius-pill;
    box-shadow: $hy-shadow-card;
  }

  &__btn-text {
    font-size: $hy-font-sm;
    color: $hy-color-primary;
  }

  &__end {
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }
}
</style>
