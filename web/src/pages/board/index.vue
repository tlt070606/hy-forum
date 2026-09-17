<template>
  <AppShell active-nav="">
    <!-- ==================== 版块头 ==================== -->
    <view v-if="board" class="board-head" data-testid="board-head">
      <view class="board-head__row">
        <text class="board-head__name" data-testid="board-title">{{ text(board.name) }}</text>
        <text v-if="bool(board.isResource)" class="board-head__tag" data-testid="board-resource-tag">
          资源版块
        </text>
      </view>
      <text v-if="board.description" class="board-head__desc">{{ board.description }}</text>
      <!--
        资源版块的提示：让用户在**列表页**就知道这里的帖子带网盘信息，
        而不是进详情才意外发现有提取码。与发帖页的字段可见性判断同源（口径 9）。
      -->
      <text v-if="bool(board.isResource)" class="board-head__hint">
        本版块的帖子可附网盘链接与提取码
      </text>
    </view>

    <!-- ==================== 排序 ==================== -->
    <view class="sortbar" data-testid="board-sort">
      <view
        v-for="opt in POST_SORT_OPTIONS"
        :key="opt.value"
        class="sortbar__item"
        :class="{ 'sortbar__item--active': sort === opt.value }"
        :data-testid="`board-sort-${opt.value}`"
        @click="changeSort(opt.value)"
      >
        <text class="sortbar__text">{{ opt.label }}</text>
      </view>
    </view>

    <ListState
      :loading="loading"
      :error="error"
      :empty="posts.length === 0"
      empty-text="这个版块还没有帖子"
      loading-text="正在加载帖子…"
      testid-base="board-state"
      @retry="reload"
    />

    <view v-if="posts.length" data-testid="board-feed">
      <PostCard v-for="post in posts" :key="post.id" :post="post" />
    </view>

    <view v-if="posts.length" class="more">
      <view v-if="hasMore" class="more__btn" data-testid="board-load-more" @click="loadMore">
        <text class="more__btn-text">{{ loadingMore ? '加载中…' : '加载更多' }}</text>
      </view>
      <text v-else class="more__end" data-testid="board-list-end">没有更多了</text>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 版块页：某版块的帖子列表（支持 最新 / 热门 / 精选 + 分页）。
 *
 * 契约：`GET /api/posts?boardId&sort&page&size`
 *
 * ==========================================================================
 * 版块名为什么要从 `GET /api/boards` 里查
 * ==========================================================================
 * **契约里没有 `GET /api/boards/{id}`**（14 个路径里只有 `GET /api/boards`）。
 * 契约没有的接口前端不得编造，所以复用版块列表：版块是少量数据（§4.3 默认 7 个），
 * 一次全量拉取后按 id 定位即可 —— 代价是多一次轻量请求，换来"零契约编造"。
 *
 * ==========================================================================
 * 版块信息与帖子列表**分开报错**
 * ==========================================================================
 * 版块信息挂了只影响头部，列表照样应该能看 —— 所以两个请求各自 catch，
 * 不共用一个 loading/error（共用会导致任一失败就是整页错误态）。
 */
import { ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import AppShell from '@/components/shell/AppShell.vue'
import ListState from '@/components/ListState.vue'
import PostCard from '@/components/PostCard.vue'
import { fetchBoards, findBoard } from '@/api/boards'
import { fetchPosts } from '@/api/posts'
import { POST_SORT_OPTIONS, type BoardVO, type PostSort } from '@/api/types'
import { ApiError, PAGE_SIZE_MAX } from '@/utils/request'
import { bool, num, text, toPostCard, type PostCardView } from '@/utils/postView'

const boardId = ref(0)
const board = ref<BoardVO | null>(null)

const posts = ref<PostCardView[]>([])
const sort = ref<PostSort>('latest')

const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
const hasMore = ref(false)
const page = ref(1)

onLoad((options) => {
  /*
   * 路由参数是**字符串**，必须显式转数字。
   * 非法 id **不发请求**：直接给出错误文案并发错 —— 与后端 H12（路径变量类型不匹配）
   * 是同一类问题的前端侧防线：别把非法 id 发出去。
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

async function loadBoard(): Promise<void> {
  try {
    const all = await fetchBoards()
    board.value = findBoard(all, boardId.value)
    /*
     * 找不到版块时不静默留白：管理员删掉版块后，旧链接会落到这里，
     * 用户需要知道"这个版块不存在"，而不是看着一个没有标题的列表。
     */
    if (!board.value) error.value = '版块不存在或已被删除'
  } catch (e) {
    console.warn('[board] 版块信息加载失败', e)
  }
}

/** 追加一页。`hasMore` 用**满页判定**（理由见首页同名函数） */
function appendPage(batch: PostCardView[], requestedPage: number): void {
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
    appendPage(res.list.map(toPostCard), 1)
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
    appendPage(res.list.map(toPostCard), next)
  } catch (e) {
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
  void reload()
}

onReachBottom(() => {
  void loadMore()
})
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

/* ---------- 版块头 ---------- */
.board-head {
  padding: 16px;
  margin-bottom: $hy-shell-gap;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;

  &__row {
    display: flex;
    align-items: center;
  }

  &__name {
    font-size: $hy-font-xl;
    font-weight: 700;
    color: $hy-text-primary;
  }

  &__tag {
    margin-left: 10px;
    padding: 1px 10px;
    font-size: $hy-font-xs;
    color: $hy-text-inverse;
    background-color: $hy-color-success;
    border-radius: $hy-radius-pill;
  }

  &__desc {
    display: block;
    margin-top: 6px;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
    line-height: 1.6;
  }

  &__hint {
    display: block;
    margin-top: 6px;
    font-size: $hy-font-xs;
    color: $hy-color-warning;
  }
}

/* ---------- 排序条（与首页同款） ---------- */
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
