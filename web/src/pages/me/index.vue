<template>
  <AppShell active-nav="me">
    <view class="me">
      <!-- ==================== 未登录：整页引导 ==================== -->
      <view v-if="!auth.isLoggedIn" class="card guest" data-testid="me-guest">
        <text class="guest__title">你还没有登录</text>
        <text class="guest__desc">登录后可以发帖、评论、收藏，换设备也能看到</text>
        <view class="guest__actions">
          <view class="btn btn--primary" data-testid="me-go-login" @click="goLogin">
            <text class="btn__text">登录</text>
          </view>
          <view class="btn btn--ghost" data-testid="me-go-register" @click="goRegister">
            <text class="btn__text btn__text--ghost">注册</text>
          </view>
        </view>
        <!-- 注册模式取不到时明确说明，不静默降级（否则用户会以为"注册坏了"） -->
        <text v-if="registerModeError" class="guest__warn" data-testid="me-register-mode-error">
          注册状态获取失败（{{ registerModeError }}），仍可尝试登录
        </text>
      </view>

      <!-- ==================== 已登录 ==================== -->
      <template v-else>
        <!-- ---------- 资料卡 ---------- -->
        <view class="card profile" data-testid="me-profile">
          <view class="profile__top">
            <!--
              ⚠️ **点击更换头像点不动**：契约里**没有任何写接口**
              （`profile` / `avatar` / `nickname` 全查过，没有）。所以这里：
              - 视觉上与参考图一致（大头像 + 下面一行小字）；
              - 但那行小字写的是「待 M5 交付」而**不是**「点击更换」——
                **不给一个点了不生效的按钮**（需求方 2026-09-18 选定）。
            -->
            <view class="profile__avatar">
              <Avatar :url="auth.user?.avatarUrl || ''" :nickname="auth.displayName" :size="88" />
              <text class="profile__avatar-hint" data-testid="me-avatar-hint">头像编辑待 M5 交付</text>
            </view>

            <view class="profile__main">
              <view class="profile__name-row">
                <text class="profile__nickname" data-testid="me-nickname">
                  {{ auth.user?.nickname || '—' }}
                </text>
                <text class="profile__badge">已登录</text>
              </view>
              <text class="profile__username" data-testid="me-username">@{{ auth.user?.username || '—' }}</text>
              <view class="profile__meta">
                <text class="profile__meta-item" data-testid="me-id">ID: {{ auth.user?.id ?? '—' }}</text>
              </view>
              <view class="profile__meta">
                <text class="profile__meta-item" data-testid="me-created">
                  注册：{{ formatDate(auth.user?.createdAt) }}
                </text>
              </view>
            </view>
          </view>

          <!--
            统计**另起一行**（需求方 2026-09-18 指定：粉丝、点赞、收藏）。
            数据来源逐条写清楚：
            - 粉丝 / 获赞 → `GET /api/user/me`（`UserVO.fansCount` / `likeReceivedCount`，**已有，不用额外请求**）
            - 收藏 → 契约里**没有"收藏数"字段**（`UserVO` / `UserProfileVO` 都没有），
              只能从 `GET /api/user/collections` 的 `total` 拿 → **多一次请求**（只取 size=1，只要 total）
            "点赞"按**获赞**理解（他收到的赞），这也正是契约里唯一有的那个数。
          -->
          <view class="stats">
            <view class="stat" data-testid="me-stat-fans">
              <text class="stat__num">{{ auth.user?.fansCount ?? 0 }}</text>
              <text class="stat__label">粉丝</text>
            </view>
            <view class="stat" data-testid="me-stat-like">
              <text class="stat__num">{{ compactCount(auth.user?.likeReceivedCount) }}</text>
              <text class="stat__label">获赞</text>
            </view>
            <view class="stat" data-testid="me-stat-collect">
              <text class="stat__num">{{ collectTotal === null ? '—' : collectTotal }}</text>
              <text class="stat__label">收藏</text>
            </view>
            <view class="stat" data-testid="me-stat-post">
              <text class="stat__num">{{ auth.user?.postCount ?? 0 }}</text>
              <text class="stat__label">帖子</text>
            </view>
          </view>
        </view>

        <!-- ---------- 个人简介（只读） ---------- -->
        <view class="card section" data-testid="me-bio-card">
          <text class="section__title">个人简介</text>
          <view class="bio">
            <text v-if="auth.user?.bio" class="bio__text" data-testid="me-bio">{{ auth.user.bio }}</text>
            <text v-else class="bio__empty" data-testid="me-bio-empty">还没有填写简介</text>
          </view>
          <!--
            明确标注"不能改"，而不是放一个按下去没反应的保存按钮。
            依据：契约里没有改资料的接口（`profile` / `nickname` 都没有）。
          -->
          <text class="section__note" data-testid="me-bio-note">
            编辑简介与头像需要后端接口，契约里还没有 → 待 M5 交付
          </text>
        </view>

        <!-- ---------- 账号安全 ---------- -->
        <view class="card section" data-testid="me-security-card">
          <text class="section__title">账号安全</text>
          <view class="notice">
            <HyIcon type="lock" size="sm" color="#6b4bc4" />
            <text class="notice__text">密码经加盐哈希加密存储，任何人都无法查看明文密码。</text>
          </view>
          <view class="kv">
            <text class="kv__k">账号 ID</text>
            <text class="kv__v" data-testid="me-security-id">{{ auth.user?.id ?? '—' }}</text>
          </view>
          <view class="kv">
            <text class="kv__k">登录方式</text>
            <text class="kv__v">姓名 + 密码</text>
          </view>
          <view class="kv kv--last">
            <text class="kv__k">注册时间</text>
            <text class="kv__v">{{ formatDate(auth.user?.createdAt) }}</text>
          </view>
          <!-- 改密码同样没有接口，明确说出来（不做假表单） -->
          <text class="section__note" data-testid="me-password-note">
            修改密码需要后端接口，契约里还没有 → 待 M5 交付
          </text>
        </view>

        <!-- ---------- 退出 ---------- -->
        <view class="logout">
          <view class="logout__btn" data-testid="me-logout" @click="onLogout">
            <HyIcon type="logout" size="md" color="#f53f3f" />
            <text class="logout__text">退出登录</text>
          </view>
        </view>
      </template>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 个人资料（原「我的」页）。
 *
 * 需求方 2026-09-18：把这一页改成参考图那个样子（头像 + 已登录 + ID + 注册时间 +
 * 个人简介 + 账号安全），并在**资料卡下方另起一行**加粉丝 / 获赞 / 收藏。
 *
 * ==========================================================================
 * 这一页最重要的一件事：**哪些能改、哪些不能改，必须一眼看出来**
 * ==========================================================================
 * 参考图里有「点击更换头像」「个人简介 + 保存」「修改密码」，
 * 但我核对过**契约里没有任何写接口**（`profile` / `avatar` / `nickname` / `password` 全没有）。
 * 所以我**不做假的保存按钮**，而是把这三处明确标注为"待 M5 交付"。
 * 界面结构与参考图一致，但不会让人以为"改完就生效了"。
 *
 * ⚠️ 数据来源：
 * - 昵称 / 用户名 / ID / 注册时间 / 简介 / 粉丝 / 获赞 / 帖子数 → `GET /api/user/me`（**已有**）
 * - **收藏数契约里没有**（`UserVO` 与 `UserProfileVO` 都没有这个字段），
 *   只能取 `GET /api/user/collections` 的 `total`（`size=1`，只要那个数）→ 多一次请求。
 *   取不到时显示 `—` 而**不是 0**：0 会被读成"我一条都没收藏"。
 */
