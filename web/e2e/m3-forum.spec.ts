/**
 * M3 端到端测试：首页 / 搜索 / 版块 / 详情 / 发帖 / 编辑 / 删除（三栏改版后的界面）。
 *
 * ==========================================================================
 * 与 `golden-path.spec.ts` 的分工
 * ==========================================================================
 * `golden-path.spec.ts` 是 M2 的认证链路（注册/登录/登出），**不要改它** ——
 * 它已交付并通过复核。本文件只覆盖内容页，两者共用同一套前置手法
 * （API 预置账号 + UI 登录 + Redis 直读验证码），但**不共享代码**：
 * 为了共用去改动一个已交付的测试文件，风险大于重复几十行。
 *
 * ==========================================================================
 * 为什么每个"要发帖"的用例都现注册一个**全新账号**
 * ==========================================================================
 * 发帖有服务端限流（`2002 发帖过于频繁`，`message` 里带重试秒数 ——
 * 本机实测过一次「请 **8734 秒**后重试」，约 2.4 小时）。
 * 复用同一个账号会让第二次运行必然变红，而且**红的原因与代码无关** —— 那是最坏的一类失败。
 * 现注册的账号带有全新的配额，用例之间也就互相独立（不依赖执行顺序）。
 * 注册模式实测为 `open`（`GET /api/auth/register-mode`），所以这条路可行。
 *
 * ==========================================================================
 * 口径断言（本文件存在的真正理由）
 * ==========================================================================
 * 1. **`isResource` 决定网盘字段是否出现**（口径 9）—— 正反两面都断言。
 *    只断言"资源版块有"是不够的：若判断恒为真，那一条依然通过。
 * 2. **`PUT` 覆盖语义**（口径 11）—— 改标题后网盘字段**必须还在**，否则就是静默删数据。
 * 3. **一键复制的文案格式**（口径 8 / §5.5）—— 逐字校验剪贴板内容。
 * 4. **未登录点受保护动作 → 跳登录**（口径 4）。
 *
 * 前置：后端 127.0.0.1:8080；前端 5173（dev server，Playwright 直接复用）；
 *       Redis 可达（读验证码）。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

/** 后端基地址（与 `.env.development` 一致） */
const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'

/** 统一测试口令。依据《技术方案》§6.2：8–32 位且含字母与数字 */
const PASSWORD = 'Test1234abc'

/** 默认版块 id（seed 顺序，实测见 live `GET /api/boards`） */
const BOARD_RESOURCE = 2
const BOARD_GENERAL = 3

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

/* ==========================================================================
 * 辅助
 * ========================================================================== */

/**
 * 通过 API 注册一个**全新账号**（仅用于准备前置状态，不作为被测路径）。
 *
 * 图形验证码怎么过：直读 Redis 的 `hy:captcha:{uuid}` 取答案（见 `captcha-redis.ts`）。
 * 这是 M2 定下的手法 —— 不改后端、不用 OCR（OCR 会变成偶发变红的抖动测试）。
 */
async function registerViaApi(username: string, nickname: string): Promise<void> {
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

/** 注册并登录，返回用户名 */
async function freshLoggedInUser(page: Page, tag: string): Promise<string> {
  const username = `e2e_${tag}_${uniq()}`.slice(0, 20)
  await registerViaApi(username, `${tag}${username.slice(-4)}`)
  await page.goto('#/pages/auth/index?mode=login')
  await expect(page.getByTestId('login-username')).toBeVisible()
  await uniInput(page, 'login-username').fill(username)
  await uniInput(page, 'login-password').fill(PASSWORD)

  const loginResponse = page.waitForResponse(
    (res) => res.url().includes('/api/auth/login') && res.request().method() === 'POST'
  )
  await page.getByTestId('login-submit').click()
  const body = await (await loginResponse).json()
  expect(body.code, `登录应成功，实际 ${JSON.stringify(body)}`).toBe(0)
  return username
}

/**
 * 定位 uni-app `<input>` 的**真实 input 元素**。
 *
 * `data-testid` 加在 `<uni-input>` 外壳上，而 `.fill()` 必须作用于内部原生 `<input>`，
 * 否则报 "Element is not an <input>..." —— 该报错很容易被误读成"testid 没透传"。
 */
function uniInput(page: Page, testId: string) {
  return page.getByTestId(testId).locator('input')
}

/** 同上，针对 `<textarea>` */
function uniTextarea(page: Page, testId: string) {
  return page.getByTestId(testId).locator('textarea')
}

/** 点击 uni-app H5 `uni.showModal` 的确认按钮（平台组件，类名集中在这里改） */
async function confirmUniModal(page: Page): Promise<void> {
  const modal = page.locator('.uni-modal').last()
  await expect(modal, 'uni.showModal 的弹层应出现').toBeVisible({ timeout: 5000 })
  await modal.locator('.uni-modal__btn_primary').click()
}

/** 读当前登录态 token（H5 端底层即 localStorage，key 见 `utils/storage.ts`） */
async function readToken(page: Page): Promise<string> {
  return page.evaluate(() => window.localStorage.getItem('hy:token') ?? '')
}

/**
 * 通过 API 直接读帖子详情（**独立于 UI** 地核对落库结果）。
 *
 * ⚠️ `token` 不是可有可无的：详情是**可选鉴权**，而 `status=0`（待审）的帖子
 * **只有作者能看到**。改帖会让帖子回到待审，所以"改完再读一遍"必须带作者 token，
 * 否则会拿到 `404` —— 那是**正确**的后端行为，不是 bug（本机踩过）。
 */
async function fetchDetail(id: number, token?: string): Promise<{ code: number; data?: any }> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = token
  const res = await fetch(`${API_BASE}/api/posts/${id}`, { headers })
  return await res.json()
}

