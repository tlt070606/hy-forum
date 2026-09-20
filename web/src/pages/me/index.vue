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
              **头像可换**（M5 已交付 `PUT /api/user/profile`）。
              点击 → 选图 → 走 `target=avatar` 的签名（`dir = avatar/{自己id}/`）直传 OSS
              → 拿到回调返回的 url → PUT 提交。
              ⚠️ 头像**必须**走 avatar 目录：契约要求 `avatarUrl` 在自己目录下，用 post/ 会被 400。
            -->
            <view class="profile__avatar" data-testid="me-avatar" @click="changeAvatar">
              <Avatar :url="auth.user?.avatarUrl || ''" :nickname="auth.displayName" :size="88" />
              <view v-if="avatarUploading" class="profile__avatar-mask" data-testid="me-avatar-uploading">
                <text class="profile__avatar-mask-text">上传中</text>
              </view>
              <text class="profile__avatar-hint" data-testid="me-avatar-hint">
                {{ avatarUploading ? '正在上传…' : '点击更换头像' }}
              </text>
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
            - 粉丝 / 获赞 / 帖子数 → `GET /api/user/me`（**已有，不用额外请求**）
            - 收藏 → 契约里**没有"收藏数"字段**，只能从 `GET /api/user/collections` 的 `total` 拿
              → **多一次请求**（只取 size=1，只要 total）
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

        <!-- ---------- 个人简介（可编辑，M5） ---------- -->
        <view class="card section" data-testid="me-bio-card">
          <text class="section__title">个人简介</text>
          <view class="bio">
            <textarea
              v-model="bioDraft"
              class="bio__input"
              :maxlength="200"
              placeholder="介绍一下自己吧…"
              placeholder-class="bio__placeholder"
              data-testid="me-bio-input"
            />
          </view>
          <view class="bio__foot">
            <text class="bio__counter" data-testid="me-bio-counter">{{ bioDraft.length }}/200</text>
            <view
              class="bio__save"
              :class="{ 'bio__save--disabled': !bioDirty || savingBio }"
              :data-disabled="!bioDirty || savingBio ? '1' : '0'"
              data-testid="me-bio-save"
              @click="saveBio"
            >
              <text class="bio__save-text">{{ savingBio ? '保存中…' : '保存' }}</text>
            </view>
          </view>
          <text v-if="bioError" class="bio__error" data-testid="me-bio-error">{{ bioError }}</text>
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
          <!-- 改密码：契约的 PUT /api/user/profile 只接受 nickname/avatarUrl/bio/gender，没有密码字段 -->
          <text class="section__note" data-testid="me-password-note">
            修改密码需要单独的接口，契约里目前没有 → 待交付
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
 * 需求方 2026-09-18：改成参考图那个样子 + 资料卡下方另起一行加粉丝/获赞/收藏。
 * 需求方 2026-09-19：「**我的头像和个人介绍弄上去**」—— 这两项现在**真的能改**了
 * （L1 已交付 `PUT /api/user/profile`）。
 *
 * ==========================================================================
 * ⚠️⚠️ 这个接口是覆盖语义，本页有一处**必须**照做的地方
 * ==========================================================================
 * 契约原文：「**PUT = 覆盖（省略即清空）**」。
 * 也就是说只想改简介而只传 `{bio}`，**会把昵称和头像一起清空**（数据损失）。
 * 所以本页每次都从 `auth.user` 取**当前值**，只覆盖用户真正改的那一项，
 * 四件套（nickname / avatarUrl / bio / gender）**全量提交**（`api/profile.ts` 的类型也把它们设为必填）。
 *
 * 头像另有硬约束：`avatarUrl` 必须在自己目录下 → 上传走 `fetchSignature('avatar')`
 * （后端给 `dir = avatar/{自己id}/`，用户 id 取自登录态、前端不拼这个目录）。
 */
