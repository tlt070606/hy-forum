/**
 * 黄金路径 E2E：注册 → 登录 → 进入需要登录的页面。
 *
 * 这是 M2 验收标准里**唯一能自动化**的部分（任务书 §5.4），因此必须针对
 * **真实后端**（`http://127.0.0.1:8080`）运行，不允许 mock。
 *
 * ==========================================================================
 * 图形验证码怎么过（这是本套 E2E 的核心难点）
 * ==========================================================================
 * 契约要求注册必须带 `captchaUuid` + `captchaCode`，而图形验证码的存在意义
 * 就是阻止自动化。三条路的取舍：
 *
 * ① 改后端加"测试模式" → **明确排除**。任务书 §3 禁止改 `server/**`，
 *    后端已交付并复核，为前端测试改后端是本末倒置。
 * ② OCR 识别图片 → 不稳定。验证码含干扰线，OCR 会变成偶发变红的"抖动测试"，
 *    比没有测试更糟。
 * ③ **直读 Redis 答案**（本方案）→ 答案存在 `hy:captcha:{uuid}`（技术方案 §6.2），
 *    直接读取，后端零改动且结果确定。实现见 `captcha-redis.ts`，
 *    前提已由 `probe-captcha.ts` 独立验证过。
 *
 * ⚠️ 关于「用 API 建号再用 UI 登录」这一手法：
 *    登录用例的**被测对象是登录页本身**，而不是注册页。用 API 预置账号
 *    能让每个用例**相互独立**（不依赖注册用例先跑、不依赖执行顺序），
 *    这是 Playwright 官方推荐的"用最可靠的通道准备前置状态"。
 *    而注册流程本身由专门用例**完整走 UI** 验证，覆盖没有缺失。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

/** 后端基地址。与 .env.development 保持一致 */
const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'

/** 页面路径。uni-app H5 用 hash 路由（小程序端没有 Vue Router，见 pages.json） */
const ROUTES = {
  index: '#/pages/index/index',
  login: '#/pages/login/index',
  register: '#/pages/register/index',
  me: '#/pages/me/index',
} as const

/** 统一的测试口令。规则依据《技术方案》§6.2：8–32 位且含字母与数字 */
const PASSWORD = 'Test1234abc'

/** 生成唯一用户名。规则：4–20 位字母数字下划线 */
function uniqueUsername(): string {
  // 时间戳后 8 位 + 随机 3 位，既唯一又不会超出 20 字符
  const ts = Date.now().toString(36).slice(-8)
  const rand = Math.random().toString(36).slice(2, 5)
  return `e2e_${ts}${rand}`
}

/**
 * 通过 API 直接注册一个账号（**仅用于准备前置状态**，不作为被测路径）。
 * @returns 用户名（密码固定为 PASSWORD）
 */
