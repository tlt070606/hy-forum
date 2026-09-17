<template>
  <view class="shell-page">
    <TopBar />

    <view class="shell">
      <view class="shell__inner">
        <!--
          左栏。⚠️ 用**外层包一层再隐藏**的方式做响应式，而不是 `v-if` + JS 断点：
          纯 CSS 的 @media 不依赖运行时宽度计算，三端行为一致，也不会在首屏闪一下。
        -->
        <view class="shell__left">
          <LeftRail :active="activeNav" :post-total="postTotal" />
        </view>

        <!-- 中间主列 -->
        <view class="shell__center">
          <slot />
        </view>

        <view class="shell__right">
          <RightRail />
        </view>
      </view>
    </view>

    <!-- 移动端底部导航（仅窄屏出现） -->
    <MobileNav :active="activeNav" />
  </view>
</template>

<script setup lang="ts">
/**
 * 三栏外壳（顶栏 + 左栏 + 主列 + 右栏）。
 *
 * ==========================================================================
 * 响应式：宽屏三栏 / 窄屏单栏
 * ==========================================================================
 * 需求方 2026-09-16 选定。断点是一个 SCSS 变量（`$hy-shell-breakpoint` = 1000px），
 * 取值理由见 `styles/variables.scss`：左 240 + 右 300 + 中栏最小可用 ~420 + 两道 20 间隙 = 1000。
 * 低于它就必须塌成单栏，否则中间的信息流会被压到不可读。
 *
 * ⚠️ 窄屏时左/右栏是 **`display: none` 隐藏**，不是不渲染 ——
 *    因此窄屏下左栏的「今日数据」等请求仍然发生。这是刻意的：
 *    那些数据本来就要给移动端用（底部导航 + 首页顶部的统计），
 *    而且避免"先渲染再按宽度决定请求"这种会闪的写法。
 *    但**右侧栏的假数据因此也在窄屏被计算**（纯常量，无成本）。
 *
 * ⚠️ 为什么移动端用**自绘的底部导航**而不是 `pages.json` 的 `tabBar`：
 *    tabBar 在 H5 端是固定在视口底部的独立层，在桌面宽屏下会变成一条贴着屏幕底边、
 *    与三栏版式无关的横条（参考图里没有这个东西）。自绘的可以用 @media 精确控制：
 *    宽屏不出现、窄屏才出现。
 */
import TopBar from '@/components/shell/TopBar.vue'
import LeftRail from '@/components/shell/LeftRail.vue'
import RightRail from '@/components/shell/RightRail.vue'
import MobileNav from '@/components/shell/MobileNav.vue'

withDefaults(
  defineProps<{
    /**
     * 当前激活的导航项。转发给左栏与底部导航（`me` 只在底部导航有对应项）。
     * `''` = **不高亮任何一项** —— 搜索页 / 版块页 / 详情页都不属于左栏那三个入口，
     * 硬把其中一项点亮是对用户说假话（会让人以为"我在首页"）。
     */
    activeNav?: 'home' | 'topic' | 'collect' | 'me' | ''
    /** 帖子总数：`null` = 未知（请求中/失败），显示 `—` 而不是 `0` */
    postTotal?: number | null
  }>(),
  { activeNav: 'home', postTotal: null }
)
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.shell-page {
  min-height: 100vh;
  background-color: $hy-bg-page;
}

.shell {
  max-width: $hy-shell-max-width;
  margin: 0 auto;
  padding: $hy-shell-gap 24px 40px;
  box-sizing: border-box;

  &__inner {
    display: flex;
    align-items: flex-start;
  }

  &__left {
    /* 宽度交给 LeftRail 自己定（它需要知道自己多宽才能排版） */
    flex-shrink: 0;
    margin-right: $hy-shell-gap;
    /* 参考图的左右栏随页面滚动（不是吸顶），先按最简实现 —— 
           吸顶需要 position: sticky + 各端对 sticky 的支持差异，留到视觉定稿后再评估 */
  }

  &__center {
    flex: 1;
    /* ⚠️ min-width: 0 是必须的：flex 子项默认 min-width:auto，
       长标题不会触发省略号而是把整行撑宽，把左右栏挤出屏幕 */
    min-width: 0;
  }

  &__right {
    flex-shrink: 0;
    margin-left: $hy-shell-gap;
  }
}

/* ==========================================================================
 * 窄屏：塌成单栏 + 让出底部导航的高度
 * ========================================================================== */
@media (max-width: $hy-shell-breakpoint) {
  .shell {
    padding: 12px 12px 0;
    /* 底部留白 = 导航高度 + 安全区 + 一点呼吸 */
    padding-bottom: calc(#{$hy-mobile-nav-height} + 24px);
  }

  .shell__inner {
    /* 显式覆盖成块级流：flex + 隐藏子项在部分端会留下 margin 残影 */
    display: block;
  }

  .shell__left,
  .shell__right {
    display: none;
  }
}
</style>
