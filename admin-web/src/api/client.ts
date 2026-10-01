/**
 * 请求封装：token 注入、401 统一登出、错误归一化为 ApiError。
 *
 * <p>与前台 request.ts 的口径对齐，但**刻意更薄**：管理端是桌面浏览器专用工具，
 * 不需要 uni.request 跨端、不需要 403/401 区分策略（401 就是重新登录）。</p>
 */

import { clearAuth, getToken } from '../stores/auth'

export class ApiError extends Error {
  constructor(
    /** 后端业务码（网络失败为 -1，便于 UI 分支判断"根本没到后端"）。 */
    public code: number,
    message: string,
    /** HTTP 状态码（业务码 200 型错误时为 200）。 */
    public httpStatus: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface Envelope<T> {
  code: number
  message: string
  data: T
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  const token = getToken()
  if (token) {
    headers.Authorization = token
  }
  if (body !== undefined) {
    // 带 body 的请求必须声明 JSON：否则 Spring 按 text/plain 拒收（HttpMediaTypeNotSupportedException）
    headers['Content-Type'] = 'application/json'
  }
  let res: Response
  try {
    res = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(-1, '网络错误：无法连接后端（检查后端是否在跑）', 0)
  }

  if (res.status === 401) {
    // 后台登录态失效：清掉并回登录页（路由守卫会兜底）
    clearAuth()
    if (!location.hash.startsWith('#/login')) {
      location.hash = '#/login'
    }
    throw new ApiError(401, '登录已失效，请重新登录', 401)
  }

  let env: Envelope<T>
  try {
    env = (await res.json()) as Envelope<T>
  } catch {
    throw new ApiError(-1, `响应不是 JSON（HTTP ${res.status}），后端可能没起`, res.status)
  }
  if (env.code !== 0) {
    throw new ApiError(env.code, env.message || `操作失败（code=${env.code}）`, res.status)
  }
  return env.data
}

export function get<T>(path: string): Promise<T> {
  return request<T>('GET', path)
}

export function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('POST', path, body)
}

export function put<T>(path: string, body: unknown): Promise<T> {
  return request<T>('PUT', path, body)
}

export function del<T>(path: string, body?: unknown): Promise<T> {
  return request<T>('DELETE', path, body)
}

/** 分页查询串（page/size 由调用方给，其余 query 原样拼）。 */
export function pageQuery(page: number, size: number, extra?: Record<string, string | number | undefined>): string {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  for (const [k, v] of Object.entries(extra ?? {})) {
    if (v !== undefined && v !== '') {
      params.set(k, String(v))
    }
  }
  return params.toString()
}
