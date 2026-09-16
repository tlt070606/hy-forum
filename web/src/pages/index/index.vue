<template>
  <view class="page">
    <!--
      顶部搜索框。
      ⚠️ 用 `view` 而不是 `uni-input` 做"假输入框"：真实输入发生在
        `pages/search/index`。这样首页不会因为输入法弹起而抖动，
        也避免"在首页输入了却没反应"的困惑（点哪都是跳搜索页）。
    -->
    <view class="searchbar" data-testid="home-search" @click="goSearch">
      <text class="searchbar__icon">🔍</text>
      <text class="searchbar__placeholder">搜索帖子标题或内容</text>
    </view>

    <!-- ================= 版块宫格 ================= -->
    <view class="section">
      <view class="section__head">
        <text class="section__title">版块</text>
      </view>

      <HyState
        :loading="boardsLoading"
        :error="boardsError"
        :empty="boards.length === 0"
        empty-text="暂无版块"
        loading-text="正在加载版块…"
        @retry="loadBoards"
      />

      <!--
        两列宫格（需求方 2026-09-16 定）。
        `isResource` 角标是**口径 9 的可见化**：用户从列表就能看出哪个版块需要填网盘信息，
        不必进到发帖页才发现表单多了两栏。
      -->
      <view v-if="boards.length" class="boards" data-testid="board-grid">
        <view
          v-for="board in boards"
          :key="board.id"
          class="board"
          data-testid="board-item"
          hover-class="board--hover"
          @click="goBoard(board)"
        >
          <view class="board__head">
            <text class="board__name" data-testid="board-name">{{ text(board.name) }}</text>
            <text v-if="bool(board.isResource)" class="board__tag" data-testid="board-resource-tag">
              资源
            </text>
          </view>
          <text class="board__desc">{{ text(board.description) }}</text>
          <text class="board__count">{{ num(board.postCount) }} 帖</text>
        </view>
      </view>
    </view>

    <!-- ================= 最新帖子 ================= -->
    <view class="section">
      <view class="section__head">
        <text class="section__title">最新帖子</text>
        <!--
          排序口径：首页固定 `latest`。
          更多排序（热门/精华）在版块页给，符合"首页看全站最新、版块页细看"的直觉。
        -->
        <text class="section__hint">按发布时间</text>
      </view>

      <HyState
        :loading="postsLoading"
        :error="postsError"
        :empty="postViews.length === 0"
        empty-text="还没有人发帖，点右下角「＋」发第一帖"
        loading-text="正在加载帖子…"
        @retry="loadPosts"
      />

      <view v-if="postViews.length" class="post-list" data-testid="home-post-list">
        <PostListItem v-for="item in postViews" :key="item.id" :item="item" />
      </view>
    </view>

    <!--
      悬浮发帖按钮（需求方 2026-09-16 定：放首页右下角，不动 tabBar）。
      tabBar 之上要留出高度：H5 端 tabBar 是固定的，贴太下会被盖住。
    -->
    <view class="fab" data-testid="home-compose" @click="goCompose">
      <text class="fab__plus">＋</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 首页：搜索入口 + 版块宫格 + 全站最新帖子流 + 悬浮发帖按钮。
 *
 * ==========================================================================
 * 为什么首页是"版块宫格 + 全站最新"，而不是《技术方案》§4.1 写的"关注/全部双流"
 * ==========================================================================
 * §4.1 的双流依赖 `GET /api/feed?type=follow|all`，而**该路径不在当前契约里**
 * （`openapi.json` 共 14 个路径，没有 `/api/feed`）。
 * 契约没有的接口前端不得编造（任务书 §2/§3），因此：
 * - 「全部流」用 `GET /api/posts`（不传 `boardId` = 全站）等价实现；
 * - 「关注流」**不做** —— 它同时依赖 `POST/DELETE /api/follow/{userId}`（也不在契约里），
 *   而且未登录时按 §8.5 本就该引导登录。等 M4 交付 `feed` 与 `follow` 后再补。
 * 这两条已写进交付报告，供 L1 判断归属。
 *
 * ==========================================================================
 * 数据加载策略
 * ==========================================================================
 * 版块与帖子**并行**请求、**各自独立**报错：版块挂了不该让帖子也看不见，
 * 反之亦然。所以不用一个 `loading`/`error` 覆盖两者（那样任一失败就是整页报错）。
 */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import HyState from '@/components/HyState.vue'
import PostListItem from '@/components/PostListItem.vue'
import { fetchBoards } from '@/api/boards'
import { fetchPosts } from '@/api/posts'
import type { BoardVO } from '@/api/types'
import { ApiError } from '@/utils/request'
import { bool, num, text, toListItem, type PostListItemView } from '@/utils/format'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

/* ---------------------------------------------------------------------------
 * 版块
 * ------------------------------------------------------------------------- */

const boards = ref<BoardVO[]>([])
const boardsLoading = ref(false)
const boardsError = ref('')

async function loadBoards(): Promise<void> {
  boardsLoading.value = true
  boardsError.value = ''
  try {
    boards.value = await fetchBoards()
  } catch (e) {
    /*
     * 错误文案一律取自 `ApiError.message`（`request` 层已按错误码表映射过，
     * 见 `utils/error-code.ts`），**不在这里重写一套文案** ——
     * 两处文案迟早会不一致，而错误码表是契约的一部分（口径 2：必须按码分支）。
     */
    boardsError.value = e instanceof ApiError ? e.message : '版块加载失败，请稍后重试'
  } finally {
    boardsLoading.value = false
  }
}

