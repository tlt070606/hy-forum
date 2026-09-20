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
          两处必须说的取舍：
          1. `:show-author="false"` —— `CollectionItemVO` **没有作者字段**
             （收藏记录本身不存作者），显示一个空作者行比不显示更糟；
          2. 底栏用 `extra` 插槽补「收藏于 X」与「取消收藏」——
             它们是**这个列表特有的**，不属于通用卡片。
          ⚠️ 卡片里的点赞/收藏按钮**依然可点**（真接口）：收藏本就是已收藏态，
             所以进页面时会把这几条在会话态里标成已收藏（见 `load` 里的 mark），
             否则书签图标会显示成"没收藏"而列表又明明是我收藏的 —— 自相矛盾。
        -->
        <PostCard
          v-for="c in items"
          :key="c.postId"
          :post="c.card"
          :show-author="false"
          :data-testid="'collect-card'"
        >
          <template #extra>
            <text class="collected-at" data-testid="collect-card-at">
              收藏于 {{ c.collectedText }}
            </text>
            <view class="collect-remove" data-testid="collect-card-remove" @click.stop="uncollect(c)">
              <text class="collect-remove__text">取消收藏</text>
            </view>
          </template>
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
 * 2. **与首页同一张卡片**（复用 `PostCard`，`:show-author="false"`）——
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
import { collectPost } from '@/api/interaction'
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
 * ⚠️ 这里**没有**"构造假作者"这一步（那会显示出"匿名用户"这种假东西）。
 *    作者行由 `:show-author="false"` 整行不渲染 —— 契约没给的数据就不显示。
 *    `viewCount` 契约里没有 → 填 0，但卡片上浏览量那格**照常显示 0**，
 *    这一点如实写在报告里（收藏列表的浏览量是"未知"，不是"0 次浏览"）。
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
    imageCount: c.imageCount,
    viewCount: 0,
    likeCount: c.likeCount,
    commentCount: c.commentCount,
    collectCount: c.collectCount,
    authorName: '',
    authorAvatarUrl: '',
    // 卡片上的时间格用"收藏时间"（这里没有发帖时间可用）—— 底栏另有「收藏于」
    timeText: c.collectedText,
    isTop: false,
    isEssence: false,
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
 * 取消收藏。
 * 幂等端点（§8.1），所以即使本地状态记错了、重复调用也不会造成数据错误。
 */
async function uncollect(c: CollectRow): Promise<void> {
  const idx = items.value.findIndex((x) => x.postId === c.postId)
  if (idx < 0) return
  // 先移除（用户就是要它消失），失败再插回**原位** —— 插回原位而不是"重拉列表"，
  // 这样即使中间翻了好几页也不会把用户的位置弄丢
  const backup = items.value[idx]
  items.value = items.value.filter((x) => x.postId !== c.postId)
  total.value = Math.max(0, total.value - 1)
  try {
    await collectPost(c.postId, false)
    // 成功后才改会话态：失败的话上面的回滚已经把卡片放回来了
    interaction.markPostCollected(c.postId, false)
  } catch (e) {
    const next = items.value.slice()
    next.splice(idx, 0, backup)
    items.value = next
    total.value += 1
    uni.showToast({ title: e instanceof ApiError ? e.message : '取消失败，请稍后重试', icon: 'none' })
  }
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

/* ---------- 底栏插槽里的两样东西（收藏页特有） ---------- */
.collected-at {
  font-size: $hy-font-xs;
  color: $hy-text-secondary;
}

.collect-remove {
  margin-left: auto;

  &__text {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
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
</style>
