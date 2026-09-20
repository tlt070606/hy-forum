<template>
  <AppShell active-nav="me">
    <view class="settings">
      <view class="card head">
        <view class="head__back" data-testid="settings-back" @click="goBack">
          <HyIcon type="chevronLeft" size="md" />
          <text class="head__back-text">返回</text>
        </view>
        <text class="head__title">设置</text>
      </view>

      <!-- ==================== 未登录 ==================== -->
      <view v-if="!auth.isLoggedIn" class="card guest" data-testid="settings-login-hint">
        <text class="guest__title">登录后才能设置</text>
        <text class="guest__desc">通知偏好是跟着账号走的</text>
        <view class="guest__btn" data-testid="settings-login" @click="goLogin">
          <text class="guest__btn-text">去登录</text>
        </view>
      </view>

      <template v-else>
        <!-- ==================== 个人资料入口 ==================== -->
        <view class="card row" data-testid="settings-row-profile" @click="goProfile">
          <HyIcon type="user" size="lg" />
          <view class="row__main">
            <text class="row__title">个人资料</text>
            <text class="row__desc">查看昵称、账号 ID、注册时间</text>
          </view>
          <HyIcon type="chevron" size="md" color="#c9cdd4" />
        </view>

        <!-- ==================== 通知设置 ==================== -->
        <view class="card">
          <text class="section__title">通知设置</text>

          <!--
            ⚠️⚠️ 这一段是本页**最要紧**的东西，不要删、也不要弱化：
            通知接口**不属于已交付的契约**（通知属 M5，`openapi.json` 里没有任何
            notification 路径 —— 已核对）。所以下面这些开关**只存在本机**，
            拨动它们**不会真的影响任何推送**。
            需求方 2026-09-18 选定："开关能拨、状态存本机，但卡片上必须明写它现在不生效"
            —— 界面完整，但**绝不拿假开关冒充已完成**。
          -->
          <view class="warn" data-testid="settings-notify-notice">
            <HyIcon type="shield" size="sm" color="#ff7d00" />
            <text class="warn__text">
              通知接口属 M5，契约里还没有。下面的开关**只保存在本机**，
              现在不会真的影响任何推送。
            </text>
          </view>

          <view v-for="(item, idx) in NOTIFY_ITEMS" :key="item.key" class="toggle-row">
            <view class="toggle-row__main">
              <text class="toggle-row__title">{{ item.title }}</text>
              <text class="toggle-row__desc">{{ item.desc }}</text>
            </view>
            <!--
              开关：用 view + class 实现，不引第三方组件。
              可用 `aria-*` 之类的属性在小程序端支持不一，所以这里靠 `data-on` 暴露状态，
              E2E 直接断言它（比断言颜色稳）。
            -->
            <view
              class="switch"
              :class="{ 'switch--on': prefs[item.key] }"
              :data-testid="`settings-toggle-${item.key}`"
              :data-on="prefs[item.key] ? '1' : '0'"
              @click="toggle(item.key)"
            >
              <view class="switch__knob" />
            </view>
          </view>
        </view>

        <!-- ==================== 账号安全（只读） ==================== -->
        <view class="card">
          <text class="section__title">账号安全</text>
          <view class="notice">
            <HyIcon type="lock" size="sm" color="#6b4bc4" />
            <text class="notice__text">密码经加盐哈希加密存储，任何人都无法查看明文密码。</text>
          </view>
          <view class="kv">
            <text class="kv__k">账号 ID</text>
            <text class="kv__v" data-testid="settings-account-id">{{ auth.user?.id ?? '—' }}</text>
          </view>
          <view class="kv">
            <text class="kv__k">登录方式</text>
            <text class="kv__v">姓名 + 密码</text>
          </view>
          <view class="kv kv--last">
            <text class="kv__k">注册时间</text>
            <text class="kv__v">{{ formatDate(auth.user?.createdAt) }}</text>
          </view>
          <!-- 改密码没有接口：明说，而不是放一个假表单 -->
          <text class="section__note" data-testid="settings-password-note">
            修改密码需要后端接口，**契约里目前没有** → 待交付
          </text>
        </view>

        <!-- ==================== 退出 ==================== -->
        <view class="logout">
          <view class="logout__btn" data-testid="settings-logout" @click="onLogout">
            <HyIcon type="logout" size="md" color="#f53f3f" />
            <text class="logout__text">退出登录</text>
          </view>
        </view>
      </template>

      <!--
        「外观主题」刻意**不做**（需求方 2026-09-18：「这个设置的外观改变，不用做都行」）。
        这里只留一行说明，而不是画一个拨不动的开关 —— 让"没做"这件事有据可查。
      -->
      <text class="footnote" data-testid="settings-theme-note">
        外观主题（浅色/深色）按需求方要求暂不实现
      </text>
    </view>
  </AppShell>
</template>

<script setup lang="ts">
/**
 * 设置页。
 *
 * 需求方 2026-09-18 定下的三条：
 * 1. **通知开关能拨、状态存本机**，但卡片顶部必须**明写它现在不生效**
 *    （通知接口属 M5，契约里没有任何 notification 路径 —— 已核对过）；
 * 2. **外观主题不做**（只留一行说明，不画假开关）；
 * 3. 账号安全是**只读**的（改密码同样没有接口）。
 *
 * ⚠️ 为什么不去 `uni.setStorageSync` 直接用：项目已有 `utils/storage.ts` 那层
 *    带类型与命名空间的封装（`hy:` 前缀），绕过它会出现"同一种东西两种存法"。
 */
import { onMounted, ref } from 'vue'
import AppShell from '@/components/shell/AppShell.vue'
import HyIcon from '@/components/HyIcon.vue'
import { getObject, setObject } from '@/utils/storage'
import { ApiError } from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

