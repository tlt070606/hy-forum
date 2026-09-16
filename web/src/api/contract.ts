/**
 * 接口契约的**唯一事实来源**（前端侧）。
 *
 * ==========================================================================
 * 为什么这个文件存在，以及它的权威性边界
 * ==========================================================================
 * 权威契约是后端导出的 `openapi.json`（由 L1 冻结，**任何人不手工编辑**）。
 * 本工程内保存一份**快照**（`src/api/generated/openapi.snapshot.json`）并据此生成类型，
 * 同时记录其 SHA256 / 路径数用于漂移检测。
 *
 * 📌 2026-09-15 起本前端**已不再是独立仓库** —— 它由 `git subtree` 合并进后端仓库的
 *    `web/`，因此「构建时读不到后端文件」这个理由**已经消失**。
 *    快照目前仍在用，它的退役方式与时机见
 *    `docs/agents/M3-计划与前置.md` §5（**不要在退役前手工删它**：`gen:api` 的输入就是它）。
 *
 * ⚠️ 快照不新鲜时**不要手工改它、也不要手工改下面的类型**：
 *     按 AGENTS.md，发现契约不足应**提 CR**（见 docs/agents/工作计划.md §3），
 *     由后端实现接口后重新导出 openapi.json，再更新快照与生成类型。
 *
 * 更新快照的步骤：
 *   1. 用后端仓库根目录的 `openapi.json` 覆盖 `src/api/generated/openapi.snapshot.json`
 *   2. 运行 `npm run gen:api`（重新生成 `schema.d.ts`）
 *   3. **同步本文下方两个常量**（SHA256 与路径数）
 *   4. 运行 `npm run check:contract`（校验快照 ↔ 在线后端，**并校验第 3 步有没有忘**）
 *
 * ==========================================================================
 * 本文件的类型为什么是**手写**的，而不是全部来自生成物
 * ==========================================================================
 * ⚠️ **这一节描述的"形状缺口"已经在 2026-09-16 由 CR-005 修掉了**，保留原文只为说明由来：
 *
 *   `GET /api/auth/captcha`        → 契约声明为 ApiResponseMapStringObject
 *   `GET /api/auth/register-mode`  → 契约声明为 ApiResponseMapStringObject
 *
 * 即 `data` 在契约里是 `Record<string, any>`，**具体字段名没有静态声明**。
 * 字段名与取值的依据是《技术方案》§6.2 与 §8.8 的文字约定：
 *   - captcha       → `{ uuid: string, base64Image: string }`
 *   - register-mode → `'open' | 'invite' | 'closed'`
 *
 * **现状**（M3 第一交付段，CR-005）：契约里已有具名 schema `CaptchaVO` / `RegisterModeVO`，
 * `mode` 带 `enum: [open, invite, closed]`，导出的类型不再是 `Record<string, any>`。
 * 已实测：`M3ContractTypingTest` 逐条断言了这些（含"响应 schema 不再是 MapStringObject"）。
 *
 * 但 `src/api/auth.ts` 里那套**人工窄化 + 运行时校验**仍在（其注释所依据的前提已失效），
 * 退役它是一件独立的前端小事，已登记为交接事项 **H14**
 * （见 `docs/agents/工作计划.md` §6.5）。**不要**顺手删掉运行时校验 ——
 * 类型在运行时会被擦除，边界校验本身是否保留是另一个判断，见 H14 的说明。
 */

/**
 * 后端导出的 openapi.json 快照的 SHA256（小写）。与后端仓库广播值一致。
 *
 * ⚠️ 本常量**由 `npm run check:contract` 机器校验**（`checkDeclaredConstants`）：
 *    它与快照不符会直接报漂移。改快照就必须改这里，反之亦然。
 */
export const OPENAPI_SNAPSHOT_SHA256 =
  'e62390afbf005b65a87f3b724f95d23cfb18f55d5668ead42d1c57453689456f'

/** 快照对应的后端版本，便于人肉核对这场快照是什么时候的。 */
export const OPENAPI_SNAPSHOT_VERSION = '0.0.1'

/** 快照导出时后端暴露的路径数量（用于冒烟核对，防止残缺快照）。同样由 check:contract 校验。 */
export const OPENAPI_SNAPSHOT_PATH_COUNT = 12

/**
 * 本前端工程实际使用的全部端点。
 *
 * 集中声明的目的：**让"契约缺口"可见**。
 * `status` 字段标明该端点的契约来源：`ready` = 已在 `openapi.json` 里；
 * `todo` = 后端尚未交付，前端不得据此编造接口形状。
 *
 * ⚠️ `methods` 必须与契约一致。**没有任何检查会守护本表**
 *   （它是一份人工维护的清单），所以改动接口时请顺手改这里 ——
 *   判定"某端点是否已交付"永远以 `openapi.json` 为准，不要以本表为准。
 */
export const ENDPOINTS = {
  /** 注册（需图形验证码；agreeProtocol 必填） */
  register: { methods: ['POST'], path: '/api/auth/register', status: 'ready' },
  /** 登录 */
  login: { methods: ['POST'], path: '/api/auth/login', status: 'ready' },
  /** 注销当前 token */
  logout: { methods: ['POST'], path: '/api/auth/logout', status: 'ready' },
  /** 图形验证码 */
  captcha: { methods: ['GET'], path: '/api/auth/captcha', status: 'ready' },
  /** 注册模式（open / invite / closed） */
  registerMode: { methods: ['GET'], path: '/api/auth/register-mode', status: 'ready' },
  /** 当前登录用户 */
  me: { methods: ['GET'], path: '/api/user/me', status: 'ready' },

  /*
   * ---- M3 第一交付段（2026-09-16，`6ddedc1`）已交付的一批 ----
   * 依据是重新导出后的 `openapi.json`（12 路径）。**方法集合逐个来自那份契约**，
   * 不是从《技术方案》§6 抄的 —— 抄文档就会写成"只读"，而契约里 /api/posts 有 post。
   */
  /** 版块列表（免登录） */
  boards: { methods: ['GET'], path: '/api/boards', status: 'ready' },
  /** 帖子集合：列表 + 发帖 */
  posts: { methods: ['GET', 'POST'], path: '/api/posts', status: 'ready' },
  /** 单帖：详情 + 改帖 + 删帖 */
  postDetail: { methods: ['GET', 'PUT', 'DELETE'], path: '/api/posts/{id}', status: 'ready' },
  /** 帖子搜索 */
  postSearch: { methods: ['GET'], path: '/api/posts/search', status: 'ready' },

  /*
   * ---- 以下端点由《技术方案》§6 规划，**尚未交付**（当前契约里没有这些路径）----
   * 只登记路径与所属里程碑，**不声明请求/响应类型**（那是编造契约）。
   * 判定口径：以仓库根 `openapi.json` 的 paths 为准，不以本文的注释为准。
   */
  notifications: { methods: ['GET'], path: '/api/notifications', status: 'todo', milestone: 'M5' },
  collections: { methods: ['GET'], path: '/api/user/collections', status: 'todo', milestone: 'M4' },
  userProfile: { methods: ['GET'], path: '/api/users/{id}', status: 'todo', milestone: 'M4' },
} as const

/** 已就绪端点的 key 集合（用于在页面里做开发期守卫） */
export type ReadyEndpointKey = {
  [K in keyof typeof ENDPOINTS]: (typeof ENDPOINTS)[K]['status'] extends 'ready' ? K : never
}[keyof typeof ENDPOINTS]