/* ==========================================================================
 * 用例 1：首页信息流来自真接口 + 未登录点「＋」跳登录（口径 4）
 * ========================================================================== */

test('首页：信息流来自 GET /api/posts，未登录点「＋」会跳登录页', async ({ page }) => {
  /*
   * 先挂响应监听再导航 —— 顺序很重要：列表在页面 `onShow` 里就请求了，
   * 先导航再监听会漏掉这次响应。
   */
  const listResponse = page.waitForResponse(
    (res) => res.url().includes('/api/posts?') && res.request().method() === 'GET'
  )
  await page.goto('#/pages/index/index')

  const body = await (await listResponse).json()
  expect(body.code, `列表接口应 code=0，实际 ${JSON.stringify(body)}`).toBe(0)
  expect(Array.isArray(body.data?.list), '响应的 data.list 应是数组').toBe(true)
  expect(body.data.list.length, '演示数据应有帖子（否则请先跑 seed-demo-data.sql）').toBeGreaterThan(0)

  await expect(page.getByTestId('home-feed')).toBeVisible()
  await expect(page.getByTestId('post-card').first()).toBeVisible()
  // 左栏「今日数据」的帖子总数是**真数据**（同一个响应的 total）
  await expect(page.getByTestId('stats-post-total')).toHaveText(String(body.data.total))

  // 未登录点发帖入口 → 跳登录页，且**不该**出现发帖表单
  await page.getByTestId('home-composer').click()
  await expect(page.getByTestId('login-username')).toBeVisible({ timeout: 10_000 })
  await expect(page.locator('[data-testid="compose-submit"]')).toHaveCount(0)
})

/* ==========================================================================
 * 用例 2：搜索 —— 空关键词不发请求
 * ========================================================================== */

test('搜索：空关键词不发请求；有关键词才请求并显示结果数', async ({ page }) => {
  await page.goto('#/pages/search/index')
  await expect(page.getByTestId('search-input')).toBeVisible()

  /*
   * 空关键词点搜索 → **不应**产生 `/api/posts/search` 请求。
   * 契约里 `keyword` 必填，缺了是 400；但"没输入就点搜索"是正常操作，
   * 前端该在发请求前拦掉，而不是让后端回一个 400 再弹"提交的内容有误"。
   */
  let searchCalls = 0
  page.on('request', (req) => {
    if (req.url().includes('/api/posts/search')) searchCalls++
  })
  await page.getByTestId('search-submit').click()
  await page.waitForTimeout(1000)
  expect(searchCalls, '空关键词不应发出搜索请求').toBe(0)

  // 有关键词 → 发请求，并显示"关键词「x」共 N 条结果"
  await uniInput(page, 'search-input').fill('AI')
  const res = page.waitForResponse((r) => r.url().includes('/api/posts/search'))
  await page.getByTestId('search-submit').click()
  const body = await (await res).json()
  expect(body.code, `搜索接口应 code=0，实际 ${JSON.stringify(body)}`).toBe(0)
  expect(searchCalls, '有关键词应发出搜索请求').toBeGreaterThan(0)

  await expect(page.getByTestId('search-summary')).toContainText('关键词「AI」')
  await expect(page.getByTestId('search-summary')).toContainText(`共 ${body.data.total} 条结果`)
})

/* ==========================================================================
 * 用例 3：版块页 + isResource 的**正反两面**（口径 9）
 * ========================================================================== */