/* ---------------------------------------------------------------------------
 * 帖子
 * ------------------------------------------------------------------------- */

const posts = ref<PostListItemView[]>([])
const postsLoading = ref(false)
const postsError = ref('')

const postViews = computed(() => posts.value)

async function loadPosts(): Promise<void> {
  postsLoading.value = true
  postsError.value = ''
  try {
    // 不传 boardId = 全站；size 用契约硬上限 20（口径 5，`api/posts.ts` 内部已再截一刀）
    const page = await fetchPosts({ sort: 'latest', page: 1 })
    posts.value = page.list.map(toListItem)
  } catch (e) {
    postsError.value = e instanceof ApiError ? e.message : '帖子加载失败，请稍后重试'
  } finally {
    postsLoading.value = false
  }
}

/* ---------------------------------------------------------------------------
 * 生命周期与导航
 * ------------------------------------------------------------------------- */

/**
 * 用 `onShow` 而不是 `onLoad`：从详情页返回时 `onLoad` **不会**再触发，
 * 于是刚发完帖/删完帖回到首页看到的还是旧列表。`onShow` 每次回到本页都刷新，
 * 这是列表页的正确语义（也让"发帖成功 → 返回首页能看到"这条验收路径成立）。
 */
onShow(() => {
  void loadBoards()
  void loadPosts()
})

function goSearch(): void {
  uni.navigateTo({ url: '/pages/search/index' })
}

function goBoard(board: BoardVO): void {
  // 版块 id 缺失时不该跳到一个异常页面，直接忽略（契约里 id 可选，实际后端都返回）
  const id = num(board.id)
  if (!id) return
  uni.navigateTo({ url: `/pages/board/index?id=${id}` })
}

/**
 * 发帖入口。
 *
 * 未登录 → **跳登录页**（口径 4 / 《技术方案》§8.5「未登录用户点击关注流时引导登录」同源）。
 * 为什么不在这里弹 toast：能跳转就不要只提示，用户的目标是发帖，引导他去能完成目标的页面。
 *
 * ⚠️ 仅做**前端引导**，真正的准入判定始终在后端（`POST /api/posts` 需要登录）。
 */
function goCompose(): void {
  if (!auth.isLoggedIn) {
    uni.navigateTo({ url: '/pages/auth/index?mode=login' })
    return
  }
  uni.navigateTo({ url: '/pages/post/edit' })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.page {
  min-height: 100vh;
  /* 底部留白：让悬浮按钮不压住最后一屏内容（按钮自身高约 110rpx） */
  padding-bottom: 160rpx;
  box-sizing: border-box;
}

/* ---------- 搜索框 ---------- */
.searchbar {
  margin: $hy-space-md;
  padding: 0 $hy-space-md;
  height: 72rpx;
  display: flex;
  align-items: center;
  background-color: $hy-bg-card;
  border: 1rpx solid $hy-border-color;
  border-radius: 36rpx;

  &__icon {
    margin-right: $hy-space-xs;
    font-size: $hy-font-sm;
  }

  &__placeholder {
    font-size: $hy-font-sm;
    color: $hy-text-placeholder;
  }
}

/* ---------- 分区 ---------- */
.section {
  margin-bottom: $hy-space-md;

  &__head {
    padding: 0 $hy-space-md $hy-space-sm;
    display: flex;
    align-items: baseline;
    justify-content: space-between;
  }

  &__title {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__hint {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 版块宫格 ---------- */
.boards {
  padding: 0 $hy-space-md;
  display: flex;
  flex-wrap: wrap;
  /* 用负 margin + 卡片 margin 做两列等宽间隙：
     兼容性比 gap 好（小程序基础库对 flex gap 支持较晚） */
  justify-content: space-between;
}

.board {
  /* 两列：50% 宽再减去一半的间隙（间隙 $hy-space-sm = 16rpx → 减 8rpx） */
  width: calc(50% - 8rpx);
  margin-bottom: $hy-space-sm;
  padding: $hy-space-sm $hy-space-md;
  box-sizing: border-box;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;

  &--hover {
    background-color: $hy-bg-hover;
  }

  &__head {
    display: flex;
    align-items: center;
  }

  &__name {
    flex: 1;
    min-width: 0;
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  /* 资源版块角标：`isResource === true` 时出现（口径 9） */
  &__tag {
    flex-shrink: 0;
    margin-left: $hy-space-xs;
    padding: 2rpx 10rpx;
    border-radius: $hy-radius-sm;
    font-size: $hy-font-xs;
    line-height: 1.6;
    color: $hy-text-inverse;
    background-color: $hy-color-success;
  }

  &__desc {
    display: block;
    margin-top: 4rpx;
    height: 32rpx;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__count {
    display: block;
    margin-top: 4rpx;
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }
}

/* ---------- 帖子列表 ---------- */
.post-list {
  background-color: $hy-bg-card;
}

/* ---------- 悬浮发帖按钮 ---------- */
.fab {
  position: fixed;
  /* 44px ≈ 88rpx：tabBar 的高度。加一点余量避免贴住 tabBar 上沿 */
  right: $hy-space-lg;
  bottom: 130rpx;
  z-index: 10;
  width: 104rpx;
  height: 104rpx;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: $hy-color-primary;
  /* 阴影让按钮从内容上"浮"起来：没有它，按钮压在白色卡片上边界会糊在一起 */
  box-shadow: 0 6rpx 20rpx rgba(43, 108, 255, 0.35);

  &__plus {
    font-size: 56rpx;
    line-height: 1;
    color: $hy-text-inverse;
  }
}
</style>
