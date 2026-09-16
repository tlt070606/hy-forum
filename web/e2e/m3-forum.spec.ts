/**
 * M3 端到端测试：版块 / 列表 / 搜索 / 详情 / 发帖 / 编辑 / 删除。
 *
 * ==========================================================================
 * 这个文件与 `golden-path.spec.ts` 的分工
 * ==========================================================================
 * `golden-path.spec.ts` 是 M2 的认证链路（注册/登录/登出），**不要改它** ——
 * 它已交付并通过复核。本文件只覆盖 M3 新增的内容页，两者共用同一套前置手法
 * （API 预置账号 + UI 登录 + Redis 直读验证码），但**不共享代码**：
 * 为了共用而改动一个已交付的测试文件，风险大于重复几十行。
 *
 * ==========================================================================
 * 四个"口径"断言（这些是本文件存在的真正理由）
 * ==========================================================================
 * 1. **未登录点受保护动作 → 跳登录**（口径 4）：首页「＋」
 * 2. **`isResource` 决定网盘字段是否出现**（口径 9）：这是**反向验证的目标**，
 *    把判断写反时本用例必须变红（见交付报告 §反向验证）
 * 3. **`PUT` 覆盖语义**（口径 11）：改标题后网盘字段**必须还在** ——
 *    这是最容易静默删数据的一处
 * 4. **一键复制的文案格式**（口径 8 / 《技术方案》§5.5）：逐字校验剪贴板内容
 *
 * 前置：后端 127.0.0.1:8080 运行中；前端 5173 可访问；Redis 可达（读验证码用）。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

/** 后端基地址（与 `.env.development` 一致） */
const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'

/** 统一测试口令。依据《技术方案》§6.2：8–32 位且含字母与数字 */
const PASSWORD = 'Test1234abc'

/**
 * 路由表（uni-app H5 用 hash 路由）。
 * ⚠️ 新增页面必须在 `src/pages.json` 里注册过，否则 hash 打开会落到默认页 ——
 *    那种失败现象是"页面显示的是首页"，很容易被误读成"跳转写错了"。
 */
const ROUTES = {
  index: '#/pages/index/index',
  login: '#/pages/auth/index?mode=login',
  compose: '#/pages/post/edit',
  search: '#/pages/search/index',
  board: (id: number) => `#/pages/board/index?id=${id}`,
  detail: (id: number) => `#/pages/post/detail?id=${id}`,
} as const

/** 默认版块 id（来自 `docs/技术方案.md` §4.3 的 seed 顺序，实测见 live /api/boards） */
const BOARD_RESOURCE = 2
const BOARD_GENERAL = 3

/* ==========================================================================
 * 辅助
 * ========================================================================== */

