/**
 * 应用根组件。
 *
 * uni-app 里 `App.vue` **不产生任何可见 DOM/节点**：它只承载应用级生命周期与全局样式。
 * 所有页面都在 `pages.json` 注册，不要往这里写页面结构。
 *
 * 生命周期说明（uni-app 特有，不是 Vue 的 onMounted）：
 * - `onLaunch`：应用冷启动，只触发一次 —— 适合做「一次性初始化」
 * - `onShow`  ：应用从后台切回前台
 * - `onHide`  ：应用切到后台
 */
<script setup lang="ts">
import { onLaunch, onShow, onHide } from '@dcloudio/uni-app'
import { useAuthStore } from '@/stores/auth'

onLaunch(() => {
  // 冷启动时把 token 从本地存储恢复进内存（Pinia）。
  //
  // 为什么在 onLaunch 做而不是在 store 的 defineStore 顶层做：
  // store 的定义时机可能早于平台存储 API 就绪（小程序端尤其），
  // 放在 onLaunch 里能保证 uni.getStorageSync 一定可用。
  const auth = useAuthStore()
  auth.restoreFromStorage()
})

onShow(() => {
  // 目前无需处理。将来若要做「回到前台刷新未读数」在这里加。
})

onHide(() => {
  // 目前无需处理。
})
</script>

<style lang="scss">
/*
 * 全局公共样式。
 * 设计取向：**简洁论坛风** —— 以内容为主，弱化装饰；白底、细分隔线、克制的强调色。
 * 具体色值与间距统一走 CSS 变量，避免在页面里散落魔法值。
 */
@use '@/styles/variables.scss' as *;

page {
  background-color: $hy-bg-page;
  color: $hy-text-primary;
  font-size: 28rpx;
  /* 中文正文行高放宽，长段落更好读 */
  line-height: 1.6;
  font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
}

/*
 * 通用可点按反馈。小程序端没有 :active 的 hover 语义，用 uni-app 的 hover-class 更可靠，
 * 但纯 CSS 的 :active 在 H5 与小程序基础库较新版本上也可用，故保留。
 */
.hy-card {
  background-color: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx;
}

.hy-divider {
  height: 1rpx;
  background-color: $hy-border-color;
}

/* 文本截断工具类：帖子标题普遍需要单行/两行截断 */
.hy-ellipsis {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.hy-ellipsis-2 {
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
</style>
