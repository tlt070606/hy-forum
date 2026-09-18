/**
 * 上传探针：走一遍「选图 → 上传」，把**每一步的可见结果**打出来。
 *
 * 为什么需要它：用户反馈"发帖时选了图、缩略图不出来"，而我这边的小图（1.4KB）是能传上去的。
 * 所以要用**真实大小的图**（2~3MB）复现，并且把下面这些一次性测清：
 * - OSS 的响应状态与响应体
 * - 页面上有没有出现缩略图 / 错误文案（分别读 testid）
 * - 控制台有没有报错
 *
 * 用法：
 *   PROBE_FIXTURE=web/e2e/fixtures/sample-large.png node scripts/upload-probe.mjs
 */
import { chromium } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const BASE = process.env.PROBE_BASE || 'http://127.0.0.1:5173'
const API = process.env.PROBE_API || 'http://127.0.0.1:8080'
const FIXTURE = resolve(process.env.PROBE_FIXTURE || 'e2e/fixtures/sample-large.png')
const SIZE = readFileSync(FIXTURE).length

/** 造一个能登录的账号：直接登录已有的 e2e 账号（注册要过验证码，探针里没必要） */
async function login(username) {
  const res = await fetch(`${API}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: 'Test1234abc' }),
  })
  const body = await res.json()
  if (body.code !== 0) throw new Error(`登录失败：${JSON.stringify(body)}`)
  return body.data.token
}

const token = await login(process.env.PROBE_USER || 'e2e_m3_45kwuhiy')

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })

const consoleMsgs = []
page.on('console', (m) => consoleMsgs.push(`[${m.type()}] ${m.text()}`))
page.on('pageerror', (e) => consoleMsgs.push(`[pageerror] ${e.message}`))

const oss = []
page.on('response', async (r) => {
  if (r.url().includes('aliyuncs.com')) {
    let body = ''
    try {
      body = (await r.text()).slice(0, 300)
    } catch {
      body = '(读不到)'
    }
    oss.push(`${r.status()} ${r.request().method()} ${r.url().slice(0, 110)}\n      body: ${body}`)
  }
})

// 先把 token 塞进 localStorage（页面用 hy:token 持久化，见 utils/storage.ts）
await page.goto(`${BASE}/#/pages/auth/index?mode=login`)
await page.evaluate((t) => window.localStorage.setItem('hy:token', t), token)

await page.goto(`${BASE}/#/pages/post/edit`)
await page.waitForTimeout(2500)
await page.getByTestId('compose-board-3').click()
await page.waitForTimeout(300)

console.log(`fixture = ${FIXTURE}  (${(SIZE / 1024 / 1024).toFixed(2)} MB)`)

/** 选图（uni.chooseImage 会触发一个隐藏的 file input） */
const [chooser] = await Promise.all([
  page.waitForEvent('filechooser', { timeout: 15000 }),
  page.getByTestId('compose-add-image').click(),
])

/*
 * `PROBE_SYNTH_MB` 会给一个**合成文件**（指定字节数的零 + 指定的名字与 MIME）。
 * 为什么需要它：真实的大图不好造（纯色 PNG 压得很小），但"超过 5MB 上限"这条路径必须测到 ——
 * 而上限是由**文件字节数**决定的，所以合成一个 6MB 的 buffer 就够了。
 */
const synthMb = Number(process.env.PROBE_SYNTH_MB || 0)
if (synthMb > 0) {
  await chooser.setFiles({
    name: process.env.PROBE_SYNTH_NAME || 'big.png',
    mimeType: process.env.PROBE_SYNTH_MIME || 'image/png',
    buffer: Buffer.alloc(Math.round(synthMb * 1024 * 1024)),
  })
  console.log(`已选择**合成**文件：${synthMb} MB, name=${process.env.PROBE_SYNTH_NAME || 'big.png'}`)
} else {
  await chooser.setFiles(FIXTURE)
  console.log(`已选择文件 ${FIXTURE}（${(SIZE / 1024).toFixed(0)} KB），等待上传结果…`)
}

// 给足时间（真实大小的图 + 外网）
for (let i = 0; i < 12; i++) {
  await page.waitForTimeout(2500)
  const thumb = await page.getByTestId('compose-uploaded-image-0').count()
  const err = await page
    .getByTestId('compose-upload-error')
    .textContent()
    .catch(() => null)
  if (thumb > 0 || err) break
}

const thumbCount = await page.getByTestId('compose-uploaded-image-0').count()
const addText = await page.getByTestId('compose-add-image').textContent().catch(() => '')
const errText = await page
  .getByTestId('compose-upload-error')
  .textContent()
  .catch(() => null)

console.log('--- OSS 响应 ---')
oss.forEach((s) => console.log('  ' + s))
console.log('--- 页面结果 ---')
console.log(`  缩略图 testid 数 = ${thumbCount}`)
console.log(`  添加按钮文案     = ${JSON.stringify((addText || '').trim())}`)
console.log(`  上传错误文案     = ${JSON.stringify((errText || '').trim())}`)
console.log('--- 控制台（最后 12 条）---')
consoleMsgs.slice(-12).forEach((m) => console.log('  ' + m))

await browser.close()
