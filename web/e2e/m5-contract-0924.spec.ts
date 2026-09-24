/**
 * 2026-09-24 契约变更的验收（L1 给的三件 + 一条真取字节的验证）。
 *
 *  ① 四个点赞/收藏写端点返回 `InteractionStateVO{liked,collected,likeCount,collectCount}`
 *     → 以返回值为准，不本地推算计数
 *  ② `NotificationVO` 有 `postId` / `commentId` → 点通知跳帖子（帖子已删显示 404 友好页）
 *  ③ `CollectionItemVO` 有 `author` 与 `imageThumbs` → 收藏卡片显示作者头像与多图
 *  + CR-S 修好后：**缩略图要真能读出字节**（不是只看 DOM 有没有 <img>）
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = 'Test1234abc'

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

async function createUser(tag: string): Promise<{ username: string; id: number; token: string }> {
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
        nickname: `约${username.slice(-4)}`,
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
  return { username, id: Number(login.data.user?.id), token: login.data.token }
}

async function loginOnPage(page: Page, username: string): Promise<void> {
  await page.goto('#/pages/auth/index?mode=login')
  await page.getByTestId('login-username').locator('input').fill(username)
  await page.getByTestId('login-password').locator('input').fill(PASSWORD)
  await page.getByTestId('login-submit').click()
  await expect(page.getByTestId('login-username')).toHaveCount(0, { timeout: 15_000 })
}

/* ==========================================================================
 * ① 写端点返回状态：界面必须与**返回值**一致（不是与本地推算一致）
 * ========================================================================== */

test('点赞写端点：界面计数与状态等于返回的 InteractionStateVO', async ({ page }) => {
  const list = await (await fetch(`${API_BASE}/api/posts?page=1&size=1`)).json()
  const postId = Number(list.data.list[0].id)
  const me = await createUser('ir')

  // 从"未点赞"开始（幂等端点）
  const del = await (
    await fetch(`${API_BASE}/api/posts/${postId}/like`, {
      method: 'DELETE',
      headers: { Authorization: me.token },
    })
  ).json()
  // 契约保证 DELETE 也返回状态体
  expect(del.data, 'DELETE /like 也应返回 InteractionStateVO（CR-R）').toBeTruthy()
  expect(del.data.liked, '取消后 liked 应为 false').toBe(false)

  await loginOnPage(page, me.username)
  await page.goto(`#/pages/post/detail?id=${postId}`)
  await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })

  // 点一次，抓**响应体**，再断言界面 == 响应体
  const resPromise = page.waitForResponse(
    (r) => r.url().endsWith(`/api/posts/${postId}/like`) && r.request().method() === 'POST'
  )
  await page.getByTestId('detail-like').click()
  const body = await (await resPromise).json()
  expect(body.code).toBe(0)
  const state = body.data as { liked: boolean; likeCount: number }

  await expect
    .poll(async () => Number((await page.getByTestId('detail-like').locator('.interact__text').textContent())?.trim()), {
      timeout: 10_000,
    })
    .toBe(state.likeCount)

  // 清理
  await fetch(`${API_BASE}/api/posts/${postId}/like`, {
    method: 'DELETE',
    headers: { Authorization: me.token },
  })
})

/* ==========================================================================
 * ② 通知按 postId 跳帖子（CR-N）
 * ========================================================================== */

