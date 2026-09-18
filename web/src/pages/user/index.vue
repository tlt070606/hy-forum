<template>
  <AppShell active-nav="">
    <view class="user-page">
      <ListState
        :loading="loading && !profile"
        :error="loadError"
        :empty="false"
        testid-base="user-state"
        @retry="loadProfile"
      />

      <template v-if="profile">
        <!-- ==================== 资料卡 ==================== -->
        <view class="card profile" data-testid="user-profile">
          <view class="profile__top">
            <Avatar :url="profile.avatarUrl" :nickname="profile.nickname" :size="72" />
            <view class="profile__main">
              <text class="profile__name" data-testid="user-nickname">{{ profile.nickname }}</text>
              <!-- 简介为空就不占一行（后端允许空简介，空行会让资料卡看起来缺一块） -->
              <text v-if="profile.bio" class="profile__bio" data-testid="user-bio">
                {{ profile.bio }}
              </text>
              <text v-else class="profile__bio profile__bio--empty">这个人还没写简介</text>
              <text class="profile__joined">加入于 {{ profile.joinedText }}</text>
            </view>

            <!--
              关注按钮：**只在个人主页与详情页作者区**（需求方 2026-09-18 定）。
              依据是契约 —— 只有 `UserProfileVO` 带 `isFollowing`，
              列表项的 author 是 `UserBriefVO`，没有这个字段；放列表上会重演 CR-K（状态刷新即丢）。
              ⚠️ 自己看自己**不显示**任何关注按钮（后端也不允许关注自己）。
            -->
            <view v-if="!isSelf" class="profile__action">
              <view
                class="follow-btn"
                :class="{ 'follow-btn--on': profile.isFollowing }"
                data-testid="user-follow"
                @click="toggleFollow"
              >
                <text class="follow-btn__text">{{ followLabel }}</text>
              </view>
            </view>
          </view>

          <!--
            计数。只显示契约里**语义明确**的四个：
            `gender` / `level` 契约里是裸 integer、没有取值说明，**刻意不显示**（见报告 §T）。
          -->
          <view class="stats">
            <view class="stat" data-testid="user-stat-post">
              <text class="stat__num">{{ profile.postCount }}</text>
              <text class="stat__label">帖子</text>
            </view>
            <view class="stat" data-testid="user-stat-follow">
              <text class="stat__num">{{ profile.followCount }}</text>
              <text class="stat__label">关注</text>
            </view>
            <view class="stat" data-testid="user-stat-fans">
              <text class="stat__num">{{ profile.fansCount }}</text>
              <text class="stat__label">粉丝</text>
            </view>
            <view class="stat" data-testid="user-stat-like">
              <text class="stat__num">{{ compactCount(profile.likeReceivedCount) }}</text>
              <text class="stat__label">获赞</text>
            </view>
          </view>
        </view>

        <!-- ==================== 四个 Tab ==================== -->
        <view class="card">
          <view class="tabs">
            <view
              v-for="t in TABS"
              :key="t.key"
              class="tabs__item"
              :class="{ 'tabs__item--active': tab === t.key }"
              :data-testid="`user-tab-${t.key}`"
              @click="switchTab(t.key)"
            >
              <text class="tabs__text">{{ t.label }}</text>
            </view>
          </view>

          <ListState
            :loading="tabLoading && tabItems.length === 0"
            :error="tabError"
            :empty="tabItems.length === 0"
            :empty-text="TABS.find((x) => x.key === tab)?.emptyText || '还没有内容'"
            loading-text="正在加载…"
            testid-base="user-tab-state"
            @retry="loadTab(1, false)"
          />

          <!-- 帖子：复用首页的帖子卡片 -->
          <template v-if="tab === 'posts'">
            <PostCard v-for="p in postItems" :key="`p${p.id}`" :post="p" />
          </template>

          <!-- 评论：整条可点回原帖（**不做评论定位** —— 契约没有锚点机制） -->
          <template v-else-if="tab === 'comments'">
            <view
              v-for="c in commentItems"
              :key="`c${c.id}`"
              class="comment-row"
              data-testid="user-comment-row"
              @click="openPost(c.postUrl)"
            >
              <text class="comment-row__title" data-testid="user-comment-posttitle">
                {{ c.postTitle }}
              </text>
              <text class="comment-row__content">{{ c.content }}</text>
              <view class="comment-row__foot">
                <text class="comment-row__meta">{{ c.timeText }}</text>
                <text class="comment-row__meta">·</text>
                <text class="comment-row__meta">{{ c.likeCount }} 赞</text>
                <text v-if="c.replyCount > 0" class="comment-row__meta">· {{ c.replyCount }} 条回复</text>
              </view>
            </view>
          </template>

          <!-- 关注 / 粉丝：同一套渲染 -->
          <template v-else>
            <view
              v-for="u in followItems"
              :key="`f${u.userId}`"
              class="user-row"
              data-testid="user-follow-row"
              @click="openUser(u.userId)"
            >
              <Avatar :url="u.avatarUrl" :nickname="u.nickname" :size="40" />
              <view class="user-row__main">
                <text class="user-row__name">{{ u.nickname }}</text>
                <text v-if="u.bio" class="user-row__bio">{{ u.bio }}</text>
              </view>
              <text class="user-row__time">{{ u.followedAtText }}</text>
            </view>
          </template>

          <!-- 分页 -->
          <view v-if="tabItems.length && tabHasMore" class="more">
            <view class="more__btn" data-testid="user-load-more" @click="loadTab(tabPage + 1, true)">
              <text class="more__btn-text">{{ tabLoadingMore ? '加载中…' : '加载更多' }}</text>
            </view>
          </view>
          <text v-else-if="tabItems.length" class="more__end" data-testid="user-list-end">
            没有更多了
          </text>
        </view>
      </template>

      <!-- 用户不存在：与 404 分开渲染（可重试的错误条 vs 明确的"查无此人"） -->
      <view v-if="notFound" class="card notfound" data-testid="user-notfound">
        <text class="notfound__title">找不到这个用户</text>
        <text class="notfound__desc">他可能已经注销，或者链接有误</text>
        <view class="notfound__btn" data-testid="user-notfound-back" @click="goBack">
          <text class="notfound__btn-text">返回</text>
        </view>
      </view>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 个人主页：资料卡 + 四个 Tab（帖子 / 评论 / 关注 / 粉丝）。
 *
 * ==========================================================================
 * 需求方 2026-09-18 grill 后定下的四条口径
 * ==========================================================================
 * 1. **关注按钮只在本页与详情页作者区** —— 只有 `UserProfileVO` 带 `isFollowing`；
 *    列表项的 author 是 `UserBriefVO`（无此字段），放列表上会重演 CR-K。
 * 2. **`gender` / `level` 不显示** —— 契约里是裸 integer、没有取值说明，
 *    前端猜不出编码（0 是"未知"还是"男"？）。已登记 CR（§T）。
 * 3. **评论 Tab 不做评论定位** —— 整条点回原帖即可；契约没有锚点机制，
 *    详情页也不支持"滚动到某条评论"。
 * 4. **自己看自己不显示关注按钮**（后端也不允许关注自己）。
 *
 * ⚠️ 四个 Tab **各自独立分页**，且**切换时保留已加载的数据**
 *    （`loadedTabs` 记住取过哪些，切回来不重拉）—— 否则来回点两下就是一堆重复请求。
 */
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AppShell from '@/components/shell/AppShell.vue'
import Avatar from '@/components/Avatar.vue'
import ListState from '@/components/ListState.vue'
import PostCard from '@/components/PostCard.vue'
import {
  fetchUserComments,
  fetchUserFans,
  fetchUserFollows,
  fetchUserPosts,
  fetchUserProfile,
} from '@/api/users'
import { followUser } from '@/api/interaction'
import { BIZ_CODE } from '@/utils/error-code'
import { ApiError, PAGE_SIZE_MAX } from '@/utils/request'
import {
  compactCount,
  toFollowUserView,
  toPostCard,
  toProfileView,
  toUserCommentView,
  type FollowUserView,
  type PostCardView,
  type ProfileView,
  type UserCommentView,
} from '@/utils/postView'
import { useAuthStore } from '@/stores/auth'

