<template>
  <view class="page">
    <!-- 顶部品牌区 -->
    <view class="hero">
      <text class="hero__title">Hy论坛</text>
      <text class="hero__subtitle">中文综合论坛 · 含资源分享版块</text>
    </view>

    <!-- 登录态卡片：已登录显示用户信息，未登录给出登录/注册入口 -->
    <view class="hy-card user-card">
      <template v-if="auth.isLoggedIn">
        <view class="user-card__row">
          <image class="user-card__avatar" :src="avatarSrc" mode="aspectFill" />
          <view class="user-card__info">
            <text class="user-card__name" data-testid="index-nickname">{{ auth.displayName }}</text>
            <text class="user-card__meta">已登录</text>
          </view>
        </view>
        <wd-button type="info" size="small" plain block @click="onLogout">退出登录</wd-button>
      </template>

      <template v-else>
        <text class="user-card__hint">登录后可发帖、评论与收藏</text>
        <view class="user-card__actions">
          <wd-button type="primary" block @click="goLogin">登录</wd-button>
          <wd-button v-if="registerAvailable" plain block @click="goRegister">
            注册
          </wd-button>
        </view>
        <!--
          两种"注册入口用不了"的情形必须分开说，文案不能混（与 auth 页同口径）：
          · registerModeError 非空 → **状态未知**（查询失败），属异常，要让用户与开发者都看见；
          · 既无错误又是 closed → 后端**确实**关闭了注册，这是正常业务状态。
          上一版把两者混为一谈（失败被静默当成 closed），正是 `api/auth.ts` 里
          `RegisterModeResult` 那段设计要避免的事。
        -->
        <text v-if="registerModeError" class="user-card__warn" data-testid="index-register-mode-error">
          {{ registerModeError }}
        </text>
        <!-- 关闭注册时明确告知，避免用户到处找入口 -->
        <text v-else-if="registerMode === 'closed'" class="user-card__closed">
          当前暂未开放注册
        </text>
      </template>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 首页。
 *
 * M2 阶段定位：**工程骨架的落地页 + 登录态入口 + 契约缺口的可视化说明**。
 * 真正的帖子双流（关注/全部）依赖 M3 的 `/api/posts`，后端尚未交付，故此页暂不发起该请求。
 */
