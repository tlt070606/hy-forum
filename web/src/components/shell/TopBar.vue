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
          <HyIcon type="bell" size="lg" color="#4e5969" />
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

          <!--
            下拉菜单**放在头像的父节点里面**（而不是放在顶栏根节点上）。
            ⚠️ 这是一处真实修过的 bug（需求方 2026-09-18：「这个不知道跑去哪里了呀，
               没有直接在那个打开的正下方」）：
               原来 `.menu` 是 `position:absolute; right:24px`，定位基准是**顶栏整条**
               （宽度 = 视口宽），而顶栏内容有 `max-width:1280px` 居中 ——
               宽屏下内容右边缘在 x≈1576，菜单却贴到 x≈1896，
               **跑到头像右边三百多像素**，看起来就像"不知道飞哪去了"。
               现在基准是 `.actions`（`position:relative`），`right:0` + `top:100%`
               → 菜单右边缘与头像右边缘对齐、紧贴其下方。
          -->
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
            <view class="menu__item" data-testid="topbar-menu-settings" @click="goSettings">
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
      </view>
    </view>

    <!--
      下拉菜单。
      ⚠️ 遮罩层不是装饰：用来做**点击外部关闭**。
         uni-app 里监听 `document` 点击在小程序端不存在，用一层透明全屏遮罩
         是唯一三端通用、又不依赖平台 API 的办法。
    -->
    <view v-if="menuOpen" class="menu-mask" @click="menuOpen = false" />

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
import { computed, onMounted, ref } from 'vue'
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'

const auth = useAuthStore()

/*
 * 未读数是**真数据**了（M5：`GET /api/notifications/unread-count`）。
 * 放在 `stores/notify.ts` 里与消息页共享 —— 两处各请求一次的话，
 * 会出现"顶栏 3、页面 2"这种自相矛盾的状态。
 */
const notify = useNotifyStore()
const unread = computed(() => notify.unread)

/*
 * 冷启动取一次未读数。用 `onMounted` 而不是 `onShow`：
 * 顶栏是**全局组件**（每个页面都有），`onShow` 会在每次切页时都打一次接口 ——
 * 那是"每翻一页多一个请求"，不值得。消息页自己会在 `onShow` 里刷新，
 * 顶栏拿共享值即可。
 */
onMounted(() => {
  void notify.refresh()
})

const menuOpen = ref(false)

function goHome(): void {
  // reLaunch 而不是 navigateTo：首页是"根"，用 navigateTo 会把页面栈越堆越深
  uni.reLaunch({ url: '/pages/index/index' })
}

function goSearch(): void {
  uni.navigateTo({ url: '/pages/search/index' })
}

/** 跳消息页（M5 已交付，不再是"未交付"提示） */
function goNotifications(): void {
  menuOpen.value = false
  uni.navigateTo({ url: '/pages/notifications/index' })
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
/** 跳设置页（菜单里的「设置」） */
function goSettings(): void {
  menuOpen.value = false
  uni.navigateTo({ url: '/pages/settings/index' })
}

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
  /*
   * 顶栏底色 = **品牌淡紫**（需求方 2026-09-18：「导航栏变颜色」）。
   *
   * 为什么用它而不是别的：
   * - 参考站的顶栏就是这一档淡紫，`$hy-color-primary-light` 正是同一个色阶，
   *   与左侧导航的激活块、标签底色**同源**，整站不会多出一个"野生色"；
   * - 去掉原来的下边框：淡紫块与页面底 `#f7f7fb` 已经有足够区分，
   *   再加一条灰线会显得脏（两个相近的浅色之间夹一条深线很难看）。
   *
   * ⚠️ 顶栏是**全局组件**，改这里全站生效（首页/详情/搜索/版块/个人主页都在用）。
   */
  background-color: $hy-color-primary-light;
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
/*
 * 搜索框改成**白底**（原来是页面底 `#f7f7fb`）。
 * ⚠️ 这一步是换底色之后的**必要连带改动**：`#f7f7fb` 与淡紫顶栏是两个相近的浅色，
 *    叠在一起会"发脏"（看着像没洗干净），而白底在淡紫上边界清晰 ——
 *    参考站也是这么处理的（紫底上放浅色药丸）。
 */
.search {
  flex: 1;
  /* 不铺满：参考图里搜索框宽度约 620/1920，留出右半边的呼吸空间 */
  max-width: 620px;
  height: 40px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  background-color: $hy-bg-card;
  border: 1px solid $hy-color-primary-border;
  border-radius: $hy-radius-pill;

  &__icon {
    margin-right: 8px;
  }

  /*
   * 占位文字加粗（需求方 2026-09-18 明确指定的一处：「搜索框占位文字」）。
   * ⚠️ 注意必须写在这个 `placeholder-class` 上 —— 占位符的样式由 uni 单独渲染，
   *    在 `.search__input` 上写 `font-weight` 对占位符**不生效**。
   * 另外把颜色也压深了一档：原 `$hy-text-placeholder` 是给"比正文更轻"的提示用的，
   * 在白底药丸里加上加粗会显得发灰。
   */
  &__placeholder {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-secondary;
  }
}

.actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  /*
   * ⚠️ 必须 relative：下拉菜单以它（而不是顶栏整条）为定位基准。
   * 见模板里那段注释 —— 用顶栏整条做基准会在宽屏把菜单推到头像右侧三百多像素。
   */
  position: relative;
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

  /*
   * 铃铛图标在**淡紫底**上要更深一档（原来用 HyIcon 的默认色 #86909c）。
   * 上面已经用 `color="#4e5969"` 显式传了，这里只留注释说明原因：
   * 浅灰图标压在淡紫上会"糊"在一起，看不出是图标还是背景纹理。
   */
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
   * 贴着头像：右边缘对齐、位于其正下方（需求方 2026-09-18 的原始要求）。
   * 基准是 `.actions`（已设 position: relative），**不是顶栏整条** ——
   * 后者宽度等于视口宽，而顶栏内容居中且有 max-width，宽屏下两者相差三百多像素。
   */
  right: 0;
  top: calc(100% + 8px);
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
