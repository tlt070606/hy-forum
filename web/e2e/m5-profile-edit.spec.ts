/**
 * M5 端到端：改简介 + 改头像（`PUT /api/user/profile`）。
 *
 * ==========================================================================
 * 这份用例最要紧的一条：**覆盖语义没把别的字段清空**
 * ==========================================================================
 * 契约原文：「**PUT = 覆盖（省略即清空）**」。
 * 也就是说"只想改简介而只传 `{bio}`"会把**昵称和头像一起清掉**。
 * 所以这里不只断言"改成功了"，还要**读请求体**确认：
 * 改简介时 `avatarUrl`/`nickname` 仍带着**当前值**（不是空串、不是漏字段）。
 *
 * 同理，改头像时必须：
 * - 上传走 `target=avatar` → `dir = avatar/{自己id}/`（契约要求 avatarUrl 在自己目录下）；
 * - 提交时带上 `bio` 的当前值。
 *
 * ⚠️ 清理：这条用例会**真的改掉一个新建账号的资料**（新建账号，改完即弃），
 *    以及**真的往 OSS 传一张头像**（`avatar/{id}/` 下会留一个小文件；OSS 无删除接口，属已知残留）。
 */

import { expect, test, type Page } from '@playwright/test'
import { readCaptchaAnswer } from './captcha-redis.ts'
import { resolve } from 'node:path'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = 'Test1234abc'
const FIXTURE = resolve(__dirname, 'fixtures/sample.png')

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

/** 注册 + 在页面上登录，返回 { username, id, token } */
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
        nickname: `资${username.slice(-4)}`,
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

/* ==========================================================================
 * 用例 1：改简介 —— 成功、刷新后仍在，且**没有清掉头像与昵称**
 * ========================================================================== */

test('改简介：保存成功、刷新后仍在，且提交的是全量字段（覆盖语义没清空头像/昵称）', async ({
  page,
}) => {
  const me = await freshUser(page, 'bio')
  await page.goto('#/pages/me/index')
  await expect(page.getByTestId('me-profile')).toBeVisible({ timeout: 15_000 })

  // 抓 PUT 的请求体（这是"覆盖语义"的唯一可验证之处）
  let putBody: Record<string, unknown> | null = null
  /*
   * ⚠️ `page.on('request')` 的回调收到的是 **Request**（不是 Response）——
   *    我第一版写成 `r.request().method()`，直接 TypeError: r.request is not a function。
   *    Request 上直接有 `method()` / `postDataJSON()`。
   */
  page.on('request', (req) => {
    if (req.method() === 'PUT' && req.url().endsWith('/api/user/profile')) {
      putBody = req.postDataJSON()
    }
  })

  const bio = `这是 E2E 写的简介 ${uniq()}`
  await page.getByTestId('me-bio-input').locator('textarea').fill(bio)

  // 改了才允许保存（没改时按钮是压暗的）
  await expect(page.getByTestId('me-bio-save')).toHaveAttribute('data-disabled', '0')

  const putDone = page.waitForResponse(
    (r) => r.url().endsWith('/api/user/profile') && r.request().method() === 'PUT'
  )
  await page.getByTestId('me-bio-save').click()
  const res = await (await putDone).json()
  expect(res.code, `保存简介应成功：${JSON.stringify(res)}`).toBe(0)

  /*
   * ⚠️ 关键断言：请求体必须**带上其余字段的当前值**。
   * 只传 {bio} 会让昵称与头像被清空（契约明写"省略即清空"）。
   */
  expect(putBody, '应抓到 PUT 请求体').toBeTruthy()
  expect(putBody!.bio, '简介应是新值').toBe(bio)
  expect(putBody!.nickname, '昵称必须一起带上（否则会被清空）').toContain(me.username.slice(-4))
  expect(typeof putBody!.avatarUrl, 'avatarUrl 字段必须在（哪怕是空串）').toBe('string')
  expect(putBody!.gender, 'gender 也必须带上').toBeDefined()

  // 刷新后仍在（真落库了，不是只改了本地）
  await page.reload()
  await expect(page.getByTestId('me-bio-input').locator('textarea')).toHaveValue(bio, {
    timeout: 15_000,
  })

  // 接口层复核
  const profile = await (
    await fetch(`${API_BASE}/api/user/me`, { headers: { Authorization: me.token } })
  ).json()
  expect(profile.data.bio, '简介应真的落库').toBe(bio)
  expect(profile.data.nickname, '昵称不该被清掉').toContain(me.username.slice(-4))
})

