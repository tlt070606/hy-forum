<template>
  <!--
    列表页的三态占位（加载中 / 出错 / 空）。首页、搜索页、版块页共用。

    ==========================================================================
    为什么抽成一个组件
    ==========================================================================
    三态互斥的**判定顺序**是这里唯一的实质逻辑，而它很容易写错：

      **error > loading > empty**

    顺序不能反：
    1. 请求失败时 `loading` 可能仍为 true（页面没 await 完又触发了下一次）→ 错误优先；
    2. 刷新时旧数据还在 → 不该先闪一下"没有内容"；
    3. `empty` 是**正常业务状态**，只有前两者都不成立时才展示。

    把它写在三个页面里，就是三份会漂移的副本（真实教训：M2 曾把"查询失败"
    静默显示成"当前暂未开放注册"，把环境故障伪装成了业务状态）。
  -->
  <view v-if="state !== 'none'" class="list-state" :data-testid="testidBase">
    <text class="list-state__text" :class="{ 'list-state__text--error': state === 'error' }">
      {{ message }}
    </text>
    <view
      v-if="state === 'error'"
      class="list-state__retry"
      :data-testid="`${testidBase}-retry`"
      @click="emit('retry')"
    >
      <text class="list-state__retry-text">重试</text>
    </view>
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
    emptyText?: string
    loadingText?: string
    /** E2E 用。会渲染成容器 testid，重试按钮是 `${testidBase}-retry` */
    testidBase?: string
  }>(),
  {
    error: '',
    empty: false,
    emptyText: '这里还没有内容',
    loadingText: '正在加载…',
    testidBase: 'list-state',
  }
)

const emit = defineEmits<{ retry: [] }>()

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

.list-state {
  padding: 48px 16px;
  display: flex;
  flex-direction: column;
  align-items: center;
  background-color: $hy-bg-card;
  border-radius: $hy-radius-md;

  &__text {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
    text-align: center;
    line-height: 1.6;

    /* 错误用危险色：这是**异常**，不是业务状态（与 M2 的既定口径一致） */
    &--error {
      color: $hy-color-danger;
    }
  }

  &__retry {
    margin-top: 14px;
    height: 32px;
    padding: 0 20px;
    display: flex;
    align-items: center;
    border: 1px solid $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__retry-text {
    font-size: $hy-font-sm;
    color: $hy-color-primary;
  }
}
</style>