function uniqueSuffix(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

function uniqueUsername(): string {
  return `e2e_m3_${uniqueSuffix()}`
}

/**
 * 通过 API 直接注册账号（**仅用于准备前置状态**，不作为被测路径）。
 *
 * 为什么走 API 而不是 UI：登录用例的**被测对象是内容页**，不是注册页。
 * 注册流程已由 `golden-path.spec.ts` 完整走 UI 覆盖，这里不重复，
 * 以免每个用例都背上"验证码 + 注册"这段与 M3 无关的时长与失败面。
 */
async function createAccountViaApi(username: string, nickname: string): Promise<void> {
  const captchaRes = await fetch(`${API_BASE}/api/auth/captcha`)
  expect(captchaRes.ok, '后端 /api/auth/captcha 应可访问（后端起了吗？）').toBeTruthy()
  const captchaBody = await captchaRes.json()
  const uuid: string = captchaBody?.data?.uuid
  expect(uuid, '验证码响应里应有 uuid').toBeTruthy()

  const answer = await readCaptchaAnswer(uuid)
  expect(answer, `Redis 里应有 hy:captcha:${uuid} 的答案`).toBeTruthy()

  const res = await fetch(`${API_BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username,
      password: PASSWORD,
      nickname,
      captchaUuid: uuid,
      captchaCode: answer,
      agreeProtocol: true,
    }),
  })
  const body = await res.json()
  expect(body.code, `注册应成功，实际返回 ${JSON.stringify(body)}`).toBe(0)
}

/**
 * 定位 uni-app `<input>` 的**真实 input 元素**。
 *
 * 与 `golden-path.spec.ts` 里同名辅助同源：`data-testid` 加在 `<uni-input>` 外壳上，
 * 而 `.fill()` 必须作用于内部原生 `<input>`，否则报
 * "Element is not an <input>, <textarea>..."（该报错极易被误读成 testid 没透传）。
 */
function uniInput(page: Page, testId: string) {
  return page.getByTestId(testId).locator('input')
}

/** 定位 uni-app `<textarea>` 的真实 textarea 元素（同上，外壳是 `<uni-textarea>`） */
function uniTextarea(page: Page, testId: string) {
  return page.getByTestId(testId).locator('textarea')
}

/** 通过 UI 登录（前置账号已由 API 建好） */
async function loginViaUi(page: Page, username: string): Promise<void> {
  await page.goto(ROUTES.login)
  await expect(page.getByTestId('login-username')).toBeVisible()
  await uniInput(page, 'login-username').fill(username)
  await uniInput(page, 'login-password').fill(PASSWORD)

  const loginResponse = page.waitForResponse(
    (res) => res.url().includes('/api/auth/login') && res.request().method() === 'POST'
  )
  await page.getByTestId('login-submit').click()
  const res = await loginResponse
  const body = await res.json()
  expect(body.code, `登录应成功，实际 ${JSON.stringify(body)}`).toBe(0)
}

/**
 * 点击 uni-app H5 `uni.showModal` 的确认按钮。
 *
 * 为什么单独抽出来：`showModal` 是**平台组件**（不是我们写的 DOM），
 * 类名随 uni-app 版本可能微调。集中在这里，将来只改一处。
 */
async function confirmUniModal(page: Page): Promise<void> {
  const modal = page.locator('.uni-modal').last()
  await expect(modal, 'uni.showModal 的弹层应出现').toBeVisible({ timeout: 5000 })
  await modal.locator('.uni-modal__btn_primary').click()
}

/**
 * 通过 API 直接读帖子详情（用于**独立于 UI** 地核对落库结果）。
 *
 * ⚠️ `token` 参数不是可有可无的：`GET /api/posts/{id}` 是**可选鉴权**的，
 *    而 `status=0`（待审核）的帖子**只有作者能看到**。
 *    编辑会让帖子回到待审（§8.6 第 6 条），因此"编辑后再读一遍"必须带上作者 token，
 *    否则会拿到 `404 帖子不存在` —— 那是**正确**的后端行为，不是 bug。
 *    （本机实测踩到：不带 token 读刚编辑过的帖子拿到 404，一度像是"PUT 把帖子删了"。）
 */
async function fetchDetailViaApi(
  id: number,
  token?: string
): Promise<Record<string, unknown>> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = token

  const res = await fetch(`${API_BASE}/api/posts/${id}`, { headers })
  const body = await res.json()
  expect(body.code, `详情接口应返回 code=0，实际 ${JSON.stringify(body)}`).toBe(0)
  return body.data as Record<string, unknown>
}

/** 读取当前登录态 token（H5 端底层即 localStorage，key 见 `utils/storage.ts`） */
async function readToken(page: Page): Promise<string> {
  return page.evaluate(() => window.localStorage.getItem('hy:token') ?? '')
}

/* ==========================================================================
 * 用例 1：未登录浏览首页（版块宫格 + 资源角标 + 空态）
 * ========================================================================== */

test('未登录：首页显示版块宫格，资源版块带「资源」角标', async ({ page }) => {
  await page.goto(ROUTES.index)

  await expect(page.getByTestId('board-grid')).toBeVisible()
  const items = page.getByTestId('board-item')
  // seed 里有 7 个版块（§4.3）；不写死 7 以免后台增删版块导致测试变红，
  // 但至少要有 1 个，否则下面按名字定位会失去意义
  await expect.poll(async () => items.count(), { timeout: 10_000 }).toBeGreaterThan(0)

  /*
   * 口径 9 的第一半：**`isResource` 为 true 的版块才显示「资源」角标**。
   * 按版块名定位（名字来自 §4.3 的 seed），断言其内部确实有角标。
   */
  const resourceBoard = items.filter({ hasText: '资源分享' })
  await expect(resourceBoard).toHaveCount(1)
  await expect(resourceBoard.getByTestId('board-resource-tag')).toBeVisible()

  /*
   * 反向一半：**非资源版块不能有角标**。
   * 只断言"资源版块有角标"是不够的 —— 如果角标被写成恒显示，上面那条依然通过。
   * 这一条才是"isResource 判断写反"时真正变红的断言。
   */
  const generalBoard = items.filter({ hasText: '综合讨论' })
  await expect(generalBoard).toHaveCount(1)
  await expect(generalBoard.getByTestId('board-resource-tag')).toHaveCount(0)
})

/* ==========================================================================
 * 用例 2：未登录访问受保护动作 → 跳登录（口径 4）
 * ========================================================================== */

test('未登录：点首页「＋」发帖入口会跳到登录页', async ({ page }) => {
  await page.goto(ROUTES.index)
  await expect(page.getByTestId('home-compose')).toBeVisible()

  await page.getByTestId('home-compose').click()

  /*
   * 断言登录表单可见 —— 这才是"跳登录"的可观察结果。
   * ⚠️ 不断言 URL 变成 login：uni-app H5 的 hash 变化时机与 `navigateTo` 的动画
   *    有关，断言 DOM 更稳定（`golden-path.spec.ts` 里也有同样的取舍说明）。
   */
  await expect(page.getByTestId('login-username')).toBeVisible({ timeout: 10_000 })
  // 反向：发帖表单**不该**出现（未登录却进了发帖页 = 受保护动作没被拦）
  await expect(page.locator('[data-testid="compose-submit"]')).toHaveCount(0)
})

/* ==========================================================================
 * 用例 3：404 给友好页而不是白屏（口径：任务书 §6.2 第 4 页）
 * ========================================================================== */

test('详情：不存在的帖子显示友好页，且提供回首页出口', async ({ page }) => {
  await page.goto(ROUTES.detail(999999))

  await expect(page.getByTestId('post-gone')).toBeVisible({ timeout: 10_000 })
  /*
   * 友好页必须给**出口**，否则用户只能按浏览器返回。
   *
   * ⚠️ 用 testid 而不是 `getByRole('button', ...)`：`wd-button`（wot-design-uni）
   *    在 H5 端渲染成普通 `div`，**没有 button 角色**，按角色定位会找不到任何元素
   *    —— 那会是一个"看起来像页面没渲染"的误导性失败（本机实测踩到）。
   */
  await expect(page.getByTestId('post-gone-home')).toBeVisible()
})

/* ==========================================================================
 * 用例 4 + 5：登录 → 发帖（资源版块）→ 详情 → 列表 → 搜索
 * ========================================================================== */

test('发帖：资源版块显示网盘字段 → 发布 → 详情页正确展示 → 一键复制格式正确', async ({
  page,
  context,
}) => {
  const username = uniqueUsername()
  await createAccountViaApi(username, `M3用户${username.slice(-4)}`)
  await loginViaUi(page, username)

  const title = `M3资源帖 ${uniqueSuffix()}`
  const diskUrl = 'https://pan.baidu.com/s/1AbCdEfGhIjKl'
  const diskCode = 'abcd'

  await page.goto(ROUTES.compose)
  await expect(page.getByTestId('compose-title')).toBeVisible({ timeout: 10_000 })

  /* ---- 口径 9 的**第二半**：选了资源版块 → 网盘字段必须出现 ---- */
  await page.getByTestId(`compose-board-${BOARD_RESOURCE}`).click()
  await expect(page.getByTestId('compose-disk-card')).toBeVisible()

  await uniInput(page, 'compose-title').fill(title)
  await uniTextarea(page, 'compose-content').fill('这是 M3 端到端测试写下的正文。')
  await uniInput(page, 'compose-disk-url').fill(diskUrl)
  await uniInput(page, 'compose-disk-code').fill(diskCode)
  // 网盘类型选「百度网盘」（契约 diskType=1）
  await page.getByTestId('compose-disk-type-1').click()

  const createResponse = page.waitForResponse(
    (res) => res.url().endsWith('/api/posts') && res.request().method() === 'POST'
  )
  await page.getByTestId('compose-submit').click()

  const createRes = await createResponse
  const createBody = await createRes.json()
  expect(createBody.code, `发帖应成功，实际 ${JSON.stringify(createBody)}`).toBe(0)
  const newId = Number(createBody.data?.id)
  expect(newId, '发帖响应应带新帖 id').toBeGreaterThan(0)

  /* ---- 发帖后用正跳到了详情页 ---- */
  await expect(page.getByTestId('post-title')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('post-title')).toHaveText(title)

  /* ---- 网盘卡片与提取码 ---- */
  await expect(page.getByTestId('post-disk')).toBeVisible()
  await expect(page.getByTestId('post-disk-url')).toHaveText(diskUrl)
  await expect(page.getByTestId('post-disk-code')).toHaveText(diskCode)

  /* ---- 一键复制的文案格式（口径 8 / §5.5 逐字校验） ---- */
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByTestId('post-copy-disk').click()

  /*
   * ⚠️ 比较前必须把 `\r\n` 归一成 `\n`。
   *
   * 根因（本机实测）：Windows 的**系统剪贴板**会把写入的 `\n` 规范化成 `\r\n`
   * （`Write-Clipboard`/`navigator.clipboard` 都会），于是读回来比期望多出两个 `\r`。
   * 这是**平台行为，不是文案错**：粘贴到记事本/浏览器里看到的换行完全正确。
   * 若不归一，这条断言会永远失败，而失败信息里两段文本长得一模一样
   * （Playwright 的 diff 只标出末尾的不可见差异）—— 极难定位。
   *
   * 归一化只针对行尾符：`链接：`/`提取码：`/`来自 Hy论坛` 三行的**内容与顺序**
   * 仍是逐字校验的，没有放松。
   */
  await expect
    .poll(
      async () => (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n'),
      { timeout: 10_000 }
    )
    .toBe(`链接：${diskUrl}\n提取码：${diskCode}\n来自 Hy论坛`)

  /* ---- 独立于 UI 核对落库（用 API 而不是只看界面） ---- */
  const detail = await fetchDetailViaApi(newId)
  expect(detail.title, '详情接口返回的标题应与提交一致').toBe(title)
  expect(detail.diskUrl, '网盘链接应落库').toBe(diskUrl)
  expect(detail.diskCode, '提取码应落库').toBe(diskCode)
  // 未命中敏感词 → 先发后审 → status=1 直接可见（§8.6 第 5 条）
  expect(detail.status, '未命中敏感词的帖子应为 status=1（先发后审）').toBe(1)
  /*
   * ⚠️ 上传被契约缺口阻断，因此这里**必须**断言没有图片 ——
   *    否则将来有人接上上传后，这条"We have no images"的证据会悄悄失效，
   *    而截图看不出差别（本来就是 0 张）。
   */
  expect(Array.isArray(detail.images) ? detail.images.length : 0, '当前版本不应有图片').toBe(0)

  /* ---- 版块页列表里能找到它（列表 → 详情这条主路径） ---- */
  await page.goto(ROUTES.board(BOARD_RESOURCE))
  const rows = page.getByTestId('post-item')
  const mine = rows.filter({ hasText: title })
  await expect(mine).toHaveCount(1, { timeout: 15_000 })

  /* ---- 搜索能命中 ---- */
  await page.goto(ROUTES.search)
  await uniInput(page, 'search-input').fill(title)
  await page.getByTestId('search-submit').click()
  await expect(page.getByTestId('search-post-list').getByTestId('post-item')).toHaveCount(1, {
    timeout: 15_000,
  })
})

/* ==========================================================================
 * 用例 6：非资源版块**不显示**网盘字段（口径 9 的反向）
 * ========================================================================== */

test('发帖：非资源版块不显示网盘字段', async ({ page }) => {
  const username = uniqueUsername()
  await createAccountViaApi(username, `M3用户${username.slice(-4)}`)
  await loginViaUi(page, username)

  await page.goto(ROUTES.compose)
  await expect(page.getByTestId('compose-title')).toBeVisible({ timeout: 10_000 })

  // 先选资源版块 → 出现
  await page.getByTestId(`compose-board-${BOARD_RESOURCE}`).click()
  await expect(page.getByTestId('compose-disk-card')).toBeVisible()

  // 再切到普通版块 → 必须消失（这条同时验证了"切换版块会实时联动"）
  await page.getByTestId(`compose-board-${BOARD_GENERAL}`).click()
  await expect(page.getByTestId('compose-disk-card')).toHaveCount(0)
})

/* ==========================================================================
 * 用例 7：编辑（覆盖语义 —— 网盘字段不能被静默清空）
 * ========================================================================== */

test('编辑：改标题后网盘信息仍在，且帖子回到待审', async ({ page }) => {
  const username = uniqueUsername()
  await createAccountViaApi(username, `M3用户${username.slice(-4)}`)
  await loginViaUi(page, username)

  /* ---- 先用 API 造一个资源帖（本用例的被测对象是**编辑**，不是发帖） ---- */
  const token = await readToken(page)
  expect(token, '登录后 localStorage 里应有 token').toBeTruthy()

  const originalTitle = `M3待编辑 ${uniqueSuffix()}`
  const diskUrl = 'https://pan.quark.cn/s/AbCdEfGh'
  const created = await fetch(`${API_BASE}/api/posts`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      // 直接复用 UI 登录后写进 storage 的 token，避免再走一遍登录
      Authorization: token,
    },
    body: JSON.stringify({
      boardId: BOARD_RESOURCE,
      title: originalTitle,
      content: '编辑用例的原始正文',
      diskType: 3,
      diskUrl,
    }),
  })
  const createdBody = await created.json()
  expect(createdBody.code, `预置帖子应成功，实际 ${JSON.stringify(createdBody)}`).toBe(0)
  const postId = Number(createdBody.data.id)

  /* ---- 进详情 → 编辑 ---- */
  await page.goto(ROUTES.detail(postId))
  await expect(page.getByTestId('post-title')).toHaveText(originalTitle)

  // 作者才看得到操作区
  await expect(page.getByTestId('post-actions')).toBeVisible()
  await page.getByTestId('post-edit').click()

  /* ---- 编辑页：版块只读（契约 PUT 没有 boardId） ---- */
  await expect(page.getByTestId('compose-board-readonly')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('compose-board-chips')).toHaveCount(0)
  // 网盘字段应被回填（编辑模式用 `boardIsResource` 判断）
  await expect(page.getByTestId('compose-disk-card')).toBeVisible()
  await expect(uniInput(page, 'compose-disk-url')).toHaveValue(diskUrl)

  const newTitle = `M3已编辑 ${uniqueSuffix()}`
  await uniInput(page, 'compose-title').fill(newTitle)

  const updateResponse = page.waitForResponse(
    (res) => res.url().includes(`/api/posts/${postId}`) && res.request().method() === 'PUT'
  )
  await page.getByTestId('compose-submit').click()
  const updateRes = await updateResponse
  const updateBody = await updateRes.json()
  expect(updateBody.code, `改帖应成功，实际 ${JSON.stringify(updateBody)}`).toBe(0)

  /* ---- 回到详情：标题已变、待审横幅出现、网盘信息仍在 ---- */
  await expect(page.getByTestId('post-title')).toHaveText(newTitle, { timeout: 15_000 })
  await expect(page.getByTestId('post-pending')).toBeVisible()

  /*
   * ======================================================================
   * 这条断言是本用例的核心：**`PUT` 覆盖语义下网盘字段必须被原样带回**
   * ======================================================================
   * 若 `buildUpdatePayload()` 漏了 `diskUrl`，契约语义（没传 = 清空）会让它
   * 变成空字符串 —— 界面表现是"网盘卡片整块消失"，而没有任何报错。
   * 单独用 API 再核一遍（不依赖界面的渲染条件），避免"卡片因为别的原因没渲染"
   * 造成误判。
   */
  await expect(page.getByTestId('post-disk-code')).toHaveCount(0) // 原帖没填提取码
  /*
   * ⚠️ 必须带**作者 token**：帖子此刻已回到 `status=0`（待审），
   *    而不带 token 的游客读不到待审帖（后端返回 404 —— 这是正确行为）。
   */
  const after = await fetchDetailViaApi(postId, token)
  expect(after.title, '标题应已更新').toBe(newTitle)
  expect(after.diskUrl, '⚠️ 覆盖语义下网盘链接不能被清空').toBe(diskUrl)
  expect(after.status, '编辑后 status 必须回到 0（重新进入审核，§8.6 第 6 条）').toBe(0)
})

/* ==========================================================================
 * 用例 8：删除
 * ========================================================================== */

test('删除：作者删除后详情变友好页，版块列表里也不再有它', async ({ page }) => {
  const username = uniqueUsername()
  await createAccountViaApi(username, `M3用户${username.slice(-4)}`)
  await loginViaUi(page, username)

  const title = `M3待删除 ${uniqueSuffix()}`
  const created = await fetch(`${API_BASE}/api/posts`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: await readToken(page),
    },
    body: JSON.stringify({ boardId: BOARD_GENERAL, title, content: '待删除的正文' }),
  })
  const createdBody = await created.json()
  expect(createdBody.code, `预置帖子应成功，实际 ${JSON.stringify(createdBody)}`).toBe(0)
  const postId = Number(createdBody.data.id)

  await page.goto(ROUTES.detail(postId))
  await expect(page.getByTestId('post-title')).toHaveText(title)

  await page.getByTestId('post-delete').click()
  await confirmUniModal(page)

  /* ---- 详情接口此后应返回 404（逻辑删除） ---- */
  await expect
    .poll(
      async () => {
        const res = await fetch(`${API_BASE}/api/posts/${postId}`)
        const body = await res.json()
        return body.code
      },
      { timeout: 15_000 }
    )
    .toBe(404)

  /* ---- 版块列表里不应再有它 ---- */
  await page.goto(ROUTES.board(BOARD_GENERAL))
  await expect(page.getByTestId('board-post-list').filter({ hasText: title })).toHaveCount(0, {
    timeout: 15_000,
  })
})
