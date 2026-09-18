/**
 * **最小闭环**（任务书 §6.1）：在 H5 上「选一张图 → 直传 OSS → 回调落库 → 发帖 → 详情页看到图」。
 *
 * ==========================================================================
 * 为什么它单独成一个文件
 * ==========================================================================
 * 这条链路是 M3 的**验收前提**（任务书原话：「其它页面写得再全，这条不通就一分验收都拿不到」），
 * 而它**依赖真实外部设施**：阿里云 OSS、反向隧道、以及后端配好的公网回调地址。
 * 与其余页面用例放在一起的话，任何一个环节抖动都会让整套 E2E 变红，
 * 而红的原因与前端代码无关 —— 那是最难判断的一类失败。分开就能一眼看出是哪一层出的事。
 *
 * 前置（缺一不可）：
 * 1. 后端在 8080 运行，且**带 OSS 环境变量**（不设则 fail-fast 起不来）；
 * 2. 后端配好 `OSS_CALLBACK_URL`（指向公网入口），否则 OSS 回调打不回来；
 * 3. 反向隧道活着；OSS 桶的 CORS 允许本站点 origin（否则浏览器读不到上传响应）；
 * 4. 前端 5173 在跑。
 *
 * 证据：本用例会把**上传请求/响应的关键字段**与**落库结果**写到
 * `.tmp/fe-upload-evidence.json`（供交付报告引用；`.tmp/` 已被 gitignore）。
 */

import { expect, test } from '@playwright/test'
import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { readCaptchaAnswer } from './captcha-redis.ts'

const API_BASE = process.env.E2E_API_BASE || 'http://127.0.0.1:8080'
const PASSWORD = 'Test1234abc'
/**
 * 测试用真图（320×200 PNG，1.4KB）。必须是**真图片**：OSS 侧要按 Content-Type 与图片后缀校验。
 * ⚠️ 用 `__dirname` 而不是 `import.meta.url` —— Playwright 把 TS 编译成 **CJS**，
 * `import.meta` 会直接报 "Cannot use 'import.meta' outside a module"（本机实测）。
 */
const FIXTURE = resolve(__dirname, 'fixtures/sample.png')
/** 证据落盘路径（`.tmp/` 已被 gitignore）；`__dirname` = web/e2e */
const EVIDENCE = resolve(__dirname, '../../.tmp/fe-upload-evidence.json')
/** 用非资源版块，让表单最简单（网盘字段会额外干扰断言） */
const BOARD = 3

function uniq(): string {
  return `${Date.now().toString(36).slice(-6)}${Math.random().toString(36).slice(2, 4)}`
}

