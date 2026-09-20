<template>
  <AppShell :active-nav="activeNav">
    <view class="stub" data-testid="stub-page">
      <view class="stub__icon">
        <HyIcon type="shield" size="xl" color="#c9cdd4" />
      </view>
      <text class="stub__title" data-testid="stub-title">{{ title }}还没有做</text>
      <text class="stub__desc">
        这一页的界面还没有按参考图铺出来。本轮交付的是三栏外壳 + 首页，
        其余页面会按参考图逐个补上。
      </text>
      <text class="stub__desc stub__desc--muted">
        它对应的后端能力也未必已交付。这里既没有假界面、也没有假数据 ——
        项目约定是「接口没交付时明确说明，不用假数据冒充已完成」。
      </text>
      <view class="stub__back" data-testid="stub-back" @click="goHome">
        <text class="stub__back-text">回到首页</text>
      </view>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 「这一页还没做」的共用占位内容。
 *
 * ==========================================================================
 * 为什么需要它，而不是让链接点了没反应
 * ==========================================================================
 * 三栏外壳 + 首页这一轮里，导航与卡片会指向若干**还没有铺**的页面
 * （话题 / 收藏 / 搜索 / 帖子详情 / 发帖 / 版块）。
 * 三种做法：
 * ① 链接不可点 → 用户以为坏了；
 * ② 每页各写一个空白页 → 看起来像渲染失败；
 * ③ **明确说明状态的占位页** ← 本方案。
 *
 * 它同时是一道**防"假完成"的闸门**：占位页存在的地方，就说明那里**确实**没有实现，
 * 而不是被人悄悄填了假界面。
 *
 * ⚠️ 刻意做成「共用组件 + 每个路由一个薄壳」，而不是让所有链接都跳到
 *    `/pages/placeholder/index?title=xxx`：
 *    后者会让 `postView.ts` 里 `detailUrl = '/pages/post/detail?id=…'` 变成一句假话
 *    （名字叫详情、实际是占位），下一轮还得回头改所有调用点。
 *    薄壳方案**路由是稳定的**，下一轮只替换页面内容，调用点一个都不用动。
 */
import AppShell from '@/components/shell/AppShell.vue'
import HyIcon from '@/components/HyIcon.vue'

withDefaults(
  defineProps<{
    /** 页面名，用于标题。例如「帖子详情」 */
    title: string
    /** 底部/左侧导航该高亮哪一项 */
    activeNav?: 'home' | 'boards' | 'collect' | 'me' | 'notifications' | ''
  }>(),
  { activeNav: 'home' }
)

function goHome(): void {
  uni.reLaunch({ url: '/pages/index/index' })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.stub {
  padding: 64px 32px;
  display: flex;
  flex-direction: column;
  align-items: center;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;
  box-shadow: $hy-shadow-card;

  &__icon {
    margin-bottom: 16px;
  }

  &__title {
    font-size: $hy-font-xl;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__desc {
    margin-top: 12px;
    max-width: 520px;
    font-size: $hy-font-sm;
    color: $hy-text-regular;
    text-align: center;
    line-height: 1.8;

    &--muted {
      color: $hy-text-secondary;
    }
  }

  &__back {
    margin-top: 24px;
    height: 36px;
    padding: 0 24px;
    display: flex;
    align-items: center;
    background-color: $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__back-text {
    font-size: $hy-font-sm;
    color: $hy-text-inverse;
  }
}
</style>