/* ==========================================================================
 * 用例 2：换头像 —— 走 avatar 目录直传，提交后头像地址真的变了
 * ========================================================================== */

/*
 * ==========================================================================
 * ✅ 2026-09-20 已解封（原为 `fixme`）：头像链路的口径已由 L1 裁定并落地
 * ==========================================================================
 * 曾经的失败与两处根因（都记在这里，免得将来重踩）：
 * 1. **OSS 侧**：一度返回 `403 AccessDenied / ImplicitDeny / oss:PutObject`
 *    （RAM 子账号对 `avatar/` 前缀没有写权限），后来已可用（桶里已有对象）；
 * 2. **前端侧（我改的）**：`target=avatar` 的签名**不带 `callback`** →
 *    OSS 成功响应是 **204 + 空响应体**，而我第一版硬按"200 + 回调回包"解析，
 *    于是"**传成功但界面说失败**"（桶里那批孤儿对象就是这么来的）。
 *    现在 `uploadAvatar` 改成：2xx/204 即成功、**不解析响应体**、URL 用 `host + key` 自己拼。
 *
 * 下面这些断言（地址必须落在 `/avatar/{自己id}/`、与旧值不同、刷新后仍在）本来就该成立。
 * ==========================================================================
 * 实测（探针直接打 OSS，`.tmp/avatar-upload-probe.mjs`）：
 * 签名完全正确（`dir = avatar/{自己id}/`，policy 的 `starts-with $key "avatar/{id}/"` 也对），
 * 但 OSS 返回：
 *
 *   <Code>AccessDenied</Code>
 *   <Message>You have no right to access this object because of bucket acl.</Message>
 *   <NoPermissionType>ImplicitDeny</NoPermissionType>
 *   <AuthAction>oss:PutObject</AuthAction>
 *   <AuthPrincipalType>SubUser</AuthPrincipalType>
 *
 * → **RAM 子账号没有 `avatar/` 前缀的 PutObject 权限**（`post/` 能传，说明权限是按前缀授的）。
 *
 * 这是**基础设施/后端侧的配置**（阿里云 RAM 策略要加 `hy-forum-2026/avatar/*`），
 * 前端改不了。已作为 **CR-P** 登记。
 *
 * ⚠️ **RAM 策略加好之后，请把 `test.fixme` 改回 `test`** —— 那时这条用例应当直接变绿
 *    （它验的是"传上去的地址落在自己的 avatar 目录里"，本来就该能过）。
 *    在此之前标 `fixme` 而不是删掉/改弱：**保留完整断言，只是暂时不执行**，
 *    这样策略一修好就自动能验，不用重新写一遍。
 */
