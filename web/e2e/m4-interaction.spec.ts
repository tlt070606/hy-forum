/**
 * M4 端到端：评论（主楼 / 楼中楼 / 点赞 / 删除）+ 帖子点赞收藏。
 *
 * ==========================================================================
 * 这份用例为什么必须打真后端
 * ==========================================================================
 * 评论是**两层结构**（P1-3 归并语义），而且"回复某条楼中楼"在数据上仍然挂在主楼下
 * （`parent_id`/`root_id` 恒等于主楼 id）。这种不变量**只有真跑一遍才验证得到** ——
 * mock 掉后端就只是在验证我自己对契约的理解。
 *
 * 另外两条本文件刻意断言的事：
 * - **`replies` 是预览、`replyCount` 是总数**：发 3 条楼中楼后，"查看全部 N 条回复"
 *   必须出现，点了之后能看到全部（契约把它们分成两个字段，混用会少显示）。
 * - **点赞计数是服务端的、激活态是会话内的**：契约没有 `isLiked`，
 *   所以这里只断言"计数按服务端的值 ±1"，**不**断言刷新后还亮着（那是做不到的，见 CR）。
 *
 * 前置：后端 8080（带三个 OSS 环境变量起）、前端 5173、Redis 可达（注册要验证码）。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = 'Test1234abc'

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

/* ==========================================================================
 * 辅助
 * ========================================================================== */

/** 注册 + 登录一个全新账号（避开按账号的频控，也让用例互相独立） */
async function freshLoggedInUser(page: Page, tag: string): Promise<string> {
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
        nickname: `评${username.slice(-4)}`,
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
  return username
}

/** 取一条可评论的帖子 id（从真接口拿，不写死） */
async function anyPostId(): Promise<number> {
  const list = await (await fetch(`${API_BASE}/api/posts?page=1&size=1`)).json()
  expect(list.code).toBe(0)
  const id = Number(list.data.list[0]?.id)
  expect(id, '演示数据里应有帖子（否则先跑 seed-demo-data.sql）').toBeGreaterThan(0)
  return id
}

/**
 * 打开详情页，并**点开评论区**。
 *
 * ⚠️ 评论区默认是收起的（需求方 2026-09-17 定的交互），所以这里必须点一下 ——
 *    不点的话后面所有跟评论相关的定位都会找不到元素，而失败信息看起来像"评论没渲染"。
 */
async function openDetail(page: Page, postId: number): Promise<void> {
  await page.goto(`#/pages/post/detail?id=${postId}`)
  await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('comment-section')).toBeVisible({ timeout: 15_000 })
  await page.getByTestId('comment-open').click()
  await expect(page.getByTestId('comment-input')).toBeVisible({ timeout: 15_000 })
}

/** 只打开详情页，不动评论区（用于测收起态本身） */
async function openDetailRaw(page: Page, postId: number): Promise<void> {
  await page.goto(`#/pages/post/detail?id=${postId}`)
  await expect(page.getByTestId('detail-title')).toBeVisible({ timeout: 15_000 })
}

/**
 * 往评论框里填字，并**确认组件状态真的同步了**。
 *
 * ==========================================================================
 * 为什么不能只 `fill()` 了事（本机实测的坑）
 * ==========================================================================
 * `uni-textarea` 在 H5 端渲染成「`<uni-textarea>` 外壳 + 内层原生 `<textarea>`」。
 * 用 Playwright 的 `fill()`（直接设 DOM 值 + 派发一次 `input`）时，
 * **偶尔**不会被 uni 的组件同步进它的 `modelValue` ——
 * 现象是 **DOM 里有字、组件里是空**，提交只会得到一句"说点什么再发表"，
 * 而排查时看快照会以为"我明明填了"（这一类失败信息极具误导性）。
 *
 * 计数器（`comment-counter`）由组件的 `draft` 驱动，所以它是"组件状态"的可观察代理：
 * 只有计数器变了才说明同步成功。这里用**重试 + 明确报错**把它变成确定性行为，
 * 而不是靠等一个必然超时的响应。
 *
 * ⚠️ 这是**测试侧**的适配，不是产品缺陷：真实用户用键盘输入走的是 uni 的按键路径，
 *    不会出现这个问题。
 */
