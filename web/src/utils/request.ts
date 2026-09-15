/**
 * 请求层：对 `uni.request` 的统一封装。
 *
 * ==========================================================================
 * 为什么不用 Axios（这是本项目最容易被"顺手"写错的一条）
 * ==========================================================================
 * 小程序端**没有 XHR / 没有 DOM**，Axios 在小程序里根本无法工作。
 * 且 axios 的 uni-app 适配包（axios-adapter-uniapp 之流）属于额外依赖，
 * 会与「禁止在低配档位引入重组件」的取向相悖。
 * 因此按《技术方案》§3.1：**统一用 `uni.request` 自己封装**。
 *
 * ==========================================================================
 * 本封装承担的职责（对应任务书 §5.2）
 * ==========================================================================
 * 1. 拼基地址（基地址来自环境变量，铁律 5）
 * 2. 注入 `Authorization: {token}` 头（Sa-Token，注意**不带 Bearer 前缀**）
 * 3. 拆统一响应体 `{code, message, data}`：`code===0` 返回 `data`，否则抛 `ApiError`
 * 4. 错误码 → 用户文案（见 `error-code.ts`）
 * 5. `401` 时清理本地登录态并抛错，由调用方/页面决定是否跳登录
 * 6. 网络层失败（断网、超时、后端没起）统一转成 `ApiError`，
 *    让调用方只需 catch 一种错误类型
 *
 * ==========================================================================
 * 设计选择：为什么抛异常而不是返回 `[err, data]` 元组
 * ==========================================================================
 * 抛异常让业务代码能顺序书写（try/catch），避免每层都判断返回值。
 * 但**所有错误都被归一化为 `ApiError`**，所以 catch 分支无需分辨错误来源。
 */

import { getApiBaseUrl, ENABLE_REQUEST_LOG } from './env'
import { BIZ_CODE, messageOfCode, isAuthExpired } from './error-code'
import { STORAGE_KEYS, clearAuth, getString } from './storage'

/**
 * 后端统一响应体。
 * 形状来自 `openapi.json` 的 `ApiResponse*` 系列（`{code, message, data}`）。
 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

/**
 * 分页响应体。
 * 形状来自《技术方案》§6.1：`{list, total, page, size}`，每页上限 20。
 */
export interface PageData<T> {
  list: T[]
  total: number
  page: number
  size: number
}

/** 分页每页上限（契约硬约束，前端不得请求更大值） */
export const PAGE_SIZE_MAX = 20

/**
 * 归一化后的 API 错误。
 *
 * 区分三类，便于调用方精细处理：
 * - `business`：后端返回了统一响应体但 `code !== 0`（如 1001 用户名已存在）
 * - `http`：HTTP 状态异常且响应体不是统一响应体（如 Nginx 502 HTML 页）
 * - `network`：请求根本没到达服务端（断网、后端未启动、超时）
 */
export class ApiError extends Error {
  readonly kind: 'business' | 'http' | 'network'
  /** 业务码；`http`/`network` 类错误时可能是 HTTP 状态码或 -1 */
  readonly code: number
  /** HTTP 状态码，未知为 0 */
  readonly httpStatus: number

  constructor(params: {
    kind: 'business' | 'http' | 'network'
    code: number
    httpStatus?: number
    message: string
  }) {
    super(params.message)
    this.name = 'ApiError'
    this.kind = params.kind
    this.code = params.code
    this.httpStatus = params.httpStatus ?? 0
  }

  /** 是否为「登录态失效」，调用方据此决定是否跳登录页 */
  get isAuthExpired(): boolean {
    return this.kind === 'business' && isAuthExpired(this.code)
  }
}

/** 请求可选项 */
export interface RequestOptions {
  /** 请求方法，默认 GET */
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  /** 查询参数。值为 undefined/null 的键会被丢弃（避免拼出 `?a=undefined`） */
  query?: Record<string, string | number | boolean | undefined | null>
  /** 请求体（JSON） */
  data?: unknown
  /**
   * 是否自动附带 token。默认 `true`。
   * 公开接口（如登录、验证码）也带 token 无副作用，但显式关闭更清晰。
   */
  withAuth?: boolean
  /**
   * 是否在 401 时自动清理本地登录态。默认 `true`。
   * 登录接口本身要设 false —— 密码错误若返回 401，不该把用户已有会话清掉。
   */
  clearAuthOn401?: boolean
}

/**
 * 把 query 对象拼成查询串。
 *
 * 边界处理：跳过 `undefined` / `null`（否则会发出 `?keyword=undefined`，
 * 后端会当成真实字符串过滤，出现"搜不到任何东西"的诡异现象）；
 * 显式保留 `0` / `false` / 空字符串 —— 它们是合法取值。
 */
