<template>
  <!--
    顶栏：品牌 + 全局搜索 + 通知 + 头像菜单。
    布局按参考图：logo 靠左，搜索框偏左居中，右侧是通知与头像。
  -->
  <view class="topbar">
    <view class="topbar__inner">
      <!-- 品牌。点击回首页 -->
      <view class="brand" data-testid="topbar-brand" @click="goHome">
        <text class="brand__text">Hy论坛</text>
      </view>

      <!--
        搜索框。
        ⚠️ 这是**假输入框**（`view` 而非 `input`）：真实输入发生在 `pages/search/index`。
           理由与参考图一致 —— 点哪都是跳搜索页，在顶栏输入不会有结果，
           做成真输入框反而会让人以为"输了没反应"。也避免输入法弹起时顶栏抖动。
      -->
      <view class="search" data-testid="topbar-search" @click="goSearch">
        <HyIcon type="search" size="sm" class="search__icon" />
        <text class="search__placeholder">搜索话题、用户...</text>
      </view>

      <view class="actions">
        <!-- 通知。接口属 M5，契约里没有 → 未读数是假数据（见 mock/hotContent.ts） -->
        <view class="action" data-testid="topbar-bell" @click="goNotifications">
          <HyIcon type="bell" size="lg" />
          <view v-if="unread > 0" class="action__badge">
            <text class="action__badge-text">{{ unread > 99 ? '99+' : unread }}</text>
          </view>
        </view>

        <!-- 头像 / 登录入口 -->
        <view class="user" data-testid="topbar-user" @click="onUserTap">
          <Avatar
            v-if="auth.isLoggedIn"
            :url="auth.user?.avatarUrl || ''"
            :nickname="auth.displayName"
            :size="32"
          />
          <view v-else class="user__login">
            <text class="user__login-text">登录</text>
          </view>
        </view>
      </view>
    </view>

    <!--
      下拉菜单。
      ⚠️ 遮罩层不是装饰：用来做**点击外部关闭**。
         uni-app 里监听 `document` 点击在小程序端不存在，用一层透明全屏遮罩
         是唯一三端通用、又不依赖平台 API 的办法。
    -->
    <view v-if="menuOpen" class="menu-mask" @click="menuOpen = false" />
    <view v-if="menuOpen" class="menu" data-testid="topbar-menu">
      <view class="menu__head">
        <text class="menu__name" data-testid="topbar-menu-name">{{ auth.displayName }}</text>
        <text class="menu__id">ID: {{ auth.user?.id ?? '—' }}</text>
      </view>
      <view class="menu__divider" />
      <view class="menu__item" data-testid="topbar-menu-profile" @click="goProfile">
        <HyIcon type="user" size="md" />
        <text class="menu__label">个人资料</text>
      </view>
      <view class="menu__item" data-testid="topbar-menu-settings" @click="goStub('设置')">
        <HyIcon type="settings" size="md" />
        <text class="menu__label">设置</text>
      </view>
      <view class="menu__divider" />
      <view class="menu__item menu__item--danger" data-testid="topbar-menu-logout" @click="onLogout">
        <HyIcon type="logout" size="md" color="#f53f3f" />
        <text class="menu__label menu__label--danger">退出登录</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 顶栏。
 *
 * 真/假数据的边界（需求方 2026-09-16 定的口径：真接口优先，无契约才用假数据）：
 * - 登录态、昵称、ID → **真**（`stores/auth.ts`，底层 `GET /api/user/me`）
 * - 退出登录 → **真**（`POST /api/auth/logout`）
 * - 未读通知数 → **假**（通知接口属 M5，契约 14 个路径里没有）
 */
import { ref } from 'vue'
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import { MOCK_UNREAD_NOTIFICATION_COUNT } from '@/mock/hotContent'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

/** 未读通知数（假数据，见文件头说明） */
const unread = ref(MOCK_UNREAD_NOTIFICATION_COUNT)

const menuOpen = ref(false)

function goHome(): void {
  // reLaunch 而不是 navigateTo：首页是"根"，用 navigateTo 会把页面栈越堆越深
  uni.reLaunch({ url: '/pages/index/index' })
}

function goSearch(): void {
  uni.navigateTo({ url: '/pages/search/index' })
}

function goNotifications(): void {
  uni.showToast({ title: '通知功能将在 M5 交付', icon: 'none', duration: 2000 })
}

/** 点头像：已登录展开菜单，未登录直接去登录页（而不是弹一句"请先登录"） */
function onUserTap(): void {
  if (!auth.isLoggedIn) {
    uni.navigateTo({ url: '/pages/auth/index?mode=login' })
    return
  }
  menuOpen.value = !menuOpen.value
}

/**
 * 未交付页面的统一出口。
 *
 * 不用假页面冒充已完成（项目既有约定）：这些页面**确实还没铺**，
 * 所以跳到一个明确说明"这一页还没做"的占位页，而不是给一个空白页让人以为坏了。
 */
function goStub(what: string): void {
  menuOpen.value = false
  uni.navigateTo({ url: `/pages/placeholder/index?title=${encodeURIComponent(what)}` })
}

/**
 * 「个人资料」→ M2 已交付的 `pages/me/index`。
 *
 * 为什么不走占位页：那一页**真实存在且已验收**（资料卡 + 统计 + 菜单 + 退出登录），
 * 有真的页面却把人引到"还没做"，是把已有的东西藏起来。
 * 它会用新设计令牌重绘（下一轮统一），但功能是真的。
 */
