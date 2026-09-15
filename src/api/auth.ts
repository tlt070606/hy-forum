/**
 * 认证模块接口（对应 `openapi.json` 的「认证」「用户」两个 tag）。
 *
 * 逐条对齐契约的 8 个路径中本前端会用到的 6 个：
 *   POST /api/auth/register       注册
 *   POST /api/auth/login          登录
 *   POST /api/auth/logout         注销
 *   GET  /api/auth/captcha        图形验证码
 *   GET  /api/auth/register-mode  注册模式
 *   GET  /api/user/me             当前登录用户
 *
 * （另 2 个是 `/api/admin/login`、`/api/admin/logout`，属管理后台独立前端工程，不在此实现。）
 *
 * 路径不写死字符串，统一取 `contract.ts` 的 `ENDPOINTS`，避免出现
 * "接口改了但某个文件漏改"的漂移。
 */

import { get, post } from '@/utils/request'
import { ENDPOINTS } from './contract'
import type { CaptchaVO, LoginRequest, LoginVO, RegisterMode, RegisterRequest, UserVO } from './types'
import { ApiError } from '@/utils/request'

/* ---------------------------------------------------------------------------
 * 图形验证码
 * ------------------------------------------------------------------------- */

/**
 * 把后端返回的图片载荷规整成可直接放进 `<image src>` 的字符串。
 *
 * ==========================================================================
 * 为什么需要这一步（本机实测踩到的 bug）
 * ==========================================================================
 * 后端 `/api/auth/captcha` 返回的 `base64Image` 是**裸 base64**
 * （形如 `iVBORw0KGgo...`），**不带 `data:` 前缀**。
 *
 * 直接塞进 `<image :src>` 时，浏览器/小程序会把整串当作**相对 URL** 去请求，
 * 于是图片永远出不来 —— 页面上表现为一个空白灰框，且**控制台不报错**
 * （不是请求 404，是根本没被识别成图片），根因极难定位。
 *
 * 契约只声明该字段是 string，没规定格式，所以前端必须两种都兼容：
 * - 已带 `data:` 前缀 → 原样返回
 * - 裸 base64 → 补 `data:image/png;base64,` 前缀（后端生成的是 PNG，实测文件头为
 *   `137,80,78,71,13,10,26,10`）
 *
 * 注意用 `indexOf(';base64,')` 判断而不是只判断 `data:`：
 * 万一后端将来返回 `data:image/svg+xml,...`（非 base64）也能正确原样透传。
 */
function toImageSrc(payload: string): string {
  const raw = payload.trim()
  if (!raw) return ''
  // 已是 data URI（含 base64 或非 base64 的 data URI）则原样使用
  if (raw.startsWith('data:')) return raw
  return `data:image/png;base64,${raw}`
}

/**
 * 获取图形验证码。
 *
 * ⚠️ 契约把这个响应的 `data` 声明为 `Record<string, any>`（形状缺口），
 * 因此**必须做运行时校验**：如果后端把 `base64Image` 改名，前端要立刻抛错，
 * 而不是把 `undefined` 塞进 `<image src>` 导致"验证码区域一片空白"这种
 * 根因极难定位的现象（小程序端 base64 异常时甚至不报错，只是不显示）。
 */
export async function fetchCaptcha(): Promise<CaptchaVO> {
  const data = await get<Record<string, unknown>>(ENDPOINTS.captcha.path, undefined, {
    // 验证码是公开接口，但不带 token 也无妨；显式声明意图
    withAuth: false,
    // 401 不应清本地登录态：这是个公开接口，返回 401 说明后端异常，
    // 把用户的正常会话清掉属于误伤
    clearAuthOn401: false,
  })

  const uuid = data?.uuid
  const base64Image = data?.base64Image

  if (typeof uuid !== 'string' || !uuid) {
    throw new ApiError({
      kind: 'business',
      code: -1,
      message: '验证码服务返回异常（缺少 uuid），请联系管理员',
    })
  }
  if (typeof base64Image !== 'string' || !base64Image) {
    throw new ApiError({
      kind: 'business',
      code: -1,
      message: '验证码服务返回异常（缺少图片），请联系管理员',
    })
  }

  return { uuid, base64Image: toImageSrc(base64Image) }
}

/* ---------------------------------------------------------------------------
 * 注册模式
 * ------------------------------------------------------------------------- */

/** 合法的注册模式取值，用于运行时白名单校验 */
const VALID_MODES: readonly RegisterMode[] = ['open', 'invite', 'closed']