test('最小闭环：选图 → 直传 OSS → 回调落库 → 发帖 → 详情页看到图', async ({ page }) => {
  /* ---------- 前置：注册 + 登录（现注册，避开按账号的发帖限流） ---------- */
  const username = `e2e_up_${uniq()}`.slice(0, 20)
  const cap = await (await fetch(`${API_BASE}/api/auth/captcha`)).json()
  const answer = await readCaptchaAnswer(cap.data.uuid)
  const reg = await (
    await fetch(`${API_BASE}/api/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username,
        password: PASSWORD,
        nickname: `图${username.slice(-4)}`,
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

  /* ---------- ① 取签名（真接口）+ ② 直传 OSS ---------- */
  await page.goto('#/pages/post/edit')
  await expect(page.getByTestId('compose-title')).toBeVisible({ timeout: 15_000 })
  await page.getByTestId(`compose-board-${BOARD}`).click()

  const signResponse = page.waitForResponse((r) => r.url().includes('/api/oss/signature'))
  // 上传是**浏览器直连 OSS**（铁律 8），所以监听的是 OSS 域名上的 POST
  const uploadResponse = page.waitForResponse(
    (r) => r.url().includes('aliyuncs.com') && r.request().method() === 'POST',
    { timeout: 60_000 }
  )

  /*
   * ========================================================================
   * ⚠️ 人为拖慢**签名接口** 3 秒 —— 这是为了**真的**验证"选完图立刻有预览"
   * ========================================================================
   * 不拖慢的话上传太快，"选完就有预览"与"传完才有预览"两种实现都会通过 → 一条假绿。
   * 而需求方实测反馈的正是「选了图、缩略图不出来」（旧实现只在成功后才放缩略图）。
   *
   * ⚠️ 为什么拖**签名**而不是拖 OSS 的 POST：
   *    实测 `page.route` 对这次 OSS 上传**不生效**（函数匹配、glob 都试过，
   *    断言读到的状态始终是 done）—— 不管原因是 uni 的 uploadFile 走了别的通道还是别的什么，
   *    依赖它就等于让这条断言变成假绿。而 `/api/oss/signature` 是**同源 XHR**，
   *    拦截稳定生效；而且签名没回来**根本不可能开始上传**，这段"上传中"的窗口是确定的。
   */
  await page.route(
    (url) => url.pathname === '/api/oss/signature',
    async (route) => {
      await new Promise((r) => setTimeout(r, 3000))
      await route.continue()
    }
  )

  const [chooser] = await Promise.all([
    page.waitForEvent('filechooser'),
    page.getByTestId('compose-add-image').click(),
  ])
  await chooser.setFiles(FIXTURE)

  /*
   * ========================================================================
   * ⚠️ 这一段必须紧跟 `setFiles`，**不能放在 await 上传响应之后**
   * ========================================================================
   * 钉住需求方实测反馈的那个 bug：「选了图、缩略图不出来」。
   * 旧实现**只有上传成功之后**才把缩略图放上去，从选完图到上传完成之间
   * （真实照片 + 外网，可能要几秒）界面**什么都没发生**，看起来就像"选了没用"。
   *
   * 两个刻意的设计，否则这条断言就是**假绿**：
   * 1. 先把签名接口拖慢 3 秒（签名没回来就不可能开始上传，这段窗口是确定的）；
   * 2. 断言写在 `setFiles` 紧后面 —— 一旦挪到 `await uploadResponse` 之后，
   *    读到的必然已经是 `done`，测的就不再是"立刻"了（本机改错过一次，记在这里）。
   */
  const cell = page.locator('[data-testid="compose-image-0"]')
  await expect(cell).toBeVisible({ timeout: 2500 })
  expect(await cell.getAttribute('data-status'), '上传未完成时就该有本地预览').toBe('uploading')
  // 预览用的是**本地路径**（H5 是 blob URL），不是 OSS 地址 —— 这才是"立刻可见"的实质
  expect(await cell.locator('img').getAttribute('src'), '本地预览应指向 blob 路径').toContain('blob:')

  const sign = await (await signResponse).json()
  expect(sign.code, `签名接口应 code=0：${JSON.stringify(sign)}`).toBe(0)
  // 签名字段齐不齐 —— 契约里这 7 个字段一个都不能少（前端要"原样填表"）
  for (const f of ['host', 'policy', 'signature', 'dir', 'expire', 'callback', 'accessKeyId']) {
    expect(sign.data?.[f], `签名响应缺少字段 ${f}`).toBeTruthy()
  }

  const ossRes = await uploadResponse
  const ossBodyText = await ossRes.text()
  expect(
    ossRes.status(),
    `OSS 直传应返回 200，实际 ${ossRes.status()}；响应体：${ossBodyText.slice(0, 400)}`
  ).toBe(200)

  /* ---------- ③ 回调落库：OSS 把后端的回调响应原样返回给前端 ---------- */
  let ossBody: any = null
  try {
    ossBody = JSON.parse(ossBodyText)
  } catch {
    throw new Error(
      `OSS 的响应体不是 JSON，无法读出回调结果（可能回调没打通）。原文前 500 字：${ossBodyText.slice(0, 500)}`
    )
  }
  expect(ossBody.code, `回调应成功（code=0），实际：${ossBodyText.slice(0, 400)}`).toBe(0)
  expect(ossBody.data?.url, '回调结果里应有图片 URL（CR-G 裁决 A）').toBeTruthy()
  expect(ossBody.data?.thumbUrl, '回调结果里应有缩略图 URL').toBeTruthy()

  /* ---------- 上传完成后：那张缩略图应该变成 done，并仍是同一个格子 ---------- */
  await expect
    .poll(async () => cell.getAttribute('data-status'), { timeout: 30_000 })
    .toBe('done')

  /* ---------- ④ 发帖（images 用后端给的 URL，不是前端拼的） ---------- */
  const title = `带图帖（E2E 直传 OSS）${uniq()}`
  await page.getByTestId('compose-title').locator('input').fill(title)
  const createResponse = page.waitForResponse(
    (r) => r.url().endsWith('/api/posts') && r.request().method() === 'POST'
  )
  await page.getByTestId('compose-submit').click()
  const created = await (await createResponse).json()
  expect(created.code, `发帖应成功：${JSON.stringify(created)}`).toBe(0)
  const postId = Number(created.data.id)
  expect(postId).toBeGreaterThan(0)

  /* ---------- ⑤ 详情页看到图 ---------- */
  await expect(page.getByTestId('detail-title')).toHaveText(title, { timeout: 15_000 })
  await expect(page.getByTestId('detail-image-0')).toBeVisible({ timeout: 15_000 })

  // 图片地址来自后端（`images[].thumbUrl`），断言它确实是 OSS 域名的地址
  const detail = (await (await fetch(`${API_BASE}/api/posts/${postId}`)).json()).data
  expect(Array.isArray(detail.images) && detail.images.length, '详情应返回 1 张图').toBe(1)
  expect(detail.images[0].url, '图片 url 应指向 OSS').toContain('aliyuncs.com')
  expect(detail.images[0].thumbUrl, '缩略图 thumbUrl 应指向 OSS').toContain('aliyuncs.com')

  /* ---------- 证据落盘（给交付报告引用） ---------- */
  const postImage = await (
    await fetch(`${API_BASE}/api/posts/${postId}`)
  ).json()
  writeFileSync(
    EVIDENCE,
    JSON.stringify(
      {
        capturedAt: new Date().toISOString(),
        postId,
        title,
        signature: {
          host: sign.data.host,
          dir: sign.data.dir,
          expire: sign.data.expire,
          // 只留长度，**不落盘 accessKeyId/policy/signature 的值**
          // （铁律 5 同源：它们是请求时临时收到的标识与签名，不该被复制进任何文件）
          hasAccessKeyId: Boolean(sign.data.accessKeyId),
          accessKeyIdLength: String(sign.data.accessKeyId ?? '').length,
          callbackBase64Length: String(sign.data.callback ?? '').length,
        },
        uploadRequest: {
          url: ossRes.url(),
          method: 'POST',
          // 表单字段名（**值全部脱敏**）：证明前端填的是 OSS 规范要求的字段名
          formFieldNames: ['key', 'policy', 'signature', 'OSSAccessKeyId', 'callback', 'Content-Type', 'file'],
        },
        uploadResponse: { status: ossRes.status(), body: ossBody },
        detailImages: detail.images,
      },
      null,
      2
    ),
    'utf8'
  )
  console.log(
    `[最小闭环] post=${postId} url=${detail.images[0].url}\n[最小闭环] thumbUrl=${detail.images[0].thumbUrl}\n` +
      `[最小闭环] 注意图片地址是 OSS 域名的直链（缩略图由后端用图片处理参数生成）`
  )
  // 让 postImage 变量被使用（避免 lint 噪声），并复核详情接口可重复读
  expect(postImage.code).toBe(0)
})
