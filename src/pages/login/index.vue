<template>
  <view class="page">
    <view class="header">
      <text class="header__title">登录</text>
      <text class="header__subtitle">使用用户名与密码登录 Hy论坛</text>
    </view>

    <view class="hy-card form-card">
      <!-- 用户名 -->
      <view class="field">
        <text class="field__label">用户名</text>
        <input
          v-model="form.username"
          data-testid="login-username"
          class="field__input"
          type="text"
          placeholder="请输入用户名"
          placeholder-class="field__placeholder"
          :maxlength="20"
          :disabled="submitting"
          @confirm="onSubmit"
        />
      </view>

      <!-- 密码 -->
      <view class="field">
        <text class="field__label">密码</text>
        <view class="field__password">
          <input
            v-model="form.password"
            data-testid="login-password"
            class="field__input field__input--with-action"
            :password="!showPassword"
            placeholder="请输入密码"
            placeholder-class="field__placeholder"
            :maxlength="32"
            :disabled="submitting"
            @confirm="onSubmit"
          />
          <!-- 明文开关：中文输入法环境下密码打错很常见，给用户自查手段 -->
          <text class="field__action" @click="showPassword = !showPassword">
            {{ showPassword ? '隐藏' : '显示' }}
          </text>
        </view>
      </view>

      <wd-button type="primary" block :loading="submitting" data-testid="login-submit" @click="onSubmit">
        登录
      </wd-button>

      <view v-if="registerMode !== 'closed'" class="form-card__footer">
        <text class="form-card__hint">还没有账号？</text>
        <text class="form-card__link" @click="goRegister">立即注册</text>
      </view>
      <view v-else class="form-card__footer">
        <text class="form-card__hint">当前暂未开放注册</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 登录页。
 *
 * 契约：`POST /api/auth/login`，参数 `{username, password}`，
 * 成功返回 `LoginVO = { token, user }`（见 openapi.json）。
 *
 * 设计要点：
 * - 登录**不需要图形验证码**（契约里只有注册需要），因此本页不放验证码控件
 * - 失败文案全部来自请求层归一化后的 `ApiError.message`，页面不重复维护错误码映射
 * - 提交中禁用输入与按钮，避免重复提交（后端有频率限制，重复提交会撞 429）
 */
import { onLoad } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { fetchRegisterMode } from '@/api/auth'
import type { RegisterMode } from '@/api/types'
import { ApiError } from '@/utils/request'

const auth = useAuthStore()

const form = reactive({
  username: '',
  password: '',
})

const submitting = ref(false)
const showPassword = ref(false)
const registerMode = ref<RegisterMode>('closed')

onLoad(async () => {
  // 登录页也要知道注册模式：否则「立即注册」按钮可能指向一个已关闭的页面
  registerMode.value = await fetchRegisterMode()
})

/**
 * 提交登录。
 *
 * 前端只做**最低限度**的非空校验：真正的规则校验（用户名 4–20 位等）由后端负责，
 * 前端重复实现一遍规则容易出现"前端放行、后端拒绝"或反之的不一致。
 * 但空值不值得发一次请求（浪费一次可能触发限流的调用），所以拦一下。
 */
async function onSubmit() {
  if (submitting.value) return

  const username = form.username.trim()
  const password = form.password

  if (!username) {
    uni.showToast({ title: '请输入用户名', icon: 'none' })
    return
  }
  if (!password) {
    uni.showToast({ title: '请输入密码', icon: 'none' })
    return
  }

  submitting.value = true
  try {
    await auth.login(username, password)
    uni.showToast({ title: '登录成功', icon: 'success' })

    /*
     * 登录后的跳转策略：
     * 优先回到来源页（`redirect` 参数），否则回首页。
     * 用 `reLaunch` 而不是 `navigateBack`：登录页可能来自任意入口
     * （直接打开、被守卫重定向），navigateBack 在页面栈只有一层时会失败。
     */
    const pages = getCurrentPages()
    setTimeout(() => {
      if (pages.length > 1) {
        uni.navigateBack()
      } else {
        uni.reLaunch({ url: '/pages/index/index' })
      }
    }, 600)
  } catch (e) {
    const message =
      e instanceof ApiError ? e.message : '登录失败，请检查网络后重试'
    uni.showToast({ title: message, icon: 'none', duration: 2500 })
  } finally {
    submitting.value = false
  }
}

function goRegister() {
  uni.navigateTo({ url: '/pages/register/index' })
}
</script>

<style lang="scss" scoped>
@use '@/styles/variables.scss' as *;

.page {
  min-height: 100vh;
  padding: $hy-space-lg $hy-space-md;
  box-sizing: border-box;
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

  &__input {
    width: 100%;
    height: 88rpx;
    padding: 0 $hy-space-md;
    box-sizing: border-box;
    background-color: $hy-bg-page;
    border-radius: $hy-radius-md;
    font-size: $hy-font-md;
    color: $hy-text-primary;

    &--with-action {
      padding-right: 120rpx;
    }
  }

  &__placeholder {
    color: $hy-text-placeholder;
  }

  /* 密码输入框右侧的「显示/隐藏」开关 */
  &__password {
    position: relative;
  }

  &__action {
    position: absolute;
    right: $hy-space-md;
    top: 0;
    height: 88rpx;
    line-height: 88rpx;
    font-size: $hy-font-sm;
    color: $hy-color-primary;
  }
}
</style>