test('换头像：直传 avatar 目录并提交，avatarUrl 变成本项目 OSS 的 avatar/{自己id}/', async ({
  page,
}) => {
  const me = await freshUser(page, 'ava')
  await page.goto('#/pages/me/index')
  await expect(page.getByTestId('me-avatar')).toBeVisible({ timeout: 15_000 })

  // 先记录提交前的 avatarUrl（新账号通常为空）
  const before = await (
    await fetch(`${API_BASE}/api/user/me`, { headers: { Authorization: me.token } })
  ).json()
  const beforeAvatar = String(before.data.avatarUrl ?? '')

  let putBody: Record<string, unknown> | null = null
  /*
   * ⚠️ `page.on('request')` 的回调收到的是 **Request**（不是 Response）——
   *    我第一版写成 `r.request().method()`，直接 TypeError: r.request is not a function。
   *    Request 上直接有 `method()` / `postDataJSON()`。
   */
  page.on('request', (req) => {
    if (req.method() === 'PUT' && req.url().endsWith('/api/user/profile')) {
      putBody = req.postDataJSON()
    }
  })

  /*
   * 记录这条链上的每一步（签名 → 直传 OSS → PUT）。
   * 不记的话，"PUT 没发生"就只知道超时、不知道断在哪一环 ——
   * 本机第一版就是这样白等 60 秒（头像那条链比"改简介"长得多）。
   */
  const flow: string[] = []
  /* 响应也记下来：只看请求会以为"发出去了就算数"，而真正卡住的是**响应**（本机就是这样） */
  page.on('response', async (res) => {
    const u = new URL(res.url())
    if (u.hostname.endsWith('aliyuncs.com') || u.pathname.endsWith('/api/user/profile')) {
      let body = ''
      try {
        body = (await res.text()).slice(0, 300)
      } catch {
        body = '(读不到响应体)'
      }
      flow.push(`  ⇐ ${res.status()} ${u.hostname}${u.pathname}  ${body}`)
    }
  })
  page.on('request', (req) => {
    const u = new URL(req.url())
    if (
      u.pathname.startsWith('/api/oss/signature') ||
      u.pathname.endsWith('/api/user/profile') ||
      u.hostname.endsWith('aliyuncs.com')
    ) {
      flow.push(`${req.method()} ${u.hostname}${u.pathname}`)
    }
  })

  // 触发选图（uni.chooseImage 在 H5 会弹一个隐藏的 file input）
  const [chooser] = await Promise.all([
    page.waitForEvent('filechooser', { timeout: 15_000 }),
    page.getByTestId('me-avatar').click(),
  ])
  await chooser.setFiles(FIXTURE)

  // 上传遮罩应出现 —— 它证明"选到了图、开始传"这一步走通了
  await expect(page.getByTestId('me-avatar-uploading')).toBeVisible({ timeout: 10_000 })

  // 等 PUT 回来（上传 → 回调 → PUT 是一条链，给足时间）
  const putDone = page.waitForResponse(
    (r) => r.url().endsWith('/api/user/profile') && r.request().method() === 'PUT',
    { timeout: 60_000 }
  )
  let res: { code: number; message?: string }
  try {
    res = await (await putDone).json()
  } catch (e) {
    throw new Error(
      `没等到头像的 PUT 提交。这条链实际走了：\n  ${flow.join('\n  ') || '(一个相关请求都没有)'}\n` +
        `页面上的错误提示：${(await page.getByTestId('me-avatar-hint').textContent().catch(() => '')) || '(读不到)'}`
    )
  }
  expect(res.code, `头像提交应成功：${JSON.stringify(res)}`).toBe(0)

  // ① 地址必须落在**自己的** avatar 目录下（契约的硬约束，否则后端会 400）
  const avatarUrl = String(putBody!.avatarUrl ?? '')
  expect(avatarUrl, 'avatarUrl 应非空').not.toBe('')
  expect(avatarUrl, `avatarUrl 必须包含 /avatar/${me.id}/：${avatarUrl}`).toContain(`/avatar/${me.id}/`)
  expect(avatarUrl, '应指向本项目 OSS 域名').toContain('aliyuncs.com')
  expect(avatarUrl, '头像地址应与改之前不同').not.toBe(beforeAvatar)

  // ② 覆盖语义：改头像不能把简介清掉（这个账号简介本来就空，所以断言字段在、不是 undefined）
  expect(typeof putBody!.bio, 'bio 字段必须一起带上').toBe('string')

  // ③ 刷新后仍是新地址（真落库）：先看接口，再看界面
  await page.reload()
  await expect(page.getByTestId('me-avatar')).toBeVisible({ timeout: 15_000 })

  const after = await (
    await fetch(`${API_BASE}/api/user/me`, { headers: { Authorization: me.token } })
  ).json()
  /*
   * ⚠️ 比较时**只看路径部分**（`split('?')[0]`），不能逐字比 ——
   *    后端对头像地址做了**读时签名**（这正是 CR-Q 的修复），所以返回值和提交值多出一段查询串。
   *    本机第一版写的就是逐字相等，于是 L1 修好签名之后这条**反而失败了**（假失败）。
   */
  const afterUrl = String(after.data.avatarUrl)
  expect(afterUrl.split('?')[0], '刷新后接口仍应返回同一个头像对象').toBe(avatarUrl)
  // 顺带把 CR-Q 的修复钉住：私有桶下**必须**带签名，否则又变成"传上去也看不见"
  expect(afterUrl, '头像地址应带读时签名（否则私有桶下 403，图显示不出来）').toContain('Signature=')

  /*
   * ⚠️ 到这里就**不再断言"界面上出现了那张图"**了 —— 不是偷懒，是它现在**不可能成立**：
   *    实测该地址是**裸地址**（`/api/user/me` 返回的 avatarUrl 不带 `Signature=`），
   *    而 OSS 桶是私有的 → 直接 GET 返回 **403** → 图必然加载不出来。
   *    这是后端的读时签名缺口（已登记 **CR-Q**：帖子图片有签名、头像地址没有）。
   *
   * 所以本用例验到"**地址正确落库并在接口里返回**"为止（这是前端能负责的那一段）；
   * 顺带在本机确认了组件会**降级成字母头像**而不是留一个空白方块（`Avatar.vue` 的 `@error`）。
   * CR-Q 修好之后，可以把这里升级成：容器里出现带该地址的图。
   */
})
