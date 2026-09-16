<template>
  <view class="page">
    <!-- 未登录：整页引导登录 -->
    <view v-if="!auth.isLoggedIn" class="guest">
      <view class="guest__avatar">
        <image class="guest__avatar-img" src="/static/avatar-default.png" mode="aspectFill" />
      </view>
      <text class="guest__title">你还没有登录</text>
      <text class="guest__desc">登录后可查看个人资料、发帖与收藏</text>
      <view class="guest__actions">
        <wd-button type="primary" block @click="goLogin">登录</wd-button>
        <wd-button v-if="registerAvailable" plain block @click="goRegister">注册</wd-button>
      </view>
      <!--
        注册模式查询失败（状态未知）时**必须把原因说出来**：
        这是网络/配置问题，不是"管理员关闭了注册"，混为一谈会把排查方向带偏
        （见 `api/auth.ts` 里 `RegisterModeResult` 的说明）。
      -->
      <text v-if="registerModeError" class="guest__warn" data-testid="me-register-mode-error">
        {{ registerModeError }}
      </text>
    </view>

    <!-- 已登录：资料卡 + 统计 + 菜单 -->
    <template v-else>
      <view class="hy-card profile">
        <view class="profile__head">
          <image class="profile__avatar" :src="avatarSrc" mode="aspectFill" />
          <view class="profile__info">
            <text class="profile__nickname" data-testid="me-nickname">{{ auth.user?.nickname || '—' }}</text>
            <text class="profile__username" data-testid="me-username">@{{ auth.user?.username || '—' }}</text>
          </view>
        </view>
        <text v-if="auth.user?.bio" class="profile__bio">{{ auth.user.bio }}</text>

        <!--
          统计数据：字段全部来自契约的 UserVO（postCount / followCount /
          fansCount / likeReceivedCount），**不是猜测的字段名**。
        -->
        <view class="stats">
          <view class="stats__item">
            <text class="stats__value">{{ auth.user?.postCount ?? 0 }}</text>
            <text class="stats__label">帖子</text>
          </view>
          <view class="stats__item">
            <text class="stats__value">{{ auth.user?.followCount ?? 0 }}</text>
            <text class="stats__label">关注</text>
          </view>
          <view class="stats__item">
            <text class="stats__value">{{ auth.user?.fansCount ?? 0 }}</text>
            <text class="stats__label">粉丝</text>
          </view>
          <view class="stats__item">
            <text class="stats__value">{{ auth.user?.likeReceivedCount ?? 0 }}</text>
            <text class="stats__label">获赞</text>
          </view>
        </view>
      </view>

      <!--
        菜单项。其中「我的帖子 / 我的评论 / 我的收藏 / 资料设置」依赖 M3–M4 的接口，
        后端尚未交付（实测 404）。这里**点按会明确提示"接口未交付"**，
        而不是切到一个空白页面让人以为坏了。
      -->
      <view class="hy-card menu">
        <view
          v-for="item in menuItems"
          :key="item.key"
          class="menu__item"
          hover-class="menu__item--hover"
          @click="onMenuTap(item)"
        >
          <text class="menu__label">{{ item.label }}</text>
          <view class="menu__right">
            <text v-if="item.todo" class="menu__todo">待 M{{ item.todo }}</text>
            <text class="menu__arrow">›</text>
          </view>
        </view>
      </view>

      <view class="logout">
        <wd-button type="error" plain block data-testid="me-logout" @click="onLogout">退出登录</wd-button>
      </view>
    </template>
  </view>
</template>

<script setup lang="ts">
/**
 * 「我的」页。
 *
 * 契约：`GET /api/user/me`（需要登录态）。
 *
 * 关键设计：**token 失效要自动降级**，而不是弹错。
 * `auth.ensureProfile()` 在收到 401 时会清掉本地登录态并返回 null，
 * 于是本页会自然渲染成"未登录"引导 —— 用户不会看到"明明登录着却一直报错"。
 */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '@/stores/auth'
import { fetchRegisterMode } from '@/api/auth'
import type { RegisterMode } from '@/api/types'

const auth = useAuthStore()
const registerMode = ref<RegisterMode>('closed')

/**
 * 注册模式**查询失败**的文案。非空表示"状态未知"，必须让用户看到。
 * 与 `pages/index/index.vue`、`pages/auth/index.vue` 同口径：
 * 失败绝不静默当成 `'closed'`，否则网络故障会被伪装成"管理员关闭了注册"。
 * 设计依据见 `api/auth.ts` 的 `RegisterModeResult`。
 */
const registerModeError = ref('')

/**
 * 注册入口是否显示：只有**明确知道**注册开放时才显示。
 * 状态未知（`registerModeError` 非空）时一律不显示，但由上面的文案说明真实原因，
 * **不冒充"已关闭"**。
 */
const registerAvailable = computed(
  () => registerModeError.value === '' && registerMode.value !== 'closed'
)

const avatarSrc = computed(() => auth.user?.avatarUrl || '/static/avatar-default.png')