import { onMounted, ref } from 'vue'
import AppShell from '@/components/shell/AppShell.vue'
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import { fetchMyCollections } from '@/api/users'
import { fetchRegisterMode } from '@/api/auth'
import { ApiError } from '@/utils/request'
import { compactCount } from '@/utils/postView'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

/** 我的收藏总数。`null` = 还不知道（界面显示 `—`，不用 0 冒充） */
const collectTotal = ref<number | null>(null)
const registerModeError = ref('')

/** 时间戳 → `2026/09/15`（与参考图一致的紧凑格式） */
function formatDate(value: string | undefined | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`
}

/** 登录后补一次资料（刷新时 `auth.user` 可能只有本地缓存那份），再拉收藏数 */
onMounted(async () => {
  if (!auth.isLoggedIn) {
    // 未登录时探一次注册模式：关闭注册的话，引导页要能说清楚
    try {
      await fetchRegisterMode()
    } catch (e) {
      registerModeError.value = e instanceof ApiError ? e.message : '未知原因'
    }
    return
  }

  try {
    await auth.ensureProfile(true)
  } catch {
    // 资料刷新失败不阻断页面：本地缓存那份照常显示
  }

  /*
   * 收藏数：`size=1` 只为拿 `total`（契约里没有"收藏数"字段，见文件头）。
   * 失败静默 —— 少一个数字不该让整页报错，界面显示 `—`。
   */
  try {
    const res = await fetchMyCollections(1, 1)
    collectTotal.value = res.total
  } catch {
    collectTotal.value = null
  }
})

function goLogin(): void {
  uni.navigateTo({ url: '/pages/auth/index?mode=login' })
}

function goRegister(): void {
  uni.navigateTo({ url: '/pages/auth/index?mode=register' })
}

async function onLogout(): Promise<void> {
  const res = await uni.showModal({ title: '退出登录', content: '确定要退出当前账号吗？' })
  if (!res.confirm) return
  try {
    await auth.logout()
    /*
     * 退出后**回登录页**（用 reLaunch 清掉页面栈）。
     * ⚠️ 这一步我重写本页时漏过一次，被 `golden-path.spec.ts` 的退出用例抓到 ——
     *    它断言退出后能看到登录表单。不清栈的话用户按返回键还能回到"我的"页（已是未登录态），
     *    看起来像"退了个寂寞"。
     */
    uni.reLaunch({ url: '/pages/auth/index?mode=login' })
  } catch (e) {
    uni.showToast({ title: e instanceof ApiError ? e.message : '退出失败，请稍后重试', icon: 'none' })
  }
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

/* ---------- 未登录 ---------- */
.guest {
  padding: 48px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__title {
    font-size: 20px;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__desc {
    margin-top: 10px;
    font-size: $hy-font-md;
    color: $hy-text-secondary;
  }

  &__actions {
    margin-top: 22px;
    display: flex;
    align-items: center;
  }

  &__warn {
    margin-top: 16px;
    font-size: $hy-font-xs;
    color: $hy-color-warning;
    text-align: center;
    line-height: 1.6;
  }
}

.btn {
  height: 38px;
  padding: 0 30px;
  display: flex;
  align-items: center;
  border-radius: $hy-radius-pill;

  &--primary {
    background-color: $hy-color-primary;
  }

  /* 次要按钮用描边：与主按钮拉开层次，避免两个实心块抢注意力 */
  &--ghost {
    margin-left: 12px;
    border: 1px solid $hy-color-primary;
  }

  &__text {
    font-size: $hy-font-md;
    color: $hy-text-inverse;

    &--ghost {
      color: $hy-color-primary;
    }
  }
}

/* ---------- 资料卡 ---------- */
.profile {
  &__top {
    display: flex;
    align-items: flex-start;
  }

  &__avatar {
    display: flex;
    flex-direction: column;
    align-items: center;
    flex-shrink: 0;
  }

  &__avatar-hint {
    margin-top: 8px;
    max-width: 96px;
    font-size: 10px;
    line-height: 1.4;
    color: $hy-text-placeholder;
    text-align: center;
  }

  &__main {
    flex: 1;
    min-width: 0;
    margin-left: 18px;
  }

  &__name-row {
    display: flex;
    align-items: center;
  }

  &__nickname {
    font-size: 22px;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__badge {
    margin-left: 10px;
    padding: 2px 8px;
    font-size: $hy-font-xs;
    color: $hy-color-success;
    background-color: rgba(0, 180, 42, 0.1);
    border-radius: $hy-radius-sm;
  }

  &__username {
    display: block;
    margin-top: 4px;
    font-size: $hy-font-md;
    color: $hy-text-secondary;
  }

  &__meta {
    margin-top: 6px;
    display: flex;
    align-items: center;
  }

  &__meta-item {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }
}

.stats {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid $hy-border-color;
  display: flex;
}

.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__num {
    font-size: 18px;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__label {
    margin-top: 4px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 分区卡片 ---------- */
.section {
  &__title {
    display: block;
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__note {
    display: block;
    margin-top: 14px;
    padding-top: 12px;
    border-top: 1px dashed $hy-border-color;
    font-size: $hy-font-xs;
    line-height: 1.6;
    color: $hy-text-placeholder;
  }
}

.bio {
  margin-top: 12px;
  min-height: 72px;
  padding: 14px;
  background-color: $hy-bg-page;
  border-radius: $hy-radius-sm;

  &__text {
    font-size: $hy-font-md;
    line-height: 1.7;
    color: $hy-text-regular;
    white-space: pre-wrap;
    word-break: break-word;
  }

  &__empty {
    font-size: $hy-font-md;
    color: $hy-text-placeholder;
  }
}

.notice {
  margin-top: 14px;
  padding: 10px 14px;
  display: flex;
  align-items: center;
  background-color: $hy-color-primary-light;
  border-radius: $hy-radius-sm;

  &__text {
    margin-left: 8px;
    font-size: $hy-font-sm;
    color: $hy-color-primary;
    line-height: 1.5;
  }
}

.kv {
  margin-top: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid $hy-border-color;
  display: flex;
  align-items: center;

  &--last {
    border-bottom: none;
    padding-bottom: 0;
  }

  &__k {
    font-size: $hy-font-md;
    color: $hy-text-secondary;
  }

  &__v {
    margin-left: auto;
    font-size: $hy-font-md;
    color: $hy-text-primary;
  }
}

/* ---------- 退出 ---------- */
.logout {
  display: flex;
  justify-content: center;

  &__btn {
    height: 42px;
    padding: 0 40px;
    display: flex;
    align-items: center;
    background-color: $hy-bg-card;
    border: 1px solid $hy-color-danger;
    border-radius: $hy-radius-pill;
  }

  &__text {
    margin-left: 8px;
    font-size: $hy-font-md;
    color: $hy-color-danger;
  }
}
</style>
