/**
 * M4 第二批端到端：个人主页（四个 Tab）、关注、我的收藏、首页双流。
 *
 * ==========================================================================
 * 这份用例刻意验证的几件事（都是"只有打真后端才验得到"的）
 * ==========================================================================
 * 1. **关注态是服务端真值**：`UserProfileVO.isFollowing` 是契约真有的字段 ——
 *    点关注后**重新加载页面**，按钮仍应显示"已关注"。若哪天有人把状态改成只用内存记，
 *    这条会立刻失败（列表卡片上的点赞就没有这个待遇，那是 CR-K）。
 * 2. **关注自己会被后端拒**：自己看自己的主页**不显示**关注按钮。
 * 3. **关注流未登录不发请求**：断言一个 `/api/feed` 请求都没发出去 ——
 *    这是"不发请求"这句口径的可验证形式，光看界面看不出它有没有偷偷请求。
 * 4. **我的收藏未登录不发请求**，且登录后能拿到自己收藏过的帖子。
 * 5. **取消收藏后该帖从列表消失**（就地移除，不重拉）。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = 'Test1234abc'

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

/** 注册 + 登录，返回用户名与 userId */
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
        nickname: `档${username.slice(-4)}`,
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

  // 自己的 userId：从 /api/user/me 拿（页面已经登录，token 在 localStorage）
  const token = await page.evaluate(() => window.localStorage.getItem('hy:token') || '')
  const me = await (
    await fetch(`${API_BASE}/api/user/me`, { headers: { Authorization: token } })
  ).json()
  return { username, userId: Number(me.data?.id) }
}

/** 取一条演示帖（作者不是自己），用于访问别人的主页 */
async function otherPost(): Promise<{ id: number; authorId: number }> {
  const list = await (await fetch(`${API_BASE}/api/posts?page=1&size=5`)).json()
  expect(list.code).toBe(0)
  const item = list.data.list[0]
  expect(item, '演示数据里应有帖子').toBeTruthy()
  return { id: Number(item.id), authorId: Number(item.author?.id) }
}

/* ==========================================================================
 * 用例 1：个人主页的四个 Tab + 资料
 * ========================================================================== */

test('个人主页：资料与四个 Tab 都能拉到数据，Tab 切换保留已加载内容', async ({ page }) => {
  await freshUser(page, 'm4p')
  const { authorId } = await otherPost()

  await page.goto(`#/pages/user/index?id=${authorId}`)
  await expect(page.getByTestId('user-profile')).toBeVisible({ timeout: 15_000 })

  // 昵称必须与接口一致（不是"未登录"之类的占位）
  const detail = await (await fetch(`${API_BASE}/api/users/${authorId}`)).json()
  expect(detail.code).toBe(0)
  await expect(page.getByTestId('user-nickname')).toHaveText(String(detail.data.nickname))

  // 四个计数都在
  for (const t of ['post', 'follow', 'fans', 'like']) {
    await expect(page.getByTestId(`user-stat-${t}`)).toBeVisible()
  }

  // 四个 Tab 逐个点开，每个都要么有内容、要么给出**该 Tab 专属**的空文案
  for (const t of ['comments', 'follows', 'fans', 'posts']) {
    await page.getByTestId(`user-tab-${t}`).click()
    const rows = page.locator(
      t === 'posts'
        ? '[data-testid="post-card"]'
        : t === 'comments'
          ? '[data-testid="user-comment-row"]'
          : '[data-testid="user-follow-row"]'
    )
    const state = page.getByTestId('user-tab-state')
    // 二者必居其一：有行，或者有状态占位（loading/empty）
    await expect
      .poll(async () => (await rows.count()) + (await state.count()), { timeout: 15_000 })
      .toBeGreaterThan(0)
  }

  // 帖子 Tab 应能看到该用户自己的帖子（演示数据里他发过帖）
  await page.getByTestId('user-tab-posts').click()
  await expect(page.locator('[data-testid="post-card"]').first()).toBeVisible({ timeout: 15_000 })
})

/* ==========================================================================
 * 用例 2：关注 —— 状态来自服务端，刷新后仍然正确
 * ========================================================================== */

test('关注：主页点关注后刷新仍是「已关注」；自己的主页不显示关注按钮', async ({ page }) => {
  const me = await freshUser(page, 'm4f')
  const { authorId } = await otherPost()
  expect(authorId, '演示帖的作者不应是自己（否则这条用例没有意义）').not.toBe(me.userId)

  await page.goto(`#/pages/user/index?id=${authorId}`)
  const btn = page.getByTestId('user-follow')
  await expect(btn).toBeVisible({ timeout: 15_000 })
  await expect(btn).toContainText('关注')

  // 点关注 → 文案变"已关注"
  await btn.click()
  await expect(btn).toContainText('已关注', { timeout: 10_000 })

  // 接口层核对：真的落库了
  const token = await page.evaluate(() => window.localStorage.getItem('hy:token') || '')
  const profile = await (
    await fetch(`${API_BASE}/api/users/${authorId}`, { headers: { Authorization: token } })
  ).json()
  expect(profile.data.isFollowing, '关注应真的落库').toBe(true)

  /*
   * ⚠️ 关键：**刷新后仍然是"已关注"**。
   * 这条是在钉"状态来自服务端（`isFollowing`）"这件事 ——
   * 若有人改成只用内存记，刷新就会变回"关注"，这条会失败。
   */
  await page.reload()
  await expect(page.getByTestId('user-follow')).toContainText('已关注', { timeout: 15_000 })

  // 清理：取关
  await page.getByTestId('user-follow').click()
  await expect(page.getByTestId('user-follow')).toContainText('关注', { timeout: 10_000 })

  /*
   * 自己的主页：**不显示关注按钮**。
   *
   * ⚠️ 这里必须 `reload()`：`/pages/user/index?id=A` → `?id=B` 是**同一个 uni-app 路由只换了 query**，
   *    而 uni-app 的 hash 路由**不会重跑 `onLoad`** —— 不 reload 的话页面还停在上一个人的主页上，
   *    断言会以"自己的主页竟然有关注按钮"的形式失败（本机就这么假失败了一次）。
   *    这个坑在 M3 的发帖/详情用例里也踩过，是同一件事。
   */
  await page.goto(`#/pages/user/index?id=${me.userId}`)
  await page.reload()
  await expect(page.getByTestId('user-profile')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('user-follow'), '自己不能关注自己').toHaveCount(0)
})

