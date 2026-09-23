/**
 * 列表卡片的**九宫格**（需求方 2026-09-18：「我要跟九宫格一样，有多少个就画多少个」）。
 *
 * ==========================================================================
 * 为什么这条现在才写
 * ==========================================================================
 * 之前契约的列表项**只有 `coverUrl` 一张图**（第 2 张起前端没有地址），
 * 所以只能画一格 + 「N 张」角标。这是当时如实上报的 **CR-L**。
 * 现在 `PostSummaryVO`/`FeedItemVO` 有了 **`imageThumbs`（前最多 3 张，读时签名）** ✓
 * → 多图帖可以画真九宫格了。
 *
 * ⚠️ 只画 **≤3 格**、超过的部分用「+N」角标 —— 因为**契约就只给 3 张**。
 *    断言里也据此写：格子数 = min(3, 该帖有图的张数)，且**不自己去补第 4 个地址**。
 */

import { expect, test } from '@playwright/test'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'

test('列表九宫格：多图帖按 imageThumbs 画多格，单图帖仍是一格', async ({ page }) => {
  // 用宽视口（三栏布局下卡片宽度才是常态）
  await page.setViewportSize({ width: 1440, height: 900 })

  /**
   * 记录 OSS 图片的响应 —— 用来验证"图真的加载出来了"。
   * ⚠️ 不能靠读 DOM 的 `naturalWidth`：uni-app H5 的 `<image>` 渲染成
   *    `<uni-image>` 外壳 + 内部一个用 `background-image` 的 div，**根本没有 `<img>` 子元素**
   *    （本机为此白绕了一圈）。查网络响应既与实现无关，又正好验的是"拉下来了且没失败"。
   */
  const ossResponses: Array<{ url: string; status: number }> = []
  page.on('response', (r) => {
    if (r.url().includes('aliyuncs.com')) ossResponses.push({ url: r.url(), status: r.status() })
  })

  // 从接口挑一篇**有 ≥2 张缩略图**的帖子（不写死 id）
  const list = await (await fetch(`${API_BASE}/api/posts?page=1&size=20`)).json()
  const multi = (list.data.list as Array<{ id: number; title: string; imageThumbs?: string[] }>).find(
    (p) => (p.imageThumbs || []).length >= 2
  )
  expect(multi, '演示库里应有带 ≥2 张图的帖子（否则这条用例没有意义）').toBeTruthy()
  const expectCells = Math.min(3, (multi!.imageThumbs || []).length)

  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('post-card').first()).toBeVisible({ timeout: 15_000 })

  // 定位那一帖的卡片（按标题），断言它画了九宫格
  const card = page.getByTestId('post-card').filter({ hasText: multi!.title }).first()
  await expect(card, '那一帖应出现在首页信息流里').toHaveCount(1, { timeout: 10_000 })

  const grid = card.locator('[data-testid="post-card-grid"]')
  await expect(grid, '多图帖应画九宫格而不是单格封面').toHaveCount(1)

  const cells = grid.locator('[data-testid^="post-card-thumb-"]')
  await expect(cells, `格子数应等于 min(3, 缩略图数) = ${expectCells}`).toHaveCount(expectCells)

  // 每格都必须真的加载出图（不是灰块）
  /*
   * ⚠️ 这里**不能**去读 `naturalWidth` —— 实测 uni-app H5 的 `<image>` 渲染成
   *    `<uni-image>` 外�壳 + 内部一个**用 `background-image` 的 div**，
   *    **根本没有 `<img>` 子元素**（本机为此刻意踩了一次）。
   *    改成查**网络响应**：既与组件实现无关，又正好验的是"这些图真的被拉下来了、且没失败"。
   */
  await expect
    .poll(() => ossResponses.length, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(expectCells)
  const failed = ossResponses.filter((r) => r.status >= 400)
  expect(
    failed,
    `不该有加载失败的图：${failed.map((f) => `${f.status} ${f.url.slice(0, 90)}`).join(' | ')}`
  ).toHaveLength(0)
  const ok = ossResponses.filter((r) => r.status < 400)
  expect(ok.length, '应至少有等于格子数的成功图片响应').toBeGreaterThanOrEqual(expectCells)

  /*
   * ⚠️ 反面对照：这一帖**不该**同时出现"单格封面"那套（否则说明两条分支都渲染了）。
   *    用 `post-card-cover`（单格封面的 testid）来区分。
   */
  await expect(card.locator('[data-testid="post-card-cover"]')).toHaveCount(0)
})
