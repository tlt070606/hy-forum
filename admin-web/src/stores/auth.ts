/**
 * 登录态：localStorage 为持久化副本，模块内变量为运行时唯一源
 * （与前台 stores/auth.ts 同一口径——避免每次读写都碰 storage）。
 */

const TOKEN_KEY = 'hy-admin:token'
const ADMIN_KEY = 'hy-admin:profile'

export interface AdminProfile {
  adminId: number
  nickname: string
  role: string
}

let cachedToken: string | null = null
let cachedProfile: AdminProfile | null = null

export function getToken(): string | null {
  if (cachedToken === null) {
    cachedToken = localStorage.getItem(TOKEN_KEY)
  }
  return cachedToken
}

export function getProfile(): AdminProfile | null {
  if (cachedProfile === null) {
    const raw = localStorage.getItem(ADMIN_KEY)
    if (raw) {
      try {
        cachedProfile = JSON.parse(raw) as AdminProfile
      } catch {
        cachedProfile = null
      }
    }
  }
  return cachedProfile
}

export function saveAuth(token: string, profile: AdminProfile): void {
  cachedToken = token
  cachedProfile = profile
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(ADMIN_KEY, JSON.stringify(profile))
}

export function clearAuth(): void {
  cachedToken = null
  cachedProfile = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ADMIN_KEY)
}
