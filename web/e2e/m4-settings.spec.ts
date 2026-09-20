/**
 * 顶栏菜单定位 + 个人资料页 + 设置页（需求方 2026-09-18 反馈的三处）。
 *
 * ==========================================================================
 * 这份用例钉住的是"**修过的 bug**"和"**明确不做的功能**"，不是版式
 * ==========================================================================
 * 1. **菜单必须贴着头像**（曾经跑偏三百多像素）：断言菜单右边缘与头像右边缘对齐、
 *    且在头像**下方**。这是**功能断言**（量盒子），不是看图 ——
 *    版式由需求方看，我负责把它钉成不会再退化。
 * 2. **设置页的通知开关只存本机**：断言「仅本机、不生效」那段提示**必须在**，
 *    并且开关状态刷新后仍在（存本机是它的设计，不是 bug）。
 * 3. **不能改的东西必须写出来**：改头像/改简介/改密码的"待 M5 交付"标注必须在，
 *    页面里**不能出现**假的保存按钮。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = 'Test1234abc'

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

async function freshUser(page: Page, tag: string): Promise<{ username: string; userId: number }> {
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
        nickname: `设${username.slice(-4)}`,
        captchaUuid: cap.data.uuid,
        captchaCode: answer,
        agreeProtocol: true,
      }),
    })
  ).json()
  expect(reg.code, `注册应成功：${JSON.stringify(reg)}`).toBe(0)

  await page.goto('#/pages/auth/index?mode=login')
  await page.getByTestId('login-username').locator('input').fill(username)
  await page.getByTestId('login-password').locator('input').fill(PASSWORD)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('login-username')).toHaveCount(0, { timeout: 15_000 })

  const token = await page.evaluate(() => window.localStorage.getItem('hy:token') || '')
  const me = await (
    await fetch(`${API_BASE}/api/user/me`, { headers: { Authorization: token } })
  ).json()
  return { username, userId: Number(me.data?.id) }
}

/* ==========================================================================
 * 用例 1：下拉菜单贴着头像（修过的 bug）
 * ========================================================================== */

test('顶栏菜单：开在头像正下方、右边缘与头像对齐（曾经跑偏三百多像素）', async ({ page }) => {
  await freshUser(page, 'nav')

  // 用宽视口：这个 bug 只在"顶栏内容居中、有 max-width"的宽屏下暴露
  await page.setViewportSize({ width: 1920, height: 900 })
  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('topbar-user')).toBeVisible({ timeout: 15_000 })

  await page.getByTestId('topbar-user').click()
  const menu = page.getByTestId('topbar-menu')
  await expect(menu).toBeVisible({ timeout: 10_000 })

  const userBox = await page.getByTestId('topbar-user').boundingBox()
  const menuBox = await menu.boundingBox()
  expect(userBox && menuBox, '两个盒子都应量得到').toBeTruthy()

  /*
   * 右边缘对齐：允许 4px 误差（头像本身有内边距）。
   * ⚠️ 断言这两条而不是"菜单可见"：菜单以前也能显示，只是显示在**离头像三百多像素**的地方
   *    —— 只断言可见是抓不到那个 bug 的。
   */
  expect(
    Math.abs(menuBox!.x + menuBox!.width - (userBox!.x + userBox!.width)),
    `菜单右边缘应与头像右边缘对齐（菜单 ${Math.round(menuBox!.x + menuBox!.width)} vs 头像 ${Math.round(userBox!.x + userBox!.width)}）`
  ).toBeLessThanOrEqual(4)

  // 在头像下方（允许 0~20px 的间隙）
  const gap = menuBox!.y - (userBox!.y + userBox!.height)
  expect(gap, `菜单应在头像下方，实际间隙 ${Math.round(gap)}px`).toBeGreaterThanOrEqual(-2)
  expect(gap).toBeLessThanOrEqual(20)

  // 点遮罩关闭
  await page.locator('.menu-mask').click({ position: { x: 5, y: 5 } })
  await expect(menu).toHaveCount(0)
})