test('版块页：资源版块有「资源版块」标记，普通版块没有', async ({ page }) => {
  await page.goto(`#/pages/board/index?id=${BOARD_RESOURCE}`)
  await expect(page.getByTestId('board-title')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('board-resource-tag')).toBeVisible()

  /*
   * 反向那半才是"判断写反时真正变红"的断言：
   * 只断言"资源版块有角标"的话，一个恒为真的判断也能通过。
   */
  /*
   * ⚠️ 必须 `reload()`：`page.goto` 只改 hash 时，uni-app H5 **复用同一个页面实例**，
   * `onLoad` 不会重跑 —— 页面仍显示上一个版块的数据（本机实测踩到）。
   * 应用内不存在"从版块 A 直接跳到版块 B"的路径（版块页没有切换器，
   * 只能从首页/详情页的版块标签 navigateTo 进来，那会创建**新实例**），
   * 所以这里 reload 才是"打开一个新页面"的等价写法。
   */
  await page.goto(`#/pages/board/index?id=${BOARD_GENERAL}`)
  await page.reload()
  await expect(page.getByTestId('board-title')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('board-resource-tag')).toHaveCount(0)
})

/* ==========================================================================
 * 用例 4：详情页 —— 正文 / 网盘卡片 / 一键复制格式（口径 8）/ 404 友好页
 * ========================================================================== */