import { onShow } from '@dcloudio/uni-app'
import { computed, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { fetchRegisterMode } from '@/api/auth'
import type { RegisterMode } from '@/api/types'

const auth = useAuthStore()

/** 注册模式。默认按 closed 保守处理，拿到真实值后再决定是否显示注册入口 */
const registerMode = ref<RegisterMode>('closed')

/**
 * 注册模式**查询失败**的文案。非空表示"状态未知"（网络 / 配置问题），必须让用户看到。
 *
 * 为什么不把失败也塞进 `registerMode`：
 * 上一版查询失败时静默返回 `'closed'`，结果小程序端连不上后端时界面显示
 * "当前暂未开放注册" —— **把环境故障伪装成了业务状态**，排查成本极高。
 * 详见 `api/auth.ts` 里 `RegisterModeResult` 的说明。
 */
const registerModeError = ref('')

/**
 * 注册入口是否显示：只有**明确知道**注册开放（`open` / `invite`）时才显示。
 *
 * 状态未知（`registerModeError` 非空）时一律不显示，但**不冒充"已关闭"** ——
 * 真实原因由 `registerModeError` 单独呈现给用户。
 * 与 `pages/auth/index.vue` 的 `registerAvailable` 同口径。
 */
const registerAvailable = computed(
  () => registerModeError.value === '' && registerMode.value !== 'closed'
)

/** 头像地址：用户没设头像时用本地占位图，避免 <image> 空 src 的告警 */
const avatarSrc = ref('/static/avatar-default.png')

onShow(async () => {
  /*
   * 注册模式：每次进入首页刷新一次，管理员切换后无需用户重开应用。
   *
   * ⚠️ 必须按 `ok` 分支取 `result.mode`，**不能**把整个结果对象赋给 `registerMode`。
   *    `fetchRegisterMode()` 返回的是 `{ok:true,mode} | {ok:false,error}`（见 `api/auth.ts`）；
   *    上一版直接写 `registerMode.value = await fetchRegisterMode()`，
   *    于是模板里 `registerMode !== 'closed'` **恒为真**、`registerMode === 'closed'` **恒为假**：
   *    管理员把注册模式切成 `closed` 之后，注册按钮照样显示、
   *    「当前暂未开放注册」永远不出现、`ok:false` 这条报错分支等于白写。
   */
  const result = await fetchRegisterMode()
  if (result.ok) {
    registerModeError.value = ''
    registerMode.value = result.mode
  } else {
    // 查询失败 = 状态未知：不猜成 closed（那是编造业务状态），也不静默 —— 交给模板显式报错。
    // 注意**不**改写 registerMode：保留上一次已知值无副作用，因为上面有
    // registerAvailable 把关（有错误文案时注册入口一律不显示）。
    registerModeError.value = result.error
  }

  // 已登录则校准用户信息（token 失效会自动降级为未登录）
  if (auth.isLoggedIn) {
    try {
      const me = await auth.ensureProfile()
      if (me?.avatarUrl) avatarSrc.value = me.avatarUrl
    } catch (e) {
      // 网络问题不该阻塞首页渲染，仅提示
      console.warn('[index] 获取用户信息失败', e)
    }
  } else {
    // 未登录时确保不残留上一个账号的信息
    avatarSrc.value = '/static/avatar-default.png'
  }
})

function goLogin() {
  uni.navigateTo({ url: '/pages/auth/index?mode=login' })
}

function goRegister() {
  uni.navigateTo({ url: '/pages/auth/index?mode=register' })
}

async function onLogout() {
  const res = await uni.showModal({
    title: '退出登录',
    content: '确定要退出当前账号吗？',
  })
  if (res.confirm) {
    await auth.logout()
    /*
     * 退出后回到登录页。
     * 为什么不是留在首页：登录页现在是应用入口，退出登录的语义就是"回到未登录起点"。
     * 留在首页会让用户看不出自己已经退出（首页未登录态与已登录态差别不明显）。
     */
    uni.reLaunch({ url: '/pages/auth/index' })
  }
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.page {
  min-height: 100vh;
  padding: $hy-space-md;
  box-sizing: border-box;
}

/* ---------- 品牌区 ---------- */
.hero {
  padding: $hy-space-lg $hy-space-sm $hy-space-xl;
  display: flex;
  flex-direction: column;

  &__title {
    font-size: 56rpx;
    font-weight: 600;
    color: $hy-color-primary;
    letter-spacing: 2rpx;
  }

  &__subtitle {
    margin-top: $hy-space-xs;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }
}

/* ---------- 用户卡片 ---------- */
.user-card {
  margin-bottom: $hy-space-md;

  &__row {
    display: flex;
    align-items: center;
    margin-bottom: $hy-space-md;
  }

  &__avatar {
    width: 96rpx;
    height: 96rpx;
    border-radius: 50%;
    background-color: $hy-bg-hover;
    flex-shrink: 0;
  }

  &__info {
    margin-left: $hy-space-md;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  &__name {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__meta {
    margin-top: 4rpx;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }

  &__hint {
    display: block;
    font-size: $hy-font-md;
    color: $hy-text-regular;
    margin-bottom: $hy-space-md;
  }

  &__actions {
    display: flex;
    flex-direction: column;
    gap: $hy-space-sm;
  }

  &__closed {
    display: block;
    margin-top: $hy-space-sm;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    text-align: center;
  }

  /*
   * 注册状态查询失败的警告文案。
   * 刻意用危险色而非普通灰：这是**异常**，不是业务状态 ——
   * 上一版把网络故障显示成普通的"暂未开放注册"，用户与开发者都察觉不到异常。
   */
  &__warn {
    display: block;
    margin-top: $hy-space-sm;
    font-size: $hy-font-xs;
    color: $hy-color-danger;
    text-align: center;
    line-height: 1.6;
  }
}
</style>