async function createAccountViaApi(username: string, nickname: string): Promise<void> {
  // 1) 取验证码
  const captchaRes = await fetch(`${API_BASE}/api/auth/captcha`)
  expect(captchaRes.ok, '后端 /api/auth/captcha 应可访问（后端起了吗？）').toBeTruthy()
  const captchaBody = await captchaRes.json()
  const uuid: string = captchaBody?.data?.uuid
  expect(uuid, '验证码响应里应有 uuid').toBeTruthy()

  // 2) 从 Redis 直读答案
  const answer = await readCaptchaAnswer(uuid)
  expect(answer, `Redis 里应有 hy:captcha:${uuid} 的答案`).toBeTruthy()

  // 3) 注册
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

/** 从浏览器 localStorage 读 token（项目用 storage.ts 封装，H5 端底层即 localStorage） */
async function readToken(page: Page): Promise<string> {
  return page.evaluate(() => window.localStorage.getItem('hy:token') ?? '')
}

/**
 * 定位 uni-app `<input>` 的**真实 input 元素**。
 *
 * ==========================================================================
 * 为什么必须下钻一层（本机实测踩到的坑）
 * ==========================================================================
 * uni-app 的 `<input>` 组件在 H5 端渲染成 `<uni-input>` **外壳元素**，
 * 真正的原生 `<input>` 在它内部。而 `data-testid` 是加在外壳上的，
 * 于是 `getByTestId('login-username')` 命中的是 `<uni-input>`，直接 `.fill()`
 * 会失败并报：
 *   Error: Element is not an <input>, <textarea>, <select> or [contenteditable]
 *
 * 这个报错容易被误读成"testid 没透传"或"页面没渲染"，实际两者都正常 ——
 * 只是选择器层级不对。统一用本辅助下钻，避免每个用例各写一遍。
 */
function uniInput(page: Page, testId: string) {
  return page.getByTestId(testId).locator('input')
}

/* ==========================================================================
 * 用例 1：注册（完整走 UI，含图形验证码）
 * ========================================================================== */

test('注册：填写表单含图形验证码，成功后跳转登录页', async ({ page }) => {
  const username = uniqueUsername()
  const nickname = `测试用户${username.slice(-4)}`

  /*
   * 先挂响应监听再导航 —— 顺序很重要。
   * 页面 onLoad 里就会请求验证码，若先导航再监听会**漏掉**这次响应，
   * 于是拿不到 uuid，用例会以"超时"失败，根因却不明显。
   */
  const captchaResponse = page.waitForResponse(
    (res) => res.url().includes('/api/auth/captcha') && res.status() === 200
  )

  await page.goto(ROUTES.register)

  // 确认页面确实渲染了（而不是白屏）—— 白屏时后续断言会给出误导性的失败信息
  await expect(page.getByTestId('reg-username')).toBeVisible()

  // 拿到验证码 uuid，并从 Redis 读答案
  const captchaBody = await (await captchaResponse).json()
  const uuid: string = captchaBody?.data?.uuid
  expect(uuid, '页面加载时应请求到验证码').toBeTruthy()
  const answer = await readCaptchaAnswer(uuid)
  expect(answer, 'Redis 里应有验证码答案（TTL 300s，超过就会拿不到）').toBeTruthy()

  // 填表（走 uniInput 下钻到真实 input）
  await uniInput(page, 'reg-username').fill(username)
  await uniInput(page, 'reg-password').fill(PASSWORD)
  await uniInput(page, 'reg-nickname').fill(nickname)
  await uniInput(page, 'reg-captcha-code').fill(answer as string)

  /*
   * 校验填值真的进了组件状态（而不只是进了 DOM）。
   * uni-app 的 input 是受控组件，若 v-model 绑定有问题，DOM 里有值但表单数据是空的，
   * 提交后会以"请填写用户名"这类**误导性**提示失败。这里提前拿到确凿证据。
   */
  await expect(uniInput(page, 'reg-username')).toHaveValue(username)
  await expect(uniInput(page, 'reg-password')).toHaveValue(PASSWORD)
  await expect(uniInput(page, 'reg-nickname')).toHaveValue(nickname)
  await expect(uniInput(page, 'reg-captcha-code')).toHaveValue(answer as string)

  // 勾选协议（契约里 agreeProtocol 必填，不勾会被前端拦下）
  await page.getByTestId('reg-agree').click()

  // 提交
  await page.getByTestId('reg-submit').click()

  /*
   * 断言注册成功。
   * 不以 toast 文案作为主要断言（toast 是瞬时元素，时序不稳），
   * 而是断言**跳转到登录页** —— 这是注册成功后的确定行为（契约不返回 token，
   * 因此前端必须引导去登录，见 register/index.vue 的注释）。
   */
  await expect(page).toHaveURL(new RegExp(ROUTES.login.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), {
    timeout: 15_000,
  })
  await expect(page.getByTestId('login-username')).toBeVisible()

  /*
   * 反向校验：注册**不应**产生登录态。
   * 契约上 register 只返回 UserVO、不返回 token，
   * 若这里能读到 token，说明有人给前端加了"注册即登录"的臆测行为。
   */
  const tokenAfterRegister = await readToken(page)
  expect(tokenAfterRegister, '注册不应写入登录 token（契约不返回 token）').toBe('')
})

/* ==========================================================================
 * 用例 2：登录成功 → token 落库 → 进入需登录页 → 刷新后仍可用
 * ========================================================================== */

test('登录：登录成功写入 token，可进入「我的」页并在刷新后保持登录', async ({ page }) => {
  const username = uniqueUsername()
  await createAccountViaApi(username, `登录${username.slice(-4)}`)

  await page.goto(ROUTES.login)
  await expect(page.getByTestId('login-username')).toBeVisible()

  await uniInput(page, 'login-username').fill(username)
  await uniInput(page, 'login-password').fill(PASSWORD)

  // 监听登录响应，用它证明"请求真的打到了后端并成功"
  const loginResponse = page.waitForResponse(
    (res) => res.url().includes('/api/auth/login') && res.request().method() === 'POST'
  )
  await page.getByTestId('login-submit').click()

  const res = await loginResponse
  expect(res.status(), '登录请求应返回 HTTP 200').toBe(200)
  const body = await res.json()
  expect(body.code, `登录业务码应为 0，实际 ${JSON.stringify(body)}`).toBe(0)
  expect(body.data?.token, '登录响应应包含 token').toBeTruthy()

  // token 必须被持久化（页面用的是封装的存储模块，H5 端即 localStorage）
  await expect
    .poll(async () => readToken(page), { timeout: 10_000 })
    .toBe(body.data.token)

  // 进入需要登录的页面
  await page.goto(ROUTES.me)
  await expect(page.getByTestId('me-nickname')).toBeVisible()
  await expect(page.getByTestId('me-nickname')).toHaveText(`登录${username.slice(-4)}`)
  await expect(page.getByTestId('me-username')).toHaveText(`@${username}`)

  /*
   * 刷新后仍保持登录：验证 token 是**从存储恢复**的，而不是只活在内存里。
   * 这一条能抓到"只写了内存 store、忘了持久化"的实现缺陷。
   */
  await page.reload()
  await expect(page.getByTestId('me-nickname')).toBeVisible()
  await expect(page.getByTestId('me-nickname')).toHaveText(`登录${username.slice(-4)}`)
})

/* ==========================================================================
 * 用例 3：登录失败的错误码映射（契约 1002）
 * ========================================================================== */

test('登录失败：密码错误时展示可读文案而非技术信息', async ({ page }) => {
  const username = uniqueUsername()
  await createAccountViaApi(username, `错误${username.slice(-4)}`)

  await page.goto(ROUTES.login)
  await expect(page.getByTestId('login-username')).toBeVisible()

  await uniInput(page, 'login-username').fill(username)
  await uniInput(page, 'login-password').fill('WrongPass9999')

  const loginResponse = page.waitForResponse(
    (res) => res.url().includes('/api/auth/login') && res.request().method() === 'POST'
  )
  await page.getByTestId('login-submit').click()

  const body = await (await loginResponse).json()
  expect(body.code, '密码错误应返回契约登记的 1002').toBe(1002)

  /*
   * 断言用户看到的是**映射后的中文文案**，而不是后端原文或错误码数字。
   * 文案来源 src/utils/error-code.ts 的 1002 映射：
   * 「用户名或密码不正确」
   */
  await expect(page.getByText('用户名或密码不正确')).toBeVisible({ timeout: 10_000 })

  // 失败时不应写入 token
  expect(await readToken(page)).toBe('')
})

/* ==========================================================================
 * 用例 4：未登录访问需登录页 → 展示登录引导（不白屏、不报错）
 * ========================================================================== */

test('未登录：访问「我的」页展示登录引导而非错误', async ({ page }) => {
  await page.goto(ROUTES.me)

  // 未登录时应看到登录引导
  await expect(page.getByText('你还没有登录')).toBeVisible({ timeout: 10_000 })

  // 不应出现任何错误提示 toast
  await expect(page.getByText(/失败|错误|异常/)).toHaveCount(0)
})

/* ==========================================================================
 * 用例 5：退出登录 → token 被清除
 * ========================================================================== */

test('退出登录：token 被清除且回到未登录态', async ({ page }) => {
  const username = uniqueUsername()
  await createAccountViaApi(username, `退出${username.slice(-4)}`)

  // 先登录
  await page.goto(ROUTES.login)
  await uniInput(page, 'login-username').fill(username)
  await uniInput(page, 'login-password').fill(PASSWORD)
  await page.getByTestId('login-submit').click()
  await expect.poll(async () => readToken(page), { timeout: 10_000 }).not.toBe('')

  // 进入「我的」页并退出
  await page.goto(ROUTES.me)
  await expect(page.getByTestId('me-logout')).toBeVisible()
  await page.getByTestId('me-logout').click()

  /*
   * 退出会弹 showModal 确认框（uni-app H5 是**自绘弹窗**，不是原生 window.confirm，
   * 因此不需要处理 Playwright 的 dialog 事件）。
   *
   * ⚠️ 确认按钮文案**不能写死成「确定」**：实测 uni-app H5 的 showModal
   *    **不读我们传的 `confirmText`**，而是按浏览器/系统 locale 生成按钮文字 ——
   *    在本机（英文 locale）渲染出来是 `Cancel` / `OK`；标题才是我们传的「退出登录」。
   *    写死中文会让用例在英文环境必然失败（本机实测踩到）。
   *    因此用「中文 ∪ 英文」正则同时兼容两种 locale。
   *
   *    顺带记一条产品问题：中文用户在英文 locale 浏览器上会看到英文确认按钮。
   *    若要修，应改用自绘弹窗（wot-design-uni 的 wd-message-box）替代 uni.showModal；
   *    这属于体验改进，不由本用例承担。
   */
  const confirmButton = page.getByText(/^(确定|OK)$/).last()
  await expect(confirmButton).toBeVisible({ timeout: 10_000 })
  await confirmButton.click()

  // token 必须被清除（无论服务端注销成功与否，本地都必须清 —— 见 stores/auth.ts 注释）
  await expect.poll(async () => readToken(page), { timeout: 10_000 }).toBe('')

  // 页面回到未登录引导
  await expect(page.getByText('你还没有登录')).toBeVisible({ timeout: 10_000 })
})
