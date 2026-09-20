<template>
  <aside class="right-rail">
    <!-- ==================== 版块榜 ==================== -->
    <!--
      ⚠️ 这一栏原来是**三块假数据**（热门话题榜 / 推荐关注 / 热门活动），
      需求方 2026-09-18 定：**换成有后端接口的**。
      现在两块都是真数据：
      - 版块榜 ← `GET /api/boards`（按 `postCount` 排，帖子数是真的）
      - 最新发帖的人 ← `GET /api/posts`（从最新帖子里取作者去重）
      「热门活动」**没有接口**（活动在本项目里没有表）→ **删掉**，不做假数据。
    -->
    <view class="card" data-testid="board-rank">
      <view class="card__head">
        <HyIcon type="diamond" size="md" color="#ff7d00" />
        <text class="card__title">版块榜</text>
        <text class="card__action" data-testid="board-rank-more" @click="goBoards">全部 ›</text>
      </view>

      <view
        v-for="(b, index) in rankBoards"
        :key="b.id"
        class="rank"
        :data-testid="`board-rank-item-${index}`"
        :data-board-id="b.id"
        @click="openBoard(b.id)"
      >
        <!-- 名次色：前 3 名用品牌紫、其余中性灰（名次本身就是信息） -->
        <text class="rank__no" :class="`rank__no--${rankTone(index)}`">{{ index + 1 }}</text>
        <view class="rank__body">
          <view class="rank__title-row">
            <text class="rank__name">{{ b.name }}</text>
            <text v-if="b.isResource" class="rank__badge">资源</text>
          </view>
          <view class="rank__meta">
            <text class="rank__count" data-testid="board-rank-count">
              {{ compactCount(b.postCount ?? 0) }} 帖
            </text>
          </view>
        </view>
      </view>

      <!-- 加载中 / 失败：明确说出来，而不是让这一栏空着让人以为"没有版块" -->
      <text v-if="boardsLoading" class="card__hint" data-testid="board-rank-loading">正在加载…</text>
      <text v-else-if="boardsError" class="card__hint" data-testid="board-rank-error">
        {{ boardsError }}
      </text>
      <text v-else-if="rankBoards.length === 0" class="card__hint">还没有版块</text>
    </view>

    <!-- ==================== 最新发帖的人 ==================== -->
    <view class="card" data-testid="recent-authors">
      <view class="card__head">
        <HyIcon type="users" size="md" color="#6b4bc4" />
        <text class="card__title">最新发帖的人</text>
      </view>

      <view
        v-for="(p, index) in recentAuthors"
        :key="p.id"
        class="person"
        :data-testid="`author-${index}`"
        @click="openUser(p.id)"
      >
        <Avatar :url="p.avatarUrl" :nickname="p.nickname" :size="36" />
        <view class="person__body">
          <text class="person__name">{{ p.nickname }}</text>
          <text class="person__bio">{{ p.lastPostAtText }}发过帖</text>
        </view>
        <HyIcon type="chevron" size="md" color="#c9cdd4" />
      </view>

      <text v-if="authorsLoading" class="card__hint">正在加载…</text>
      <text v-else-if="authorsError" class="card__hint" data-testid="authors-error">
        {{ authorsError }}
      </text>
      <text v-else-if="recentAuthors.length === 0" class="card__hint">还没有人发帖</text>

      <!--
        ⚠️ 这里**刻意不放"关注"按钮**（原来那块假数据是有的）：
        `isFollowing` 只有 `UserProfileVO` 带（`/api/users/{id}`），
        列表项的作者是 `UserBriefVO`，**没有这个字段** ——
        放了就是"点完刷新又变回未关注"的假按钮（与报告里 CR-K 同源）。
        想看某个人、想关注他，点进主页去做，那里的状态是真的。
      -->
    </view>
  </aside>
</template>

<script setup lang="ts">
/**
 * 右栏（真数据版）。
 *
 * ==========================================================================
 * 2026-09-18 需求方裁定：把三块假数据换成**有后端接口的**
 * ==========================================================================
 * 改前：热门话题榜（假 #话题 + 假讨论数）/ 推荐关注（假人 + 假粉丝数 + 假关注按钮）/
 *      热门活动（假活动 + 假参与人数）—— `mock/hotContent.ts` 里那一整份都是编的，
 *      因为话题/活动在本项目里**既没有表也没有接口**。
 * 改后：
 * - **版块榜** ← `GET /api/boards`，按 `postCount` 降序（`postCount` 是后端维护的真实计数）
 * - **最新发帖的人** ← `GET /api/posts?page=1&size=20`，按作者去重取前几个
 *   （"最新发帖的人"这个口径**只用了真实数据**：列表本身就是按时间倒序的）
 * - **热门活动** → **删除**（没有接口，不做假的）
 *
 * ⚠️ 两条边界：
 * 1. 「关注」按钮**不放**（理由见模板注释：列表项没有 `isFollowing`）；
 * 2. 两块的加载/失败/空**都要显示**出来 —— 缺一块会让人以为"这里本来就没有内容"。
 */
import { computed, onMounted, ref } from 'vue'
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import { fetchBoards } from '@/api/boards'
import { fetchPosts } from '@/api/posts'
import { ApiError } from '@/utils/request'
import { compactCount, relativeTime, authorName } from '@/utils/postView'
import type { BoardVO } from '@/api/types'

/** 版块榜取前几个（与参考图的 7 条一致） */
const RANK_COUNT = 7
/** 「最新发帖的人」取几个。它靠一页最新帖子去重得来，取多会拉长右栏 */
const AUTHOR_COUNT = 5

