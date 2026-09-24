<template>
  <AppShell active-nav="collect">
    <view class="collect-page">
      <view class="card head">
        <view class="head__back" data-testid="collect-back" @click="goBack">
          <HyIcon type="chevronLeft" size="md" />
          <text class="head__back-text">返回</text>
        </view>
        <text class="head__title">我的收藏</text>
        <text class="head__count" data-testid="collect-total">{{ total }}</text>
      </view>

      <!--
        ⚠️ 未登录时**不发请求**，直接给登录引导（需求方 2026-09-18 定）。
        理由：`/api/user/collections` 需要登录，未登录必然是 401；
        而 401 在 `request` 层会被转成"登录已过期"—— 对没登录的人是**错误信息**，不是引导。
      -->
      <view v-if="!auth.isLoggedIn" class="card login-hint" data-testid="collect-login-hint">
        <text class="login-hint__title">登录后查看收藏</text>
        <text class="login-hint__desc">收藏的帖子会出现在这里，换设备也能看到</text>
        <view class="login-hint__btn" data-testid="collect-login" @click="goLogin">
          <text class="login-hint__btn-text">去登录</text>
        </view>
      </view>

      <template v-else>
        <ListState
          :loading="loading && items.length === 0"
          :error="loadError"
          :empty="items.length === 0"
          empty-text="还没有收藏，去首页看看吧"
          loading-text="正在加载收藏…"
          testid-base="collect-state"
          @retry="reload"
        />

        <!--
          ====================================================================
          收藏卡片**就是首页那张卡片**（需求方 2026-09-18：「收藏的UI和首页的UI
          做成一样啊」）—— 复用 `PostCard` 组件，而不是复制一份样式。
          ====================================================================
          两条说明：
          1. **作者行照常显示** —— 契约 `CollectionItemVO.author` 现在有了（`UserBriefVO`），
             所以收藏卡片与首页卡片**结构完全一致**；
          2. 「收藏于 X」放在**作者行的位置**（需求方：「收藏的时间就放在头像旁边」），
             也就是首页卡片里"发帖时间"那一格。
          ⚠️ 卡片里的点赞/收藏按钮**依然可点**（真接口）：收藏本就是已收藏态，
             所以进页面时会把这几条在会话态里标成已收藏（见 `load` 里的 mark），
             否则书签图标会显示成"没收藏"而列表又明明是我收藏的 —— 自相矛盾。
        -->
        <PostCard
          v-for="c in items"
          :key="c.postId"
          :post="c.card"
          :data-testid="'collect-card'"
          @collect-change="(on: boolean) => onCollectChange(c, on)"
        >
          <!--
            「收藏于 X」放在**卡片最顶部那一行**（= 首页卡片里作者行的位置）。
            需求方 2026-09-18：「收藏的时间就放在头像旁边」——
            头像那一行现在只能放时间，因为 `CollectionItemVO` 里**没有作者字段**（见 CR-R）。
          -->


        </PostCard>

        <view v-if="items.length && hasMore" class="more">
          <view class="more__btn" data-testid="collect-load-more" @click="loadMore">
            <text class="more__btn-text">{{ loadingMore ? '加载中…' : '加载更多' }}</text>
          </view>
        </view>
        <text v-else-if="items.length" class="more__end" data-testid="collect-list-end">
          没有更多了
        </text>
      </template>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 我的收藏。
 *
 * 契约：`GET /api/user/collections`（**需登录**）→ `PageResultCollectionItemVO`。
 *
 * 三条口径（需求方 grill 后定）：
 * 1. **未登录不发请求**，直接给登录引导；
 * 2. **与首页同一张卡片**（复用 `PostCard`，**作者行照常显示** —— `CollectionItemVO.author` 已补）——
 *    这是需求方 2026-09-18 明确要求的"UI 做成一样"，
 *    而**共用组件**是"一样"的唯一可靠保证（复制样式迟早会漂）；
 * 3. 取消收藏**就地移除**，失败插回**原位**（不重拉整页，免得把翻了几页的位置弄丢）。
 */