/**
 * 菜单项定义。
 * `todo` 字段标明该功能对应的后端里程碑 —— 值是契约缺口，不是"随便写个 M3"：
 * 依据《技术方案》§6.3/§6.5 的接口归属与 PLAN.md 的里程碑划分。
 */
const menuItems = [
  { key: 'posts', label: '我的帖子', todo: '3' },
  { key: 'comments', label: '我的评论', todo: '4' },
  { key: 'collections', label: '我的收藏', todo: '4' },
  { key: 'profile-edit', label: '资料设置', todo: '3' },
] as const

onShow(async () => {
  /*
   * ⚠️ 必须按 `ok` 分支取 `result.mode`，**不能**把整个结果对象赋给 `registerMode`。
   *    `fetchRegisterMode()` 返回 `{ok:true,mode} | {ok:false,error}`（见 `api/auth.ts`）；
   *    上一版直接写 `registerMode.value = await fetchRegisterMode()`，
   *    类型上就已经不成立（`RegisterModeResult` 不是 `RegisterMode`），
   *    运行时更糟：模板里 `registerMode !== 'closed'` **恒为真** →
   *    管理员关闭注册后注册按钮仍然显示。
   */
  const result = await fetchRegisterMode()
  if (result.ok) {
    registerModeError.value = ''
    registerMode.value = result.mode
  } else {
    // 状态未知：显式报错（模板里那行 warn 文案），绝不猜成 closed
    registerModeError.value = result.error
  }

  if (!auth.isLoggedIn) return

  try {
    // force=true：每次进入本页都重新拉一次，保证统计数字是最新的
    await auth.ensureProfile(true)
  } catch (e) {
    // 网络失败只提示，不清登录态（清的话会让用户无故退出）
    console.warn('[me] 获取用户信息失败', e)
    uni.showToast({ title: '资料刷新失败，请检查网络', icon: 'none' })
  }
})

function onMenuTap(item: (typeof menuItems)[number]) {
  uni.showToast({
    title: `该功能依赖 M${item.todo} 接口，后端尚未交付`,
    icon: 'none',
    duration: 2500,
  })
}

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
  if (!res.confirm) return
  await auth.logout()
  /*
   * 退出后回登录页（登录页已是应用入口）。
   * 用 reLaunch 清空页面栈：本页在未登录态下只是一个引导页，
   * 把它留在栈里没有意义，而且会让"我的"页的未登录态与登录页重复。
   */
  uni.reLaunch({ url: '/pages/auth/index' })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.page {
  min-height: 100vh;
  padding: $hy-space-md;
  box-sizing: border-box;
}

/* ---------- 未登录引导 ---------- */
.guest {
  padding: $hy-space-xl $hy-space-md;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__avatar {
    width: 160rpx;
    height: 160rpx;
    border-radius: 50%;
    overflow: hidden;
    background-color: $hy-bg-hover;
    margin-bottom: $hy-space-lg;
  }

  &__avatar-img {
    width: 100%;
    height: 100%;
  }

  &__title {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__desc {
    margin-top: $hy-space-xs;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
    text-align: center;
  }

  &__actions {
    width: 100%;
    margin-top: $hy-space-xl;
    display: flex;
    flex-direction: column;
    gap: $hy-space-sm;
  }

  /*
   * 注册状态查询失败的警告文案：用危险色而非普通灰 —— 这是异常，不是业务状态。
   * 与 `pages/auth/index.vue` 的 `.form__warn`、`pages/index/index.vue` 的
   * `.user-card__warn` 保持一致。
   */
  &__warn {
    display: block;
    margin-top: $hy-space-md;
    font-size: $hy-font-xs;
    color: $hy-color-danger;
    text-align: center;
    line-height: 1.6;
  }
}

/* ---------- 资料卡 ---------- */
.profile {
  &__head {
    display: flex;
    align-items: center;
  }

  &__avatar {
    width: 120rpx;
    height: 120rpx;
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

  &__nickname {
    font-size: $hy-font-xl;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__username {
    margin-top: 4rpx;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }

  &__bio {
    display: block;
    margin-top: $hy-space-md;
    font-size: $hy-font-sm;
    color: $hy-text-regular;
    line-height: 1.6;
  }
}

/* ---------- 统计 ---------- */
.stats {
  margin-top: $hy-space-lg;
  padding-top: $hy-space-md;
  border-top: 1rpx solid $hy-border-color;
  display: flex;

  &__item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  &__value {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__label {
    margin-top: 4rpx;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 菜单 ---------- */
.menu {
  margin-top: $hy-space-md;
  padding: 0 $hy-space-md;

  &__item {
    height: 100rpx;
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-bottom: 1rpx solid $hy-border-color;

    &:last-child {
      border-bottom: none;
    }

    &--hover {
      background-color: $hy-bg-hover;
    }
  }

  &__label {
    font-size: $hy-font-md;
    color: $hy-text-primary;
  }

  &__right {
    display: flex;
    align-items: center;
  }

  &__todo {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
    margin-right: $hy-space-xs;
  }

  &__arrow {
    font-size: 36rpx;
    color: $hy-text-placeholder;
    line-height: 1;
  }
}

.logout {
  margin-top: $hy-space-xl;
}
</style>
