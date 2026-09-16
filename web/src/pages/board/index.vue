<template>
  <view class="page">
    <!-- 版块头：名称与简介来自 `GET /api/boards` 的同一条记录 -->
    <view v-if="board" class="board-head" data-testid="board-head">
      <view class="board-head__row">
        <text class="board-head__name" data-testid="board-title">{{ text(board.name) }}</text>
        <text v-if="bool(board.isResource)" class="board-head__tag">资源版块</text>
      </view>
      <text v-if="board.description" class="board-head__desc">{{ board.description }}</text>
      <!--
        资源版块的提示语：让用户在**列表页**就知道这里的帖子带网盘信息，
        而不是进详情才意外发现有提取码。与发帖页的字段可见性判断同源（口径 9）。
      -->
      <text v-if="bool(board.isResource)" class="board-head__hint">
        本版块的帖子可附网盘链接与提取码
      </text>
    </view>

    <!-- 排序切换：latest / hot / essence（依据《技术方案》§6.5） -->
    <view class="sortbar" data-testid="board-sort">
      <view
        v-for="opt in POST_SORT_LABELS"
        :key="opt.value"
        class="sortbar__item"
        :class="{ 'sortbar__item--active': sort === opt.value }"
        :data-testid="`board-sort-${opt.value}`"
        @click="changeSort(opt.value)"
      >
        <text class="sortbar__text">{{ opt.label }}</text>
      </view>
    </view>

    <HyState
      :loading="loading"
      :error="error"
      :empty="!loading && posts.length === 0"
      empty-text="这个版块还没有帖子"
      loading-text="正在加载帖子…"
      @retry="reload"
    />

    <view v-if="posts.length" class="post-list" data-testid="board-post-list">
      <PostListItem v-for="item in posts" :key="item.id" :item="item" />
    </view>

    <!--
      分页：底部给出明确的"更多"状态。
      ⚠️ `hasMore` 的判定**不看 `total`** —— 见下方 `appendPage` 的说明。
    -->
    <view v-if="posts.length" class="more">
      <wd-button
        v-if="hasMore"
        type="info"
        size="small"
        plain
        :loading="loadingMore"
        data-testid="board-load-more"
        @click="loadMore"
      >
        加载更多
      </wd-button>
      <text v-else class="more__end" data-testid="board-list-end">没有更多了</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 版块页：某版块的帖子列表（支持 最新 / 热门 / 精华 排序 + 分页）。
 *
 * 契约：`GET /api/posts?boardId&sort&page&size`
 *
 * ==========================================================================
 * 为什么版块名要从 `GET /api/boards` 里查，而不是单独请求一个"版块详情"
 * ==========================================================================
 * **契约里没有 `GET /api/boards/{id}`**（14 个路径只有 `GET /api/boards`）。
 * 契约没有的接口前端不得编造（任务书 §2/§3），因此复用版块列表 ——
 * 版块是少量数据（§4.3 默认 7 个），一次全量拉取后按 id 定位即可，
 * 代价只是多一次轻量请求，换来"零契约编造"。
 *
 * ==========================================================================
 * 排序参数的契约缺口
 * ==========================================================================
 * 契约里 `sort` 是裸 `string`（**没有 enum**），取值 `latest|hot|essence` 只能来自
 * 《技术方案》§6.5。前端用 `PostSort` 联合类型收窄，缺口已登记进交付报告。
 */
import { ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import HyState from '@/components/HyState.vue'
import PostListItem from '@/components/PostListItem.vue'
import { fetchBoards, findBoard } from '@/api/boards'
import { fetchPosts } from '@/api/posts'
import { PAGE_SIZE_MAX } from '@/utils/request'
import { ApiError } from '@/utils/request'
import { POST_SORT_LABELS, type BoardVO, type PostSort } from '@/api/types'
import { bool, num, text, toListItem, type PostListItemView } from '@/utils/format'

/** 版块 id（来自路由 query） */
const boardId = ref(0)
const board = ref<BoardVO | null>(null)

const posts = ref<PostListItemView[]>([])
const sort = ref<PostSort>('latest')

const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')

/** 是否还有下一页 */
const hasMore = ref(false)
/** 当前已加载到第几页（从 1 开始） */
const page = ref(1)

onLoad((options) => {
  /*
   * 路由参数是**字符串**（uni-app 的 query 一律 string），必须显式转数字。
   * `Number('abc')` → NaN，此时不请求并发错 —— 与后端 `H12`（路径变量类型不匹配
   * 应返回 400 而不是 500）是同一类问题的前端侧防线：**别把非法 id 发出去**。
   */
  const raw = options?.id
  const parsed = Number(raw)
  if (!raw || Number.isNaN(parsed) || parsed <= 0) {
    error.value = '版块参数有误，请返回重试'
    return
  }
  boardId.value = parsed
  void loadBoard()
  void reload()
})

/** 版块信息（名称/简介/isResource）。失败只影响头部，不影响列表 —— 因此单独 catch */
async function loadBoard(): Promise<void> {
  try {
    const all = await fetchBoards()
    board.value = findBoard(all, boardId.value)
    /*
     * 找不到版块：**不静默留白**。管理员删掉版块后，旧链接会落到这里，
     * 用户需要知道"这个版块不存在"，而不是看着一个没有标题的列表。
     */
    if (!board.value) error.value = '版块不存在或已被删除'
  } catch (e) {
    console.warn('[board] 版块信息加载失败', e)
  }
}

/**
 * 追加一页结果。
 *
 * ⚠️ **`hasMore` 不用 `total` 判断**，而是"本页是否满页"：
 *    `shape.ts` 的 `expectPage()` 在 `total` 缺失时会退化成"本页条数"，
 *    此时 `list.length < total` 恒为 false → 永远不显示"加载更多"，
 *    表现为"列表只有 20 条且没有翻页入口"这种难查的现象。
 *    满页判定与 `total` 无关，是 offset 分页下更稳的口径。
 */
function appendPage(batch: PostListItemView[], requestedPage: number): void {
  posts.value = requestedPage === 1 ? batch : posts.value.concat(batch)
  hasMore.value = batch.length >= PAGE_SIZE_MAX
  page.value = requestedPage
}

/** 重新加载第 1 页（进入页面、切换排序、点重试都走这里） */
async function reload(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const res = await fetchPosts({ boardId: boardId.value, sort: sort.value, page: 1 })
    appendPage(res.list.map(toListItem), 1)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '帖子加载失败，请稍后重试'
    // 失败时清空列表：留着上一版排序的数据会让人以为"切换排序没生效"
    posts.value = []
    hasMore.value = false
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const next = page.value + 1
    const res = await fetchPosts({ boardId: boardId.value, sort: sort.value, page: next })
    appendPage(res.list.map(toListItem), next)
  } catch (e) {
    /*
     * 加载更多失败**不覆盖已加载的内容**，只 toast 提示。
     * 若这里走 error 分支，整页会被替换成一个错误态，用户已经看到的内容全没了 ——
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

/** 切换排序：回到第 1 页重新加载 */
function changeSort(next: PostSort): void {
  if (sort.value === next) return
  sort.value = next
  void reload()
}

/** 触底自动加载（与「加载更多」按钮并存：按钮保证可发现性，触底保证连贯性） */
onReachBottom(() => {
  void loadMore()
})
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.page {
  min-height: 100vh;
  padding-bottom: $hy-space-xl;
  box-sizing: border-box;
}

/* ---------- 版块头 ---------- */
.board-head {
  padding: $hy-space-md;
  background-color: $hy-bg-card;
  border-bottom: 1rpx solid $hy-border-color;

  &__row {
    display: flex;
    align-items: center;
  }

  &__name {
    font-size: $hy-font-xl;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__tag {
    margin-left: $hy-space-sm;
    padding: 2rpx 12rpx;
    border-radius: $hy-radius-sm;
    font-size: $hy-font-xs;
    color: $hy-text-inverse;
    background-color: $hy-color-success;
  }

  &__desc {
    display: block;
    margin-top: $hy-space-xs;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
    line-height: 1.6;
  }

  &__hint {
    display: block;
    margin-top: $hy-space-xs;
    font-size: $hy-font-xs;
    color: $hy-color-warning;
  }
}

/* ---------- 排序条 ---------- */
.sortbar {
  display: flex;
  align-items: center;
  padding: $hy-space-sm $hy-space-md;
  background-color: $hy-bg-card;
  border-bottom: 1rpx solid $hy-border-color;

  &__item {
    margin-right: $hy-space-lg;
    padding-bottom: 4rpx;
    /* 未选中态透明边框占位：避免选中时文字因加粗/加边框而位移 */
    border-bottom: 4rpx solid transparent;

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

.post-list {
  background-color: $hy-bg-card;
}

/* ---------- 分页 ---------- */
.more {
  padding: $hy-space-lg;
  display: flex;
  justify-content: center;

  &__end {
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }
}
</style>