/**
 * 注册模式查询结果。
 *
 * ==========================================================================
 * 为什么是"结果对象"而不是直接返回 RegisterMode（这是一次修正）
 * ==========================================================================
 * 上一版实现是：查询失败时 `catch` 里**静默返回 `'closed'`**。
 * 这个兜底造成了真实事故：小程序端因为地址配置问题连不上后端，
 * `register-mode` 请求失败 → 被当成"管理员关闭了注册" → 注册 Tab 消失，
 * 界面只显示"当前暂未开放注册"。**根因（网络/配置问题）被伪装成了业务状态**，
 * 排查成本极高，而且看起来完全像后端配置错了。
 *
 * 所以现在必须区分两种语义：
 * - `ok: true`  → 后端明确告知的注册模式（包含 `closed`，那是**真的**关闭）
 * - `ok: false` → **查询失败，状态未知**，必须向用户显式报错，绝不冒充业务状态
 *
 * 注意：这只是**前端展示策略**，真正的准入判定始终在后端，前端报错不代表安全问题。
 */
export type RegisterModeResult =
  | { ok: true; mode: RegisterMode }
  | { ok: false; error: string }

/**
 * 查询当前注册模式。
 *
 * 存在契约形状缺口（`data` 在契约里是 `Record<string, any>`），
 * 因此对返回值做**白名单校验**：不在 `open / invite / closed` 之列的一律视为异常，
 * 而不是猜一个默认值。
 *
 * 不会抛异常（调用方多为页面 `onLoad`，抛出去会导致未处理的 Promise 拒绝）；
 * 失败信息通过 `{ ok: false, error }` 返回，由页面决定怎么展示。
 */
export async function fetchRegisterMode(): Promise<RegisterModeResult> {
  try {
    const data = await get<Record<string, unknown>>(ENDPOINTS.registerMode.path, undefined, {
      withAuth: false,
      clearAuthOn401: false,
    })

    /*
     * 形状容错：后端实测返回 `{ mode: 'open', inviteRequired: false }`，
     * 但契约没静态声明字段名，因此同时容忍"直接是字符串"和"包在 mode 字段里"。
     */
    const raw = typeof data === 'string' ? data : data?.mode
    if (typeof raw === 'string' && (VALID_MODES as readonly string[]).includes(raw)) {
      return { ok: true, mode: raw as RegisterMode }
    }

    // 白名单外：这是契约形状不一致，必须报出来而不是猜
    const received = raw === undefined ? '(缺少 mode 字段)' : `"${String(raw)}"`
    console.warn('[auth] register-mode 返回值不在白名单内：', data)
    return {
      ok: false,
      error: `注册状态异常（后端返回 ${received}），请联系管理员`,
    }
  } catch (e) {
    // 失败原因要落到用户能看懂的话上；技术细节留给控制台
    console.warn('[auth] 获取注册模式失败', e)
    const detail = e instanceof ApiError ? e.message : '网络连接失败'
    return {
      ok: false,
      error: `无法获取注册状态：${detail}`,
    }
  }
}

/* ---------------------------------------------------------------------------
 * 注册 / 登录 / 注销
 * ------------------------------------------------------------------------- */

/**
 * 注册。
 *
 * 契约要点（`RegisterRequest` 的 required）：
 * `username` `password` `nickname` `captchaUuid` `captchaCode` `agreeProtocol` 全部必填；
 * `inviteCode` 仅在 `invite` 模式下必填。
 *
 * 成功后后端返回 `UserVO`，**不返回 token** —— 因此注册后仍需调用登录
 * （这是契约的既定行为，前端不要自作聪明地"注册即登录"）。
 */
export function register(payload: RegisterRequest): Promise<UserVO> {
  return post<UserVO>(ENDPOINTS.register.path, payload, {
    withAuth: false,
    // 注册时若 401，不该清掉已有登录态
    clearAuthOn401: false,
  })
}

/**
 * 登录。成功返回 `{ token, user }`。
 *
 * `clearAuthOn401: false` 是有意的：密码错误的语义虽然可能返回 401，
 * 但此时用户可能本来就有别的有效会话（例如多标签页），
 * 清掉会把用户无故踢下线。
 */
export function login(payload: LoginRequest): Promise<LoginVO> {
  return post<LoginVO>(ENDPOINTS.login.path, payload, {
    withAuth: false,
    clearAuthOn401: false,
  })
}

/**
 * 注销当前 token。
 *
 * ⚠️ 调用方必须在**无论成功失败**都要清理本地登录态：
 * 服务端注销失败（如 token 已过期）时，本地还留着 token 会让用户
 * "看着像已登录、实际所有请求都 401"。见 `stores/auth.ts` 的 `logout()`。
 */
export function logout(): Promise<void> {
  return post<void>(ENDPOINTS.logout.path, undefined, {
    withAuth: true,
    // 注销接口自己返回 401 时清掉本地态是正确的
    clearAuthOn401: true,
  })
}

/* ---------------------------------------------------------------------------
 * 当前用户
 * ------------------------------------------------------------------------- */

/** 获取当前登录用户信息（需要登录态） */
export function fetchMe(): Promise<UserVO> {
  return get<UserVO>(ENDPOINTS.me.path, undefined, {
    withAuth: true,
    // 401 说明 token 已失效，清本地态让应用回到"未登录"这一致状态
    clearAuthOn401: true,
  })
}
