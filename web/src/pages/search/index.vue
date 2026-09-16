<template>
  <view class="page">
    <!-- 搜索框：真实输入发生在这里（首页那根是"假输入框"，点击才跳过来） -->
    <view class="searchbar">
      <input
        v-model="keyword"
        class="searchbar__input"
        type="text"
        placeholder="输入标题或内容关键词"
        placeholder-class="searchbar__placeholder"
        confirm-type="search"
        data-testid="search-input"
        @confirm="onSearch"
      />
      <text v-if="keyword" class="searchbar__clear" data-testid="search-clear" @click="onClear">
        ✕
      </text>
      <view class="searchbar__btn" data-testid="search-submit" @click="onSearch">
        <text class="searchbar__btn-text">搜索</text>
      </view>
    </view>

    <!--
      结果统计。
      仅在**搜索过**（searched 为 true）之后显示 —— 一进页面就显示"共 0 条"会让人以为
      搜索已经执行过并且没有结果，而实际上用户还没输入任何东西。
    -->
    <view v-if="searched && !loading && !error" class="summary" data-testid="search-summary">
      <text class="summary__text">
        关键词「{{ submittedKeyword }}」共 {{ total }} 条结果
      </text>
    </view>

    <HyState
      :loading="loading"
      :error="error"
      :empty="searched && !error && posts.length === 0"
      empty-text="没有找到相关帖子，换个关键词试试"
      loading-text="正在搜索…"
      @retry="onSearch"
    />

    <view v-if="posts.length" class="post-list" data-testid="search-post-list">
      <PostListItem v-for="item in posts" :key="item.id" :item="item" />
    </view>

    <!-- 分页：与版块页同口径（满页判定，不看 total） -->
    <view v-if="posts.length" class="more">
      <wd-button
        v-if="hasMore"
        type="info"
        size="small"
        plain
        :loading="loadingMore"
        data-testid="search-load-more"
        @click="loadMore"
      >
        加载更多
      </wd-button>
      <text v-else class="more__end" data-testid="search-list-end">没有更多了</text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 搜索页。
 *
 * 契约：`GET /api/posts/search?keyword&page&size`，**`keyword` 必填**
 * （契约里 `required: true`，缺了后端返回 `400`）。
 *
 * ==========================================================================
 * 为什么"没输入就点搜索"不发请求
 * ==========================================================================
 * 契约说缺 `keyword` 是 `400`，前端完全可以照发然后弹"提交的内容有误"。
 * 但不发更好，理由有二：
 * 1. **"没输入就点搜索"是正常操作**，不是错误操作 —— 拿一个 400 去惩罚它，
 *    用户会以为系统坏了（口径 2 要求按码分支给准确文案，而这里根本不该有码）；
 * 2. 少一次必然失败的往返，弱网下体感明显。
 * 因此空关键词在 **API 层与页面层各拦一道**（`api/posts.ts` 的 `searchPosts` 也拦）。
 *
 * ==========================================================================
 * 服务端与页面状态的一致性
 * ==========================================================================
 * `keyword`（输入框里的实时值）与 `submittedKeyword`（**已提交**的关键词）分开存。
 * 若共用一个变量，用户改了输入框但还没点搜索时，统计文案与结果列表就会对不上
 * （显示"关键词「新词」共 N 条"，而列表其实是旧词的结果）。
 */
import { ref } from 'vue'
import { onReachBottom } from '@dcloudio/uni-app'
import HyState from '@/components/HyState.vue'
import PostListItem from '@/components/PostListItem.vue'
import { searchPosts } from '@/api/posts'
import { PAGE_SIZE_MAX } from '@/utils/request'
import { ApiError } from '@/utils/request'
import { toListItem, type PostListItemView } from '@/utils/format'

/** 输入框里的实时值 */
const keyword = ref('')
/** 已提交的关键词（统计文案与"未搜索过"判定都用它） */
const submittedKeyword = ref('')

const posts = ref<PostListItemView[]>([])
const total = ref(0)

const loading = ref(false)
const loadingMore = ref(false)
const error = ref('')
/** 是否已经执行过至少一次搜索 */
const searched = ref(false)
const hasMore = ref(false)
const page = ref(1)

function appendPage(batch: PostListItemView[], requestedPage: number, totalCount: number): void {
  posts.value = requestedPage === 1 ? batch : posts.value.concat(batch)
  // 满页判定，不用 total —— 理由见 `pages/board/index.vue` 的同名函数注释
  hasMore.value = batch.length >= PAGE_SIZE_MAX
  page.value = requestedPage
  total.value = totalCount
}

async function onSearch(): Promise<void> {
  const kw = keyword.value.trim()
  if (!kw) {
    // 空关键词：清掉上一次的结果与统计，回到"还没搜索"的初始态
    uni.showToast({ title: '请输入搜索关键词', icon: 'none' })
    return
  }

  loading.value = true
  error.value = ''
  submittedKeyword.value = kw
  searched.value = true
  try {
    const res = await searchPosts(kw, 1)
    appendPage(res.list.map(toListItem), 1, res.total)
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
    // 翻页时 total 不由本页更新（后端每次都会给，但用第 1 页的值更稳定）
    appendPage(res.list.map(toListItem), next, total.value)
  } catch (e) {
    // 局部失败不惩罚已显示的结果（与版块页同口径）
    uni.showToast({
      title: e instanceof ApiError ? e.message : '加载失败，请稍后重试',
      icon: 'none',
      duration: 2000,
    })
  } finally {
    loadingMore.value = false
  }
}

/** 清空：同时清输入框、已提交关键词与结果，回到初始态 */
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

.page {
  min-height: 100vh;
  padding-bottom: $hy-space-xl;
  box-sizing: border-box;
}

/* ---------- 搜索框 ---------- */
.searchbar {
  display: flex;
  align-items: center;
  padding: $hy-space-sm $hy-space-md;
  background-color: $hy-bg-card;
  border-bottom: 1rpx solid $hy-border-color;

  &__input {
    flex: 1;
    min-width: 0;
    height: 72rpx;
    padding: 0 $hy-space-md;
    font-size: $hy-font-sm;
    color: $hy-text-primary;
    background-color: $hy-bg-hover;
    border-radius: 36rpx;
  }

  /* placeholder 的样式要单独给（`placeholder-class`），scoped 下需用 :deep 之外的写法，
     这里提供一个全局可命中的类名即可 —— uni-app 会把它作用到生成的占位元素上 */
  &__placeholder {
    color: $hy-text-placeholder;
    font-size: $hy-font-sm;
  }

  &__clear {
    margin-left: $hy-space-sm;
    padding: 0 4rpx;
    font-size: $hy-font-md;
    color: $hy-text-placeholder;
  }

  &__btn {
    margin-left: $hy-space-sm;
    padding: $hy-space-xs $hy-space-md;
    border-radius: $hy-radius-sm;

    &-text {
      font-size: $hy-font-sm;
      font-weight: 600;
      color: $hy-color-primary;
    }
  }
}

/* ---------- 结果统计 ---------- */
.summary {
  padding: $hy-space-sm $hy-space-md;

  &__text {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

.post-list {
  background-color: $hy-bg-card;
}

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