type TabKey = 'posts' | 'comments' | 'follows' | 'fans'

/**
 * 四个 Tab 的定义。
 * `emptyText` 分开写：四个 Tab 的"空"含义不同，用同一句"暂无数据"等于什么都没说。
 */
const TABS: Array<{ key: TabKey; label: string; emptyText: string }> = [
  { key: 'posts', label: '帖子', emptyText: '他还没发过帖子' },
  { key: 'comments', label: '评论', emptyText: '他还没发过评论' },
  { key: 'follows', label: '关注', emptyText: '他还没关注任何人' },
  { key: 'fans', label: '粉丝', emptyText: '还没有人关注他' },
]

const auth = useAuthStore()

const userId = ref(0)
const profile = ref<ProfileView | null>(null)
const loading = ref(false)
const loadError = ref('')
const notFound = ref(false)

const tab = ref<TabKey>('posts')
const tabPage = ref(1)
const tabLoading = ref(false)
const tabLoadingMore = ref(false)
const tabError = ref('')
const tabHasMore = ref(false)

/** 各 Tab 已加载的数据（切换时保留，不重拉） */
const postItems = ref<PostCardView[]>([])
const commentItems = ref<UserCommentView[]>([])
const followItems = ref<FollowUserView[]>([])
/** 已取过第一页的 Tab —— 用它避免"切回来又重拉一次" */
const loadedTabs = ref<Set<TabKey>>(new Set())

