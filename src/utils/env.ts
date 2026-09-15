/**
 * 构建期环境变量的读取与校验。
 *
 * ==========================================================================
 * 为什么要有这个文件
 * ==========================================================================
 * AGENTS.md 铁律 5：**禁止硬编码域名**。所有地址必须来自环境变量。
 * 但"从环境变量读"有个常见陷阱：变量缺失时读到 `undefined`，
 * 请求会发到 `undefined/api/auth/login` 这种地址，报错信息完全指不到真正原因。
 * 所以这里做**启动期校验 + 快速失败**，让问题在第一秒暴露。
 *
 * `.env.production` 里的地址**故意留空**（域名未注册、备案未完成，见 PLAN.md A2/A4），
 * 因此生产构建若未回填会立刻抛出带指引的错误，而不是"构建成功但线上全废"。
 */

/** 是否为生产构建 */
export const IS_PROD = import.meta.env.PROD

/** 构建目标标识，仅用于调试展示 */
export const APP_ENV = import.meta.env.VITE_APP_ENV || 'unknown'

let cachedBaseUrl: string | null = null

/**
 * 取得并校验后端基地址。
 *
 * ==========================================================================
 * ⚠️ H5 端返回**同源相对地址**，这是本函数最关键的一条设计
 * ==========================================================================
 * 原因（本机实测）：后端没有任何 CORS 响应头，预检 `OPTIONS` 返回 403。
 * 而开发期 H5 页面在 `127.0.0.1:5173`、后端在 `127.0.0.1:8080` —— 跨源，
 * 浏览器会拦掉全部 `/api` 请求。
 *
 * 解法是让 H5 端请求**自己的源**（`/api/...`），由 Vite 代理（开发/E2E）
 * 或 Nginx（生产）在**服务端**转发给后端。服务端之间不受同源策略约束，
 * 因此问题从根上消失，且**不需要后端做任何改动**（禁止改后端）。
 *
 * 生产环境由 Nginx 承担同一职责，所以这不是"仅开发可用"的权宜之计，而是与生产同构。
 *
 * 小程序端与 App 端**必须使用绝对地址**：`uni.request` 在这两端不支持相对路径，
 * 且小程序端还要求域名已备案（ADR-0011 C2）。因此用条件编译区分。
 *
 * 校验规则（刻意严格，宁可启动失败也不要静默错误）：
 * 1. 必须非空
 * 2. 必须是合法 URL（能构造 `new URL`）
 * 3. **生产构建 + 非 H5：必须是 https**
 *    —— 小程序 request 只允许已备案 HTTPS 域名（ADR-0011 C2）；
 *    安卓端 targetSdk 28+ 默认也拒明文 http。
 *    本地的 `http://127.0.0.1` 是**开发期例外**（H5 走代理，不触发该校验）。
 * 4. 结尾不带 `/`，统一由这里剥掉，避免拼出 `//api/auth/login`
 */
export function getApiBaseUrl(): string {
  if (cachedBaseUrl !== null) return cachedBaseUrl

  // #ifdef H5
  // H5：同源相对地址，走 Vite 代理 / Nginx 反代
  cachedBaseUrl = ''
  return cachedBaseUrl
  // #endif

  // eslint-disable-next-line no-unreachable
  const raw = (import.meta.env.VITE_API_BASE_URL ?? '').trim()

  /*
   * 非 H5 端（小程序 / App）**必须**是绝对地址：
   * 这两端的 `uni.request` 不支持相对路径，且小程序端要求域名已备案。
   *
   * 开发期的 `.env.development` 写的是 `/api`（供 H5 走代理），
   * 这在小程序端**不能直接用**，因此这里给出明确的、可操作的报错 ——
   * 而不是让请求带着相对路径发出去，然后在运行时抛出难以理解的原生错误。
   */
  if (!raw || raw.startsWith('/')) {
    throw new Error(
      `[env] 非 H5 端（当前构建目标）必须配置**绝对地址**的 VITE_API_BASE_URL，当前值为 "${raw}"。\n` +
        '原因：小程序的 uni.request 不支持相对路径，且要求域名已备案（ADR-0011 C2）。\n' +
        '开发调试：在 .env.development 里改成绝对地址，例如 http://127.0.0.1:8080，\n' +
        '          并在微信开发者工具中开启「不校验合法域名」。\n' +
        '生产：在 .env.production 里填已备案的 https 域名。'
    )
  }

  let parsed: URL
  try {
    parsed = new URL(raw)
  } catch {
    throw new Error(
      `[env] VITE_API_BASE_URL 不是合法 URL："${raw}"。` +
        '正确示例：http://127.0.0.1:8080 或 https://api.example.com'
    )
  }

  // 生产构建必须是 https（H5 已在上面提前返回，故此处只覆盖小程序 / App）
  if (IS_PROD && parsed.protocol !== 'https:') {
    throw new Error(
      `[env] 生产构建要求 https，当前为 "${parsed.protocol}"。\n` +
        '原因：小程序端只允许已备案 HTTPS 域名；安卓端默认拒绝明文 http。\n' +
        '（ADR-0011 C2 —— 这不是可以"先上线回头再改"的事项）'
    )
  }

  // 去掉结尾斜杠，统一拼接口径
  cachedBaseUrl = raw.replace(/\/+$/, '')
  return cachedBaseUrl
}

/**
 * 是否应在控制台输出请求日志。
 * 只在开发期开，避免生产环境把用户数据打到控制台。
 */
export const ENABLE_REQUEST_LOG = !IS_PROD
