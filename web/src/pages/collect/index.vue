<template>
  <AppShell active-nav="collect">
    <view class="collect-page">
      <!-- 页头：与详情页一致的返回条 -->
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
        而 401 在 `request` 层会被转成"登录已过期"——对没登录的人来说是**错误信息**，不是引导。
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

        <view
          v-for="c in items"
          :key="c.postId"
          class="card collect-card"
          data-testid="collect-card"
        >
          <view class="collect-card__body" @click="openPost(c.detailUrl)">
            <text class="collect-card__title" data-testid="collect-card-title">{{ c.title }}</text>
            <view v-if="c.coverUrl" class="collect-card__cover-box">
              <image
                class="collect-card__cover"
                :src="c.coverUrl"
                mode="aspectFill"
                data-testid="collect-card-cover"
              />
              <text v-if="c.imageCount > 1" class="collect-card__more">{{ c.imageCount }} 张</text>
            </view>
          </view>

          <!--
            ⚠️ 收藏卡片**没有作者行** —— `CollectionItemVO` 里确实没有作者字段
            （收藏记录本身不存作者）。这是契约事实，不是我省略的；已登记在报告里。
          -->
          <view class="collect-card__foot">
            <text class="collect-card__meta">收藏于 {{ c.collectedText }}</text>
            <view class="collect-card__metrics">
              <view class="metric">
                <HyIcon type="heartOutline" size="md" />
                <text class="metric__text">{{ compactCount(c.likeCount) }}</text>
              </view>
              <view class="metric">
                <HyIcon type="comment" size="md" />
                <text class="metric__text">{{ compactCount(c.commentCount) }}</text>
              </view>
              <!-- 收藏数：用它自己表示"已收藏"（这个列表里全都是已收藏的） -->
              <view class="metric metric--on">
                <HyIcon type="bookmarkFilled" size="md" />
                <text class="metric__text">{{ compactCount(c.collectCount) }}</text>
              </view>
            </view>
            <!--
              取消收藏：接口是幂等的 DELETE /api/posts/{id}/collect。
              成功后**把它从列表里移掉**（就地移除，不重拉整页 —— 用户就是要它消失）。
            -->
            <view class="collect-card__remove" data-testid="collect-card-remove" @click="uncollect(c)">
              <text class="collect-card__remove-text">取消收藏</text>
            </view>
          </view>
        </view>

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
 * 三条口径（需求方 2026-09-18 grill 后定）：
 * 1. **未登录不发请求**，直接给登录引导（401 会被转成"登录已过期"，对未登录的人是错误信息）；
 * 2. **复用帖子卡片的视觉，但没有作者行** —— `CollectionItemVO` 真的没有作者字段；
 * 3. 取消收藏**就地移除**，不重拉整页。
 */
import { ref } from 'vue'
import AppShell from '@/components/shell/AppShell.vue'
import HyIcon from '@/components/HyIcon.vue'
import ListState from '@/components/ListState.vue'
import { fetchMyCollections } from '@/api/users'
import { collectPost } from '@/api/interaction'
import { ApiError, PAGE_SIZE_MAX } from '@/utils/request'
import { compactCount, toCollectionView, type CollectionView } from '@/utils/postView'
import { useAuthStore } from '@/stores/auth'
import { useInteractionStore } from '@/stores/interaction'

const auth = useAuthStore()
const interaction = useInteractionStore()

const items = ref<CollectionView[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const loadingMore = ref(false)
const loadError = ref('')
const hasMore = ref(false)

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
    const list = res.list.map(toCollectionView)
    items.value = append ? items.value.concat(list) : list
    total.value = res.total
    hasMore.value = res.list.length >= PAGE_SIZE_MAX
    page.value = targetPage
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

/*
 * 首屏加载：登录态是**在 onLoad 之前**就能读到的（token 从本地存储同步读入），
 * 所以直接判断即可，不需要等异步。
 */
if (auth.isLoggedIn) reload()

/**
 * 取消收藏。
 * 幂等端点（§8.1），所以即使本地状态记错了、重复调用也不会造成数据错误。
 */
async function uncollect(c: CollectionView): Promise<void> {
  const idx = items.value.findIndex((x) => x.postId === c.postId)
  if (idx < 0) return
  // 先移除（用户就是要它消失），失败再插回原位 —— 插回原位而不是"重拉列表"，
  // 这样即使中间翻了好几页也不会把用户的位置弄丢
  const backup = items.value[idx]
  items.value = items.value.filter((x) => x.postId !== c.postId)
  total.value = Math.max(0, total.value - 1)
  interaction.markPostCollected(c.postId, false)
  try {
    await collectPost(c.postId, false)
  } catch (e) {
    const next = items.value.slice()
    next.splice(idx, 0, backup)
    items.value = next
    total.value += 1
    uni.showToast({ title: e instanceof ApiError ? e.message : '取消失败，请稍后重试', icon: 'none' })
  }
}

function openPost(url: string): void {
  uni.navigateTo({ url })
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

/* ---------- 收藏卡片 ---------- */
.collect-card {
  &__title {
    display: block;
    font-size: $hy-font-lg;
    font-weight: 600;
    line-height: 1.5;
    color: $hy-text-primary;
  }

  &__cover-box {
    position: relative;
    width: calc(33.33% - 8px);
    height: 92px;
    margin-top: 10px;
  }

  &__cover {
    width: 100%;
    height: 100%;
    border-radius: $hy-radius-sm;
    background-color: $hy-bg-hover;
  }

  &__more {
    position: absolute;
    right: 4px;
    bottom: 4px;
    padding: 0 6px;
    font-size: 10px;
    line-height: 16px;
    color: #ffffff;
    background-color: rgba(29, 33, 41, 0.55);
    border-radius: 3px;
  }

  &__foot {
    margin-top: 12px;
    padding-top: 10px;
    border-top: 1px solid $hy-border-color;
    display: flex;
    align-items: center;
  }

  &__meta {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__metrics {
    margin-left: auto;
    display: flex;
    align-items: center;
  }

  &__remove {
    margin-left: 20px;
  }

  &__remove-text {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }
}

.metric {
  display: flex;
  align-items: center;
  margin-left: 18px;

  :deep(.hy-icon) {
    color: $hy-text-regular;
  }

  &--on :deep(.hy-icon) {
    color: $hy-color-primary;
  }

  &__text {
    margin-left: 6px;
    font-size: $hy-font-sm;
    color: $hy-text-regular;
  }
}

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