/** 关注按钮文案：区分"关注 / 已关注 / 互相关注"（`isFollowedBy` 是契约真有的） */
const followLabel = computed(() => {
  if (!profile.value) return '关注'
  if (profile.value.isFollowing) return profile.value.isFollowedBy ? '互相关注' : '已关注'
  return profile.value.isFollowedBy ? '回关' : '关注'
})

const isSelf = computed(() => Boolean(auth.user?.id) && auth.user!.id === userId.value)

/**
 * 当前 Tab 的数据。
 * 用 computed 把四个列表收敛成一个"当前列表"，`ListState` 的 loading/empty 判断就只写一处。
 */
const tabItems = computed<unknown[]>(() => {
  if (tab.value === 'posts') return postItems.value
  if (tab.value === 'comments') return commentItems.value
  return followItems.value
})

/* ---------------------------------------------------------------------------
 * 加载
 * ------------------------------------------------------------------------- */

async function loadProfile(): Promise<void> {
  if (!userId.value) return
  loading.value = true
  loadError.value = ''
  notFound.value = false
  try {
    profile.value = toProfileView(await fetchUserProfile(userId.value))
  } catch (e) {
    if (e instanceof ApiError && e.code === BIZ_CODE.NOT_FOUND) {
      // 404 = 查无此人。与"网络错"分开：前者重试没有意义，后者有
      notFound.value = true
    } else {
      loadError.value = e instanceof ApiError ? e.message : '资料加载失败，请稍后重试'
    }
  } finally {
    loading.value = false
  }
}

/** 拉某个 Tab 的某一页。`append = false` 表示重新加载第一页 */
async function loadTab(targetPage: number, append: boolean): Promise<void> {
  if (!userId.value) return
  if (append) {
    if (tabLoadingMore.value || !tabHasMore.value) return
    tabLoadingMore.value = true
  } else {
    tabLoading.value = true
  }
  tabError.value = ''

  const current = tab.value
  try {
    const size = PAGE_SIZE_MAX
    if (current === 'posts') {
      const res = await fetchUserPosts(userId.value, targetPage, size)
      const list = res.list.map(toPostCard)
      postItems.value = append ? postItems.value.concat(list) : list
      tabHasMore.value = res.list.length >= size
    } else if (current === 'comments') {
      const res = await fetchUserComments(userId.value, targetPage, size)
      const list = res.list.map(toUserCommentView)
      commentItems.value = append ? commentItems.value.concat(list) : list
      tabHasMore.value = res.list.length >= size
    } else {
      const res =
        current === 'follows'
          ? await fetchUserFollows(userId.value, targetPage, size)
          : await fetchUserFans(userId.value, targetPage, size)
      const list = res.list.map(toFollowUserView)
      followItems.value = append ? followItems.value.concat(list) : list
      tabHasMore.value = res.list.length >= size
    }
    tabPage.value = targetPage
    const next = new Set(loadedTabs.value)
    next.add(current)
    loadedTabs.value = next
  } catch (e) {
    tabError.value = e instanceof ApiError ? e.message : '加载失败，请稍后重试'
    if (!append) {
      if (current === 'posts') postItems.value = []
      else if (current === 'comments') commentItems.value = []
      else followItems.value = []
      tabHasMore.value = false
    }
  } finally {
    tabLoading.value = false
    tabLoadingMore.value = false
  }
}

/** 切 Tab：取过的直接用缓存，没取过的才请求（避免来回点产生重复请求） */
function switchTab(key: TabKey): void {
  if (tab.value === key) return
  tab.value = key
  tabError.value = ''
  // 分页状态是按 Tab 共享的：切换时先复位，已加载过的 Tab 用"是否取过首页"来决定
  tabPage.value = 1
  tabHasMore.value = tabItems.value.length >= PAGE_SIZE_MAX
  if (!loadedTabs.value.has(key)) void loadTab(1, false)
}

/* ---------------------------------------------------------------------------
 * 关注
 * ------------------------------------------------------------------------- */

/**
 * 关注 / 取关 / 回关。
 *
 * 状态**以服务端为准**（`isFollowing` 是契约真字段），所以成功后就地翻转本地值，
 * 并按方向调整粉丝数（关注对方 → 对方的粉丝 +1）。
 * 失败回滚**两个值一起**，避免"按钮变回去了、粉丝数没变"。
 */
