<template>
  <view class="auth">
    <!-- ========== 头部：渐变 + 图标 + 标题 ========== -->
    <view class="auth__hero">
      <view class="auth__logo">
        <Icon class="auth__logo-icon" type="shield" size="lg" />
      </view>
      <text class="auth__brand">Hy论坛</text>
      <text class="auth__slogan">中文综合论坛 · 含资源分享版块</text>
    </view>

    <!-- ========== 卡片：Tab + 表单 ========== -->
    <view class="auth__card">
      <!-- Tab 切换。登录 / 注册共用一个页面（与设计稿一致） -->
      <view class="tabs">
        <view
          v-for="t in TABS"
          :key="t.key"
          class="tabs__item"
          :class="{ 'tabs__item--active': mode === t.key }"
          :data-testid="`auth-tab-${t.key}`"
          @click="switchMode(t.key)"
        >
          <Icon class="tabs__icon" :type="t.key === 'login' ? 'arrow' : 'plus'" size="sm" />
          <text class="tabs__label">{{ t.label }}</text>
          <view v-if="mode === t.key" class="tabs__bar" />
        </view>
      </view>

      <!-- ===================== 登录表单 ===================== -->
      <view v-if="mode === 'login'" class="form">
        <view class="field">
          <text class="field__label">用户名</text>
          <view class="control">
            <Icon class="control__icon" type="user" />
            <input
              v-model="form.username"
              data-testid="login-username"
              class="control__input"
              type="text"
              placeholder="请输入用户名"
              placeholder-class="control__placeholder"
              :maxlength="20"
              :disabled="submitting"
              @confirm="onLogin"
            />
          </view>
        </view>

        <view class="field">
          <text class="field__label">密码</text>
          <view class="control">
            <Icon class="control__icon" type="lock" />
            <input
              v-model="form.password"
              data-testid="login-password"
              class="control__input"
              :password="!showPassword"
              placeholder="请输入密码"
              placeholder-class="control__placeholder"
              :maxlength="32"
              :disabled="submitting"
              @confirm="onLogin"
            />
            <!-- 密码明文开关。中文输入法下密码打错很常见，给用户自查手段 -->
            <view class="control__action" data-testid="login-toggle-pwd" @click="showPassword = !showPassword">
              <Icon class="control__action-icon" :type="showPassword ? 'eye-off' : 'eye'" />
            </view>
          </view>
        </view>

        <wd-button type="primary" block :loading="submitting" data-testid="login-submit" @click="onLogin">
          登录
        </wd-button>

        <view v-if="registerMode !== 'closed'" class="form__footer">
          <text class="form__hint">还没有账号？</text>
          <text class="form__link" data-testid="login-go-register" @click="switchMode('register')">
            立即注册
          </text>
        </view>
        <view v-else class="form__footer">
          <text class="form__hint">当前暂未开放注册</text>
        </view>
      </view>

      <!-- ===================== 注册表单 ===================== -->
      <view v-else class="form">
        <view class="field">
          <text class="field__label">用户名</text>
          <view class="control">
            <Icon class="control__icon" type="user" />
            <input
              v-model="form.username"
              data-testid="reg-username"
              class="control__input"
              type="text"
              placeholder="4-20 位字母、数字或下划线"
              placeholder-class="control__placeholder"
              :maxlength="20"
              :disabled="submitting"
            />
          </view>
        </view>

        <view class="field">
          <text class="field__label">密码</text>
          <view class="control">
            <Icon class="control__icon" type="lock" />
            <input
              v-model="form.password"
              data-testid="reg-password"
              class="control__input"
              :password="!showPassword"
              placeholder="8-32 位，需同时包含字母和数字"
              placeholder-class="control__placeholder"
              :maxlength="32"
              :disabled="submitting"
            />
            <view class="control__action" data-testid="reg-toggle-pwd" @click="showPassword = !showPassword">
              <Icon class="control__action-icon" :type="showPassword ? 'eye-off' : 'eye'" />
            </view>
          </view>
        </view>

        <view class="field">
          <text class="field__label">昵称</text>
          <view class="control">
            <Icon class="control__icon" type="user" />
            <input
              v-model="form.nickname"
              data-testid="reg-nickname"
              class="control__input"
              type="text"
              placeholder="1-20 个字符，展示给其他用户"
              placeholder-class="control__placeholder"
              :maxlength="20"
              :disabled="submitting"
            />
          </view>
        </view>

        <!--
          邀请码：**仅 invite 模式渲染**。
          依据《技术方案》§8.8 —— 注册模式由后台配置（open/invite/closed），
          前端先查 register-mode 再决定是否显示本输入框。
        -->
        <view v-if="registerMode === 'invite'" class="field">
          <text class="field__label">
            邀请码
            <text class="field__label-tag">邀请制</text>
          </text>
          <view class="control">
            <Icon class="control__icon" type="ticket" />
            <input
              v-model="form.inviteCode"
              data-testid="reg-invite-code"
              class="control__input"
              type="text"
              placeholder="请输入邀请码"
              placeholder-class="control__placeholder"
              :maxlength="64"
              :disabled="submitting"
            />
          </view>
        </view>

        <view class="field">
          <text class="field__label">图形验证码</text>
          <!--
            验证码图片**独立成块并放大**。
            后端返回的图是 130x48 的小图，之前挤在输入框右侧（220rpx）导致
            数字几乎看不清。这里改成：
              · 图块宽度撑满、高度按图片比例放大（aspectFit 保持不变形）
              · 点击图块刷新，并叠加「点击刷新」提示层
            注意 `base64Image` 已在 `api/auth.ts` 里补过 `data:image/png;base64,` 前缀 ——
            后端返回的是**裸 base64**，不补前缀图片根本不会渲染（本机实测过的 bug）。
          -->
          <view class="captcha" data-testid="reg-captcha-box" @click="refreshCaptcha">
            <!--
              容器宽度 = 图片宽度（由 CSS 控制），高度**按图片原生比例自适应**。
              这样才能：
                · 宽屏（H5 桌面浏览器）下不会把 130x48 的图横向拉成 1230x88 的变形条
                · 移动端下图上足够大、看得清
              `aspectFit` 保证不变形；比例由容器承担，容器比例由 `height:auto` 与
              固定的图片宽高比共同决定（见样式里的说明）。
            -->
            <image
              v-if="captchaImage"
              class="captcha__image"
              :src="captchaImage"
              mode="aspectFit"
            />
            <view v-else class="captcha__fallback">
              <text class="captcha__fallback-text">
                {{ captchaLoading ? '验证码加载中…' : '点击获取验证码' }}
              </text>
            </view>
            <view class="captcha__hint">
              <text class="captcha__hint-text">{{ captchaLoading ? '加载中' : '点击图片刷新' }}</text>
            </view>
          </view>

          <view class="control control--mt">
            <Icon class="control__icon" type="shield" />
            <input
              v-model="form.captchaCode"
              data-testid="reg-captcha-code"
              class="control__input"
              type="text"
              placeholder="请输入图中字符"
              placeholder-class="control__placeholder"
              :maxlength="8"
              :disabled="submitting"
            />
          </view>
        </view>

        <!-- 协议勾选：契约里 agreeProtocol 必填（合规要求），必须由用户主动勾选 -->
        <view class="agreement">
          <view class="agreement__check" data-testid="reg-agree" @click="form.agreeProtocol = !form.agreeProtocol">
            <view class="agreement__box" :class="{ 'agreement__box--checked': form.agreeProtocol }">
              <view v-if="form.agreeProtocol" class="agreement__tick" />
            </view>
            <text class="agreement__text">我已阅读并同意</text>
          </view>
          <text class="agreement__link" @click.stop="goDoc('agreement')">《用户协议》</text>
          <text class="agreement__text">与</text>
          <text class="agreement__link" @click.stop="goDoc('privacy')">《隐私政策》</text>
        </view>

        <wd-button type="primary" block :loading="submitting" data-testid="reg-submit" @click="onRegister">
          注册
        </wd-button>

        <view class="form__footer">
          <text class="form__hint">已有账号？</text>
          <text class="form__link" data-testid="reg-go-login" @click="switchMode('login')">去登录</text>
        </view>
      </view>

      <text class="auth__note">密码经加盐哈希加密存储，服务端不保存明文</text>
    </view>

    <!-- 关闭注册时的兜底提示（不遮罩，避免用户看不出为什么注册不了） -->
    <view v-if="registerMode === 'closed' && mode === 'register'" class="closed">
      <text class="closed__text">
        管理员已暂时关闭新用户注册。如已有账号可直接登录；若需要账号，请联系管理员。
      </text>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 登录 / 注册页（**单页 Tab 切换**）。
 *
 * ==========================================================================
 * 为什么两页合一
 * ==========================================================================
 * 原实现是 `pages/login` 与 `pages/register` 两个独立页，与设计稿不符：
 * 设计稿用顶部 Tab 在登录/注册间切换。合并后：
 * - 用户切换不再有页面栈进出，体验更顺
 * - 但**登录成功后不能再 `navigateBack()`**（栈里可能只有本页），
 *   因此改为 `reLaunch` 回首页（见 `onLogin` 的注释）
 *
 * ==========================================================================
 * ⚠️ 关于「滑块拼图验证码」—— 本页**没有**实现，且前端无法实现
 * ==========================================================================
 * 设计稿用的是滑块拼图验证。本项目**不能**做，原因是契约侧的硬约束：
 * 后端 `POST /api/auth/register` 的字段是 `captchaUuid` + `captchaCode`（**字符型**），
 * Redis 里存的也是 4 个字符的答案（实测读到过 "FXPH"，TTL 300s）。
 * 滑块拼图要求后端提供「抠图背景图 + 正确缺口坐标 + 轨迹校验」，
 * 属于**后端接口变更**，而任务约束要求后端零改动（后端 M1 已交付并复核）。
 *
 * 因此本页保留字符验证码，但把图**放大到可辨识**（见 captcha 样式）。
 * 若确认要做滑块，正确路径是提 CR 让后端加接口，而不是前端造假控件。
 */
