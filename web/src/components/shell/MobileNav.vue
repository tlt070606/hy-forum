<template>
  <!--
    移动端底部导航（**仅窄屏出现**，宽屏由 CSS 隐藏）。

    ⚠️ 为什么不用 `pages.json` 的 `tabBar`：
       tabBar 在 H5 端是固定在视口底部的独立层，桌面宽屏下会变成一条与三栏版式无关的横条
       （参考图里没有这个东西）。自绘的可以用 @media 精确控制"宽屏不出现、窄屏才出现"。
       代价是失去了 tabBar 的原生切换优化 —— 对这个规模的应用可以接受。

    ⚠️ 为什么「我的」指向 M2 已交付的 `pages/me/index`：
       那一页是真实存在且已验收的（资料卡 + 统计 + 退出登录）。
       让它成为本设计的"我的"入口，比再造一个假页面好 ——
       新视觉会在下一轮统一（它目前仍是 M2 的移动端版式）。
  -->
  <view class="mobile-nav" data-testid="mobile-nav">
    <view
      v-for="item in ITEMS"
      :key="item.key"
      class="mobile-nav__item"
      :class="{ 'mobile-nav__item--active': item.key === active }"
      :data-testid="`mobile-nav-${item.key}`"
      @click="onTap(item)"
    >
      <view class="mobile-nav__icon-wrap">
        <HyIcon :type="item.icon" size="lg" class="mobile-nav__icon" />
        <!-- 未读红点：只在「消息」上、且确实有未读时出现（数与顶栏同源） -->
        <view
          v-if="item.key === 'notifications' && notify.unread > 0"
          class="mobile-nav__badge"
          data-testid="mobile-nav-badge"
        />
      </view>
      <text class="mobile-nav__label">{{ item.label }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import HyIcon, { type IconType } from '@/components/HyIcon.vue'
import { useNotifyStore } from '@/stores/notify'

const notify = useNotifyStore()

withDefaults(defineProps<{ active?: 'home' | 'topic' | 'collect' | 'me' | 'notifications' | '' }>(), { active: '' })

interface NavItem {
  key: 'home' | 'topic' | 'notifications' | 'collect' | 'me'
  label: string
  icon: IconType
  url: string | null
}

const ITEMS: readonly NavItem[] = [
  { key: 'home', label: '首页', icon: 'home', url: null },
  { key: 'topic', label: '话题', icon: 'hash', url: '/pages/topic/index' },
  /*
   * ⚠️ 这个入口是**必须有的**：窄屏下顶栏的铃铛被 `display:none` 隐藏了
   *    （顶栏注释写着"底部导航才是移动端主入口"），而在本项加入之前，
   *    底部导航里**没有任何消息入口** —— 也就是说**手机上根本进不去消息页**。
   *    这是新增消息功能后由 E2E 抓到的真问题（原注释的假设对通知不成立）。
   */
  { key: 'notifications', label: '消息', icon: 'bell', url: '/pages/notifications/index' },
  { key: 'collect', label: '收藏', icon: 'bookmark', url: '/pages/collect/index' },
  { key: 'me', label: '我的', icon: 'user', url: '/pages/me/index' },
] as const

function onTap(item: NavItem): void {
  if (!item.url) return
  // 「我的」是 tabBar 语义的根页 → reLaunch；其余用 navigateTo 保留返回栈
  if (item.key === 'me' || item.key === 'home') {
    uni.navigateTo({ url: item.url })
    return
  }
  uni.navigateTo({ url: item.url })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.mobile-nav {
  /* 宽屏下整体不出现 */
  display: none;
}

@media (max-width: $hy-shell-breakpoint) {
  .mobile-nav {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 90;
    display: flex;
    align-items: center;
    height: $hy-mobile-nav-height;
    /* 刘海屏/手势条安全区：不支持 env() 的设备该值回落为 0，不影响布局 */
    padding-bottom: constant(safe-area-inset-bottom);
    padding-bottom: env(safe-area-inset-bottom);
    background-color: $hy-bg-card;
    border-top: 1px solid $hy-border-color;

    &__item {
      flex: 1;
      height: $hy-mobile-nav-height;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
    }

    &__item--active {
      .mobile-nav__icon {
        color: $hy-color-primary;
      }

      .mobile-nav__label {
        color: $hy-color-primary;
        font-weight: 600;
      }
    }

    &__icon-wrap {
      position: relative;
      /* 图标与文字之间留 2px，太高会把文字挤出导航条 */
      margin-bottom: 2px;
    }

    /* 红点：绝对定位到图标右上角，不参与布局（不会把 5 个入口挤变形） */
    &__badge {
      position: absolute;
      right: -4px;
      top: -2px;
      width: 8px;
      height: 8px;
      background-color: $hy-color-danger;
      border-radius: 50%;
    }

    &__label {
      font-size: 10px;
      color: $hy-text-secondary;
    }
  }
}
</style>