/** 通知偏好的本机存储键（**刻意带 `hy:` 前缀**，与项目其他键同族） */
const PREFS_KEY = 'hy:notifyPrefs'

type NotifyKey = 'like' | 'comment' | 'follow' | 'message'
type NotifyPrefs = Record<NotifyKey, boolean>

/** 四个开关。默认值：私信关、其余开（与参考图一致：私信那一条是关的） */
const DEFAULTS: NotifyPrefs = { like: true, comment: true, follow: true, message: false }

const NOTIFY_ITEMS: Array<{ key: NotifyKey; title: string; desc: string }> = [
  { key: 'like', title: '点赞通知', desc: '有人点赞你的帖子时提醒' },
  { key: 'comment', title: '评论通知', desc: '有人评论你的帖子时提醒' },
  { key: 'follow', title: '关注通知', desc: '有新粉丝关注时提醒' },
  { key: 'message', title: '私信通知', desc: '收到新私信时提醒' },
]

const auth = useAuthStore()
const prefs = ref<NotifyPrefs>({ ...DEFAULTS })

/** 读本机偏好。存坏了（或被手改过）就退回默认值，不让页面崩 */
onMounted(() => {
  const saved = getObject<Partial<NotifyPrefs> | null>(PREFS_KEY, null)
  if (saved && typeof saved === 'object') {
    prefs.value = { ...DEFAULTS, ...saved }
  }
})

/** 拨动开关并落本机存储 */
function toggle(key: NotifyKey): void {
  prefs.value = { ...prefs.value, [key]: !prefs.value[key] }
  setObject(PREFS_KEY, prefs.value)
  /*
   * 刻意**只 toast 不假装成功**：这件事本来就没到服务端。
   * 文案直接说"仅本机"，避免用户以为设置已经生效到账号上。
   */
  uni.showToast({
    title: `${NOTIFY_ITEMS.find((i) => i.key === key)?.title}已${prefs.value[key] ? '打开' : '关闭'}（仅本机）`,
    icon: 'none',
    duration: 1600,
  })
}

function formatDate(value: string | undefined | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`
}

function goProfile(): void {
  uni.navigateTo({ url: '/pages/me/index' })
}

function goLogin(): void {
  uni.navigateTo({ url: '/pages/auth/index?mode=login' })
}

function goBack(): void {
  uni.navigateBack()
}

async function onLogout(): Promise<void> {
  const res = await uni.showModal({ title: '退出登录', content: '确定要退出当前账号吗？' })
  if (!res.confirm) return
  try {
    await auth.logout()
    // 退出后回登录页（清栈），与「我的」页保持同一行为
    uni.reLaunch({ url: '/pages/auth/index?mode=login' })
  } catch (e) {
    uni.showToast({ title: e instanceof ApiError ? e.message : '退出失败，请稍后重试', icon: 'none' })
  }
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.head {
  display: flex;
  align-items: center;

  &__back {
    display: flex;
    align-items: center;
  }

  &__back-text {
    margin-left: 4px;
    font-size: $hy-font-md;
    color: $hy-text-regular;
  }

  &__title {
    margin-left: 12px;
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }
}

/* ---------- 未登录 ---------- */
.guest {
  padding: 44px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;

  &__title {
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__desc {
    margin-top: 8px;
    font-size: $hy-font-md;
    color: $hy-text-secondary;
  }

  &__btn {
    margin-top: 20px;
    height: 36px;
    padding: 0 28px;
    display: flex;
    align-items: center;
    background-color: $hy-color-primary;
    border-radius: $hy-radius-pill;
  }

  &__btn-text {
    font-size: $hy-font-md;
    color: $hy-text-inverse;
  }
}

/* ---------- 入口行 ---------- */
.row {
  display: flex;
  align-items: center;

  &__main {
    flex: 1;
    min-width: 0;
    margin-left: 12px;
  }

  &__title {
    font-size: $hy-font-md;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__desc {
    display: block;
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

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

/* ---------- 「现在不生效」的警示条 ---------- */
.warn {
  margin-top: 12px;
  padding: 10px 12px;
  display: flex;
  align-items: flex-start;
  background-color: $hy-color-warning-light;
  border-radius: $hy-radius-sm;

  &__text {
    flex: 1;
    margin-left: 8px;
    font-size: $hy-font-xs;
    line-height: 1.6;
    color: $hy-color-warning;
  }
}

/* ---------- 开关行 ---------- */
.toggle-row {
  padding: 14px 0;
  border-bottom: 1px solid $hy-border-color;
  display: flex;
  align-items: center;

  &:last-of-type {
    border-bottom: none;
  }

  &__main {
    flex: 1;
    min-width: 0;
  }

  &__title {
    font-size: $hy-font-md;
    color: $hy-text-primary;
  }

  &__desc {
    display: block;
    margin-top: 2px;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

.switch {
  flex-shrink: 0;
  position: relative;
  width: 44px;
  height: 24px;
  background-color: $hy-border-color;
  border-radius: $hy-radius-pill;
  transition: background-color 0.2s;

  &--on {
    background-color: $hy-color-primary;
  }

  &__knob {
    position: absolute;
    left: 2px;
    top: 2px;
    width: 20px;
    height: 20px;
    background-color: $hy-bg-card;
    border-radius: 50%;
    transition: left 0.2s;
  }

  /* 打开时圆点滑到右侧 */
  &--on &__knob {
    left: 22px;
  }
}

/* ---------- 只读信息 ---------- */
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

.footnote {
  display: block;
  padding: 4px 0 8px;
  font-size: $hy-font-xs;
  color: $hy-text-placeholder;
  text-align: center;
}
</style>