import { onLoad } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { fetchCaptcha, fetchRegisterMode, register } from '@/api/auth'
import type { RegisterMode, RegisterRequest } from '@/api/types'
import { ApiError } from '@/utils/request'
/*
 * 图标用**纯 CSS 绘制的 Icon 组件**，不用 SVG data URI、也不用图标字体。
 * 原因：微信小程序的 `<image>` 对 SVG data URI 支持不可靠，
 * 而图标全空白是致命观感问题。方案对比见 `src/components/Icon.vue` 顶部注释。
 */
import Icon from '@/components/Icon.vue'

const auth = useAuthStore()

type Mode = 'login' | 'register'

/** Tab 定义。图标类型在模板里按 key 决定，不在这里存 data URI */
const TABS: { key: Mode; label: string }[] = [
  { key: 'login', label: '登录' },
  { key: 'register', label: '注册' },
]

const mode = ref<Mode>('login')
const submitting = ref(false)
const showPassword = ref(false)
const registerMode = ref<RegisterMode>('closed')

const captchaUuid = ref('')
const captchaImage = ref('')
const captchaLoading = ref(false)

const form = reactive({
  username: '',
  password: '',
  nickname: '',
  captchaCode: '',
  inviteCode: '',
  agreeProtocol: false,
})

/**
 * 页面加载。
 *
 * `mode` 参数支持从其它页面直接打开注册 Tab（`?mode=register`），
 * 这样"立即注册"入口不必依赖用户再点一次 Tab。
 */
