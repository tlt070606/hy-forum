/**
 * 接口契约的**唯一事实来源**（前端侧）。
 *
 * ==========================================================================
 * 为什么这个文件存在，以及它的权威性边界
 * ==========================================================================
 * 权威契约是后端导出的 `openapi.json`（由 L1 冻结，**任何人不手工编辑**）。
 * 但本前端工程是**独立仓库**，构建时无法依赖后端仓库的相对路径存在，
 * 所以这里保存一份**快照**，并记录其 SHA256 用于漂移检测 —— 这比"构建时去隔壁仓库摸文件"
 * 更可靠：快照缺失会立刻发现，而不是产物悄悄变成空壳。
 *
 * ⚠️ 快照不新鲜时**不要手工改它、也不要手工改下面的类型**：
 *     按 AGENTS.md，发现契约不足应**提 CR**（见 docs/agents/工作计划.md §3），
 *     由后端实现接口后重新导出 openapi.json，再更新快照与生成类型。
 *
 * 更新快照的步骤：
 *   1. 用后端仓库根目录的 `openapi.json` 覆盖 `src/api/generated/openapi.snapshot.json`
 *   2. 运行 `npm run gen:api`（重新生成 `schema.d.ts`）
 *   3. 运行 `npm run check:contract`（校验 SHA256 与下方记录一致，防止忘了第 1 步）
 *
 * ==========================================================================
 * 本文件的类型为什么是**手写**的，而不是全部来自生成物
 * ==========================================================================
 * 生成物（`src/api/generated/schema.d.ts`）里有两个**形状缺口**，必须在这里手工窄化：
 *
 *   `GET /api/auth/captcha`        → 契约声明为 ApiResponseMapStringObject
 *   `GET /api/auth/register-mode`  → 契约声明为 ApiResponseMapStringObject
 *
 * 即 `data` 在契约里是 `Record<string, any>`，**具体字段名没有静态声明**。
 * 字段名与取值的依据是《技术方案》§6.2 与 §8.8 的文字约定：
 *   - captcha       → `{ uuid: string, base64Image: string }`
 *   - register-mode → `'open' | 'invite' | 'closed'`
 *
 * 因为没有静态约束，这两个接口在运行时**必须做防御性校验**（见 `src/api/auth.ts`），
 * 不能直接信任返回形状 —— 否则后端改字段名时前端会静默拿到 undefined。
 */

/** 后端导出的 openapi.json 快照的 SHA256（小写）。与后端仓库广播值一致。 */
export const OPENAPI_SNAPSHOT_SHA256 =
  'f641bbf59ef44b3d22d1a5d55512ddc1308f4f48e7319bc5d36d660abbe15370'

/** 快照对应的后端版本，便于人肉核对这场快照是什么时候的。 */
export const OPENAPI_SNAPSHOT_VERSION = '0.0.1'

/** 快照导出时后端暴露的路径数量（用于冒烟核对，防止残缺快照） */
export const OPENAPI_SNAPSHOT_PATH_COUNT = 8

/**
 * 本前端工程实际使用的全部端点。
 *
 * 集中声明的目的：**让"契约缺口"可见**。
 * `status` 字段标明该端点的契约来源，`todo` 表示后端尚未交付
 * （已实测返回 404），前端不得据此编造接口形状。
 */
export const ENDPOINTS = {
  /** 注册（需图形验证码；agreeProtocol 必填） */
  register: { method: 'POST', path: '/api/auth/register', status: 'ready' },
  /** 登录 */
  login: { method: 'POST', path: '/api/auth/login', status: 'ready' },
  /** 注销当前 token */
  logout: { method: 'POST', path: '/api/auth/logout', status: 'ready' },
  /** 图形验证码 */
  captcha: { method: 'GET', path: '/api/auth/captcha', status: 'ready' },
  /** 注册模式（open / invite / closed） */
  registerMode: { method: 'GET', path: '/api/auth/register-mode', status: 'ready' },
  /** 当前登录用户 */
  me: { method: 'GET', path: '/api/user/me', status: 'ready' },

  /*
   * ---- 以下端点由《技术方案》§6 规划，但 M1 未交付（实测 HTTP 404）----
   * 只登记路径与所属里程碑，**不声明请求/响应类型**（那是编造契约）。
   * 对应页面（首页/版块/详情/发帖/通知）在 M2 只做骨架，不发真实请求。
   */
  boards: { method: 'GET', path: '/api/boards', status: 'todo', milestone: 'M3' },
  posts: { method: 'GET', path: '/api/posts', status: 'todo', milestone: 'M3' },
  postDetail: { method: 'GET', path: '/api/posts/{id}', status: 'todo', milestone: 'M3' },
  notifications: { method: 'GET', path: '/api/notifications', status: 'todo', milestone: 'M5' },
  collections: { method: 'GET', path: '/api/user/collections', status: 'todo', milestone: 'M4' },
  userProfile: { method: 'GET', path: '/api/users/{id}', status: 'todo', milestone: 'M4' },
} as const

/** 已就绪端点的 key 集合（用于在页面里做开发期守卫） */
export type ReadyEndpointKey = {
  [K in keyof typeof ENDPOINTS]: (typeof ENDPOINTS)[K]['status'] extends 'ready' ? K : never
}[keyof typeof ENDPOINTS]