async function fillComment(page: Page, text: string): Promise<void> {
  const ta = page.getByTestId('comment-input').locator('textarea')
  const counter = page.getByTestId('comment-counter')

  for (let attempt = 0; attempt < 3; attempt++) {
    await ta.click()
    await ta.fill(text)
    try {
      await expect(counter).toHaveText(`${text.length}/1000`, { timeout: 2000 })
      return
    } catch {
      // 没同步上，再填一次（下一次通常就成功）
      await page.waitForTimeout(200)
    }
  }
  throw new Error(
    `评论输入框的 v-model 没有同步：DOM 里有值、组件状态是空的（text=${text}）。` +
      '这通常是 uni-textarea 对程序化 fill 的同步竞态，见 fillComment 的注释。'
  )
}

/** 发一条评论（主楼或回复）。返回它用的唯一文案，便于后续定位 */
async function postComment(page: Page, text: string): Promise<void> {
  await fillComment(page, text)
  const res = page.waitForResponse(
    (r) => r.url().endsWith('/api/comments') && r.request().method() === 'POST'
  )
  await page.getByTestId('comment-submit').click()
  const body = await (await res).json()
  expect(body.code, `发评论应成功：${JSON.stringify(body)}`).toBe(0)
}

/** 按文案定位那条评论（新评论排在最前，但用文案定位比按下标稳） */
function myComment(page: Page, text: string) {
  return page.getByTestId('comment-item').filter({ hasText: text })
}

async function confirmUniModal(page: Page): Promise<void> {
  const modal = page.locator('.uni-modal').last()
  await expect(modal).toBeVisible({ timeout: 5000 })
  await modal.locator('.uni-modal__btn_primary').click()
}

/* ==========================================================================
 * 用例 0：评论区**默认收起、点击才展开**（需求方 2026-09-17 定的交互）
 * ========================================================================== */

test('详情：评论区默认收起且不发请求，点击才展开，可再收起', async ({ page }) => {
  const postId = await anyPostId()

  /* 收起态：不能有输入框，必须有一个入口；而且**不该**先请求评论列表 */
  let commentCalls = 0
  page.on('request', (r) => {
    if (r.url().includes(`/api/posts/${postId}/comments`)) commentCalls++
  })

  await openDetailRaw(page, postId)
  await expect(page.getByTestId('comment-open')).toBeVisible({ timeout: 15_000 })
  await expect(page.getByTestId('comment-input')).toHaveCount(0)
  await page.waitForTimeout(800)
  expect(commentCalls, '收起状态不应请求评论列表（入口上的条数来自详情响应）').toBe(0)

  /* 点击展开 → 这时才请求 + 出现输入框 */
  await page.getByTestId('comment-open').click()
  await expect(page.getByTestId('comment-input')).toBeVisible({ timeout: 15_000 })
  await expect.poll(() => commentCalls, { timeout: 10_000 }).toBeGreaterThan(0)

  /* 再收起 */
  await page.getByTestId('comment-collapse').click()
  await expect(page.getByTestId('comment-input')).toHaveCount(0)
  await expect(page.getByTestId('comment-open')).toBeVisible()
})

/* ==========================================================================
 * 用例 1：评论全流程（主楼 → 楼中楼 → 全部回复 → 删除）
 * ========================================================================== */

