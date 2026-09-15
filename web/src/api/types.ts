/**
 * 接口类型出口。
 *
 * ==========================================================================
 * 规则（任务书 §5.2）
 * ==========================================================================
 * 业务代码**一律从这里导入类型**，不要直接 import 生成物：
 * - 生成物（`./generated/schema.d.ts`）由 `openapi-typescript` 从 `openapi.json` 快照产出，
 *   **任何人不得手改**（任务书写明该目录由契约所有者独占）
 * - 生成物的类型路径较深（`components['schemas']['UserVO']`），
 *   集中在这里做一次别名，业务代码写起来干净，且将来契约版本升级只需改这一处
 *
 * ==========================================================================
 * 形状缺口（必须知道，否则会踩坑）
 * ==========================================================================
 * 契约里 `GET /api/auth/captcha` 与 `GET /api/auth/register-mode` 的响应
 * 都被声明为 `ApiResponseMapStringObject`，即 `data` 是 `Record<string, any>`——
 * **具体字段名没有静态声明**。
 *
 * 下面用「人工窄化类型 + 运行时校验」补这一层：
 * - 类型在这里声明，并在注释里写明依据（《技术方案》§6.2 / §8.8）
 * - **运行时校验在 `api/auth.ts` 里做**，因为类型在运行时不存在，
 *   后端改字段名时前端必须能立刻发现，而不是静默拿到 undefined
 *
 * ⚠️ 这是"契约不足"而非"前端自由发挥"：按 AGENTS.md，正式做法是提 CR 让后端
 *    把这几个字段写进 openapi.json。在 CR 落地前，此处的窄化是**有据可依的临时层**，
 *    不是编造。
 */

import type { components } from './generated/schema'

/** 后端契约的全部 schema 类型 */
export type Schemas = components['schemas']

/* ---------------------------------------------------------------------------
 * 契约中**已静态声明**的类型（直接来自生成物，无人工成分）
 * ------------------------------------------------------------------------- */

/** 当前用户信息。字段与 `openapi.json` 的 `UserVO` 逐字对应 */
export type UserVO = Schemas['UserVO']

/** 注册请求体。`agreeProtocol` 在契约里是必填（合规要求） */
export type RegisterRequest = Schemas['RegisterRequest']

/** 登录请求体 */
export type LoginRequest = Schemas['LoginRequest']

/** 登录响应数据：token + 用户信息 */
export type LoginVO = Schemas['LoginVO']

/** 管理员登录响应（管理后台是**独立前端工程**，此处仅为类型完整性保留） */
export type AdminLoginVO = Schemas['AdminLoginVO']

/* ---------------------------------------------------------------------------
 * 形状缺口的人工窄化类型（依据见文件头说明）
 * ------------------------------------------------------------------------- */

/**
 * 注册模式。
 * 依据：《技术方案》§8.8 —— `sys_config.register_mode`，取值 `open` / `invite` / `closed`。
 *
 * - `open`   开放注册：任何人都可注册
 * - `invite` 邀请制：必须填邀请码（合规降级方案，见 PLAN.md §2 #12）
 * - `closed` 关闭注册：前端应隐藏注册入口
 */
export type RegisterMode = 'open' | 'invite' | 'closed'

/**
 * 图形验证码。
 * 依据：《技术方案》§6.2 —— 返回 `{uuid, base64Image}`，
 * 答案存 Redis `hy:captcha:{uuid}`，TTL 300s。
 */
export interface CaptchaVO {
  /** 验证码标识，注册时随表单一起提交 */
  uuid: string
  /**
   * 图片的 Base64 数据（含 `data:image/...;base64,` 前缀，可直接作为 `image` 的 src）。
   * 小程序端 `<image>` 支持 base64，因此两端同一套代码。
   */
  base64Image: string
}

/* ---------------------------------------------------------------------------
 * 前端内部类型
 * ------------------------------------------------------------------------- */

/** 登录态（持久化到本地存储的结构） */
export interface AuthState {
  token: string
  user: UserVO | null
}
