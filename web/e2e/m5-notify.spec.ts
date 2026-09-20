/**
 * M5 端到端：消息通知（红点 / 列表 / 已读）+ 举报。
 *
 * ==========================================================================
 * 这几条用例刻意验证的、只有打真后端才看得到的东西
 * ==========================================================================
 * 1. **未读数来自服务端**：自己造一条通知（让 B 关注 A）后再看 A 的红点 ——
 *    这条同时验了"后端真的写通知"与"前端真的读它"。
 *    之前顶栏那个未读数是**写死的假数据**，这条用例是把它换成真数据后的第一道防线。
 * 2. **`content` 可能是 null**（实测：关注类通知的 `content` 就是 null）→
 *    界面必须能兜出一句可读的话，而不是显示空白。
 * 3. **关注类通知要能跳到对方主页**（它没有 targetType/targetId，只有 fromUserId）。
 * 4. **未选原因时提交按钮必须是禁用的**，而不是"点了才报错"。
 * 5. **未登录不发请求**（消息页与顶栏红点都不该打 `/api/notifications*`）。
 *
 * ⚠️ 清理：关注关系在结尾用 `DELETE /api/follow/{userId}` 解除（幂等）。
 *    **举报记录清不掉** —— 契约里没有"撤销/删除举报"的接口，
 *    所以每次跑这条用例会在 `report` 表留一行（界面上看不见，属已知残留，报告里写明）。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = 'Test1234abc'

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

/** 注册一个账号（API），返回 { username, id, token } */
async function createUserViaApi(tag: string): Promise<{ username: string; id: number; token: string }> {
  const username = `e2e_${tag}_${uniq()}`.slice(0, 20)
  const cap = await (await fetch(`${API_BASE}/api/auth/captcha`)).json()
  const answer = await readCaptchaAnswer(cap.data.uuid)
  const reg = await (
    await fetch(`${API_BASE}/api/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username,
        password: PASSWORD,
        nickname: `通${username.slice(-4)}`,
        captchaUuid: cap.data.uuid,
        captchaCode: answer,
        agreeProtocol: true,
      }),
    })
  ).json()
  expect(reg.code, `注册应成功：${JSON.stringify(reg)}`).toBe(0)

  const login = await (
    await fetch(`${API_BASE}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password: PASSWORD }),
    })
  ).json()
  expect(login.code, `登录应成功：${JSON.stringify(login)}`).toBe(0)
  return { username, id: Number(login.data.user?.id ?? reg.data?.id), token: login.data.token }
}

/** 在页面上登录（写 localStorage + 走登录表单都行；这里直接用页面登录，顺便验证登录态生效） */
async function loginOnPage(page: Page, username: string): Promise<void> {
  await page.goto('#/pages/auth/index?mode=login')
  await page.getByTestId('login-username').locator('input').fill(username)
  await page.getByTestId('login-password').locator('input').fill(PASSWORD)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('login-username')).toHaveCount(0, { timeout: 15_000 })
}

/* ==========================================================================
 * 用例 1：未登录 —— 不发请求、也没有红点
 * ========================================================================== */

test('未登录：消息页给登录引导且不发 /api/notifications 请求，顶栏无红点', async ({ page }) => {
  let notifyCalls = 0
  page.on('request', (r) => {
    /*
     * ⚠️ 匹配必须**锚在 pathname 上**，不能用 `includes('/api/notifications')`。
     * 原因：H5 里前端自己的模块路径是 `/src/api/notifications.ts` ——
     * 它**包含** `/api/notifications`，于是"模块加载"会被数成"API 调用"，
     * 一条真请求都没发也会让断言失败（本机踩到，排查时白绕了一圈）。
     */
    if (new URL(r.url()).pathname.startsWith('/api/notifications')) notifyCalls++
  })

  await page.goto('#/pages/notifications/index')
  await expect(page.getByTestId('notify-login-hint')).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(700)
  expect(notifyCalls, '未登录时不该请求通知（401 会被显示成"登录已过期"）').toBe(0)

  /*
   * 窄屏（本配置视口 420×900）顶栏铃铛是**被隐藏**的（`.action { display:none }`），
   * 所以移动端的入口是**底部导航的「消息」** —— 而这个入口在原设计里根本不存在，
   * 是新增消息功能后由这条用例暴露出来的真问题（那时手机上根本进不去消息页）。
   * 这里同时钉住"入口在"与"未登录不显示红点"。
   */
  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('mobile-nav-notifications')).toBeVisible({ timeout: 15_000 })
  // 未登录：两处都不该有红点
  await expect(page.getByTestId('mobile-nav-badge')).toHaveCount(0)
  await expect(page.locator('[data-testid="topbar-bell"] .action__badge')).toHaveCount(0)
})

/* ==========================================================================
 * 用例 2：收到关注通知 → 红点出现 → 点进去 → 已读
 * ========================================================================== */