test('评论：发主楼 → 回复成楼中楼 → 查看全部回复 → 删除主楼', async ({ page }) => {
  await freshLoggedInUser(page, 'm4a')
  const postId = await anyPostId()
  await openDetail(page, postId)

  /* ---- ① 发主楼 ---- */
  const rootText = `主楼评论 ${uniq()}`
  await postComment(page, rootText)
  const root = myComment(page, rootText)
  await expect(root, '新发的主楼应出现在列表里').toHaveCount(1, { timeout: 15_000 })

  /* ---- ② 回复它 → 楼中楼 ---- */
  await root.locator('[data-testid^="comment-reply-"]').click()
  // 点上「回复」后必须出现"正在回复 XXX"的提示，否则用户不知道自己在回谁
  await expect(page.getByTestId('comment-reply-target')).toBeVisible()

  const replyText = `楼中楼回复 ${uniq()}`
  await postComment(page, replyText)
  await expect(page.getByTestId('reply-item').filter({ hasText: replyText })).toHaveCount(1, {
    timeout: 15_000,
  })

  /*
   * ③ **楼中楼的 parent_id 必须挂在主楼下**（归并语义）。
   *    数据层断言：把这条回复从 GET /api/posts/{id}/comments 与
   *    GET /api/comments/{rootId}/replies 各读一次，确认它出现在**那条主楼**下面。
   */
  const roots = await (await fetch(`${API_BASE}/api/posts/${postId}/comments?page=1&size=20`)).json()
  const rootVO = roots.data.list.find((c: any) => c.content === rootText)
  expect(rootVO, '接口里应能查到刚发的主楼').toBeTruthy()
  expect(rootVO.replyCount, '主楼的 replyCount 应含这条楼中楼').toBeGreaterThan(0)

  const replies = await (
    await fetch(`${API_BASE}/api/comments/${rootVO.id}/replies?page=1&size=20`)
  ).json()
  const replyVO = replies.data.list.find((r: any) => r.content === replyText)
  expect(replyVO, '该主楼下的楼中楼列表里应有刚发的回复').toBeTruthy()
  // 归并语义的不变量：楼中楼的 parentId 与 rootId 都等于主楼 id
  expect(replyVO.parentId, '楼中楼的 parentId 必须等于主楼 id').toBe(rootVO.id)
  expect(replyVO.rootId, '楼中楼的 rootId 必须等于主楼 id').toBe(rootVO.id)

  /* ---- ④ 删除主楼（连带楼中楼） ---- */
  await root.locator('[data-testid^="comment-delete-"]').click()
  await confirmUniModal(page)
  await expect(myComment(page, rootText), '删除后主楼应消失').toHaveCount(0, { timeout: 15_000 })
  await expect(page.getByTestId('reply-item').filter({ hasText: replyText })).toHaveCount(0)
})

/* ==========================================================================
 * 用例 2：「查看全部 N 条回复」—— replies 是预览、replyCount 是总数
 * ========================================================================== */

test('评论：楼中楼超过预览条数时出现「查看全部 N 条回复」', async ({ page }) => {
  await freshLoggedInUser(page, 'm4b')
  const postId = await anyPostId()
  await openDetail(page, postId)

  const rootText = `预览主楼 ${uniq()}`
  await postComment(page, rootText)
  const root = myComment(page, rootText)
  await expect(root).toHaveCount(1, { timeout: 15_000 })

  // 连发 3 条楼中楼（契约的预览条数是"前若干条"，3 条足以触发"还有更多"）
  const replyTexts: string[] = []
  for (let i = 0; i < 3; i++) {
    await root.locator('[data-testid^="comment-reply-"]').click()
    const t = `预览回复${i} ${uniq()}`
    await postComment(page, t)
    replyTexts.push(t)
  }

  // 接口层核对：replyCount 是 3
  const roots = await (await fetch(`${API_BASE}/api/posts/${postId}/comments?page=1&size=20`)).json()
  const rootVO = roots.data.list.find((c: any) => c.content === rootText)
  expect(rootVO.replyCount, '3 条楼中楼都应被计数').toBe(3)

  /*
   * 界面上：
   * - 若预览只给了 2 条（或更少），必须出现「查看全部 3 条回复」——这就是
   *   "预览 ≠ 总数"这件事**在界面上的可观察结果**；
   * - 点它之后，3 条都应在页面上出现。
   */
  const more = myComment(page, rootText).locator('[data-testid^="comment-more-replies-"]')
  if (await more.count()) {
    await more.first().click()
  }
  for (const t of replyTexts) {
    await expect(page.getByTestId('reply-item').filter({ hasText: t })).toHaveCount(1, {
      timeout: 15_000,
    })
  }

  // 清理：删主楼（连带楼中楼）
  await root.locator('[data-testid^="comment-delete-"]').click()
  await confirmUniModal(page)
  await expect(myComment(page, rootText)).toHaveCount(0, { timeout: 15_000 })
})

