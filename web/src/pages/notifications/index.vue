<template>
  <AppShell active-nav="notifications">
    <view class="notify-page">
      <view class="card head">
        <view class="head__back" data-testid="notify-back" @click="goBack">
          <HyIcon type="chevronLeft" size="md" />
          <text class="head__back-text">返回</text>
        </view>
        <text class="head__title">消息</text>
        <text v-if="unread > 0" class="head__unread" data-testid="notify-unread">
          {{ unread }} 条未读
        </text>
        <!-- 有未读才出现的"全部已读"：没有未读时放一个按钮是噪声 -->
        <view
          v-if="unread > 0"
          class="head__action"
          data-testid="notify-read-all"
          @click="readAll"
        >
          <text class="head__action-text">{{ readingAll ? '处理中…' : '全部已读' }}</text>
        </view>
      </view>

      <!--
        未登录：不发请求（与收藏/关注流同口径）。
        `GET /api/notifications` 需要登录，未登录必然是 401，
        而 401 会被 `request` 层转成"登录已过期" —— 对没登录的人是错误信息，不是引导。
      -->
      <view v-if="!auth.isLoggedIn" class="card login-hint" data-testid="notify-login-hint">
        <text class="login-hint__title">登录后查看消息</text>
        <text class="login-hint__desc">点赞、评论、回复、关注你的动态都会出现在这里</text>
        <view class="login-hint__btn" data-testid="notify-login" @click="goLogin">
          <text class="login-hint__btn-text">去登录</text>
        </view>
      </view>

      <template v-else>
        <!-- 类型筛选（`0` = 全部，不传 type） -->
        <view class="filters" data-testid="notify-filters">
          <view
            v-for="f in NOTIFY_FILTERS"
            :key="f.value"
            class="filters__item"
            :class="{ 'filters__item--active': typeFilter === f.value }"
            :data-testid="`notify-filter-${f.value}`"
            @click="changeFilter(f.value)"
          >
            <text class="filters__text">{{ f.label }}</text>
          </view>
        </view>

        <ListState
          :loading="loading && items.length === 0"
          :error="error"
          :empty="items.length === 0"
          empty-text="还没有消息"
          loading-text="正在加载…"
          testid-base="notify-state"
          @retry="reload"
        />

        <view
          v-for="n in items"
          :key="n.id"
          class="card row"
          :class="{ 'row--unread': !n.isRead }"
          data-testid="notify-row"
          :data-type="n.type"
          :data-read="n.isRead ? '1' : '0'"
          @click="onTap(n)"
        >
          <view class="row__icon">
            <HyIcon :type="n.iconType" size="md" :color="n.iconColor" />
          </view>
          <Avatar :url="n.fromAvatarUrl" :nickname="n.fromName" :size="36" />
          <view class="row__body">
            <text class="row__content" data-testid="notify-content">{{ n.content }}</text>
            <view class="row__meta">
              <text class="row__type">{{ n.typeLabel }}</text>
              <text class="row__dot">·</text>
              <text class="row__time">{{ n.timeText }}</text>
            </view>
          </view>
          <!-- 未读小圆点：这是"哪条是新的"的唯一视觉线索 -->
          <view v-if="!n.isRead" class="row__badge" data-testid="notify-row-unread" />
        </view>

        <view v-if="items.length && hasMore" class="more">
          <view class="more__btn" data-testid="notify-load-more" @click="loadMore">
            <text class="more__btn-text">{{ loadingMore ? '加载中…' : '加载更多' }}</text>
          </view>
        </view>
        <text v-else-if="items.length" class="more__end" data-testid="notify-list-end">
          没有更多消息了
        </text>
      </template>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 消息（通知）页 —— M5 前端的第一块。
 *
 * ==========================================================================
 * 三条口径（都写进代码，别靠记忆）
 * ==========================================================================
 * 1. **未登录不发请求**（同收藏/关注流）：401 会被转成"登录已过期"，
 *    对没登录的人来说是错误信息而不是引导。
 * 2. **未读数是"服务端的数"**，不是"本地算出来的"：顶栏红点与本页头部的未读数
 *    都来自 `GET /api/notifications/unread-count`；标记已读成功后**重新取一次**
 *    （而不是本地减一）—— 因为批量已读的条数由后端决定，本地减容易和真值分叉。
 * 3. **评论类通知跳不了**（契约没给 postId），点击只标已读并**明确告知原因**，
 *    不做假跳转。详见 `utils/notifyView.ts` 的文件头。
 */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import AppShell from '@/components/shell/AppShell.vue'
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import ListState from '@/components/ListState.vue'
import { fetchNotifications, fetchUnreadCount, markNotificationsRead } from '@/api/notifications'
import { ApiError, PAGE_SIZE_MAX } from '@/utils/request'
import { NOTIFY_FILTERS, toNotificationView, type NotificationView } from '@/utils/notifyView'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'

const auth = useAuthStore()
/** 顶栏红点与本页共享同一个未读数（见 stores/notify.ts） */
const notify = useNotifyStore()

const items = ref<NotificationView[]>([])
const typeFilter = ref(0)
const page = ref(1)
const loading = ref(false)
const loadingMore = ref(false)
const readingAll = ref(false)
const error = ref('')
const hasMore = ref(false)

