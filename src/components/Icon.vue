<template>
  <!--
    纯 CSS 绘制的图标，**不使用任何图片资源或字体**。

    ==========================================================================
    为什么不用其它方案（这是跨端一致性的关键决策）
    ==========================================================================
    1. **SVG data URI**（我最初的做法，已废弃）
       缺点：微信小程序的 `<image>` 对 SVG 支持不可靠（贴吧/wiki 均记录过
       "真机与工具表现不一致"）。而图标全空白是**致命**的观感问题，
       不能赌平台实现。
    2. **图标字体**（如 wd-icon）
       缺点：需要确保字体文件被正确打包与加载，小程序端字体加载有异步时序问题。
       能工作，但多一层不确定性。
    3. **`<image>` + PNG 文件 → 需要为每个图标准备资源、且无法换色**
       缺点：得往 `src/static/` 塞一堆文件，颜色写死。

    4. **纯 CSS 绘制（本方案）**
       优点：零资源、零加载时序、**三端渲染完全一致**（就是 border/background/transform）、
             颜色可用 SCSS 变量直接控制，不依赖任何平台特性。
       代价：只能做简单几何图形（圆、矩形、线条、三角），
             但本项目需要的恰好都是这类（用户、锁、眼睛、盾、票券、加号）。

    结论：对于"小而简单、不允许出问题"的图标，纯 CSS 是最稳的选择。
    将来若要更精致的图标，建议用 **PNG/SVG 文件放进 `src/static/`** 并用
    `<image>` 引用（三端都支持静态文件），而不是 SVN data URI 或图标字体。
  -->
  <view class="icon" :class="[`icon--${type}`, `icon--${size}`]">
    <!-- 锁：锁体 + 锁梁 -->
    <template v-if="type === 'lock'">
      <view class="icon__lock-shackle" />
      <view class="icon__lock-body" />
    </template>

    <!-- 眼睛 / 闭眼：圆形瞳孔；闭眼多一条对角线 -->
    <template v-else-if="type === 'eye' || type === 'eye-off'">
      <view class="icon__eye-ring" />
      <view class="icon__eye-pupil" />
      <view v-if="type === 'eye-off'" class="icon__eye-slash" />
    </template>

    <!-- 盾牌：上窄下宽的多边形（用 clip-path 支持度不一，改用边框三角 + 矩形拼） -->
    <template v-else-if="type === 'shield'">
      <view class="icon__shield-body" />
    </template>

    <!-- 票券：矩形 + 中间虚线 -->
    <template v-else-if="type === 'ticket'">
      <view class="icon__ticket-body" />
      <view class="icon__ticket-line" />
    </template>

    <!-- 加号（注册 Tab 用；与"用户"组合表示新增用户） -->
    <template v-else-if="type === 'plus'">
      <view class="icon__plus-h" />
      <view class="icon__plus-v" />
    </template>

    <!-- 箭头（登录 Tab / 进入） -->
    <template v-else-if="type === 'arrow'">
      <view class="icon__arrow-line" />
      <view class="icon__arrow-head" />
    </template>

    <!-- 用户：圆头 + 半圆肩（默认类型） -->
    <template v-else>
      <view class="icon__head" />
      <view class="icon__shoulder" />
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * 纯 CSS 图标组件。
 *
 * `easycom` 不会自动识别 `src/components/Icon.vue`（它的规则是目录名 = 组件名），
 * 因此使用方需要显式 import。这比在 `pages.json` 里加 custom 规则更直观。
 */
export type IconType = 'user' | 'lock' | 'eye' | 'eye-off' | 'shield' | 'ticket' | 'plus' | 'arrow'
export type IconSize = 'sm' | 'md' | 'lg'

withDefaults(
  defineProps<{
    /** 图形类型 */
    type?: IconType
    /** 尺寸：sm 用于 Tab，md 用于输入框内，lg 用于头部品牌 */
    size?: IconSize
  }>(),
  {
    type: 'user',
    size: 'md',
  }
)
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

/*
 * 图标统一规则：
 * - `position: relative` + 内部绝对定位的子元素，保证图形锚定在盒子中心
 * - 颜色统一用 `$hy-icon-color`，需要换色时改这一个变量（或在页面里覆盖）
 * - 尺寸用 rpx，三端等比一致
 */
