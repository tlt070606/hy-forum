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
        <wd-button v-if="registerMode !== 'closed'" plain block @click="goRegister">注册</wd-button>
      </view>
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
  registerMode.value = await fetchRegisterMode()

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
  uni.showToast({ title: '已退出登录', icon: 'none' })
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