async function toggleFollow(): Promise<void> {
  if (!profile.value) return
  if (!auth.isLoggedIn) {
    uni.showToast({ title: '请先登录', icon: 'none' })
    setTimeout(() => uni.navigateTo({ url: '/pages/auth/index?mode=login' }), 700)
    return
  }
  const was = profile.value.isFollowing
  const beforeFans = profile.value.fansCount
  profile.value = {
    ...profile.value,
    isFollowing: !was,
    fansCount: Math.max(0, beforeFans + (was ? -1 : 1)),
  }
  try {
    await followUser(profile.value.id, !was)
  } catch (e) {
    profile.value = { ...profile.value, isFollowing: was, fansCount: beforeFans }
    uni.showToast({ title: e instanceof ApiError ? e.message : '操作失败，请稍后重试', icon: 'none' })
  }
}

/* ---------------------------------------------------------------------------
 * 跳转
 * ------------------------------------------------------------------------- */

function openUser(id: number): void {
  if (!id || id === userId.value) return
  uni.navigateTo({ url: `/pages/user/index?id=${id}` })
}

function openPost(url: string): void {
  uni.navigateTo({ url })
}

function goBack(): void {
  uni.navigateBack()
}

onLoad((options) => {
  const raw = options?.id
  const parsed = Number(raw)
  if (!raw || Number.isNaN(parsed) || parsed <= 0) {
    notFound.value = true
    return
  }
  userId.value = parsed
  void loadProfile()
  void loadTab(1, false)
})
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

/* ---------- 资料卡 ---------- */
.profile {
  &__top {
    display: flex;
    align-items: flex-start;
  }

  &__main {
    flex: 1;
    min-width: 0;
    margin-left: 14px;
  }

  &__name {
    font-size: 20px;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__bio {
    display: block;
    margin-top: 6px;
    font-size: $hy-font-md;
    line-height: 1.6;
    color: $hy-text-regular;
    word-break: break-word;

    &--empty {
      color: $hy-text-placeholder;
    }
  }

  &__joined {
    display: block;
    margin-top: 6px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__action {
    margin-left: 12px;
  }
}

.follow-btn {
  height: 34px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  background-color: $hy-color-primary;
  border-radius: $hy-radius-pill;

  /* 已关注：改描边（"已关注"用实心会让人以为点了还能再点一次） */
  &--on {
    background-color: transparent;
    border: 1px solid $hy-border-color;

    .follow-btn__text {
      color: $hy-text-regular;
    }
  }

  &__text {
    font-size: $hy-font-md;
    color: $hy-text-inverse;
  }
}

.stats {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid $hy-border-color;
  display: flex;
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__num {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__label {
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- Tab ---------- */
.tabs {
  display: flex;
  margin-bottom: 8px;

  &__item {
    flex: 1;
    height: 38px;
    display: flex;
    align-items: center;
    justify-content: center;

    &--active .tabs__text {
      color: $hy-color-primary;
      font-weight: 600;
    }
  }

  &__text {
    font-size: $hy-font-md;
    color: $hy-text-regular;
  }
}

/* ---------- 评论行 ---------- */
.comment-row {
  padding: 12px 0;
  border-top: 1px solid $hy-border-color;

  &__title {
    display: block;
    font-size: $hy-font-sm;
    color: $hy-color-primary;
  }

  &__content {
    display: block;
    margin-top: 6px;
    font-size: $hy-font-md;
    line-height: 1.7;
    color: $hy-text-regular;
    white-space: pre-wrap;
    word-break: break-word;
  }

  &__foot {
    margin-top: 6px;
    display: flex;
    align-items: center;
  }

  &__meta {
    margin-right: 6px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 关注/粉丝行 ---------- */
.user-row {
  padding: 12px 0;
  border-top: 1px solid $hy-border-color;
  display: flex;
  align-items: center;

  &__main {
    flex: 1;
    min-width: 0;
    margin-left: 10px;
  }

  &__name {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__bio {
    display: block;
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    /* 简介过长就截断一行，避免把行撑高 */
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  &__time {
    margin-left: 10px;
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }
}

/* ---------- 分页 ---------- */
.more {
  padding-top: 14px;
  display: flex;
  justify-content: center;

  &__btn {
    height: 32px;
    padding: 0 22px;
    display: flex;
    align-items: center;
    border: 1px solid $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__btn-text {
    font-size: $hy-font-sm;
    color: $hy-color-primary;
  }

  &__end {
    display: block;
    padding-top: 14px;
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
    text-align: center;
  }
}

/* ---------- 查无此人 ---------- */
.notfound {
  padding: 48px 0;

  &__title {
    display: block;
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
    text-align: center;
  }

  &__desc {
    display: block;
    margin-top: 8px;
    font-size: $hy-font-md;
    color: $hy-text-secondary;
    text-align: center;
  }

  &__btn {
    margin: 20px auto 0;
    width: 120px;
    height: 34px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 1px solid $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__btn-text {
    font-size: $hy-font-md;
    color: $hy-color-primary;
  }
}
</style>