.icon {
  position: relative;
  display: inline-block;
  flex-shrink: 0;
  color: $hy-icon-color;

  /* ---------- 尺寸 ---------- */
  &--sm {
    width: 32rpx;
    height: 32rpx;
  }
  &--md {
    width: 36rpx;
    height: 36rpx;
  }
  &--lg {
    width: 64rpx;
    height: 64rpx;
  }

  /* ---------- 用户：圆头 + 肩 ---------- */
  &__head {
    position: absolute;
    left: 50%;
    top: 6%;
    width: 46%;
    height: 46%;
    margin-left: -23%;
    border-radius: 50%;
    border: 3rpx solid currentColor;
    box-sizing: border-box;
  }

  &__shoulder {
    position: absolute;
    left: 50%;
    bottom: 8%;
    width: 78%;
    height: 42%;
    margin-left: -39%;
    /* 只保留上半圆的边框，做成"肩膀"轮廓 */
    border: 3rpx solid currentColor;
    border-bottom: none;
    border-radius: 50% 50% 0 0 / 100% 100% 0 0;
    box-sizing: border-box;
  }

  /* ---------- 锁：锁体 + 锁梁 ---------- */
  &__lock-body {
    position: absolute;
    left: 12%;
    bottom: 6%;
    width: 76%;
    height: 56%;
    border: 3rpx solid currentColor;
    border-radius: 4rpx;
    box-sizing: border-box;
  }

  &__lock-shackle {
    position: absolute;
    left: 50%;
    top: 4%;
    width: 44%;
    height: 42%;
    margin-left: -22%;
    border: 3rpx solid currentColor;
    border-bottom: none;
    border-radius: 50% 50% 0 0 / 100% 100% 0 0;
    box-sizing: border-box;
  }

  /* ---------- 眼睛 ---------- */
  &__eye-ring {
    position: absolute;
    left: 2%;
    top: 50%;
    width: 96%;
    height: 56%;
    margin-top: -28%;
    border: 3rpx solid currentColor;
    border-radius: 50%;
    box-sizing: border-box;
  }

  &__eye-pupil {
    position: absolute;
    left: 50%;
    top: 50%;
    width: 26%;
    height: 26%;
    margin-left: -13%;
    margin-top: -13%;
    border-radius: 50%;
    background-color: currentColor;
  }

  /* 斜线表示"隐藏"：旋转 45° 的细长矩形，跨过整个图标 */
  &__eye-slash {
    position: absolute;
    left: 50%;
    top: 50%;
    width: 112%;
    height: 3rpx;
    margin-left: -56%;
    margin-top: -1.5rpx;
    background-color: currentColor;
    transform: rotate(-45deg);
  }

  /* ---------- 盾牌：上方矩形 + 下方收窄（用两个矩形近似）---------- */
  &__shield-body {
    position: absolute;
    left: 14%;
    top: 6%;
    width: 72%;
    height: 88%;
    border: 3rpx solid currentColor;
    box-sizing: border-box;
    /* 上圆角、下尖角：用 border-radius 的斜杠语法做下尖 */
    border-radius: 20% 20% 50% 50% / 12% 12% 60% 60%;
  }

  /* ---------- 票券 ---------- */
  &__ticket-body {
    position: absolute;
    left: 4%;
    top: 22%;
    width: 92%;
    height: 56%;
    border: 3rpx solid currentColor;
    border-radius: 4rpx;
    box-sizing: border-box;
  }

  &__ticket-line {
    position: absolute;
    left: 50%;
    top: 22%;
    width: 3rpx;
    height: 56%;
    margin-left: -1.5rpx;
    /* 虚线竖线：用 repeating-linear-gradient 画，避免 border-style: dashed 在小程序端表现不一 */
    background-image: linear-gradient(
      to bottom,
      currentColor 0,
      currentColor 4rpx,
      transparent 4rpx,
      transparent 8rpx
    );
  }

  /* ---------- 加号 ---------- */
  &__plus-h {
    position: absolute;
    left: 10%;
    top: 50%;
    width: 80%;
    height: 3rpx;
    margin-top: -1.5rpx;
    background-color: currentColor;
  }

  &__plus-v {
    position: absolute;
    left: 50%;
    top: 10%;
    width: 3rpx;
    height: 80%;
    margin-left: -1.5rpx;
    background-color: currentColor;
  }

  /* ---------- 箭头 ---------- */
  &__arrow-line {
    position: absolute;
    left: 10%;
    top: 50%;
    width: 80%;
    height: 3rpx;
    margin-top: -1.5rpx;
    background-color: currentColor;
  }

  &__arrow-head {
    position: absolute;
    right: 10%;
    top: 50%;
    width: 34%;
    height: 34%;
    margin-top: -17%;
    border-top: 3rpx solid currentColor;
    border-right: 3rpx solid currentColor;
    box-sizing: border-box;
    transform: rotate(45deg);
  }
}
</style>
