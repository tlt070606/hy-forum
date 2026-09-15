/**
 * 登录态 store（Pinia）。
 *
 * ==========================================================================
 * 唯一状态源原则
 * ==========================================================================
 * 登录态由两处共同承载：
 * - **本地存储**（`storage.ts` 封装）：冷启动恢复用，是"持久化副本"
 * - **本 store**（内存）：页面响应式读取用，是"运行时唯一源"
 *
 * 两者的一致性由本 store 独家维护 —— **页面不要自己去读写 storage**，
 * 否则会出现"内存里已退出、存储里还有 token"这类状态分叉。
 *
 * ⚠️ 不使用 `pinia-plugin-persistedstate` 之类的自动持久化插件：
 * 它会绕过 `storage.ts` 直接摸 localStorage，在小程序端不可用。
 */

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import type { UserVO } from '@/api/types'
import { STORAGE_KEYS, clearAuth, getObject, getString, setObject, setString } from '@/utils/storage'
import { ApiError } from '@/utils/request'

export const useAuthStore = defineStore('auth', () => {
  /* -------------------------------------------------------------------------
   * state
   * ----------------------------------------------------------------------- */

  /** 当前 token。空字符串表示未登录 */
  const token = ref<string>('')
  /** 当前用户信息。未登录或尚未拉取时为 null */
  const user = ref<UserVO | null>(null)
  /** 是否已从本地存储恢复过（避免重复恢复与首屏误判） */
  const restored = ref(false)

  /* -------------------------------------------------------------------------
   * getters
   * ----------------------------------------------------------------------- */

  /** 是否已登录。**判定依据只有 token**，不依赖 user 是否已加载 */
  const isLoggedIn = computed(() => token.value.length > 0)

  /** 展示用昵称：优先昵称，回退用户名，再回退占位 */
  const displayName = computed(() => user.value?.nickname || user.value?.username || '未登录')

  /* -------------------------------------------------------------------------
   * actions
   * ----------------------------------------------------------------------- */

  /**
   * 冷启动时从本地存储恢复登录态。
   * 在 `App.vue` 的 `onLaunch` 调用（见该文件注释：必须在此时机，确保存储 API 可用）。
   *
   * 只恢复**本地已有的**数据，不发起网络请求 —— 冷启动不该阻塞在网络上。
   * 真实有效性由后续 `ensureProfile()` 校验。
   */
  function restoreFromStorage(): void {
    token.value = getString(STORAGE_KEYS.TOKEN)
    user.value = getObject<UserVO | null>(STORAGE_KEYS.USER_INFO, null)
    restored.value = true
  }

  /**
   * 写入登录态（登录成功、或 token 刷新后调用）。
   * 同时更新内存与本地存储，保证两者不漂移。
   */
  function setSession(newToken: string, newUser: UserVO | null): void {
    token.value = newToken
    user.value = newUser
    setString(STORAGE_KEYS.TOKEN, newToken)
    if (newUser) {
      setObject(STORAGE_KEYS.USER_INFO, newUser)
    }
  }

  /** 清空登录态（内存 + 本地存储） */
  function clearSession(): void {
    token.value = ''
    user.value = null
    clearAuth()
  }

  /**
   * 登录。
   * @throws {ApiError} 登录失败（含验证码/密码错误、账号封禁）
   */
  async function login(username: string, password: string): Promise<UserVO | null> {
    const vo = await authApi.login({ username, password })
    // 契约上 LoginVO.token / user 是可选字段，做一次防御性判断：
    // 没有 token 的"成功"响应是无效登录，必须当失败处理，
    // 否则会出现"接口成功、页面还是未登录"的诡异现象
    if (!vo?.token) {
      throw new ApiError({
        kind: 'business',
        code: -1,
        message: '登录响应异常（未返回登录凭证），请稍后重试',
      })
    }
    setSession(vo.token, vo.user ?? null)
    return user.value
  }

  /**
   * 注销。
   *
   * **无论服务端注销是否成功，都清理本地登录态**。
   * 理由：若服务端失败（token 已过期等）而本地保留 token，
   * 用户会处于"看起来已登录、每个请求都 401"的坏状态 —— 比"提前退出"更糟。
   * 服务端残留的 token 会按其自身 TTL 自然过期。
   */
  async function logout(): Promise<void> {
    try {
      if (token.value) await authApi.logout()
    } catch (e) {
      // 仅记录，不向上抛：退出登录对用户必须是"一定能成功"的操作
      console.warn('[auth] 服务端注销失败，仍清理本地登录态', e)
    } finally {
      clearSession()
    }
  }

  /**
   * 拉取（或刷新）当前用户信息。
   *
   * - 未登录时直接返回 null，不发请求
   * - token 失效（`ApiError.isAuthExpired`）时清理登录态并返回 null，
   *   让页面自然回到未登录状态，而不是把 401 当异常抛到页面上
   * - 其它错误（网络问题等）**向上抛**：这类失败不该被当成"未登录"，
   *   否则用户会看到"莫名退出登录"
   */
  async function ensureProfile(force = false): Promise<UserVO | null> {
    if (!isLoggedIn.value) return null
    if (user.value && !force) return user.value

    try {
      const me = await authApi.fetchMe()
      user.value = me
      setObject(STORAGE_KEYS.USER_INFO, me)
      return me
    } catch (e) {
      if (e instanceof ApiError && e.isAuthExpired) {
        clearSession()
        return null
      }
      throw e
    }
  }

  return {
    // state
    token,
    user,
    restored,
    // getters
    isLoggedIn,
    displayName,
    // actions
    restoreFromStorage,
    setSession,
    clearSession,
    login,
    logout,
    ensureProfile,
  }
})
