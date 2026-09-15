/**
 * 错误码 → 用户可读文案 映射。
 *
 * 来源：《技术方案》§6.1「通用约定」 + `openapi.json` info.description 中的错误码清单。
 *
 * ==========================================================================
 * 三条实现原则
 * ==========================================================================
 * 1. **只映射契约里登记过的码**。前端不自行发明错误码语义 ——
 *    契约没有的码一律走兜底文案，避免"前端以为 1005 是某某含义"这种臆测。
 * 2. **`500` 是兜底码，文案必须通用**。契约明确要求响应体只给通用文案、
 *    不得含堆栈/SQL/类名。前端同样不根据 500 猜测原因。
 * 3. **文案面向普通用户**，不出现"参数"、"校验失败"、"token"这类开发者词汇。
 *
 * 为什么把文案放在前端而不是直接用后端 message：
 * 后端 message 可能因后端版本变化而措辞不一（例如带参数、带内部标识）。
 * 前端对已知码给出**稳定文案**，只有未知码才回退到后端 message，
 * 这样用户看到的话术可控，同时不至于丢失后端新增码的信息。
 */

/** 后端统一响应体中的业务码（`code` 字段） */
export const BIZ_CODE = {
  SUCCESS: 0,

  /* ---- 通用 HTTP 语义码 ---- */
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  TOO_MANY_REQUESTS: 429,
  SERVER_ERROR: 500,

  /* ---- 业务码 ---- */
  USERNAME_EXISTS: 1001,
  BAD_CREDENTIALS: 1002,
  CAPTCHA_WRONG: 1003,
  ACCOUNT_BANNED: 1004,
  SENSITIVE_CONTENT: 2001,
  POST_TOO_FREQUENT: 2002,
} as const

export type BizCode = (typeof BIZ_CODE)[keyof typeof BIZ_CODE]

/**
 * 已知错误码的用户文案。
 *
 * 措辞要求：**告诉用户"发生了什么"以及"怎么办"**，
 * 而不是复述技术状态（"未授权"对用户没有信息量）。
 */
const CODE_MESSAGES: Record<number, string> = {
  [BIZ_CODE.BAD_REQUEST]: '提交的内容有误，请检查后重试',
  [BIZ_CODE.UNAUTHORIZED]: '登录状态已失效，请重新登录',
  [BIZ_CODE.FORBIDDEN]: '你没有权限进行该操作',
  [BIZ_CODE.NOT_FOUND]: '内容不存在或已被删除',
  [BIZ_CODE.TOO_MANY_REQUESTS]: '操作太频繁了，请稍后再试',
  [BIZ_CODE.SERVER_ERROR]: '服务器开小差了，请稍后再试',

  [BIZ_CODE.USERNAME_EXISTS]: '该用户名已被注册，请换一个',
  [BIZ_CODE.BAD_CREDENTIALS]: '用户名或密码不正确',
  [BIZ_CODE.CAPTCHA_WRONG]: '验证码不正确或已过期，请重新获取',
  [BIZ_CODE.ACCOUNT_BANNED]: '该账号已被封禁，如有疑问请联系管理员',
  [BIZ_CODE.SENSITIVE_CONTENT]: '内容包含不适宜词语，请修改后再发布',
  [BIZ_CODE.POST_TOO_FREQUENT]: '发帖太频繁了，请稍后再试',
}

/**
 * 把业务码转成展示文案。
 *
 * @param code    后端返回的 `code`
 * @param fallback 后端返回的 `message`（未知码时使用）
 */
export function messageOfCode(code: number, fallback?: string): string {
  const known = CODE_MESSAGES[code]
  if (known) return known
  // 未知码：优先用后端 message（后端新增码时前端至少不是哑的），
  // 但若后端 message 为空则给一句通用文案，绝不给用户看 undefined
  const trimmed = (fallback ?? '').trim()
  if (trimmed) return trimmed
  return '操作失败，请稍后重试'
}

/**
 * 这些码表示「登录态失效」，前端需要清理本地 token。
 *
 * 只含 `401` 一个码：契约把 401 定义为"未登录"。
 * ⚠️ 不把 `403` 算进来 —— 403 是"已登录但无权限"，
 * 此时清 token 会把用户无故踢下线，是常见实现错误。
 */
export function isAuthExpired(code: number): boolean {
  return code === BIZ_CODE.UNAUTHORIZED
}