test('详情：正文与网盘信息正确展示，一键复制格式逐字正确，404 给友好页', async ({
  page,
  context,
}) => {
  // 找一条**资源版块**的帖子（有网盘字段），从真接口取它的 id
  const list = await (
    await fetch(`${API_BASE}/api/posts?boardId=${BOARD_RESOURCE}&page=1&size=20`)
  ).json()
  expect(list.code).toBe(0)
  const target = list.data.list[0]
  expect(target, '资源版块应有帖子（否则请先跑 seed-demo-data.sql）').toBeTruthy()

  const detail = (await fetchDetail(target.id)).data
  await page.goto(`#/pages/post/detail?id=${target.id}`)

  await expect(page.getByTestId('detail-title')).toHaveText(detail.title)
  await expect(page.getByTestId('detail-author')).toHaveText(detail.author.nickname)
  if (detail.content) {
    await expect(page.getByTestId('detail-content')).toContainText(detail.content.slice(0, 20))
  }

  // 网盘卡片：显示条件 = boardIsResource && diskUrl 非空
  await expect(page.getByTestId('detail-disk')).toBeVisible()
  await expect(page.getByTestId('detail-disk-url')).toHaveText(detail.diskUrl)
  if (detail.diskCode) {
    await expect(page.getByTestId('detail-disk-code')).toHaveText(detail.diskCode)
  }

  /* ---- 一键复制：逐字校验剪贴板（§5.5 锁定格式） ---- */
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByTestId('detail-copy-disk').click()

  /*
   * ⚠️ 期望值是**只有链接**（需求方 2026-09-18 的口径变更）：
   *    原来是 §5.5 锁定的三行式（链接：… / 提取码：… / 来自 Hy论坛），
   *    现在只复制链接本身 —— 见 `utils/postView.ts` 的 `buildDiskCopyText`。
   *
   * ⚠️ 比较前仍要把 `\r\n` 归一成 `\n`：Windows 的**系统剪贴板**会做这个规范化
   *    （平台行为，不是文案错）。单行链接本来看不出差别，但保留这一步，
   *    将来口径再变回多行时不会又踩一次 —— 那个坑当初的失败信息里两段文本"长得一模一样"。
   */
  const expected = detail.diskUrl
  await expect
    .poll(
      async () =>
        (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n').trim(),
      { timeout: 10_000 }
    )
    .toBe(expected)

  /* ---- 404 给友好页而不是白屏 ---- */
  // 同样必须 reload：详情 → 详情 的 hash 变更会复用页面实例，onLoad 不重跑
  await page.goto('#/pages/post/detail?id=999999')
  await page.reload()
  await expect(page.getByTestId('detail-gone')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('detail-gone-home')).toBeVisible()
})

/* ==========================================================================
 * 用例 5：发帖页 —— isResource 决定网盘字段是否出现（口径 9，正反两面）
 * ========================================================================== */

test('发帖页：选资源版块出现网盘字段，切到普通版块即消失', async ({ page }) => {
  await freshLoggedInUser(page, 'm3a')
  await page.goto('#/pages/post/edit')
  await expect(page.getByTestId('compose-title')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('compose-board-chips')).toBeVisible()

  await page.getByTestId(`compose-board-${BOARD_RESOURCE}`).click()
  await expect(page.getByTestId('compose-disk-card')).toBeVisible()

  // 切到普通版块 → 必须消失（同时验证了"切换版块会实时联动"）
  await page.getByTestId(`compose-board-${BOARD_GENERAL}`).click()
  await expect(page.getByTestId('compose-disk-card')).toHaveCount(0)
})

/* ==========================================================================
 * 用例 6：发帖（含网盘）→ 详情 → 编辑（覆盖语义）→ 删除
 * ========================================================================== */

test('发帖 → 详情 → 编辑后网盘仍在且回到待审 → 删除后 404', async ({ page }) => {
  await freshLoggedInUser(page, 'm3b')
  const token = await readToken(page)
  expect(token, '登录后 localStorage 里应有 token').toBeTruthy()

  const title = `E2E 资源帖 ${uniq()}`
  const diskUrl = 'https://pan.baidu.com/s/1E2eProbeAbCd'
  const diskCode = 'ev01'

  /* ---- 发帖（资源版块，含网盘字段） ---- */
  await page.goto('#/pages/post/edit')
  await expect(page.getByTestId('compose-title')).toBeVisible({ timeout: 15_000 })
  await page.getByTestId(`compose-board-${BOARD_RESOURCE}`).click()
  await expect(page.getByTestId('compose-disk-card')).toBeVisible()

  await uniInput(page, 'compose-title').fill(title)
  await uniTextarea(page, 'compose-content').fill('E2E 写入的正文。')
  await uniInput(page, 'compose-disk-url').fill(diskUrl)
  await uniInput(page, 'compose-disk-code').fill(diskCode)
  await page.getByTestId('compose-disk-type-1').click()

  const createResponse = page.waitForResponse(
    (res) => res.url().endsWith('/api/posts') && res.request().method() === 'POST'
  )
  await page.getByTestId('compose-submit').click()
  const created = await (await createResponse).json()
  expect(created.code, `发帖应成功，实际 ${JSON.stringify(created)}`).toBe(0)
  const id = Number(created.data.id)
  expect(id, '发帖响应应带新帖 id').toBeGreaterThan(0)

  /* ---- 正跳到详情页，内容正确 ---- */
  await expect(page.getByTestId('detail-title')).toHaveText(title, { timeout: 15_000 })
  await expect(page.getByTestId('detail-content')).toContainText('E2E 写入的正文。')
  await expect(page.getByTestId('detail-disk-url')).toHaveText(diskUrl)
  await expect(page.getByTestId('detail-disk-code')).toHaveText(diskCode)

  /* ---- 独立于 UI 核对落库 ---- */
  const afterCreate = (await fetchDetail(id, token)).data
  expect(afterCreate.title).toBe(title)
  expect(afterCreate.diskUrl, '网盘链接应落库').toBe(diskUrl)
  expect(afterCreate.diskCode, '提取码应落库').toBe(diskCode)
  // 未命中敏感词 → 先发后审 → status=1（§8.6 第 5 条）
  expect(afterCreate.status, '未命中敏感词的帖子应是 status=1').toBe(1)

  /* ---- 编辑：只改标题，网盘字段必须还在（覆盖语义口径 11） ---- */
  const newTitle = `${title}（已改）`
  await page.getByTestId('detail-edit').click()
  await expect(page.getByTestId('compose-board-readonly')).toBeVisible({ timeout: 15_000 })
  // 编辑模式：版块不可改（契约的改帖请求里没有 boardId）
  await expect(page.getByTestId('compose-board-chips')).toHaveCount(0)
  // 网盘字段应被回填
  await expect(page.getByTestId('compose-disk-card')).toBeVisible()
  await expect(uniInput(page, 'compose-disk-url')).toHaveValue(diskUrl)

  await uniInput(page, 'compose-title').fill(newTitle)
  const updateResponse = page.waitForResponse(
    (res) => res.url().includes(`/api/posts/${id}`) && res.request().method() === 'PUT'
  )
  await page.getByTestId('compose-submit').click()
  const updated = await (await updateResponse).json()
  expect(updated.code, `改帖应成功，实际 ${JSON.stringify(updated)}`).toBe(0)

  /* ---- 回详情：标题已改、待审横幅出现 ---- */
  await expect(page.getByTestId('detail-title')).toHaveText(newTitle, { timeout: 15_000 })
  await expect(page.getByTestId('detail-pending')).toBeVisible()

  /*
   * ⚠️ 本用例的核心断言：**`PUT` 覆盖语义下网盘字段必须被原样带回**。
   * 若 `buildUpdatePayload()` 漏了 `diskUrl`，契约语义（没传 = 清空）会让它变成空值，
   * 界面表现是"网盘卡片整块消失"**且没有任何报错**。
   * 这里用 API 再核一遍（不依赖界面渲染条件），避免"卡片因为别的原因没渲染"造成误判。
   */
  const afterUpdate = (await fetchDetail(id, token)).data
  expect(afterUpdate.title, '标题应已更新').toBe(newTitle)
  expect(afterUpdate.diskUrl, '⚠️ 覆盖语义下网盘链接不能被清空').toBe(diskUrl)
  expect(afterUpdate.status, '改帖后 status 必须回到 0（重新进入审核，§8.6 第 6 条）').toBe(0)

  /* ---- 删除 ---- */
  await page.getByTestId('detail-delete').click()
  await confirmUniModal(page)
  await expect
    .poll(async () => (await fetchDetail(id, token)).code, { timeout: 15_000 })
    .toBe(404)
})

/* ==========================================================================
 * 用例 7：网盘链接「整段粘贴」（需求方 2026-09-18 的实测场景）
 * ========================================================================== */

test('发帖：整段粘贴分享文案也能发出去，最终只保存其中的链接；夸克不显示提取码', async ({
  page,
}) => {
  /*
   * 起因：用户从夸克 App 复制出来的是**一整段文案**，粘进来发帖得到
   * `HTTP 400 {"code":400,"message":"diskUrl 必须是 http/https 链接"}`（已实测）。
   * 需求方的要求是「不要校验，直接复制过去就行」—— 所以前端**接受整段内容**，
   * 提交前把其中那条链接抽出来，并在界面上明说"将保存为：…"（不静默改写）。
   */
  await freshLoggedInUser(page, 'm3c')
  const token = await readToken(page)

  await page.goto('#/pages/post/edit')
  await expect(page.getByTestId('compose-title')).toBeVisible({ timeout: 15_000 })
  await page.getByTestId(`compose-board-${BOARD_RESOURCE}`).click()
  await expect(page.getByTestId('compose-disk-card')).toBeVisible()

  /* ---- 夸克（diskType=3）：**不显示提取码**，并说明原因 ---- */
  await page.getByTestId('compose-disk-type-3').click()
  await expect(page.getByTestId('compose-disk-code'), '夸克没有提取码，不该出现输入框').toHaveCount(0)
  await expect(page.getByTestId('compose-disk-code-not-needed')).toBeVisible()

  /* ---- 百度（diskType=1）：提取码输入框出现（它有这个概念） ---- */
  await page.getByTestId('compose-disk-type-1').click()
  await expect(page.getByTestId('compose-disk-code')).toHaveCount(1)

  /* ---- 回到夸克，整段粘贴 ---- */
  await page.getByTestId('compose-disk-type-3').click()
  const title = `整段粘贴分享文案 ${uniq()}`
  const link = 'https://pan.quark.cn/s/1a2b3c4d5e6f'
  const shareText =
    `我用夸克网盘给你分享了「26网课大全」，点击链接或复制整段内容，打开「夸克APP」即可获取。` +
    ` /~1a2b3c4d~ 链接：${link}`

  await uniInput(page, 'compose-title').fill(title)
  await uniInput(page, 'compose-disk-url').fill(shareText)

  // 界面必须**明确告知**最终会存什么（这是"不做静默改写"的落点）
  await expect(page.getByTestId('compose-disk-url-preview')).toContainText(link)

  const createResponse = page.waitForResponse(
    (res) => res.url().endsWith('/api/posts') && res.request().method() === 'POST'
  )
  await page.getByTestId('compose-submit').click()
  const created = await (await createResponse).json()
  expect(created.code, `整段粘贴发帖应成功，实际 ${JSON.stringify(created)}`).toBe(0)

  /* ---- 存下来的是**链接本身**，不是整段文案 ---- */
  expect(created.data.diskUrl, '应只保存抽出来的链接').toBe(link)
  expect(created.data.diskType).toBe(3)
  expect(created.data.diskCode ?? null, '夸克不该有提取码').toBeNull()

  /* ---- 详情页应能把它渲染成可点的网盘卡片 ---- */
  await page.goto(`#/pages/post/detail?id=${created.data.id}`)
  await expect(page.getByTestId('detail-disk-url')).toHaveText(link)
  await expect(page.getByTestId('detail-disk-code')).toHaveCount(0)

  // 清理
  await fetch(`${API_BASE}/api/posts/${created.data.id}`, {
    method: 'DELETE',
    headers: { Authorization: token },
  })
})