import { ref } from 'vue'
import AppShell from '@/components/shell/AppShell.vue'
import HyIcon from '@/components/HyIcon.vue'
import ListState from '@/components/ListState.vue'
import PostCard from '@/components/PostCard.vue'
import { fetchMyCollections } from '@/api/users'
import { ApiError, PAGE_SIZE_MAX } from '@/utils/request'
import { compactCount, toCollectionView, type CollectionView, type PostCardView } from '@/utils/postView'
import { useAuthStore } from '@/stores/auth'
import { useInteractionStore } from '@/stores/interaction'

const auth = useAuthStore()
const interaction = useInteractionStore()

/**
 * 一条收藏 = 收藏信息（收藏时间）+ **一张首页同款卡片的数据**。
 * 拆成两层是因为 `CollectionItemVO` 与 `PostSummaryVO` 形状不同：
 * 前者没有作者、多一个"收藏时间"；卡片只吃后者。
 */
interface CollectRow {
  postId: number
  collectedText: string
  card: PostCardView
}

const items = ref<CollectRow[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const loadingMore = ref(false)
const loadError = ref('')
const hasMore = ref(false)

/**
 * 把收藏项转成 `PostCardView`。
 *
 * 契约 `CollectionItemVO` 现在的字段：`postId / boardId / title / coverUrl / imageCount /
 * imageThumbs / author / likeCount / commentCount / collectCount / createdAt`。
 * 所以**作者行能显示了**（`author` 是 `UserBriefVO`）—— 这正是需求方要的"收藏跟首页一样有头像"。
 *
 * 仍然缺的（都如实处理，不编）：
 * - **没有版块名**（只给 `boardId`）→ 不显示版块标签；
 * - **没有 `viewCount`** → 那格显示 0，但这是"未知"不是"0 次浏览"（已在报告里说明）；
 * - **缩略图**：CR-S 修好后可用（见下方 `imageThumbs` 的注释）。
 */
function toRow(c: CollectionView): CollectRow {
  const view = toCollectionViewView(c)
  return { postId: c.postId, collectedText: c.collectedText, card: view }
}

/** 独立的转换函数：把 `CollectionView` 映射成卡片视图 */
function toCollectionViewView(c: CollectionView): PostCardView {
  return {
    id: c.postId,
    boardId: c.boardId,
    // 收藏项**没有版块名**（契约只给 boardId）→ 不显示版块标签，也不编一个名字
    boardName: '',
    title: c.title,
    coverUrl: c.coverUrl,
    // 作者：契约 `CollectionItemVO.author` 现在有了（`UserBriefVO`）→ 收藏卡片能显示作者行 ✓
    authorName: c.authorName,
    authorAvatarUrl: c.authorAvatarUrl,
    imageCount: c.imageCount,
    /*
     * 计数：`viewCount` 契约没给 → 0（**这是"未知"，不是"0 次浏览"**，报告里说明了）；
     * 其余三个都是契约真给的 ✓。
     */
    viewCount: 0,
    likeCount: c.likeCount,
    commentCount: c.commentCount,
    collectCount: c.collectCount,
    /*
     * 作者行下面的时间用**收藏时间**（需求方 2026-09-20：「收藏的时间就放在头像旁边」）——
     * 也就是首页卡片里"发帖时间"的那个位置。这样收藏卡片与首页卡片结构完全一致：
     * 头像 + 昵称 + 时间 / 标题 / 图 / 版块标签 / 互动行。
     */
    timeText: `收藏于 ${c.collectedText}`,
    isTop: false,
    isEssence: false,
    /*
     * 缩略图：CR-S 修好后**恢复多图**（2026-09-24 实测：收藏项三张缩略图全部 200 且实收字节）。
     * 契约 CollectionItemVO.imageThumbs（≤3 张，读时签名）→ 卡片会画九宫格。
     */
    imageThumbs: c.imageThumbs ?? [],
    /*
     * ⚠️ 只给 `collected: true`（在这个列表里必然是真的），**不给 `liked`** ——
     * `CollectionItemVO` 没有 `liked` 字段，给个 false 会把我确实点过的赞抹掉。
     * 这就是 `PostCardView.liked` 是可选的原因。
     */
    collected: true,
    detailUrl: c.detailUrl,
    boardUrl: '',
  }
}

async function load(targetPage: number, append: boolean): Promise<void> {
  if (!auth.isLoggedIn) return
  if (append) {
    if (loadingMore.value || !hasMore.value) return
    loadingMore.value = true
  } else {
    loading.value = true
  }
  loadError.value = ''
  try {
    const res = await fetchMyCollections(targetPage)
    const rows = res.list.map(toCollectionView).map(toRow)
    items.value = append ? items.value.concat(rows) : rows
    total.value = res.total
    hasMore.value = res.list.length >= PAGE_SIZE_MAX
    page.value = targetPage

    /*
     * 把这一页的帖子在**会话态**里标成"已收藏"。
     * 不标的话，卡片上的书签图标会显示成未收藏（而列表本身就是我收藏的）——
     * 自相矛盾。这是契约缺 `isCollected` 时的兜底（CR-K），只影响本次会话。
     */
    rows.forEach((r) => interaction.markPostCollected(r.postId, true))
  } catch (e) {
    loadError.value = e instanceof ApiError ? e.message : '收藏加载失败，请稍后重试'
    if (!append) {
      items.value = []
      hasMore.value = false
    }
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function reload(): void {
  void load(1, false)
}

function loadMore(): void {
  void load(page.value + 1, true)
}

/* 首屏加载：登录态在 `onLoad` 之前就能同步读到（token 从本地存储读入），不必等异步 */
if (auth.isLoggedIn) reload()

/**
 * 收藏状态变化 —— **取消收藏不再有单独的按钮**（需求方 2026-09-20：「不要这个取消收藏」）。
 * 取消的统一入口是卡片上那个**书签图标**（就是首页卡片上那个，行为完全一致）。
 *
 * 所以这里只负责一件事：**取消成功之后，把这一行从列表里移除**。
 * - 不在这里发请求：请求由 `PostCard` 内的收藏按钮发出（它负责乐观更新与回滚）；
 *   本页只在"它确认成功"之后调整列表 —— 这就是 `collectChange` 这个事件存在的原因。
 * - 重新收藏（`on === true`）时什么都不做：那一行本来就在列表里。
 */
function onCollectChange(c: CollectRow, collected: boolean): void {
  if (collected) return
  const idx = items.value.findIndex((x) => x.postId === c.postId)
  if (idx < 0) return
  items.value = items.value.filter((x) => x.postId !== c.postId)
  total.value = Math.max(0, total.value - 1)
  uni.showToast({ title: '已取消收藏', icon: 'none' })
}

function goLogin(): void {
  uni.navigateTo({ url: '/pages/auth/index?mode=login' })
}

function goBack(): void {
  uni.navigateBack()
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.head {
  display: flex;
  align-items: center;

  &__back {
    display: flex;
    align-items: center;
  }

  &__back-text {
    margin-left: 4px;
    font-size: $hy-font-md;
    color: $hy-text-regular;
  }

  &__title {
    margin-left: 12px;
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__count {
    margin-left: 8px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 未登录引导 ---------- */
.login-hint {
  padding: 40px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__title {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__desc {
    margin-top: 8px;
    font-size: $hy-font-md;
    color: $hy-text-secondary;
  }

  &__btn {
    margin-top: 20px;
    height: 36px;
    padding: 0 28px;
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

/* ---------- 收藏页特有的两处（插槽内容） ---------- */
/*
 * 「收藏于 X」：占据卡片顶部那一行（首页卡片里是作者行）。
 * ⚠️ `white-space: nowrap` 是必须的 —— 窄屏上这一行的可用宽度很小，
 *    不加就会被逐字换行，渲染成「收/藏/于/2/天/前」这种竖排单字（本机真实出现过）。
 */
.collect-head {
  display: flex;
  align-items: center;
  margin-bottom: 8px;

  &__at {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    white-space: nowrap;
  }
}

/* 「取消收藏」按钮已按需求方要求移除：取消收藏统一走卡片上的书签图标（与首页一致） */

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
</style>
