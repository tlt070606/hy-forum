<template>
  <view class="page">
    <view class="header">
      <text class="header__title">注册</text>
      <text class="header__subtitle">创建你的 Hy论坛 账号</text>
    </view>

    <view class="hy-card form-card">
      <!-- 用户名 -->
      <view class="field">
        <text class="field__label">用户名</text>
        <input
          v-model="form.username"
          data-testid="reg-username"
          class="field__input"
          type="text"
          placeholder="4-20 位字母、数字或下划线"
          placeholder-class="field__placeholder"
          :maxlength="20"
          :disabled="submitting"
        />
      </view>

      <!-- 密码 -->
      <view class="field">
        <text class="field__label">密码</text>
        <input
          v-model="form.password"
          data-testid="reg-password"
          class="field__input"
          :password="true"
          placeholder="8-32 位，需同时包含字母和数字"
          placeholder-class="field__placeholder"
          :maxlength="32"
          :disabled="submitting"
        />
      </view>

      <!-- 昵称 -->
      <view class="field">
        <text class="field__label">昵称</text>
        <input
          v-model="form.nickname"
          data-testid="reg-nickname"
          class="field__input"
          type="text"
          placeholder="1-20 个字符，展示给其他用户"
          placeholder-class="field__placeholder"
          :maxlength="20"
          :disabled="submitting"
        />
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
        <input
          v-model="form.inviteCode"
          data-testid="reg-invite-code"
          class="field__input"
          type="text"
          placeholder="请输入邀请码"
          placeholder-class="field__placeholder"
          :maxlength="64"
          :disabled="submitting"
        />
      </view>

      <!-- 图形验证码：契约要求注册必带 -->
      <view class="field">
        <text class="field__label">图形验证码</text>
        <view class="captcha">
          <input
            v-model="form.captchaCode"
            data-testid="reg-captcha-code"
            class="field__input captcha__input"
            type="text"
            placeholder="请输入图中字符"
            placeholder-class="field__placeholder"
            :maxlength="8"
            :disabled="submitting"
          />
          <!--
            点击图片刷新验证码。
            用 <image> + base64：**H5 与小程序端是同一套代码**（小程序 <image> 支持 base64），
            因此不需要平台条件编译。
          -->
          <view class="captcha__box" @click="refreshCaptcha">
            <image
              v-if="captchaImage"
              class="captcha__image"
              :src="captchaImage"
              mode="aspectFit"
            />
            <text v-else class="captcha__placeholder">
              {{ captchaLoading ? '加载中' : '点击获取' }}
            </text>
          </view>
        </view>
        <text class="field__tip">看不清？点击图片可刷新</text>
      </view>

      <!-- 协议勾选：契约里 agreeProtocol 是必填（合规要求），必须由用户主动勾选 -->
      <view class="agreement">
        <view class="agreement__check" data-testid="reg-agree" @click="toggleAgree">
          <view class="agreement__box" :class="{ 'agreement__box--checked': form.agreeProtocol }">
            <text v-if="form.agreeProtocol" class="agreement__tick">✓</text>
          </view>
          <text class="agreement__text">我已阅读并同意</text>
        </view>
        <text class="agreement__link" @click.stop="goDoc('agreement')">《用户协议》</text>
        <text class="agreement__text">与</text>
        <text class="agreement__link" @click.stop="goDoc('privacy')">《隐私政策》</text>
      </view>

      <wd-button type="primary" block :loading="submitting" data-testid="reg-submit" @click="onSubmit">
        注册
      </wd-button>

      <view class="form-card__footer">
        <text class="form-card__hint">已有账号？</text>
        <text class="form-card__link" @click="goLogin">去登录</text>
      </view>
    </view>

    <!-- 关闭注册时的整页兜底：由 redirect 或后台配置变更导致 -->
    <view v-if="registerMode === 'closed'" class="closed-mask">
      <view class="hy-card closed-mask__card">
        <text class="closed-mask__title">当前暂未开放注册</text>
        <text class="closed-mask__desc">
          管理员已暂时关闭新用户注册。如已有账号可直接登录；若需要账号，请联系管理员获取邀请码或等待开放。
        </text>
        <wd-button type="primary" block @click="goLogin">去登录</wd-button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 注册页。
 *
 * 契约：`POST /api/auth/register`
 * 必填字段（`RegisterRequest.required`）：username / password / nickname /
 *   captchaUuid / captchaCode / agreeProtocol
 * 条件必填：inviteCode（仅 invite 模式）
 *
 * ⚠️ 重要行为：注册成功后端**只返回 UserVO，不返回 token**。
 *    因此注册后必须引导用户去登录，不能假装"已登录"。
 *
 * 校验分工（有意为之）：
 * - 前端只拦「规则明确、且后端也会拦」的格式问题（用户名正则、密码含字母+数字），
 *   目的是不浪费一次可能触发限流的请求，并让用户立刻看到问题
 * - **真正的唯一性、封禁、验证码正确性由后端判定**，前端不做重复实现
 *   （重复实现会出现前后端规则不一致，是最常见的"前端放行后端拒绝"来源）
 */
import { onLoad } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import { fetchCaptcha, fetchRegisterMode, register } from '@/api/auth'
import type { RegisterMode, RegisterRequest } from '@/api/types'
import { ApiError } from '@/utils/request'

const form = reactive({
  username: '',
  password: '',
  nickname: '',
  captchaCode: '',
  inviteCode: '',
  agreeProtocol: false,
})

/** 验证码 uuid，随表单提交。由 `fetchCaptcha` 得到 */
const captchaUuid = ref('')
/** 验证码图片 base64 */
const captchaImage = ref('')
const captchaLoading = ref(false)
const submitting = ref(false)
const registerMode = ref<RegisterMode>('closed')

