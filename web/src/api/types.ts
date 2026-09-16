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
 * M3 交付的接口类型（**全部直接来自契约，无人工成分**）
 * ---------------------------------------------------------------------------
 * 依据是冻结的 `openapi.json`（14 路径 / 26 schema）。字段名逐字对应，不增不减。
 *
 * ⚠️ 契约里**所有** schema 都没有 `required` 声明（除了 PostCreateRequest 的
 *    `boardId`/`title` 与 PostUpdateRequest 的 `title`），因此生成类型里
 *    每个字段都是可选的（`title?: string`）。这是**契约的真实形状**，
 *    前端不得靠"我觉得后端一定会返回"就把它断言成必填 ——
 *    口径见《技术方案》§6.1 与任务书 §5。
 *    页面对可选字段一律用 `?? 默认值` 收敛，收敛点集中在 `utils/format.ts`。
 */

/** 版块列表项。`isResource` 用于决定发帖表单是否显示网盘字段（口径 9） */
export type BoardVO = Schemas['BoardVO']

/** 帖子列表项（**不含正文**，口径 6） */
export type PostSummaryVO = Schemas['PostSummaryVO']

/** 帖子详情 */
export type PostDetailVO = Schemas['PostDetailVO']

/** 帖子图片。列表用不到，详情九宫格与发帖预览用 */
export type PostImageVO = Schemas['PostImageVO']

/** 作者信息摘要（列表项与详情共用） */
export type UserBriefVO = Schemas['UserBriefVO']

/** 发帖请求体。`boardId`、`title` 在契约里必填 */
export type PostCreateRequest = Schemas['PostCreateRequest']

/**
 * 改帖请求体。`title` 必填。
 *
 * ⚠️ **PUT 是覆盖语义**（口径 11）：没传的字段视为清空。
 *    因此 `PostUpdateRequest` 的字段**没有一个是可省的** ——
 *    表单必须把当前值原样回填后再提交，不能只提交改动的那一项。
 */
export type PostUpdateRequest = Schemas['PostUpdateRequest']

/** 分页响应体（列表与搜索共用） */
export type PageResultPostSummaryVO = Schemas['PageResultPostSummaryVO']

/**
 * 帖子列表排序口径。
 *
 * 依据：《技术方案》§6.5 —— `sort=latest|hot|essence`。
 *
 * ⚠️ **契约缺口（不阻塞，仅记录）**：`openapi.json` 里 `GET /api/posts` 的 `sort`
 *    参数只声明为 `type: string`，**没有 `enum`**，因此这一组取值只能来自文档。
 *    契约若能补上 `enum`，前端就能由生成物得到联合类型、并让后端改取值时自动变红。
 *    已写入交付报告，供 L1 判断是否值得补。
 */
export type PostSort = 'latest' | 'hot' | 'essence'

/** 排序选项的展示文案。放在这里而不是页面里，避免两处漂移 */
export const POST_SORT_LABELS: ReadonlyArray<{ value: PostSort; label: string }> = [
  { value: 'latest', label: '最新' },
  { value: 'hot', label: '热门' },
  { value: 'essence', label: '精华' },
]

/* ---------------------------------------------------------------------------
 * 前端内部类型
 * ------------------------------------------------------------------------- */

/** 登录态（持久化到本地存储的结构） */
export interface AuthState {
  token: string
  user: UserVO | null
}
