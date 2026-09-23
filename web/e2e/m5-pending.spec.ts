/**
 * M5 前端范围第 3 条：**「待审核」的前台表现**。
 *
 * L1 口径：
 * > 内容进待审后**后端不再把它返回给你**，所以你要做的是：自己的发帖/评论提交成功后，
 * > 若响应里表明进入了待审状态，提示"已提交，审核通过后可见"。
 *
 * 判据来自**契约原文**（不是猜字段名）：
 * - `PostDetailVO.status`：**0 待审核 / 1 正常 / 2 已屏蔽**；
 * - `POST /api/posts`：「未命中敏感词**直接可见**，命中则**进待审队列**」。
 *
 * ⚠️ 怎么触发待审：`sensitive_word` 表里现在是 3 条**占位词**
 * （`__PLACEHOLDER_敏感词库待替换__` / `__占位词1__` / `__占位词2__`，真实词库属内容决策、尚未定），
 * 所以这条用例**用占位词触发** —— 验的是**机制**（命中 → status=0 → 前端提示），
 * **不是**词库内容。这一点在 M5 任务书里也是这么要求的（"不要写成内容安全达标"）。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = 'Test1234abc'
/** 生效的占位敏感词（从库里读到的第一条） */
const SENSITIVE = '__PLACEHOLDER_敏感词库待替换__'

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

async function freshUser(page: Page, tag: string): Promise<{ username: string; token: string }> {
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
        nickname: `审${username.slice(-4)}`,
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
  return { username, token }
}

const uniInput = (page: Page, id: string) => page.getByTestId(id).locator('input')

test('待审：命中敏感词发布 → 提示「已提交，审核通过后可见」而不是「发布成功」', async ({ page }) => {
  const me = await freshUser(page, 'pend')

  await page.goto('#/pages/post/edit')
  await expect(page.getByTestId('compose-title')).toBeVisible({ timeout: 15_000 })

  // 选第一个版块（普通版块即可，避免要填网盘字段）
  const boards = await (await fetch(`${API_BASE}/api/boards`)).json()
  const normal = (boards.data as Array<{ id: number; isResource?: boolean }>).find((b) => !b.isResource)
  expect(normal, '演示库里应有普通版块').toBeTruthy()
  await page.getByTestId(`compose-board-${normal!.id}`).click()

  await uniInput(page, 'compose-title').fill(`待审机制验证 ${uniq()}`)
  await page.getByTestId('compose-content').locator('textarea').fill(`正文里带上 ${SENSITIVE} 这个词`)

  const res = page.waitForResponse(
    (r) => r.url().endsWith('/api/posts') && r.request().method() === 'POST'
  )
  await page.getByTestId('compose-submit').click()
  const body = await (await res).json()
  expect(body.code, `发布应被接受（只是进待审）：${JSON.stringify(body).slice(0, 200)}`).toBe(0)

  /*
   * ① 契约判据：命中敏感词 → `status = 0`（待审）。
   *    这条先断言**接口事实**，再断言**界面文案** —— 顺序反了就会变成"文案对但状态没变"。
   */
  expect(body.data.status, '命中敏感词应进待审（status=0）').toBe(0)

  // ② 界面必须明确告知"审核通过后可见"，而不是笼统的"发布成功"
  /*
   * ⚠️ 用**文本定位**而不是 `.uni-toast`：类是 `.uni-toast` 没错（查过 uni-h5 的样式文件），
   *    但 toast 是 uni 自己挂到页面上的临时节点，用类名定位在本机拿不到（element not found）；
   *    按文案定位与容器实现无关，验的也正是"用户能不能看到这句话"。
   */
  await expect(page.getByText('审核通过后可见')).toBeVisible({ timeout: 10_000 })

  // 清理：把自己的待审帖删掉（作者有权删自己的）
  const postId = Number(body.data.id)
  if (postId) {
    await fetch(`${API_BASE}/api/posts/${postId}`, {
      method: 'DELETE',
      headers: { Authorization: me.token },
    })
  }
})
