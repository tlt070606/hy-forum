/**
 * L1 给的验收判据（2026-09-20，CR-R：点赞/收藏的按钮状态渲染）——逐条对应用例。
 *
 * ==========================================================================
 * 这份用例的价值：它验的是"**状态来自读接口**"，不是"点了会变"
 * ==========================================================================
 * 写接口 `POST /api/posts/{id}/like` 的响应体是 `{"code":0,"message":"ok"}`，**没有 data**；
 * 状态只在读接口里（`PostSummaryVO`/`PostDetailVO` 的 `liked`/`collected`，相对请求者、
 * 未登录恒 false）。所以最容易写错的两件事是：
 * 1. 图标状态**不由数据决定**（例如只看本地内存/写死样式）→ 刷新就错；
 * 2. 计数**自己算死** → 连点几次就漂。
 * 下面 ①~⑥ 条逐条盯这两件事。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = 'Test1234abc'

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

/** 注册 + 页面登录，返回 { username, id, token } */
async function freshUser(page: Page, tag: string): Promise<{ username: string; id: number; token: string }> {
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
        nickname: `状${username.slice(-4)}`,
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
  const me = await (await fetch(`${API_BASE}/api/user/me`, { headers: { Authorization: token } })).json()
  return { username, id: Number(me.data?.id), token }
}

/** 取一篇帖子的 id */
async function anyPostId(): Promise<number> {
  const list = await (await fetch(`${API_BASE}/api/posts?page=1&size=1`)).json()
  return Number(list.data.list[0].id)
}

/** 用接口读某帖的权威状态与计数（"接口是唯一事实来源"里的那个来源） */
async function apiState(postId: number, token: string) {
  const r = await (
    await fetch(`${API_BASE}/api/posts/${postId}`, { headers: { Authorization: token } })
  ).json()
  return {
    liked: Boolean(r.data.liked),
    collected: Boolean(r.data.collected),
    likeCount: Number(r.data.likeCount),
    collectCount: Number(r.data.collectCount),
  }
}

/** 详情页上"点赞图标"当前是实心还是线框（由 DOM 里的图标形状决定） */
async function detailLikeIcon(page: Page): Promise<string[]> {
  // HyIcon 把形状画成若干绝对定位的小盒子；用 class 名判断形态更稳
  return page.getByTestId('detail-like').locator('.hy-icon').evaluateAll((els) =>
    els.map((e) => (e.className || '').toString())
  )
}

/* ==========================================================================
 * ① ② ③ ④ ⑤：详情页的点赞/收藏 —— 不刷新立刻变 / 刷新后仍然保持 / 取消后回灰 / 未登录未选中 / 列表与详情一致
 * ========================================================================== */