onLoad(async (options) => {
  const wanted = (options as Record<string, string> | undefined)?.mode
  if (wanted === 'register') mode.value = 'register'

  registerMode.value = await fetchRegisterMode()

  // 已关闭注册时把用户拉回登录 Tab，并提示原因
  if (mode.value === 'register' && registerMode.value === 'closed') {
    mode.value = 'login'
    uni.showToast({ title: '当前暂未开放注册', icon: 'none' })
  }

  if (registerMode.value !== 'closed') await refreshCaptcha()
})

/** 切换 Tab。切到注册且未关闭时确保有验证码 */
async function switchMode(next: Mode) {
  if (next === 'register' && registerMode.value === 'closed') {
    uni.showToast({ title: '当前暂未开放注册', icon: 'none' })
    return
  }
  mode.value = next
  if (next === 'register' && !captchaImage.value) await refreshCaptcha()
}

/**
 * 刷新验证码。
 *
 * 失败时清空图片并给出明确提示，而不是静默留白 ——
 * 验证码取不到就无法注册，用户需要知道是网络问题还是功能坏了。
 */
async function refreshCaptcha() {
  if (captchaLoading.value) return
  captchaLoading.value = true
  try {
    const vo = await fetchCaptcha()
    captchaUuid.value = vo.uuid
    captchaImage.value = vo.base64Image
  } catch (e) {
    captchaUuid.value = ''
    captchaImage.value = ''
    const message = e instanceof ApiError ? e.message : '验证码加载失败，请点击重试'
    uni.showToast({ title: message, icon: 'none', duration: 2500 })
  } finally {
    captchaLoading.value = false
  }
}