import { computed, onMounted, ref } from 'vue'
import AppShell from '@/components/shell/AppShell.vue'
import Avatar from '@/components/Avatar.vue'
import HyIcon from '@/components/HyIcon.vue'
import { fetchMyCollections } from '@/api/users'
import { updateProfile } from '@/api/profile'
import { fetchSignature } from '@/api/oss'
import { fetchRegisterMode } from '@/api/auth'
import { precheckImage, uploadImage, type LocalImage } from '@/utils/upload'
import { ApiError } from '@/utils/request'
import { compactCount } from '@/utils/postView'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

/** 我的收藏总数。`null` = 还不知道（界面显示 `—`，不用 0 冒充） */
const collectTotal = ref<number | null>(null)
const registerModeError = ref('')

/** 简介草稿（可编辑）。进入页面与保存成功后都与服务端值同步 */
const bioDraft = ref('')
const savingBio = ref(false)
const bioError = ref('')
/** 头像上传中（上传期间点第二次要挡住，否则会并发传两张） */
const avatarUploading = ref(false)

/** 简介是否被改过（决定"保存"按钮是否可点） */
const bioDirty = computed(() => bioDraft.value.trim() !== (auth.user?.bio ?? '').trim())

/** 时间戳 → `2026/09/15`（与参考图一致的紧凑格式） */
function formatDate(value: string | undefined | null): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`
}

/**
 * 组装**全量**资料（覆盖语义的必需动作）。
 * 任何一项都不要省略 —— 省略即清空。
 */
function buildPayload(overrides: { avatarUrl?: string; bio?: string } = {}) {
  return {
    nickname: auth.user?.nickname ?? '',
    avatarUrl: overrides.avatarUrl ?? auth.user?.avatarUrl ?? '',
    bio: overrides.bio ?? auth.user?.bio ?? '',
    gender: auth.user?.gender ?? 0,
  }
}

/** 提交后刷新本地资料（顶栏的头像/昵称也会跟着变） */
async function refreshProfile(): Promise<void> {
  await auth.ensureProfile(true)
  // 简介草稿跟着服务端的值走，避免"保存成功但框里还是旧的"
  bioDraft.value = auth.user?.bio ?? ''
}

onMounted(async () => {
  if (!auth.isLoggedIn) {
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
  bioDraft.value = auth.user?.bio ?? ''

  /*
   * 收藏数：`size=1` 只为拿 `total`（契约里没有"收藏数"字段）。
   * 失败静默 —— 少一个数字不该让整页报错，界面显示 `—`。
   */
  try {
    const res = await fetchMyCollections(1, 1)
    collectTotal.value = res.total
  } catch {
    collectTotal.value = null
  }
})

/* ---------------------------------------------------------------------------
 * 头像：选图 → 直传 OSS（avatar 目录）→ PUT 提交
 * ------------------------------------------------------------------------- */

/**
 * 换头像。
 *
 * 全流程与发帖带图**同一套**（`utils/upload.ts`），只有两处不同：
 * 1. 签名用 `target='avatar'`（目录必须是 `avatar/{自己id}/`，否则后端 400）；
 * 2. 拿到 url 后调的是 `PUT /api/user/profile`，而不是发帖。
 */
function changeAvatar(): void {
  if (avatarUploading.value) return

  uni.chooseImage({
    count: 1,
    sizeType: ['compressed', 'original'],
    sourceType: ['album', 'camera'],
    success: (res) => {
      const rawPaths = res.tempFilePaths
      const paths: string[] = Array.isArray(rawPaths) ? rawPaths : rawPaths ? [rawPaths] : []
      const files = (res.tempFiles ?? []) as Array<{
        path?: string
        size?: number
        type?: string
        name?: string
      }>
      const p = paths[0]
      if (!p) return
      const f = files.find((x) => x.path === p)
      const img: LocalImage = {
        path: p,
        size: f?.size,
        // ⚠️ 类型判定优先用 File.type：H5 的 blob URL 没有扩展名（见 utils/upload.ts 文件头）
        mime: f?.type,
        name: f?.name || 'avatar',
      }
      void uploadAvatar(img)
    },
    fail: (err) => {
      const msg = String(err?.errMsg ?? '')
      if (!msg.includes('cancel')) uni.showToast({ title: '选择图片失败', icon: 'none' })
    },
  })
}

async function uploadAvatar(img: LocalImage): Promise<void> {
  // 先做本地预检（类型/大小），再取签名 —— 顺序与发帖页一致：不让服务端为注定被拒的图白签一次
  const invalid = precheckImage(img)
  if (invalid) {
    uni.showToast({ title: invalid, icon: 'none', duration: 2400 })
    return
  }

  avatarUploading.value = true
  try {
    const sign = await fetchSignature('avatar')
    const done = await uploadImage(img, sign)
    /*
     * ⚠️ 提交时**带上其余字段的当前值**（覆盖语义），只把 avatarUrl 换成新的。
     * 少了这一步，用户改头像会把简介清空。
     */
    await updateProfile(buildPayload({ avatarUrl: done.url }))
    await refreshProfile()
    uni.showToast({ title: '头像已更新', icon: 'none' })
  } catch (e) {
    uni.showToast({
      title: e instanceof ApiError ? e.message : '头像更新失败，请稍后重试',
      icon: 'none',
      duration: 2600,
    })
  } finally {
    avatarUploading.value = false
  }
}

/* ---------------------------------------------------------------------------
 * 简介：保存
 * ------------------------------------------------------------------------- */

async function saveBio(): Promise<void> {
  bioError.value = ''
  if (!bioDirty.value) return
  savingBio.value = true
  try {
    // 同样：只覆盖 bio，其余三项带当前值（覆盖语义）
    await updateProfile(buildPayload({ bio: bioDraft.value.trim() }))
    await refreshProfile()
    uni.showToast({ title: '简介已保存', icon: 'none' })
  } catch (e) {
    // 失败**不清空输入**，让用户可以直接重试
    bioError.value = e instanceof ApiError ? e.message : '保存失败，请稍后重试'
  } finally {
    savingBio.value = false
  }
}

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
     * ⚠️ 这一步我重写本页时漏过一次，被 `golden-path.spec.ts` 的退出用例抓到。
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
    position: relative;
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  /* 上传中的遮罩：盖在头像上，明确"正在传"，而不是让用户以为点了没反应 */
  &__avatar-mask {
    position: absolute;
    left: 0;
    top: 0;
    width: 88px;
    height: 88px;
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: rgba(29, 33, 41, 0.45);
    border-radius: 50%;
  }

  &__avatar-mask-text {
    font-size: $hy-font-xs;
    color: #ffffff;
  }

  &__avatar-hint {
    margin-top: 8px;
    max-width: 96px;
    font-size: 10px;
    line-height: 1.4;
    color: $hy-color-primary;
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
  padding: 12px 14px;
  background-color: $hy-bg-page;
  border-radius: $hy-radius-sm;

  &__input {
    width: 100%;
    height: 72px;
    font-size: $hy-font-md;
    line-height: 1.7;
    color: $hy-text-primary;
  }

  &__placeholder {
    color: $hy-text-placeholder;
  }

  &__foot {
    margin-top: 10px;
    display: flex;
    align-items: center;
  }

  &__counter {
    font-size: $hy-font-xs;
    color: $hy-text-placeholder;
  }

  &__save {
    margin-left: auto;
    height: 34px;
    padding: 0 24px;
    display: flex;
    align-items: center;
    background-color: $hy-color-primary;
    border-radius: $hy-radius-pill;

    /* 没改动或正在保存 → 压暗（不是"点了才报错"） */
    &--disabled {
      opacity: 0.45;
    }
  }

  &__save-text {
    font-size: $hy-font-md;
    color: $hy-text-inverse;
  }

  &__error {
    display: block;
    margin-top: 8px;
    font-size: $hy-font-sm;
    color: $hy-color-danger;
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