function goProfile(): void {
  menuOpen.value = false
  uni.navigateTo({ url: '/pages/me/index' })
}

async function onLogout(): Promise<void> {
  menuOpen.value = false
  const res = await uni.showModal({
    title: '退出登录',
    content: '确定要退出当前账号吗？',
  })
  if (!res.confirm) return

  // 无论服务端注销成功与否都清本地态（见 stores/auth.ts 的说明）
  await auth.logout()
  uni.showToast({ title: '已退出登录', icon: 'none' })
  // 回首页（而不是登录页）：新设计里首页未登录也能浏览，退出后停在原地更自然
  uni.reLaunch({ url: '/pages/index/index' })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.topbar {
  position: sticky;
  top: 0;
  z-index: 100;
  background-color: $hy-bg-card;
  border-bottom: 1px solid $hy-border-color;
  /*
   * 安全区顶部内边距。
   *
   * 新页面用了 `navigationStyle: "custom"`（隐藏 uni-app 原生导航栏），
   * 所以顶栏必须自己让开刘海/状态栏，否则在 iPhone 与小程序上 logo 会被状态栏压住。
   *
   * - H5（桌面 / 安卓浏览器）：`env()` 为 0 → 无影响
   * - H5（iOS Safari）：让开刘海
   * - 小程序：`env(safe-area-inset-top)` 只能**近似**表示状态栏高度（安卓常为 0）。
   *   小程序端本就是「仅练习不上线」，这个近似可接受；真上线要改用
   *   `uni.getSystemInfoSync().statusBarHeight` 动态计算。
   *
   * 两行 `padding-top` 是**刻意的**：旧版 iOS 只认 `constant()`，写两行让它兼得。
   */
  padding-top: constant(safe-area-inset-top);
  padding-top: env(safe-area-inset-top);

  &__inner {
    max-width: $hy-shell-max-width;
    margin: 0 auto;
    height: $hy-shell-header-height;
    padding: 0 24px;
    box-sizing: border-box;
    display: flex;
    align-items: center;
  }
}

.brand {
  flex-shrink: 0;
  margin-right: 40px;

  &__text {
    font-size: $hy-font-xxl;
    font-weight: 700;
    color: $hy-color-primary;
    letter-spacing: 1px;
  }
}

/* ---------- 搜索 ---------- */
.search {
  flex: 1;
  /* 不铺满：参考图里搜索框宽度约 620/1920，留出右半边的呼吸空间 */
  max-width: 620px;
  height: 40px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  background-color: $hy-bg-page;
  border: 1px solid $hy-border-color;
  border-radius: $hy-radius-pill;

  &__icon {
    margin-right: 8px;
  }

  &__placeholder {
    font-size: $hy-font-md;
    color: $hy-text-placeholder;
  }
}

.actions {
  margin-left: auto;
  display: flex;
  align-items: center;
}

.action {
  position: relative;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;

  &__badge {
    position: absolute;
    top: 2px;
    right: 2px;
    min-width: 16px;
    height: 16px;
    padding: 0 4px;
    box-sizing: border-box;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: $hy-color-danger;
    border-radius: $hy-radius-pill;
  }

  &__badge-text {
    font-size: 10px;
    line-height: 1;
    color: $hy-text-inverse;
  }
}

.user {
  margin-left: 8px;
  display: flex;
  align-items: center;

  &__login {
    height: 32px;
    padding: 0 16px;
    display: flex;
    align-items: center;
    background-color: $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__login-text {
    font-size: $hy-font-sm;
    color: $hy-text-inverse;
  }
}

/* ---------- 下拉菜单 ---------- */
.menu-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 110;
}

.menu {
  position: absolute;
  /*
   * 右对齐到容器内边距（24px）处。
   * 用 `right` 而不是固定 left：顶栏宽度随窗口变化，写死 left 会在窄屏上跑到屏幕外。
   */
  right: 24px;
  top: calc(#{$hy-shell-header-height} - 6px);
  z-index: 111;
  width: 200px;
  padding: 8px 0;
  background-color: $hy-bg-card;
  border: 1px solid $hy-border-color;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-pop;

  &__head {
    padding: 8px 16px 10px;
    display: flex;
    flex-direction: column;
  }

  &__name {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__id {
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__divider {
    height: 1px;
    margin: 4px 0;
    background-color: $hy-border-color;
  }

  &__item {
    height: 40px;
    padding: 0 16px;
    display: flex;
    align-items: center;

    &:active {
      background-color: $hy-bg-hover;
    }
  }

  &__label {
    margin-left: 10px;
    font-size: $hy-font-md;
    color: $hy-text-regular;

    &--danger {
      color: $hy-color-danger;
    }
  }
}

/* ==========================================================================
 * 窄屏（单栏）：品牌字号收小、搜索框压缩、隐藏通知
 * ========================================================================== */
@media (max-width: $hy-shell-breakpoint) {
  .topbar__inner {
    padding: 0 12px;
  }

  .brand {
    margin-right: 12px;

    &__text {
      font-size: 20px;
    }
  }

  .search {
    height: 34px;
    padding: 0 12px;

    &__placeholder {
      font-size: $hy-font-sm;
    }
  }

  /* 通知在窄屏收起：底部导航才是移动端的主入口，顶栏要留给搜索 */
  .action {
    display: none;
  }

  .menu {
    right: 12px;
  }
}
</style>