function buildQuery(query?: RequestOptions['query']): string {
  if (!query) return ''
  const parts: string[] = []
  Object.keys(query).forEach((key) => {
    const v = query[key]
    if (v === undefined || v === null) return
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(v))}`)
  })
  return parts.length ? `?${parts.join('&')}` : ''
}

/**
 * 发起请求，返回业务数据（已解包 `data`）。
 *
 * @throws {ApiError} 任何失败（业务码非 0 / HTTP 异常 / 网络异常）
 */
export function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', query, data, withAuth = true, clearAuthOn401 = true } = options

  return new Promise<T>((resolve, reject) => {
    // 基地址校验可能抛错（未配置/非 https），包进 Promise 让调用方统一 catch
    let url: string
    try {
      const base = getApiBaseUrl()
      /*
       * H5 平台的基地址是**空字符串**（同源相对路径，见 env.ts 的说明），
       * 此时必须直接拼 path，不能写成 `${base}${path}` 后再补斜杠 ——
       * 那会拼出 `//api/auth/login`，某些服务器会把 `//api` 当成协议相对 URL 处理。
       */
      url = base ? `${base}${path}` : path
      url += buildQuery(query)
    } catch (e) {
      reject(
        new ApiError({
          kind: 'network',
          code: -1,
          message: e instanceof Error ? e.message : String(e),
        })
      )
      return
    }

    const header: Record<string, string> = {
      'Content-Type': 'application/json',
    }

    // 鉴权头。Sa-Token 约定是 `Authorization: {token}`，**不加 Bearer 前缀**
    // —— 加了会被 Sa-Token 解析成一个带前缀的错误 token，表现为"一直未登录"。
    if (withAuth) {
      const token = getString(STORAGE_KEYS.TOKEN)
      if (token) header.Authorization = token
    }

    if (ENABLE_REQUEST_LOG) {
      console.log(`[api] → ${method} ${url}`, data ?? '')
    }

    uni.request({
      url,
      method,
      header,
      data: data as never,
      // 超时 15s：本地后端冷启动可能较慢，但也不能无限等
      timeout: 15000,

      success: (res) => {
        const status = res.statusCode ?? 0
        const body = res.data as ApiResponse<T> | undefined

        if (ENABLE_REQUEST_LOG) {
          console.log(`[api] ← ${status} ${method} ${url}`, body)
        }

        /*
         * 情况一：正常拿到统一响应体。
         *
         * 判定条件刻意宽松（`typeof code === 'number'`）而不是严格校验 message 字段，
         * 因为未来后端给 data 加字段不该让前端解析失败。
         */
        if (body && typeof body === 'object' && typeof body.code === 'number') {
          if (body.code === BIZ_CODE.SUCCESS) {
            resolve(body.data)
            return
          }

          // 登录态失效：清理本地 token，避免后续请求继续带着废 token
          if (clearAuthOn401 && isAuthExpired(body.code)) {
            clearAuth()
          }

          reject(
            new ApiError({
              kind: 'business',
              code: body.code,
              httpStatus: status,
              // 已知码用前端稳定文案，未知码回退后端 message
              message: messageOfCode(body.code, body.message),
            })
          )
          return
        }

        /*
         * 情况二：HTTP 状态码异常，且响应体不是统一响应体。
         * 典型来源：Nginx 502/504 的 HTML 错误页、网关限流页、后端未启动时的空响应。
         * 这时**不能**把原始 body 塞给用户看（可能是一大段 HTML），只给一句通用文案。
         */
        if (status < 200 || status >= 300) {
          if (clearAuthOn401 && status === 401) clearAuth()
          reject(
            new ApiError({
              kind: 'http',
              code: status,
              httpStatus: status,
              message: messageOfCode(status),
            })
          )
          return
        }

        /*
         * 情况三：HTTP 200 但响应体形状不对（例如后端改了返回结构、被中间层套了一层）。
         * 明确报错而不是"当作成功返回 undefined" —— 后者会让页面拿到 undefined
         * 然后在渲染时报奇怪的错，根因被埋掉。
         */
        reject(
          new ApiError({
            kind: 'http',
            code: -1,
            httpStatus: status,
            message: '服务端返回的数据格式无法识别，请稍后重试',
          })
        )
      },

      fail: (err) => {
        // 网络层失败：请求没能拿到 HTTP 响应
        const raw = String(err?.errMsg ?? '')
        let message = '网络连接失败，请检查网络后重试'
        // 超时单独给文案：用户能据此判断是网络慢还是服务端挂了
        if (raw.includes('timeout')) {
          message = '请求超时，请稍后重试'
        }
        if (ENABLE_REQUEST_LOG) {
          console.warn(`[api] ✗ ${method} ${url}`, raw)
        }
        reject(
          new ApiError({
            kind: 'network',
            code: -1,
            message,
          })
        )
      },
    })
  })
}

/** GET 简写 */
export function get<T>(path: string, query?: RequestOptions['query'], options?: RequestOptions) {
  return request<T>(path, { ...options, method: 'GET', query })
}

/** POST 简写 */
export function post<T>(path: string, data?: unknown, options?: RequestOptions) {
  return request<T>(path, { ...options, method: 'POST', data })
}

/** PUT 简写 */
export function put<T>(path: string, data?: unknown, options?: RequestOptions) {
  return request<T>(path, { ...options, method: 'PUT', data })
}

/** DELETE 简写 */
export function del<T>(path: string, options?: RequestOptions) {
  return request<T>(path, { ...options, method: 'DELETE' })
}