/* ---------------------------------------------------------------------------
 * 登录
 * ------------------------------------------------------------------------- */

/**
 * 登录。
 *
 * 前端只做**最低限度**的非空校验：真正的规则校验由后端负责。
 * 重复实现一遍规则容易出现"前端放行、后端拒绝"或反之的不一致。
 * 但空值不值得发一次请求（浪费一次可能触发限流的调用），所以拦一下。
 */
async function onLogin() {
  if (submitting.value) return

  const username = form.username.trim()
  if (!username) {
    uni.showToast({ title: '请输入用户名', icon: 'none' })
    return
  }
  if (!form.password) {
    uni.showToast({ title: '请输入密码', icon: 'none' })
    return
  }

  submitting.value = true
  try {
    await auth.login(username, form.password)
    uni.showToast({ title: '登录成功', icon: 'success' })

    /*
     * 跳转用 `reLaunch` 而不是 `navigateBack`。
     * 原因：本页是登录/注册合一页，页面栈里可能**只有它自己**
     * （例如用户直接从本页链接进入），此时 navigateBack 会失败且无提示。
     * reLaunch 会清空页面栈回首页，行为确定。
     */
    setTimeout(() => {
      uni.reLaunch({ url: '/pages/index/index' })
    }, 600)
  } catch (e) {
    const message = e instanceof ApiError ? e.message : '登录失败，请检查网络后重试'
    uni.showToast({ title: message, icon: 'none', duration: 2500 })
  } finally {
    submitting.value = false
  }
}

/* ---------------------------------------------------------------------------
 * 注册
 * ------------------------------------------------------------------------- */

/**
 * 前端格式校验。
 * 规则来源《技术方案》§6.2「注册校验规则」：
 *   用户名 4–20 位字母数字下划线；密码 8–32 位且至少含字母与数字；昵称 1–20 字符。
 * 返回错误文案；通过则返回空字符串。
 */
function validateRegister(): string {
  const username = form.username.trim()
  const nickname = form.nickname.trim()

  if (!/^[A-Za-z0-9_]{4,20}$/.test(username)) {
    return '用户名需为 4-20 位字母、数字或下划线'
  }
  if (form.password.length < 8 || form.password.length > 32) {
    return '密码长度需为 8-32 位'
  }
  // 至少含字母与数字：拆成两条独立正则，比单条复杂正则更易读
  if (!/[A-Za-z]/.test(form.password) || !/[0-9]/.test(form.password)) {
    return '密码需同时包含字母和数字'
  }
  if (nickname.length < 1 || nickname.length > 20) {
    return '昵称需为 1-20 个字符'
  }
  if (!form.captchaCode.trim()) {
    return '请输入图形验证码'
  }
  if (registerMode.value === 'invite' && !form.inviteCode.trim()) {
    return '邀请制下必须填写邀请码'
  }
  if (!form.agreeProtocol) {
    // 合规要求：必须主动同意
    return '请先阅读并同意用户协议与隐私政策'
  }
  return ''
}

async function onRegister() {
  if (submitting.value) return

  const error = validateRegister()
  if (error) {
    uni.showToast({ title: error, icon: 'none', duration: 2500 })
    return
  }

  /*
   * 组装请求体。
   * `inviteCode` **只在该模式下才带上** —— 给 open 模式发空字符串邀请码，
   * 后端可能把它当成"提供了邀请码"去校验，属无谓的边界风险。
   */
  const payload: RegisterRequest = {
    username: form.username.trim(),
    password: form.password,
    nickname: form.nickname.trim(),
    captchaUuid: captchaUuid.value,
    captchaCode: form.captchaCode.trim(),
    agreeProtocol: form.agreeProtocol,
  }
  if (registerMode.value === 'invite') {
    payload.inviteCode = form.inviteCode.trim()
  }

  submitting.value = true
  try {
    await register(payload)
    // 契约：注册不返回 token，故引导去登录 Tab（而不是假装已登录）
    uni.showToast({ title: '注册成功，请登录', icon: 'success', duration: 1500 })
    setTimeout(() => {
      mode.value = 'login'
      form.password = ''
      form.captchaCode = ''
      form.agreeProtocol = false
    }, 1200)
  } catch (e) {
    const message = e instanceof ApiError ? e.message : '注册失败，请检查网络后重试'
    uni.showToast({ title: message, icon: 'none', duration: 2500 })

    /*
     * 失败后**必须刷新验证码**。
     * 验证码在 Redis 里是一次性的（TTL 300s，提交即消费），
     * 不刷新的话用户怎么输都报"验证码错误" —— 注册流程最容易踩的坑。
     */
    await refreshCaptcha()
    form.captchaCode = ''
  } finally {
    submitting.value = false
  }
}

