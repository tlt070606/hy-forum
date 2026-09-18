/**
 * 参考站抓取探针：登录 topic-bridge-network.nocode.host 并把关键页面截下来。
 *
 * 用途：需求方多次让我"照这个站做"，而我的 shell 没有出网（**浏览器有**），
 * 所以只能这样把它拿下来看。登录页带滑块人机验证，这里先看它到底卡在哪一步
 * （把请求/响应与控制台都打出来，而不是猜）。
 *
 * 用法：node scripts/refsite-probe.mjs
 */
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'

const SITE = 'https://topic-bridge-network.nocode.host'
const USER = process.env.REF_USER || 'adm123'
const PASS = process.env.REF_PASS || '123456'
const OUT = process.env.REF_OUT || '.tmp/refsite'

mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome' })
const page = await browser.newPage({ viewport: { width: 420, height: 900 } })

const logs = []
page.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
page.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))
const net = []
page.on('response', (r) => {
  const u = r.url()
  if (u.startsWith('data:')) return
  net.push(`${r.status()} ${r.request().method()} ${u.slice(0, 150)}`)
})

await page.goto(`${SITE}/#/`, { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(4000)
await page.screenshot({ path: `${OUT}/s0-login.png` })
console.log('已打开登录页')

/* 找出页面上的输入框（按 placeholder 定位，不依赖框架生成的类名） */
const inputs = await page.evaluate(() =>
  Array.from(document.querySelectorAll('input')).map((i) => ({
    type: i.type,
    placeholder: i.placeholder,
    name: i.name,
  }))
)
console.log('输入框：', JSON.stringify(inputs))

try {
  await page.getByPlaceholder(/姓名/).first().fill(USER)
  await page.getByPlaceholder(/密码/).first().fill(PASS)
  console.log('已填用户名与密码')
} catch (e) {
  console.log('填表失败：', e.message)
}

/*
 * 勾选协议。
 * ⚠️ 这里点「我已阅读并同意」**弹出的是协议弹窗**（不是直接勾上）——
 * 第一次写漏了这一步，于是登录按钮被弹窗的遮罩挡住，报的是
 * "div.fixed.inset-0.z-50 拦截了指针事件"（一个与登录毫无关系的报错，很误导）。
 * 正确流程：点那一行 → 弹窗里再点一次「我已阅读并同意」→ 弹窗关闭且勾上。
 */
try {
  await page.locator('text=我已阅读并同意').first().click()
  await page.waitForTimeout(1200)
  // 弹窗里的确认按钮是最后一个同名文字
  await page.locator('text=我已阅读并同意').last().click()
  await page.waitForTimeout(800)
  console.log('已勾选协议（经弹窗确认）')
} catch (e) {
  console.log('勾选协议失败：', e.message)
}

await page.screenshot({ path: `${OUT}/s1-filled.png` })

/* 试着拖一下滑块（人机验证）。不知道缺口位置，因此只是"拖一下"看会不会被拒 */
try {
  const slider = page.locator('[class*="slider"], [class*="captcha"]').first()
  const box = await slider.boundingBox()
  if (box) {
    await page.mouse.move(box.x + 10, box.y + box.height / 2)
    await page.mouse.down()
    for (let i = 1; i <= 12; i++) {
      await page.mouse.move(box.x + 10 + i * 20, box.y + box.height / 2)
      await page.waitForTimeout(40)
    }
    await page.mouse.up()
    await page.waitForTimeout(1500)
    console.log('已尝试拖动滑块')
  } else {
    console.log('没找到滑块元素（跳过拖动）')
  }
} catch (e) {
  console.log('拖动滑块失败：', e.message)
}

/* 直接点登录，看它是不是非要先过滑块 */
try {
  const btn = page.getByText('登录', { exact: true }).last()
  await btn.click()
  await page.waitForTimeout(3500)
  console.log('已点登录')
} catch (e) {
  console.log('点登录失败：', e.message)
}
await page.screenshot({ path: `${OUT}/s2-after-login.png` })

const url = page.url()
const bodyText = (await page.locator('body').innerText().catch(() => '')).slice(0, 400)
console.log('--- 结果 ---')
console.log('URL =', url)
console.log('页面文字片段 =', JSON.stringify(bodyText.replace(/\n+/g, ' | ').slice(0, 300)))

console.log('--- 网络请求（最后 15 条）---')
net.slice(-15).forEach((n) => console.log('  ' + n))
console.log('--- 控制台（最后 10 条）---')
logs.slice(-10).forEach((l) => console.log('  ' + l))

await browser.close()
