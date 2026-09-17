<template>
  <AppShell active-nav="">
    <!-- ==================== 搜索框 ==================== -->
    <view class="searchbar">
      <input
        v-model="keyword"
        class="searchbar__input"
        type="text"
        placeholder="搜索帖子标题或内容"
        placeholder-class="searchbar__placeholder"
        confirm-type="search"
        data-testid="search-input"
        @confirm="onSearch"
      />
      <text v-if="keyword" class="searchbar__clear" data-testid="search-clear" @click="onClear">✕</text>
      <view class="searchbar__btn" data-testid="search-submit" @click="onSearch">
        <text class="searchbar__btn-text">搜索</text>
      </view>
    </view>

    <!--
      结果统计。
      只在**搜索过之后**显示：一进页面就写"共 0 条"会让人以为搜索已经执行过且没有结果，
      而实际上用户还没输入任何东西。
    -->
    <view v-if="searched && !loading && !error" class="summary" data-testid="search-summary">
      <text class="summary__text">关键词「{{ submittedKeyword }}」共 {{ total }} 条结果</text>
    </view>

    <ListState
      :loading="loading"
      :error="error"
      :empty="searched && posts.length === 0"
      empty-text="没有找到相关帖子，换个关键词试试"
      loading-text="正在搜索…"
      testid-base="search-state"
      @retry="onSearch"
    />

    <view v-if="posts.length" data-testid="search-feed">
      <PostCard v-for="post in posts" :key="post.id" :post="post" />
    </view>

    <!-- 分页：与首页同口径（满页判定，不看 total） -->
    <view v-if="posts.length" class="more">
      <view v-if="hasMore" class="more__btn" data-testid="search-load-more" @click="loadMore">
        <text class="more__btn-text">{{ loadingMore ? '加载中…' : '加载更多' }}</text>
      </view>
      <text v-else class="more__end" data-testid="search-list-end">没有更多了</text>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 搜索页。
 *
 * 契约：`GET /api/posts/search?keyword&page&size`，**`keyword` 必填**（契约里 `required: true`）。
 *
 * ==========================================================================
 * 为什么"没输入就点搜索"不发请求
 * ==========================================================================
 * 契约说缺 `keyword` 是 `400`，前端完全可以照发然后弹"提交的内容有误"。
 * 但不发更好，两条理由：
 * 1. **"没输入就点搜索"是正常操作**，不是错误操作 —— 拿一个 400 去惩罚它，
 *    用户会以为系统坏了（口径 2 要求按码给出准确文案，而这里根本不该有码）；
 * 2. 少一次必然失败的往返，弱网下体感明显。
 * 因此空关键词在**页面层与 API 层各拦一道**（`api/posts.ts` 的 `searchPosts` 也拦）。
 *
 * ==========================================================================
 * 两个状态为什么要分开存
 * ==========================================================================
 * `keyword` 是输入框里的**实时值**，`submittedKeyword` 是**已提交**的关键词。
 * 共用一个变量的话，用户改了输入框但还没点搜索时，统计文案（"关键词「新词」共 N 条"）
 * 就会和列表内容（旧词的结果）对不上。
 */
import { ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import AppShell from '@/components/shell/AppShell.vue'
import ListState from '@/components/ListState.vue'
import PostCard from '@/components/PostCard.vue'
import { searchPosts } from '@/api/posts'
import { ApiError, PAGE_SIZE_MAX } from '@/utils/request'
import { toPostCard, type PostCardView } from '@/utils/postView'

/** 输入框里的实时值 */
const keyword = ref('')
/** 已提交的关键词（统计文案与"是否搜索过"的判定都用它） */
const submittedKeyword = ref('')

const posts = ref<PostCardView[]>([])
const total = ref(0)

const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
/** 是否已经执行过至少一次搜索 */
const searched = ref(false)
const hasMore = ref(false)
const page = ref(1)

onLoad((options) => {
  // 允许从别处带着关键词进来（顶栏目前不带，但链接可分享）
  const kw = options?.keyword
  if (kw) {
    keyword.value = decodeURIComponent(String(kw))
    void onSearch()
  }
})

/** 追加一页。`hasMore` 用**满页判定**（理由见首页同名函数） */
function appendPage(batch: PostCardView[], requestedPage: number, totalCount: number): void {
  posts.value = requestedPage === 1 ? batch : posts.value.concat(batch)
  hasMore.value = batch.length >= PAGE_SIZE_MAX
  page.value = requestedPage
  total.value = totalCount
}

async function onSearch(): Promise<void> {
  const kw = keyword.value.trim()
  if (!kw) {
    // 空关键词：不发请求，只提示（见文件头说明）
    uni.showToast({ title: '请输入搜索关键词', icon: 'none' })
    return
  }

  loading.value = true
  error.value = ''
  submittedKeyword.value = kw
  searched.value = true
  try {
    const res = await searchPosts(kw, 1)
    appendPage(res.list.map(toPostCard), 1, res.total)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '搜索失败，请稍后重试'
    posts.value = []
    hasMore.value = false
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const next = page.value + 1
    const res = await searchPosts(submittedKeyword.value, next)
    // 翻页时不更新 total：用第 1 页的值更稳定（每页都返回同一个 total，没必要反复覆盖）
    appendPage(res.list.map(toPostCard), next, total.value)
  } catch (e) {
    // 局部失败不惩罚已显示的结果（与首页同口径）
    uni.showToast({
      title: e instanceof ApiError ? e.message : '加载失败，请稍后重试',
      icon: 'none',
      duration: 2000,
    })
  } finally {
    loadingMore.value = false
  }
}

/** 清空：输入框、已提交关键词、结果、统计一起回到初始态 */
function onClear(): void {
  keyword.value = ''
  submittedKeyword.value = ''
  posts.value = []
  total.value = 0
  hasMore.value = false
  page.value = 1
  searched.value = false
  error.value = ''
}

onReachBottom(() => {
  void loadMore()
})
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

/* ---------- 搜索框 ---------- */
.searchbar {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  margin-bottom: $hy-shell-gap;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;

  &__input {
    flex: 1;
    min-width: 0;
    height: 40px;
    padding: 0 16px;
    font-size: $hy-font-md;
    color: $hy-text-primary;
    background-color: $hy-bg-page;
    border-radius: $hy-radius-pill;
  }

  &__placeholder {
    color: $hy-text-placeholder;
    font-size: $hy-font-md;
  }

  &__clear {
    margin-left: 10px;
    padding: 0 4px;
    font-size: $hy-font-md;
    color: $hy-text-placeholder;
  }

  &__btn {
    margin-left: 10px;
    height: 40px;
    padding: 0 20px;
    display: flex;
    align-items: center;
    background-color: $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__btn-text {
    font-size: $hy-font-md;
    color: $hy-text-inverse;
  }
}

/* ---------- 结果统计 ---------- */
.summary {
  padding: 0 4px 12px;

  &__text {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
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

@media (max-width: $hy-shell-breakpoint) {
  .searchbar {
    padding: 10px 12px;

    &__btn {
      padding: 0 16px;
    }
  }
}
</style>