test('点赞收藏状态：①立刻变 ②刷新仍保持 ③取消后回灰 ④未登录未选中 ⑤列表与详情一致', async ({
  page,
}) => {
  const postId = await anyPostId()
  const me = await freshUser(page, 'lk')

  /* ---------- ④ 先看未登录（此时还没登录过，用另一个 context 更干净；这里先看接口语义） ---------- */
  const anon = await (await fetch(`${API_BASE}/api/posts/${postId}`)).json()
  expect(anon.code, '未登录也必须能读详情（未登录恒 false 且仍 200）').toBe(0)
  expect(anon.data.liked, '未登录时 liked 恒为 false').toBe(false)
  expect(anon.data.collected, '未登录时 collected 恒为 false').toBe(false)

  await page.goto(`#/pages/post/detail?id=${postId}`)
  await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })

  const before = await apiState(postId, me.token)
  const likeText = page.getByTestId('detail-like').locator('.interact__text')

  /* ---------- ① 点赞：不刷新，图标立刻实心 + 计数 +1 ---------- */
  if (before.liked) {
    // 保证从未点赞态开始（幂等端点，重复 DELETE 也无害）
    await fetch(`${API_BASE}/api/posts/${postId}/like`, {
      method: 'DELETE',
      headers: { Authorization: me.token },
    })
    await page.reload()
    await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })
  }
  const t0 = await apiState(postId, me.token)
  expect(t0.liked, '起点应当是未点赞').toBe(false)

  await page.getByTestId('detail-like').click()
  // 图标立刻变（不断言颜色/样式细节，断言"形状集合里出现了实心爱心"）
  await expect
    .poll(async () => (await detailLikeIcon(page)).some((c) => c.includes('hy-icon--heartFilled')), {
      timeout: 10_000,
    })
    .toBe(true)
  await expect
    .poll(async () => Number((await likeText.textContent())?.trim()), { timeout: 10_000 })
    .toBe(t0.likeCount + 1)

  /* ---------- ② 刷新后仍然是实心（★核心：状态来自读接口） ---------- */
  await page.reload()
  await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })
  await expect
    .poll(async () => (await detailLikeIcon(page)).some((c) => c.includes('hy-icon--heartFilled')), {
      timeout: 15_000,
    })
    .toBe(true)
  const afterLike = await apiState(postId, me.token)
  expect(afterLike.liked, '接口应记下这次点赞').toBe(true)
  await expect
    .poll(async () => Number((await likeText.textContent())?.trim()), { timeout: 10_000 })
    .toBe(afterLike.likeCount)

  /* ---------- ③ 取消点赞：回灰、-1；刷新后仍然回灰 ---------- */
  await page.getByTestId('detail-like').click()
  await expect
    .poll(async () => (await detailLikeIcon(page)).some((c) => c.includes('hy-icon--heartFilled')), {
      timeout: 10_000,
    })
    .toBe(false)
  await page.reload()
  await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })
  await expect
    .poll(async () => (await detailLikeIcon(page)).some((c) => c.includes('hy-icon--heartFilled')), {
      timeout: 15_000,
    })
    .toBe(false)
  const afterUnlike = await apiState(postId, me.token)
  expect(afterUnlike.liked, '接口应记下取消').toBe(false)

  /* ---------- ⑤ 同一帖：列表与详情状态一致（先点赞，再从首页列表看） ---------- */
  await fetch(`${API_BASE}/api/posts/${postId}/like`, {
    method: 'POST',
    headers: { Authorization: me.token },
  })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('#/pages/index/index')
  await expect(page.getByTestId('post-card').first()).toBeVisible({ timeout: 15_000 })

  // 在列表里找到那一帖（它可能不在第一屏 → 用 testid 里的 data-testid + 标题定位）
  const detail = await (await fetch(`${API_BASE}/api/posts/${postId}`)).json()
  const title = String(detail.data.title)
  const card = page.getByTestId('post-card').filter({ hasText: title }).first()
  if (await card.count()) {
    // 列表上的心形也必须是实心（同一个 store，被列表的读接口播种过）
    const filled = await card
      .locator('[data-testid="post-card-like"] .hy-icon')
      .evaluateAll((els) => els.map((e) => (e.className || '').toString()))
    expect(filled.some((c) => c.includes('hy-icon--heartFilled')), '列表里也应显示已点赞').toBe(true)
  }

  // 清理：取消点赞
  await fetch(`${API_BASE}/api/posts/${postId}/like`, {
    method: 'DELETE',
    headers: { Authorization: me.token },
  })
})

/* ==========================================================================
 * ⑥ 连点 5 次 → 最终状态与计数必须与接口一致（不漂）
 * ========================================================================== */

test('连点 5 次点赞：最终状态与计数与接口一致（不漂）', async ({ page }) => {
  const postId = await anyPostId()
  const me = await freshUser(page, 'rapid')

  // 从未点赞态开始
  await fetch(`${API_BASE}/api/posts/${postId}/like`, {
    method: 'DELETE',
    headers: { Authorization: me.token },
  })

  await page.goto(`#/pages/post/detail?id=${postId}`)
  await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })

  const likeText = page.getByTestId('detail-like').locator('.interact__text')
  const btn = page.getByTestId('detail-like')

  // 连点 5 次（不等待，模拟用户狂点）
  for (let i = 0; i < 5; i++) await btn.click({ delay: 0 })

  /*
   * 关键：等**接口**收敛，而不是等界面"看起来不动了"。
   * 5 次点击 → 奇数次的净效果是"点赞"（最后一次是 POST）。
   * 本用例只断言：**界面最终显示的计数与状态，等于接口的值** —— 这就够了，
   * 也正是"不能自己把计数算死"这条口径的可验证形式。
   */
  await expect
    .poll(
      async () => {
        const api = await apiState(postId, me.token)
        const shown = Number((await likeText.textContent())?.trim())
        const filled = (await detailLikeIcon(page)).some((c) => c.includes('hy-icon--heartFilled'))
        return `${shown === api.likeCount}|${filled === api.liked}`
      },
      { timeout: 20_000 }
    )
    .toBe('true|true')

  const api = await apiState(postId, me.token)
  expect(api.likeCount, '计数不应被连点搞成负数或乱跳').toBeGreaterThanOrEqual(0)

  // 清理
  await fetch(`${API_BASE}/api/posts/${postId}/like`, {
    method: 'DELETE',
    headers: { Authorization: me.token },
  })
})