const unread = computed(() => notify.unread)

/**
 * 取一页。
 * `append = false` 表示重新加载第一页（进入、切筛选、点重试都走这里）。
 */
async function load(targetPage: number, append: boolean): Promise<void> {
  if (!auth.isLoggedIn) return
  if (append) {
    if (loadingMore.value || !hasMore.value) return
    loadingMore.value = true
  } else {
    loading.value = true
  }
  error.value = ''
  try {
    const res = await fetchNotifications(typeFilter.value, targetPage)
    const list = res.list.map(toNotificationView)
    items.value = append ? items.value.concat(list) : list
    hasMore.value = res.list.length >= PAGE_SIZE_MAX
    page.value = targetPage
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '消息加载失败，请稍后重试'
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

/** 切筛选：页码复位后重拉（列表内容整体换掉，保留旧数据只会让人困惑） */
function changeFilter(next: number): void {
  if (typeFilter.value === next) return
  typeFilter.value = next
  reload()
}

/** 刷新未读数（走 store，顶栏红点会跟着变） */
async function refreshUnread(): Promise<void> {
  await notify.refresh()
}

/**
 * 点一条通知。
 *
 * 顺序是**先标已读、再跳转**：
 * - 标已读失败**不阻断跳转**（用户想看内容是主要目的，红点没消是次要问题）；
 * - 跳不了的那种（评论类）**给出明确原因**，不装作点了有效果。
 */
async function onTap(n: NotificationView): Promise<void> {
  if (!n.isRead) {
    n.isRead = true // 乐观：先把红点去掉，失败再恢复（见下）
    try {
      await markNotificationsRead({ ids: [n.id] })
      await refreshUnread()
    } catch {
      // 标已读失败：把红点恢复回去，但**不打断**下面的跳转
      n.isRead = false
    }
  }

  if (n.targetUrl) {
    uni.navigateTo({ url: n.targetUrl })
    return
  }
  // 不可跳：**说清楚为什么**，而不是点了没反应
  uni.showToast({ title: n.unreachableReason, icon: 'none', duration: 2600 })
}

/** 全部已读 */
async function readAll(): Promise<void> {
  if (readingAll.value) return
  readingAll.value = true
  try {
    await markNotificationsRead({ all: true })
    // 本地把当前页都标成已读，再向服务端确认真值（不本地减数）
    items.value = items.value.map((n) => ({ ...n, isRead: true }))
    await refreshUnread()
    uni.showToast({ title: '已全部标为已读', icon: 'none' })
  } catch (e) {
    uni.showToast({ title: e instanceof ApiError ? e.message : '操作失败，请稍后重试', icon: 'none' })
    // 失败就把这一页重新拉一次，避免界面停在"假装都读过了"
    reload()
  } finally {
    readingAll.value = false
  }
}

function goLogin(): void {
  uni.navigateTo({ url: '/pages/auth/index?mode=login' })
}

function goBack(): void {
  uni.navigateBack()
}

/*
 * 用 `onShow` 而不是 `onLoad`：从通知点进帖子再返回时，`onLoad` 不会再触发，
 * 那样刚标记的已读状态在返回后就看不到了。`onShow` 每次回到本页都刷新 —— 与首页同口径。
 */
onShow(() => {
  if (!auth.isLoggedIn) return
  void refreshUnread()
  reload()
})
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

  &__unread {
    margin-left: 8px;
    font-size: $hy-font-xs;
    color: $hy-color-danger;
  }

  &__action {
    margin-left: auto;
  }

  &__action-text {
    font-size: $hy-font-sm;
    color: $hy-color-primary;
  }
}

/* ---------- 未登录 ---------- */
.login-hint {
  padding: 44px 20px;
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

/* ---------- 类型筛选 ---------- */
.filters {
  margin-bottom: $hy-shell-gap;
  padding: 8px;
  display: flex;
  flex-wrap: wrap;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;

  &__item {
    margin: 2px 6px 2px 0;
    padding: 5px 14px;
    border-radius: $hy-radius-pill;

    &--active {
      background-color: $hy-color-primary-light;

      .filters__text {
        color: $hy-color-primary;
        font-weight: 600;
      }
    }
  }

  &__text {
    font-size: $hy-font-sm;
    color: $hy-text-regular;
  }
}

/* ---------- 一条通知 ---------- */
.row {
  display: flex;
  align-items: center;

  /* 未读：左侧一条主色竖线 + 稍亮的底色，让"新的"一眼可见 */
  &--unread {
    border-left: 3px solid $hy-color-primary;
  }

  &__icon {
    margin-right: 10px;
  }

  &__body {
    flex: 1;
    min-width: 0;
    margin-left: 10px;
  }

  &__content {
    font-size: $hy-font-md;
    line-height: 1.6;
    color: $hy-text-primary;
    word-break: break-word;
  }

  &__meta {
    margin-top: 4px;
    display: flex;
    align-items: center;
  }

  &__type {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__dot {
    margin: 0 6px;
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }

  &__time {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__badge {
    margin-left: 10px;
    width: 8px;
    height: 8px;
    background-color: $hy-color-danger;
    border-radius: 50%;
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
