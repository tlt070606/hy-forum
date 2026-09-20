/**
 * 导航改版（2026-09-18 需求方裁定）：左栏「话题」→「版块」；右栏假数据 → 真数据。
 *
 * ==========================================================================
 * 这份用例钉住的是"**假数据被换掉了**"这件事本身
 * ==========================================================================
 * 之前右栏三块（热门话题榜 / 推荐关注 / 热门活动）与左栏的「活跃用户」全是编的，
 * 连代码注释都写着"这一栏全部是假数据"。现在：
 * - **版块榜** ← `GET /api/boards`（帖子数是真的，必须与接口一致）
 * - **最新发帖的人** ← `GET /api/posts` 的作者去重
 * - **热门活动 / 活跃用户** → **删掉**（没有接口，不做假的）
 *
 * 所以断言方式也换了：**不再断言"界面上有个数字"，而是断言"界面上那个数 == 接口给的那个数"**。
 * 只断言"有内容"是抓不到"又变回假数据"的。
 */

import { expect, test } from '@playwright/test'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'

test('左栏：有「版块」、没有「话题」；点进版块总览能看到全部版块（数量与接口一致）', async ({
  page,
}) => {
  /*
   * ⚠️ 必须用**宽视口**：这两条断言的是三栏布局（左栏/右栏），
   * 而项目默认的 Playwright 视口是 420×900（移动端）——
   * 那种宽度下左右栏**根本不渲染**，断言会以"元素不存在"失败（本机踩到）。
   */
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('today-stats')).toBeVisible({ timeout: 15_000 })

  // 入口换掉了：版块在、话题不在
  await expect(page.getByTestId('nav-boards')).toBeVisible()
  await expect(page.getByTestId('nav-topic')).toHaveCount(0)

  // 点进版块总览：**数量必须与接口一致**（不是"有内容就行"）
  const boards = await (await fetch(`${API_BASE}/api/boards`)).json()
  expect(boards.code).toBe(0)
  const total = boards.data.length as number
  expect(total, '演示库里应有版块').toBeGreaterThan(0)

  await page.getByTestId('nav-boards').click()
  await expect(page.getByTestId('boards-state'), '不该停在加载态').toBeHidden({ timeout: 15_000 })
  await expect(page.getByTestId('board-card')).toHaveCount(total, { timeout: 15_000 })
  await expect(page.getByTestId('boards-total')).toHaveText(String(total))

  // 资源版块必须有标记（契约的 isResource —— 它决定发帖时要不要填网盘字段，是**有实际含义**的）
  const resourceCount = (boards.data as Array<{ isResource?: boolean }>).filter((b) => b.isResource).length
  await expect(page.getByTestId('board-resource')).toHaveCount(resourceCount)
})

test('右栏：版块榜的帖子数与接口一致；「最新发帖的人」来自真实帖子作者', async ({ page }) => {
  /*
   * ⚠️ 必须用**宽视口**：这两条断言的是三栏布局（左栏/右栏），
   * 而项目默认的 Playwright 视口是 420×900（移动端）——
   * 那种宽度下左右栏**根本不渲染**，断言会以"元素不存在"失败（本机踩到）。
   */
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('board-rank')).toBeVisible({ timeout: 15_000 })

  // ① 版块榜：条数 = min(版块数, 7)，且**第一条的帖子数**应等于接口里帖子数最多的那个
  const boards = (await (await fetch(`${API_BASE}/api/boards`)).json()).data as Array<{
    id: number
    name: string
    postCount?: number
  }>
  const expectedCount = Math.min(boards.length, 7)
  await expect(page.getByTestId('board-rank').locator('[data-testid^="board-rank-item-"]'))
    .toHaveCount(expectedCount, { timeout: 15_000 })

  const sorted = boards.slice().sort((a, b) => (b.postCount ?? 0) - (a.postCount ?? 0))
  const topName = sorted[0].name
  const topCount = sorted[0].postCount ?? 0
  // 排名第一的行里必须同时出现"版块名"与"它的真实帖子数"
  const firstRow = page.getByTestId('board-rank-item-0')
  await expect(firstRow).toContainText(topName)
  await expect(firstRow).toContainText(String(topCount))

  // ② 最新发帖的人：应来自真实帖子作者（用接口的第一页去重后核对第一个人）
  const posts = (await (await fetch(`${API_BASE}/api/posts?page=1&size=20`)).json()).data.list as Array<{
    author?: { id?: number; nickname?: string }
  }>
  const firstNickname = String(posts[0]?.author?.nickname ?? '')
  expect(firstNickname, '演示库里应有带作者的帖子').not.toBe('')
  await expect(page.getByTestId('author-0')).toContainText(firstNickname, { timeout: 15_000 })

  // ③ 假数据必须**真的消失**（不是藏在别处）
  await expect(page.getByTestId('hot-topic-rank')).toHaveCount(0)
  await expect(page.getByTestId('recommend-people')).toHaveCount(0)
  await expect(page.getByTestId('hot-activities')).toHaveCount(0)
})

/* ==========================================================================
 * 用例 3：点「首页」能直接从别的页面回去（需求方实测反馈）
 * ========================================================================== */

test('左栏：在版块页点「首页」直接回首页，不需要点返回', async ({ page }) => {
  /*
   * 修之前这里**点了没反应**：左栏把「首页」的 url 写成 null，
   * 而 onNav 用"url === null = 当前页 → 不跳"当判据 ——
   * 那条假设只在首页成立（左栏是全局外壳的一部分，在版块页/详情页也显示），
   * 于是任何非首页点「首页」都是死键。判据改成 active，并让首页走 reLaunch（清栈）。
   */
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('nav-home')).toBeVisible({ timeout: 15_000 })

  // 先去版块页（走真实入口，不直接改 URL）
  await page.getByTestId('nav-boards').click()
  await expect(page.getByTestId('board-card').first()).toBeVisible({ timeout: 15_000 })

  // 关键断言：点「首页」应当**回到首页**（有发帖入口 + 信息流）
  await page.getByTestId('nav-home').click()
  await expect(page.getByTestId('home-composer')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('post-card').first()).toBeVisible({ timeout: 15_000 })

  /*
   * 已在首页时再点一次**不该把页面搞坏**（旧实现的 no-op 语义要保住：
   * 不能因为"修了跳转"就变成"每次点都重新加载、把滚动位置和已加载的分页丢掉"）。
   */
  await page.getByTestId('nav-home').click()
  await expect(page.getByTestId('home-composer')).toBeVisible()
  await expect(page.getByTestId('post-card').first()).toBeVisible()
})

test('窄屏底部导航：在版块页点「首页」同样能直接回首页', async ({ page }) => {
  // 窄屏才是底部导航的形态（本配置默认视口就是 420×900）
  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('mobile-nav-home')).toBeVisible({ timeout: 15_000 })

  await page.getByTestId('mobile-nav-boards').click()
  await expect(page.getByTestId('board-card').first()).toBeVisible({ timeout: 15_000 })

  await page.getByTestId('mobile-nav-home').click()
  await expect(page.getByTestId('home-composer')).toBeVisible({ timeout: 15_000 })
})