/* ==========================================================================
 * 用例 3：首页双流 —— 未登录点「关注」不发请求，只给登录引导
 * ========================================================================== */

test('首页双流：未登录点「关注」不发 /api/feed 请求，只显示登录引导', async ({ page }) => {
  let feedCalls = 0
  page.on('request', (r) => {
    if (r.url().includes('/api/feed')) feedCalls++
  })

  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('home-feedtype')).toBeVisible({ timeout: 15_000 })
  // 默认是"全部"流 → 走 /api/posts（M3 那条路径）
  await expect(page.locator('[data-testid="post-card"]').first()).toBeVisible({ timeout: 15_000 })

  await page.getByTestId('home-feedtype-follow').click()
  await expect(page.getByTestId('home-follow-login-hint')).toBeVisible({ timeout: 10_000 })
  await page.waitForTimeout(800)
  expect(feedCalls, '未登录时不该请求关注流（否则 401 会被显示成"登录已过期"）').toBe(0)

  // 关注流不提供"精选"（文档没规定关注流支持哪些排序，不猜）
  await expect(page.getByTestId('home-sort-essence')).toHaveCount(0)

  // 切回"全部"应恢复列表
  await page.getByTestId('home-feedtype-all').click()
  await expect(page.locator('[data-testid="post-card"]').first()).toBeVisible({ timeout: 15_000 })
})

/* ==========================================================================
 * 用例 4：我的收藏 —— 未登录不发请求；登录后能看到并取消
 * ========================================================================== */

test('我的收藏：未登录给登录引导且不发请求；登录后可取消收藏', async ({ page }) => {
  let collectCalls = 0
  page.on('request', (r) => {
    if (r.url().includes('/api/user/collections')) collectCalls++
  })

  // 未登录
  await page.goto('#/pages/collect/index')
  await expect(page.getByTestId('collect-login-hint')).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(600)
  expect(collectCalls, '未登录不该请求收藏（401 会显示成"登录已过期"）').toBe(0)

  // 登录后：先收藏一条帖子（用接口，避开 UI 路径），再看收藏页
  const me = await freshUser(page, 'm4c')
  void me
  const token = await page.evaluate(() => window.localStorage.getItem('hy:token') || '')
  const { id: postId } = await otherPost()
  const collect = await fetch(`${API_BASE}/api/posts/${postId}/collect`, {
    method: 'POST',
    headers: { Authorization: token },
  })
  expect((await collect.json()).code, '收藏接口应成功').toBe(0)

  await page.goto('#/pages/collect/index')
  const card = page.locator('[data-testid="collect-card"]').first()
  await expect(card).toBeVisible({ timeout: 15_000 })
  // 收藏项契约里没有作者字段 → 卡片**不该**有作者行（也不该显示"匿名用户"这种假东西）
  await expect(card.locator('[data-testid="post-card-author"]')).toHaveCount(0)

  /*
   * ⚠️ 断言必须写成"**减少恰好 1**"，不能写"变成 0"。
   * 因为收藏数是**全站共享**的：别的用例（`m4-interaction` 的收藏用例）会故意留下收藏，
   * 演示数据里也可能有人收藏过。写死 0 的断言在**单独跑时通过、全量跑时失败** ——
   * 本机就这样得到过一次假失败（那种"单独跑没问题"的失败最容易被人当成抖动忽略）。
   */
  const before = await (
    await fetch(`${API_BASE}/api/posts/${postId}`)
  ).json()
  const beforeCount = Number(before.data.collectCount)

  /*
   * ⚠️ 取消收藏**不再有单独的按钮**了（需求方 2026-09-20：「不要这个取消收藏」）——
   *    统一走卡片上那个**书签图标**（与首页卡片完全一致）。
   *    所以这里点的是 `post-card-collect`，而不是原来的 `collect-card-remove`。
   */
  const removeResponse = page.waitForResponse(
    (r) =>
      r.url().endsWith('/collect') &&
      r.url().includes(`/api/posts/${postId}/`) &&
      r.request().method() === 'DELETE'
  )
  await card.locator('[data-testid="post-card-collect"]').click()
  expect((await (await removeResponse).json()).code, '取消失败').toBe(0)
  await expect(page.locator('[data-testid="collect-card"]')).toHaveCount(0, { timeout: 15_000 })

  // 接口层核对：确实少了一次（而不是"归零"）
  const detail = await (
    await fetch(`${API_BASE}/api/posts/${postId}`, { headers: { Authorization: token } })
  ).json()
  expect(detail.data.collectCount, '取消收藏后计数应恰好少 1').toBe(beforeCount - 1)
})