const boards = ref<BoardVO[]>([])
const boardsLoading = ref(false)
const boardsError = ref('')

interface RecentAuthor {
  id: number
  nickname: string
  avatarUrl: string
  /** 他最新一篇帖子发出多久了（"3 分钟前发过帖"） */
  lastPostAtText: string
}

const recentAuthors = ref<RecentAuthor[]>([])
const authorsLoading = ref(false)
const authorsError = ref('')

/**
 * 榜：按 `postCount` **降序**。
 * ⚠️ 用 `slice()` 复制后再排 —— 直接 `sort()` 会**改掉原数组**，
 *    而 `boards` 还会被别处当成"接口给的顺序"用（例如版块总览页就不重排）。
 */
const rankBoards = computed(() =>
  boards.value.slice().sort((a, b) => (b.postCount ?? 0) - (a.postCount ?? 0)).slice(0, RANK_COUNT)
)

function rankTone(index: number): 'top' | 'mid' | 'normal' {
  if (index === 0) return 'top'
  if (index < 3) return 'mid'
  return 'normal'
}

async function loadBoards(): Promise<void> {
  boardsLoading.value = true
  boardsError.value = ''
  try {
    boards.value = await fetchBoards()
  } catch (e) {
    boardsError.value = e instanceof ApiError ? e.message : '版块加载失败'
    boards.value = []
  } finally {
    boardsLoading.value = false
  }
}

async function loadRecentAuthors(): Promise<void> {
  authorsLoading.value = true
  authorsError.value = ''
  try {
    const res = await fetchPosts({ page: 1 })
    const seen = new Set<number>()
    const out: RecentAuthor[] = []
    for (const p of res.list) {
      const id = Number(p.author?.id ?? 0)
      // 去重：一个人可能在最新一页里出现多次，右栏只该出现一次
      if (!id || seen.has(id)) continue
      seen.add(id)
      out.push({
        id,
        nickname: authorName(p.author),
        avatarUrl: String(p.author?.avatarUrl ?? ''),
        lastPostAtText: relativeTime(p.createdAt),
      })
      if (out.length >= AUTHOR_COUNT) break
    }
    recentAuthors.value = out
  } catch (e) {
    authorsError.value = e instanceof ApiError ? e.message : '作者列表加载失败'
    recentAuthors.value = []
  } finally {
    authorsLoading.value = false
  }
}

/*
 * 用 `onMounted` 而不是 `onShow`：右栏是**全局布局的一部分**（每个页面都渲染），
 * `onShow` 会在每次切页时把两个接口各打一次 —— 那是"每翻一页多两个请求"。
 * 冷启动取一次即可（它展示的是"最近的"情况，不是实时榜单）。
 */
onMounted(() => {
  void loadBoards()
  void loadRecentAuthors()
})

/**
 * 打开版块页。
 * ⚠️ 参数写成可选：契约生成的类型里**所有字段都是可选的**（`id?: number`），
 *    所以 `b.id` 的类型是 `number | undefined`。这里显式判空，
 *    而不是用 `b.id!` 断言 —— 断言会把这个不确定性藏起来，
 *    将来真给了没有 id 的数据就会跳到 `?id=undefined`。
 */
function openBoard(id?: number): void {
  if (!id) return
  uni.navigateTo({ url: `/pages/board/index?id=${id}` })
}

function goBoards(): void {
  uni.navigateTo({ url: '/pages/boards/index' })
}

function openUser(id: number): void {
  if (id > 0) uni.navigateTo({ url: `/pages/user/index?id=${id}` })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.right-rail {
  display: flex;
  flex-direction: column;
  gap: $hy-shell-gap;
}

.card {
  padding: 16px;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;

  /* 一行提示（加载中/失败/空）：右栏空间小，不铺大占位 */
  &__hint {
    display: block;
    padding: 10px 0 2px;
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }

  &__head {
    display: flex;
    align-items: center;
    margin-bottom: 12px;
  }

  &__title {
    margin-left: 6px;
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__action {
    margin-left: auto;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 榜单行 ---------- */
.rank {
  padding: 8px 0;
  display: flex;
  align-items: center;

  &:active {
    opacity: 0.65;
  }

  &__no {
    width: 20px;
    flex-shrink: 0;
    font-size: $hy-font-md;
    font-weight: 600;
    text-align: center;

    &--top {
      color: $hy-color-primary;
    }

    &--mid {
      color: $hy-color-warning;
    }

    &--normal {
      color: $hy-text-placeholder;
    }
  }

  &__body {
    flex: 1;
    min-width: 0;
    margin-left: 10px;
  }

  &__title-row {
    display: flex;
    align-items: center;
  }

  &__name {
    font-size: $hy-font-sm;
    color: $hy-text-primary;
  }

  &__badge {
    margin-left: 6px;
    padding: 0 5px;
    font-size: 10px;
    line-height: 15px;
    color: $hy-color-primary;
    background-color: $hy-color-primary-light;
    border-radius: 3px;
  }

  &__meta {
    margin-top: 2px;
  }

  &__count {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 人 ---------- */
.person {
  padding: 8px 0;
  display: flex;
  align-items: center;

  &:active {
    opacity: 0.65;
  }

  &__body {
    flex: 1;
    min-width: 0;
    margin-left: 10px;
  }

  &__name {
    font-size: $hy-font-sm;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__bio {
    display: block;
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    /* 过长就截断一行，右栏窄，不能把行撑高 */
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
}
</style>