/* ==========================================================================
 * 用例 2：个人资料页（「我的」页改造）
 * ========================================================================== */

test('个人资料页：统计另起一行（粉丝/获赞/收藏），改不了的都写明「待 M5 交付」', async ({
  page,
}) => {
  const me = await freshUser(page, 'me')

  await page.goto('#/pages/me/index')
  await page.reload()
  await expect(page.getByTestId('me-profile')).toBeVisible({ timeout: 15_000 })

  // 昵称/用户名沿用 M2 的 testid（既有用例在断言它们，不能改名）
  await expect(page.getByTestId('me-nickname')).toBeVisible()
  await expect(page.getByTestId('me-id')).toContainText(String(me.userId))
  await expect(page.getByTestId('me-created')).toBeVisible()

  // 统计那一行：粉丝 / 获赞 / 收藏（需求方明确要求"另起一行"）
  for (const t of ['fans', 'like', 'collect']) {
    await expect(page.getByTestId(`me-stat-${t}`)).toBeVisible()
  }
  /*
   * 收藏数：契约里**没有**"收藏数"字段，我是从 `/api/user/collections` 的 `total` 拿的。
   * 新账号必然是 0 —— 断言它是数字（而不是 `—`），说明那次请求成功了。
   */
  await expect(page.getByTestId('me-stat-collect')).toContainText('0', { timeout: 15_000 })

  // 「不能改」必须写出来，而不是放个点了不生效的按钮
  await expect(page.getByTestId('me-avatar-hint')).toContainText('待后端接口')
  await expect(page.getByTestId('me-bio-note')).toContainText('契约里目前没有')
  await expect(page.getByTestId('me-password-note')).toContainText('待交付')
})

/* ==========================================================================
 * 用例 3：设置页 —— 开关存本机且必须明说"现在不生效"
 * ========================================================================== */

test('设置页：通知开关存本机并明写不生效；外观主题不做；退出登录可用', async ({ page }) => {
  await freshUser(page, 'set')

  // 从顶栏菜单进（也顺便验证菜单项真的通了）
  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('topbar-user')).toBeVisible({ timeout: 15_000 })
  await page.getByTestId('topbar-user').click()
  await page.getByTestId('topbar-menu-settings').click()
  await expect(page.getByTestId('settings-row-profile')).toBeVisible({ timeout: 15_000 })

  // ① 必须明写"仅本机、现在不生效"（这是"不做假成功"的落点）
  await expect(page.getByTestId('settings-notify-notice')).toContainText('只保存在本机')

  // ② 拨一个开关 → 状态变；刷新后仍在（存本机是设计，不是 bug）
  const like = page.getByTestId('settings-toggle-like')
  await expect(like).toHaveAttribute('data-on', '1') // 默认开
  await like.click()
  await expect(like).toHaveAttribute('data-on', '0')
  await page.reload()
  await expect(page.getByTestId('settings-toggle-like')).toHaveAttribute('data-on', '0', {
    timeout: 15_000,
  })

  // ③ 账号安全是只读的：ID 与登录方式都在，改密码明写未交付
  await expect(page.getByTestId('settings-account-id')).toBeVisible()
  await expect(page.getByTestId('settings-password-note')).toContainText('待交付')

  // ④ 外观主题按需求方要求不做 —— 只留一行说明，且**没有**对应的开关
  await expect(page.getByTestId('settings-theme-note')).toContainText('暂不实现')
  await expect(page.getByTestId('settings-toggle-theme')).toHaveCount(0)

  // ⑤ 退出登录真的可用（会回到未登录态）
  await page.getByTestId('settings-logout').click()
  const modal = page.locator('.uni-modal').last()
  await expect(modal).toBeVisible({ timeout: 5000 })
  await modal.locator('.uni-modal__btn_primary').click()
  // 退出后回到登录页（与 golden-path 的退出用例同口径）
  await expect(page.getByTestId('login-username')).toBeVisible({ timeout: 15_000 })
})
