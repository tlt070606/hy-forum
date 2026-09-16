<template>
  <!--
    列表/详情的**状态占位**（加载中 / 出错 / 空）。

    ==========================================================================
    为什么单独抽一个组件（这是本项目一条硬约定）
    ==========================================================================
    README §7 与前端既有页面都遵守同一条：**接口没交付或失败时，明确提示，绝不静默降级**。
    上一版`首页`曾经把「注册模式查询失败」显示成「当前暂未开放注册」——
    **把环境故障伪装成了业务状态**，排查成本极高（见 `api/auth.ts` 的 `RegisterModeResult`）。

    三态的文案口径因此固定为：
    - 加载中：不显示任何"没有内容"的字样（否则会闪一下"暂无数据"）
    - 出错：**红色**错误文案 + 「重试」按钮，让用户与开发者都能看出这是异常
    - 空：正常业务状态，中性色

    三态互斥的判定顺序是 **error > loading > empty**，见下方 `state` 计算属性。
    顺序不能反：加载中出现错误时应该显示错误，而不是继续转圈。
  -->
  <view v-if="state !== 'none'" class="hy-state" :data-testid="`state-${state}`">
    <text class="hy-state__text" :class="{ 'hy-state__text--error': state === 'error' }">
      {{ message }}
    </text>
    <wd-button
      v-if="state === 'error' && retryable"
      class="hy-state__retry"
      type="info"
      size="small"
      plain
      data-testid="state-retry"
      @click="emit('retry')"
    >
      重试
    </wd-button>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 是否正在请求 */
    loading: boolean
    /** 错误文案。非空表示失败（**优先于 loading 展示**） */
    error?: string
    /** 数据是否为空（仅在非 loading、无 error 时有意义） */
    empty?: boolean
    /** 空态文案 */
    emptyText?: string
    /** 加载中文案 */
    loadingText?: string
    /** 出错时是否给「重试」按钮 */
    retryable?: boolean
  }>(),
  {
    error: '',
    empty: false,
    emptyText: '这里还没有内容',
    loadingText: '加载中…',
    retryable: true,
  }
)

const emit = defineEmits<{ retry: [] }>()

/**
 * 三态裁决。
 *
 * 顺序**不能改**：
 * 1. `error` 优先 —— 请求失败同时 `loading` 还是 true 的竞态很常见
 *    （页面没 await 完就又触发了一次），此时显示"加载中"会把错误吞掉；
 * 2. `loading` 次之 —— 刷新时旧数据还在，不该先闪一下「没有内容」；
 * 3. `empty` 最后 —— 它是正常业务状态，只有前两者都不成立时才展示。
 */
const state = computed<'none' | 'loading' | 'error' | 'empty'>(() => {
  if (props.error) return 'error'
  if (props.loading) return 'loading'
  if (props.empty) return 'empty'
  return 'none'
})

const message = computed(() => {
  if (state.value === 'error') return props.error
  if (state.value === 'loading') return props.loadingText
  return props.emptyText
})
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.hy-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: $hy-space-xl $hy-space-md;

  &__text {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
    text-align: center;
    line-height: 1.6;
  }

  /*
   * 错误文案用危险色：这是**异常**，不是业务状态。
   * 与 `pages/index/index.vue` 的 `user-card__warn` 同口径。
   */
  &__text--error {
    color: $hy-color-danger;
  }

  &__retry {
    margin-top: $hy-space-md;
  }
}
</style>