/** 跳静态文档页 */
function goDoc(name: 'agreement' | 'privacy' | 'disclaimer') {
  uni.navigateTo({ url: `/pages/${name}/index` })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.auth {
  min-height: 100vh;
  background-color: $hy-bg-page;

  /* ---------- 头部渐变 ---------- */
  &__hero {
    padding: $hy-space-xl $hy-space-md $hy-space-lg;
    display: flex;
    flex-direction: column;
    align-items: center;
    /*
     * 蓝色系渐变（主题色仍用项目既定的 #2b6cff，不改成设计稿的紫色）。
     * 用较深的蓝收尾，白字对比度足够。
     */
    background: linear-gradient(160deg, #4a86ff 0%, $hy-color-primary 55%, #1f4fd8 100%);
  }

  &__logo {
    width: 112rpx;
    height: 112rpx;
    border-radius: 28rpx;
    background-color: rgba(255, 255, 255, 0.22);
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: $hy-space-md;
  }

  &__logo-icon {
    /* CSS 图标用 color 取色（内部走 currentColor），头部是深色底所以用白色 */
    color: #ffffff;
  }

  &__brand {
    font-size: 52rpx;
    font-weight: 600;
    color: #ffffff;
    letter-spacing: 2rpx;
  }

  &__slogan {
    margin-top: 8rpx;
    font-size: $hy-font-sm;
    color: rgba(255, 255, 255, 0.85);
  }

  /* ---------- 卡片 ---------- */
  &__card {
    margin: -32rpx $hy-space-md 0;
    padding-bottom: $hy-space-lg;
    background-color: #ffffff;
    border-radius: $hy-radius-lg;
    overflow: hidden;
  }

  &__note {
    display: block;
    margin-top: $hy-space-lg;
    text-align: center;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- Tab ---------- */
.tabs {
  display: flex;
  border-bottom: 1rpx solid $hy-border-color;

  &__item {
    position: relative;
    flex: 1;
    height: 96rpx;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__icon {
    margin-right: 8rpx;
    /* 未选中：中性灰（继承 Icon 组件的默认色） */
    color: $hy-icon-color;
  }

  &__label {
    font-size: $hy-font-md;
    color: $hy-text-secondary;
  }

  /* 选中态：文字与图标都回到主色 */
  &__item--active {
    .tabs__icon {
      opacity: 1;
    }
    .tabs__label {
      color: $hy-color-primary;
      font-weight: 600;
    }
  }

  &__bar {
    position: absolute;
    left: 25%;
    right: 25%;
    bottom: 0;
    height: 6rpx;
    border-radius: 3rpx;
    background-color: $hy-color-primary;
  }
}

/* ---------- 表单 ---------- */
.form {
  padding: $hy-space-lg $hy-space-md 0;

  &__footer {
    margin-top: $hy-space-lg;
    display: flex;
    justify-content: center;
    align-items: center;
  }

  &__hint {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }

  &__link {
    font-size: $hy-font-sm;
    color: $hy-color-primary;
    margin-left: 8rpx;
  }
}

.field {
  margin-bottom: $hy-space-lg;

  &__label {
    display: block;
    font-size: $hy-font-md;
    font-weight: 500;
    color: $hy-text-primary;
    margin-bottom: $hy-space-sm;
  }

  /* 邀请制标记：让用户知道为什么这里多了一个框 */
  &__label-tag {
    margin-left: 8rpx;
    font-size: $hy-font-xs;
    font-weight: 400;
    color: $hy-color-warning;
  }
}

/*
 * 输入框：图标 + 输入 + 可选操作（眼睛）。
 * 用统一的 .control 而不是给每个输入框写样式，避免三处样式漂移。
 */
.control {
  display: flex;
  align-items: center;
  height: 96rpx;
  padding: 0 $hy-space-md;
  box-sizing: border-box;
  background-color: #ffffff;
  border: 2rpx solid #dcdfe6;
  border-radius: $hy-radius-md;

  &--mt {
    margin-top: $hy-space-sm;
  }

  &__icon {
    flex-shrink: 0;
    color: $hy-icon-color;
  }

  &__input {
    flex: 1;
    height: 100%;
    margin-left: $hy-space-sm;
    font-size: $hy-font-md;
    color: $hy-text-primary;
  }

  &__placeholder {
    color: $hy-text-placeholder;
  }

  &__action {
    padding-left: $hy-space-sm;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    /* 加大点击热区，避免图标太小不好点 */
    height: 100%;
  }

  &__action-icon {
    color: $hy-icon-color;
  }
}

/*
 * ---------- 验证码（放大且不变形）----------
 *
 * ==========================================================================
 * 尺寸必须**显式给出**，这是跨端要求，不是审美选择
 * ==========================================================================
 * 后端验证码原图是 130x48（宽高比 130:48 ≈ 2.708）。
 * 这里把宽度定为 `480rpx`，高度按比例算：480 / 2.708 ≈ 177rpx。
 *
 * 为什么不用 `height: auto`：
 * 1. **H5 实测会塌成 0 高**。uni-app 的 `<image>` 渲染成 `<uni-image>` 外壳 +
 *    内部**绝对定位**的 `<img>`；外壳没有确定高度时，内部 img 撑不开它。
 *    实测外层宽 240px、高 0px —— 图完全不可见，且控制台无任何报错。
 * 2. **小程序端 `<image>` 有默认尺寸 300x225**，不给显式尺寸时实际显示比例与原图不符。
 * 3. rpx 是 uni-app 的跨端单位，`480rpx x 177rpx` 在三端会等比缩放且单位一致，
 *    不会出现 H5 用 px、小程序用 px 的换算差异。
 *
 * ⚠️ 教训（写给后来的人）：凡是 `<image>`，**一律显式给宽高（rpx）**，
 *    不要依赖 auto、百分比或内容撑开 —— 那是三端行为差异最大的地方。
 */
.captcha {
  position: relative;
  display: inline-block;
  width: 480rpx;
  /* 高 = 480 * (48/130)，按原图比例，避免变形 */
  height: 177rpx;
  background-color: $hy-bg-page;
  border-radius: $hy-radius-md;
  overflow: hidden;

  &__image {
    display: block;
    width: 480rpx;
    height: 177rpx;
  }

  &__fallback {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 480rpx;
    height: 177rpx;
  }

  &__fallback-text {
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }

  /* 右下角刷新提示，让"可点击"这件事可见 */
  &__hint {
    position: absolute;
    right: 0;
    bottom: 0;
    padding: 4rpx 12rpx;
    background-color: rgba(0, 0, 0, 0.45);
    border-top-left-radius: $hy-radius-sm;
  }

  &__hint-text {
    font-size: $hy-font-xs;
    color: #ffffff;
  }
}

/* ---------- 协议勾选 ---------- */
.agreement {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: $hy-space-lg;

  &__check {
    display: flex;
    align-items: center;
    margin-right: 4rpx;
  }

  &__box {
    width: 36rpx;
    height: 36rpx;
    border: 2rpx solid #c0c4cc;
    border-radius: 6rpx;
    margin-right: $hy-space-xs;
    display: flex;
    align-items: center;
    justify-content: center;
    box-sizing: border-box;

    &--checked {
      background-color: $hy-color-primary;
      border-color: $hy-color-primary;
    }
  }

  /*
   * 对勾：用一个旋转 45° 的方块，只保留右、下两条边 —— 这是最省事且三端一致的
   * "打勾"画法，比引图标或图片都稳。
   */
  &__tick {
    width: 12rpx;
    height: 22rpx;
    border-right: 4rpx solid #ffffff;
    border-bottom: 4rpx solid #ffffff;
    transform: rotate(45deg) translate(-2rpx, -2rpx);
  }

  &__text {
    font-size: $hy-font-sm;
    color: $hy-text-regular;
  }

  &__link {
    font-size: $hy-font-sm;
    color: $hy-color-primary;
  }
}

/* ---------- 关闭注册提示 ---------- */
.closed {
  margin: $hy-space-md;
  padding: $hy-space-md;
  background-color: #fff8e6;
  border: 1rpx solid #ffe1a8;
  border-radius: $hy-radius-md;

  &__text {
    font-size: $hy-font-xs;
    color: #8a6100;
    line-height: 1.7;
  }
}
</style>
