<template>
  <!--
    头像。

    ==========================================================================
    三种渲染，按优先级
    ==========================================================================
    1. **有 `avatarUrl`** → 真图
    2. **有昵称但没图** → **字母头像**（昵称首字 + 由昵称确定性推导的底色）
    3. **既没图也没昵称**（未登录的游客位）→ 一个中性的"人"图标

    第 3 种是必须的，不能省：游客位如果也走"字母头像"，就会拿 `displayName` 的兜底值
    「未登录」去取首字，渲染出一个孤零零的「未」字 —— 看起来像数据错了。
    （这是本轮截图自检时抓到的，见交付报告。）

    ==========================================================================
    为什么要有字母头像这一层
    ==========================================================================
    真实数据里**大量用户没有 `avatarUrl`**（契约 `UserBriefVO.avatarUrl` 可选，
    且本项目注册流程不上传头像）。只用 `<image :src>` 的话这些人会渲染成破图或空白方块，
    一个信息流里出现十几个空白头像，观感上就是"没做完"。
    参考图里的头像都有图，我们不能假装有图（那是假数据冒充）；正确做法是做一个体面的兜底。

    ⚠️ 底色由昵称**确定性**推导（见 `utils/postView.ts` 的 `avatarColor`），
       不能用随机数：同一个人刷新一次换一次颜色，看起来像 bug。
  -->
  <image
    v-if="url"
    class="hy-avatar"
    :style="boxStyle"
    :src="url"
    mode="aspectFill"
    :data-testid="testid || undefined"
  />
  <view
    v-else-if="nickname"
    class="hy-avatar hy-avatar--text"
    :style="boxStyle"
    :data-testid="testid || undefined"
  >
    <text class="hy-avatar__char" :style="charStyle">{{ initial }}</text>
  </view>
  <view v-else class="hy-avatar hy-avatar--guest" :style="boxStyle" :data-testid="testid || undefined">
    <HyIcon type="user" :size="guestIconSize" color="#b8bcc4" />
  </view>
</template>

<script setup lang="ts">
/**
 * 头像（真图 / 字母头像 / 游客图标 三选一）。
 *
 * ⚠️ 尺寸必须**显式**给（px）：uni-app 的 `<image>` 在 H5 端渲染成 `<uni-image>` 外壳
 *    + 内部绝对定位的 `<img>`，外壳没有确定尺寸时会被撑成 0 高。
 *    这条是 M2 踩过的坑。
 */
import { computed } from 'vue'
import HyIcon, { type IconSize } from '@/components/HyIcon.vue'
import { avatarColor, avatarInitial } from '@/utils/postView'

const props = withDefaults(
  defineProps<{
    /** 头像图片地址；为空串时退到字母头像 / 游客图标 */
    url?: string
    /** 昵称。字母头像取它的首字，底色也由它推导；空串表示"没有身份"（游客位） */
    nickname?: string
    /** 直径（px） */
    size?: number
    /** E2E 用的 testid；不传则不渲染该属性 */
    testid?: string
  }>(),
  { url: '', nickname: '', size: 40, testid: '' }
)

const boxStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
}))

const initial = computed(() => avatarInitial(props.nickname))

const charStyle = computed(() => ({
  backgroundColor: avatarColor(props.nickname),
  /* 字号取直径的 42%：视觉重心最稳，太大显挤、太小显空 */
  fontSize: `${Math.round(props.size * 0.42)}px`,
}))

/**
 * 游客图标尺寸取头像直径的 55%（图标本身有 2px 固定描边，太小会糊成一团）。
 * 映射到 HyIcon 的尺寸预设而不是传任意 px —— 见 `HyIcon` 里关于描边不随尺寸缩放的说明。
 */
const guestIconSize = computed<IconSize>(() => {
  if (props.size >= 64) return 'xl'
  if (props.size >= 36) return 'lg'
  return 'md'
})
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.hy-avatar {
  display: block;
  border-radius: 50%;
  flex-shrink: 0;
  background-color: $hy-bg-hover;

  &--text {
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }

  &--guest {
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__char {
    width: 100%;
    height: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    color: #ffffff;
    font-weight: 600;
    /* line-height 设为 1 让它垂直居中（flex + line-height 双保险，三端表现更一致） */
    line-height: 1;
  }
}
</style>