test('通知跳转：点赞类通知点开跳到**那篇帖子**（不是评论区、也不是用户主页）', async ({ page }) => {
  const a = await createUser('na')
  const b = await createUser('nb')

  /*
   * ⚠️ **A 必须是自己发一篇帖子**（我第一版随手拿了信息流里第一篇 ——
   *    而点赞通知只发给**帖子作者**，所以 A 什么也收不到，用例假失败）。
   *    这条前提本身也是"通知语义"的一部分：点赞通知的收件人是作者，不是被点赞的人。
   */
  const boards = await (await fetch(`${API_BASE}/api/boards`)).json()
  const normal = (boards.data as Array<{ id: number; isResource?: boolean }>).find((x) => !x.isResource)
  expect(normal, '演示库里应有普通版块').toBeTruthy()
  const created = await (
    await fetch(`${API_BASE}/api/posts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: a.token },
      body: JSON.stringify({
        boardId: normal!.id,
        title: `通知跳转验证 ${uniq()}`,
        content: '这条帖子的作者是 A，B 点赞后 A 应当收到带 postId 的点赞通知。',
      }),
    })
  ).json()
  expect(created.code, `A 发帖应成功：${JSON.stringify(created).slice(0, 160)}`).toBe(0)
  const postId = Number(created.data.id)

  // B 点赞 → A 会收到一条点赞通知（type=1，带 postId）
  await fetch(`${API_BASE}/api/posts/${postId}/like`, {
    method: 'POST',
    headers: { Authorization: b.token },
  })

  // 直接看接口：这条通知必须带 postId（这是本用例的前提）
  const notes = await (
    await fetch(`${API_BASE}/api/notifications?page=1&size=10`, { headers: { Authorization: a.token } })
  ).json()
  const likeNote = (notes.data.list as Array<{ type: number; postId?: number }>).find((n) => n.type === 1)
  expect(likeNote, 'A 应收到一条点赞通知').toBeTruthy()
  expect(Number(likeNote!.postId), '点赞通知应带 postId（CR-N）').toBe(postId)

  await loginOnPage(page, a.username)
  await page.goto('#/pages/notifications/index')
  await expect(page.getByTestId('notify-row').first()).toBeVisible({ timeout: 15_000 })

  await page.getByTestId('notify-row').first().click()

  // 落到**帖子详情**（有标题）—— 而不是用户主页/消息页
  await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('detail-title')).not.toHaveText('')

  // 清理：取消点赞 + 删掉这篇帖子（A 是作者，有权删）
  await fetch(`${API_BASE}/api/posts/${postId}/like`, {
    method: 'DELETE',
    headers: { Authorization: b.token },
  })
  await fetch(`${API_BASE}/api/posts/${postId}`, {
    method: 'DELETE',
    headers: { Authorization: a.token },
  })
})

/* ==========================================================================
 * ③ 收藏卡片：作者头像 + 多图，且缩略图**真能读出字节**（L1 要的那条验证）
 * ========================================================================== */

test('收藏卡片：显示作者、按 imageThumbs 画多图，且每张缩略图都能真取到字节', async ({ page }) => {
  const me = await createUser('ct')

  // 挑一篇有 ≥2 张缩略图的帖子并收藏
  const list = await (await fetch(`${API_BASE}/api/posts?page=1&size=20`)).json()
  const post = (list.data.list as Array<{ id: number; imageThumbs?: string[] }>).find(
    (p) => (p.imageThumbs || []).length >= 2
  )
  expect(post, '演示库里应有 ≥2 张图的帖子').toBeTruthy()
  await fetch(`${API_BASE}/api/posts/${post!.id}/collect`, {
    method: 'POST',
    headers: { Authorization: me.token },
  })

  // 接口层：收藏项必须带 author 与 imageThumbs
  const col = await (
    await fetch(`${API_BASE}/api/user/collections?page=1&size=20`, { headers: { Authorization: me.token } })
  ).json()
  const item = (col.data.list as Array<{ postId: number; author?: { nickname?: string }; imageThumbs?: string[] }>).find(
    (x) => x.postId === post!.id
  )
  expect(item, '应能查到刚收藏的那条').toBeTruthy()
  expect(item!.author?.nickname, '收藏项应带 author（契约已补）').toBeTruthy()
  const thumbs = item!.imageThumbs || []
  expect(thumbs.length, '收藏项应带 imageThumbs').toBeGreaterThanOrEqual(2)

  /*
   * ★ **真取字节**（L1 明确要求的那条验证）：
   * 不信 DOM、也不只看状态码 —— 把每一张缩略图**读下来**，断言 200 且字节数 > 0。
   * CR-S 之前的症状是"签名与 URL 形状不一致 → 403"，只看界面是查不出来的（会显示成灰块）。
   */
  for (const [i, url] of thumbs.entries()) {
    const r = await fetch(url)
    const buf = await r.arrayBuffer()
    expect(r.status, `thumb[${i}] 应 200（CR-S 已修）`).toBe(200)
    expect(buf.byteLength, `thumb[${i}] 应真的取到字节`).toBeGreaterThan(0)
  }

  // 界面层：收藏卡片刻画九宫格 + 作者行
  await loginOnPage(page, me.username)
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('#/pages/collect/index')
  const card = page.locator('[data-testid="collect-card"]').first()
  await expect(card).toBeVisible({ timeout: 15_000 })
  await expect(card.locator('[data-testid="post-card-author"]')).toHaveCount(1)
  await expect(card.locator('[data-testid="post-card-grid"]')).toHaveCount(1)

  // 清理：取消收藏
  await fetch(`${API_BASE}/api/posts/${post!.id}/collect`, {
    method: 'DELETE',
    headers: { Authorization: me.token },
  })
})
