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
  /*
   * ==========================================================================
   * 非 H5 端（小程序 / App）地址的选取规则 —— 这里的设计改过一次，原因值得记下来
   * ==========================================================================
   * ❌ 曾经的错法：用 `import.meta.env.PROD` 判断"生产构建就必读 VITE_API_BASE_URL"。
   *    结果：**`uni build` 对任何平台都按 production 模式运行**（`uni build -p mp-weixin`
   *    同样如此），于是小程序构建去读了 `.env.production` 里**故意留空**的
   *    `VITE_API_BASE_URL`，被编译成 `"".trim()` → 应用一启动就抛错。
   *    更糟的是构建完全成功，属于典型的"绿构建、白屏应用"。
   *
   * ✅ 现在的做法：**优先用已配置的绝对地址，回退到本机地址**。
   *    - `VITE_API_BASE_URL` 为绝对地址（含 `://`）→ 用它。
   *      这是**上线时的唯一开关**：在 `.env.production` 里填上已备案的 https 域名即可，
   *      不需要改任何代码。
   *    - 否则（留空，或开发期 H5 用的相对路径 `/api`）→ 回退 `VITE_DEV_SERVER_ORIGIN`
   *      （`.env` 里定义，所有模式都加载，默认 `http://127.0.0.1:8080`）。
   *      这样本地开发小程序/App 时无需额外配置就能连上本机后端。
   *
   * 因此：**.env.production 里 VITE_API_BASE_URL 留空 = 用本机后端；填了 = 用线上域名。**
   */
  const configuredApi = (import.meta.env.VITE_API_BASE_URL ?? '').trim()
  const devOrigin = (import.meta.env.VITE_DEV_SERVER_ORIGIN ?? '').trim()

  const raw = configuredApi.includes('://') ? configuredApi : devOrigin

  if (!raw) {
    throw new Error(
      '[env] 非 H5 端（当前构建目标）缺少后端地址配置。\n' +
        '请在 `.env.production` 里设置 `VITE_API_BASE_URL`（已备案的 https 域名），\n' +
        '或在 `.env` 里确认 `VITE_DEV_SERVER_ORIGIN`（本机后端地址）。\n' +
        '原因：小程序的 uni.request **不支持相对路径**，且要求域名已备案（ADR-0011 C2）。\n' +
        '（铁律 5：地址必须走环境变量，不硬编码）'
    )
  }

  /*
   * 必须绝对地址。若误填成 `/api` 这类相对路径，小程序端会在运行时
   * 把它解析到"项目域名"上，表现为请求全部失败、且错误信息难以理解 ——
   * 本机实测事故：注册模式请求失败 → 界面显示"当前暂未开放注册"、注册 Tab 消失。
   * 因此这里**在启动时就明确拒绝**，而不是让它带着错误配置跑起来。
   */
  if (raw.startsWith('/')) {
    throw new Error(
      `[env] 非 H5 端必须使用**绝对地址**，当前为相对路径 "${raw}"。\n` +
        '请改为 http://127.0.0.1:8080（开发）或 https://你的域名（生产）。'
    )
  }

  /*
   * ==========================================================================
   * ⚠️ 这里**不能用 `new URL()`** —— 这是本机实测踩过的跨端坑
   * ==========================================================================
   * 我最初写的是 `parsed = new URL(raw)`，在 H5/Node 里完全正常，
   * 但**微信小程序的 JS 运行时不提供标准的 `URL` 构造函数**，
   * 它抛出的 `TypeError` 被下面的 catch 捕获后，错误信息被包装成
   * 「后端地址不是合法 URL："http://127.0.0.1:8080"」——
   * **一个完全合法的地址被判为非法**，还伪装成配置问题，极具误导性。
   * （这个 bug 只有在小程序端真跑起来才暴露；H5 与构建阶段都看不出来。）
   *
   * 因此改用**纯正则**校验：不依赖任何 Web API，三端行为一致。
   * 校验目标只有两个：① 有 http/https 协议头；② 有非空主机名。
   * 这足以拦住真实配置错误（漏协议、相对路径、写错变量），又不引入平台依赖。
   */
  const urlMatch = /^(https?):\/\/([^/?#\s]+)/i.exec(raw)
  if (!urlMatch) {
    throw new Error(
      `[env] 后端地址不是合法 URL："${raw}"。` +
        '正确示例：http://127.0.0.1:8080 或 https://api.example.com'
    )
  }

  const protocol = `${urlMatch[1].toLowerCase()}:`

  // 生产构建必须是 https（H5 已在上面提前返回，故此处只覆盖小程序 / App）
  if (IS_PROD && protocol !== 'https:') {
    throw new Error(
      `[env] 生产构建要求 https，当前为 "${protocol}"。\n` +
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