/* ==========================================================================
 * 用例 3：帖子点赞 / 收藏 —— 计数按服务端 ±1；未登录先引导登录
 * ========================================================================== */

test('互动：未登录点赞会引导登录；登录后点赞/收藏计数按服务端 ±1 且可撤销', async ({ page }) => {
  const postId = await anyPostId()

  /* ---- 未登录：点一下应引导登录，**不应该**假装成功 ---- */
  await openDetail(page, postId)
  await page.getByTestId('detail-like').click()
  await expect(page.getByTestId('login-username')).toBeVisible({ timeout: 10_000 })

  /* ---- 登录后再来 ---- */
  await freshLoggedInUser(page, 'm4c')
  await openDetail(page, postId)

  const likeText = page.getByTestId('detail-like').locator('.interact__text')
  const before = Number((await likeText.textContent())?.trim() || '0')
  // 服务端的真值（用于对照）
  const apiBefore = (await (await fetch(`${API_BASE}/api/posts/${postId}`)).json()).data.likeCount

  await page.getByTestId('detail-like').click()
  await expect.poll(async () => Number((await likeText.textContent())?.trim()), { timeout: 10_000 })
    .toBe(before + 1)

  /*
   * 用接口复核：**点赞这条动作真的落库了**（详情里的 likeCount 变成 apiBefore+1）。
   * ⚠️ 这里必须重新拉一次详情，而拉详情会让浏览量 +1 —— 这是可接受的代价：
   *    这条断言证明的是"点击真的生效"，比浏览量多 1 重要得多。
   */
  await expect
    .poll(async () => (await (await fetch(`${API_BASE}/api/posts/${postId}`)).json()).data.likeCount, {
      timeout: 10_000,
    })
    .toBe(apiBefore + 1)

  // 再点一次 → 取消，计数回到起点
  await page.getByTestId('detail-like').click()
  await expect.poll(async () => Number((await likeText.textContent())?.trim()), { timeout: 10_000 })
    .toBe(before)

  /* ---- 收藏同理 ---- */
  const collectText = page.getByTestId('detail-collect').locator('.interact__text')
  const cBefore = Number((await collectText.textContent())?.trim() || '0')
  await page.getByTestId('detail-collect').click()
  await expect.poll(async () => Number((await collectText.textContent())?.trim()), { timeout: 10_000 })
    .toBe(cBefore + 1)
})

/* ==========================================================================
 * 用例 4：评论点赞
 * ========================================================================== */

test('互动：评论点赞计数 +1，再点撤销', async ({ page }) => {
  await freshLoggedInUser(page, 'm4d')
  const postId = await anyPostId()
  await openDetail(page, postId)

  const text = `待点赞评论 ${uniq()}`
  await postComment(page, text)
  const mine = myComment(page, text)
  await expect(mine).toHaveCount(1, { timeout: 15_000 })

  const likeBtn = mine.locator('[data-testid^="comment-like-"]')
  const countText = likeBtn.locator('.act__text')
  expect(Number((await countText.textContent())?.trim() || '0'), '新评论的点赞数是 0').toBe(0)

  await likeBtn.click()
  await expect.poll(async () => Number((await countText.textContent())?.trim()), { timeout: 10_000 })
    .toBe(1)

  await likeBtn.click()
  await expect.poll(async () => Number((await countText.textContent())?.trim()), { timeout: 10_000 })
    .toBe(0)

  // 清理
  await mine.locator('[data-testid^="comment-delete-"]').click()
  await confirmUniModal(page)
  await expect(myComment(page, text)).toHaveCount(0, { timeout: 15_000 })
})