test('通知：被关注后红点出现、列表有可读文案、点击跳对方主页并标记已读', async ({ page }) => {
  const a = await createUserViaApi('nta')
  const b = await createUserViaApi('ntb')

  // B 关注 A → 后端应在同一事务里写一条通知（M5 的验收点）
  const follow = await fetch(`${API_BASE}/api/follow/${a.id}`, {
    method: 'POST',
    headers: { Authorization: b.token },
  })
  expect((await follow.json()).code, '关注应成功').toBe(0)

  await loginOnPage(page, a.username)

  // 顶栏红点：数字必须来自 /api/notifications/unread-count
  await page.goto('#/pages/index/index')
  const badge = page.locator('[data-testid="topbar-bell"] .action__badge-text')
  await expect(badge).toHaveText('1', { timeout: 15_000 })
  /*
   * 窄屏下顶栏铃铛是隐藏的 → **移动端的红点挂在底部导航的「消息」上**。
   * 两处读的是同一个 store（`stores/notify.ts`），所以数字不会打架；
   * 这条断言是那个共享设计的第一道防线。
   */
  await expect(page.getByTestId('mobile-nav-badge')).toHaveCount(1, { timeout: 15_000 })

  // 消息页：能看到那一条，且**文案可读**（关注类通知的 content 是 null → 走兜底）
  await page.goto('#/pages/notifications/index')
  await expect(page.getByTestId('notify-row')).toHaveCount(1, { timeout: 15_000 })
  await expect(page.getByTestId('notify-content')).toContainText('关注了你')

  // 未读小圆点在
  await expect(page.getByTestId('notify-row-unread')).toHaveCount(1)

  // 点它 → 跳到 B 的主页（关注类没有 targetType，只有 fromUserId）
  await page.getByTestId('notify-row').first().click()
  await expect(page.getByTestId('user-profile')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('user-nickname')).toContainText(b.username.slice(-4))

  // 回消息页：那一行应已变成"已读"（未读圆点消失）
  await page.goto('#/pages/notifications/index')
  await expect(page.getByTestId('notify-row')).toHaveCount(1, { timeout: 15_000 })
  await expect(page.getByTestId('notify-row-unread')).toHaveCount(0, { timeout: 15_000 })

  // 清理：解除关注（幂等），别把关系留在演示库里
  await fetch(`${API_BASE}/api/follow/${a.id}`, {
    method: 'DELETE',
    headers: { Authorization: b.token },
  })
})

/* ==========================================================================
 * 用例 3：「全部已读」
 * ========================================================================== */

test('通知：多条未读时「全部已读」把未读数清零', async ({ page }) => {
  const a = await createUserViaApi('ntc')
  const b = await createUserViaApi('ntd')
  const c = await createUserViaApi('nte')

  for (const u of [b, c]) {
    await fetch(`${API_BASE}/api/follow/${a.id}`, {
      method: 'POST',
      headers: { Authorization: u.token },
    })
  }

  await loginOnPage(page, a.username)
  await page.goto('#/pages/notifications/index')
  await expect(page.getByTestId('notify-row')).toHaveCount(2, { timeout: 15_000 })
  await expect(page.getByTestId('notify-unread')).toContainText('2')

  await page.getByTestId('notify-read-all').click()
  // 全部已读：未读提示消失、每行都没有未读圆点
  await expect(page.getByTestId('notify-unread')).toHaveCount(0, { timeout: 15_000 })
  await expect(page.getByTestId('notify-row-unread')).toHaveCount(0)

  // 清理：解除两条关注
  for (const u of [b, c]) {
    await fetch(`${API_BASE}/api/follow/${a.id}`, {
      method: 'DELETE',
      headers: { Authorization: u.token },
    })
  }
})

/* ==========================================================================
 * 用例 4：举报帖子（未选原因禁用 → 选原因提交成功）
 * ========================================================================== */

test('举报：详情页举报弹层，未选原因时提交禁用，选后提交成功', async ({ page }) => {
  const a = await createUserViaApi('rp')
  await loginOnPage(page, a.username)

  // 找一篇真实存在的帖子（不写死 id：演示库里的 id 会变）
  const list = await (await fetch(`${API_BASE}/api/posts?page=1&size=1`)).json()
  const postId = Number(list.data.list[0].id)
  expect(postId, '演示库里应有帖子').toBeGreaterThan(0)

  await page.goto(`#/pages/post/detail?id=${postId}`)
  await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })

  // 打开举报弹层
  await page.getByTestId('detail-report').click()
  await expect(page.getByTestId('report-sheet')).toBeVisible({ timeout: 10_000 })

  // ① 未选原因：提交按钮**禁用**（不是点了才报错）
  await expect(page.getByTestId('report-submit')).toHaveAttribute('data-disabled', '1')

  // ② 选一个原因 → 按钮变成可用
  await page.getByTestId('report-reason-3').click()
  await expect(page.getByTestId('report-reason-3')).toHaveAttribute('data-active', '1')
  await expect(page.getByTestId('report-submit')).toHaveAttribute('data-disabled', '0')

  // ③ 提交：真的打到 /api/report 且业务码为 0
  const reportResponse = page.waitForResponse(
    (r) => r.url().endsWith('/api/report') && r.request().method() === 'POST'
  )
  await page.getByTestId('report-submit').click()
  const body = await (await reportResponse).json()
  expect(body.code, `举报应成功：${JSON.stringify(body)}`).toBe(0)

  // 提交成功后弹层关闭
  await expect(page.getByTestId('report-sheet')).toHaveCount(0, { timeout: 10_000 })

  /*
   * ⚠️ 这里**没有清理举报记录**：契约里没有"撤销/删除举报"的接口。
   * 界面上看不到它（后台举报列表属 M6），但每次跑这条用例会在 `report` 表留一行 ——
   * 属已知残留，已写进报告与提交信息，不假装清干净了。
   */
})