onLoad(async () => {
  registerMode.value = await fetchRegisterMode()
  // 已关闭注册时不必浪费一次验证码请求
  if (registerMode.value !== 'closed') {
    await refreshCaptcha()
  }
})

/**
 * 刷新验证码。
 *
 * 失败时给出明确提示而不是静默留白：验证码取不到就无法注册，
 * 用户需要知道"是网络问题还是功能坏了"。
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

function toggleAgree() {
  form.agreeProtocol = !form.agreeProtocol
}

/**
 * 前端格式校验。
 * 规则来源：《技术方案》§6.2「注册校验规则」
 *   用户名 4–20 位字母数字下划线；密码 8–32 位且至少含字母与数字；昵称 1–20 字符。
 * 返回错误文案，通过则返回空字符串。
 */
function validate(): string {
  const username = form.username.trim()
  const nickname = form.nickname.trim()

  if (!/^[A-Za-z0-9_]{4,20}$/.test(username)) {
    return '用户名需为 4-20 位字母、数字或下划线'
  }
  if (form.password.length < 8 || form.password.length > 32) {
    return '密码长度需为 8-32 位'
  }
  // 至少含字母与数字：用两条独立正则，比单条复杂正则更容易读
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

async function onSubmit() {
  if (submitting.value) return

  const error = validate()
  if (error) {
    uni.showToast({ title: error, icon: 'none', duration: 2500 })
    return
  }

  /*
   * 组装请求体。
   * `inviteCode` **只在该模式下才带上** —— 给 open 模式发一个空字符串邀请码，
   * 后端可能把它当成"提供了邀请码"去校验，属于无谓的边界风险。
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
    // 契约：注册不返回 token，故引导去登录页
    uni.showToast({ title: '注册成功，请登录', icon: 'success', duration: 1500 })
    setTimeout(() => {
      uni.redirectTo({ url: '/pages/login/index' })
    }, 1500)
  } catch (e) {
    const message = e instanceof ApiError ? e.message : '注册失败，请检查网络后重试'
    uni.showToast({ title: message, icon: 'none', duration: 2500 })

    /*
     * 失败后**必须刷新验证码**。
     * 原因：验证码在 Redis 里是「一次性」的（技术方案 §6.2，TTL 300s），
     * 且提交后无论成败都已消费掉。不刷新的话用户会一直看到"验证码错误"，
     * 无论怎么输都不对 —— 这是注册流程最容易踩的坑。
     */
    await refreshCaptcha()
    form.captchaCode = ''
  } finally {
    submitting.value = false
  }
}

function goLogin() {
  uni.redirectTo({ url: '/pages/login/index' })
}

/** 跳静态文档页。用 redirectTo 避免在页面栈里堆很多层 */
function goDoc(name: 'agreement' | 'privacy') {
  uni.navigateTo({ url: `/pages/${name}/index` })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.page {
  min-height: 100vh;
  padding: $hy-space-lg $hy-space-md;
  box-sizing: border-box;
  position: relative;
}

.header {
  padding: $hy-space-lg $hy-space-sm $hy-space-xl;
  display: flex;
  flex-direction: column;

  &__title {
    font-size: 48rpx;
    font-weight: 600;
    color: $hy-text-primary;
  }

  &__subtitle {
    margin-top: $hy-space-xs;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
  }
}

.form-card {
  padding: $hy-space-lg;

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
    font-size: $hy-font-sm;
    color: $hy-text-regular;
    margin-bottom: $hy-space-sm;
  }

  /* 邀请制标记：让用户知道为什么这里多了一个框 */
  &__label-tag {
    margin-left: 8rpx;
    font-size: $hy-font-xs;
    color: $hy-color-warning;
  }

  &__input {
    width: 100%;
    height: 88rpx;
    padding: 0 $hy-space-md;
    box-sizing: border-box;
    background-color: $hy-bg-page;
    border-radius: $hy-radius-md;
    font-size: $hy-font-md;
    color: $hy-text-primary;
  }

  &__placeholder {
    color: $hy-text-placeholder;
  }

  &__tip {
    display: block;
    margin-top: 8rpx;
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
  }
}

/* ---------- 验证码 ---------- */
.captcha {
  display: flex;
  align-items: center;

  &__input {
    flex: 1;
  }

  &__box {
    width: 220rpx;
    height: 88rpx;
    margin-left: $hy-space-sm;
    flex-shrink: 0;
    background-color: $hy-bg-page;
    border-radius: $hy-radius-md;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
  }

  &__image {
    width: 100%;
    height: 100%;
  }

  &__placeholder {
    font-size: $hy-font-xs;
    color: $hy-text-secondary;
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
    border: 2rpx solid $hy-border-color;
    border-radius: $hy-radius-sm;
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

  &__tick {
    color: #ffffff;
    font-size: 24rpx;
    line-height: 1;
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

/* ---------- 关闭注册的整页兜底 ---------- */
.closed-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(255, 255, 255, 0.96);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: $hy-space-lg;
  box-sizing: border-box;

  &__card {
    width: 100%;
    text-align: center;
    padding: $hy-space-xl $hy-space-lg;
  }

  &__title {
    display: block;
    font-size: $hy-font-lg;
    font-weight: 600;
    color: $hy-text-primary;
    margin-bottom: $hy-space-sm;
  }

  &__desc {
    display: block;
    font-size: $hy-font-sm;
    color: $hy-text-secondary;
    line-height: 1.7;
    margin-bottom: $hy-space-lg;
  }
}
</style>
