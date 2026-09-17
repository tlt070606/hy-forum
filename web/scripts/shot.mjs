/**
 * 截图探针（**不是测试用例**，故不叫 `*.spec.ts`）。
 *
 * 用途：改完版式后先自己看一眼再交付。Playwright 的 `testMatch` 只收 `*.spec.ts`，
 * 所以本文件不会被 `npx playwright test` 当作用例跑。
 *
 * 用法：`node web/scripts/shot.mjs`
 * 前置：前端 dev server 在 5173、后端在 8080。
 */
import { chromium } from '@playwright/test'
import { mkdirSync } from 'node:fs'

const BASE = process.env.SHOT_BASE || 'http://127.0.0.1:5173'
const OUT = process.env.SHOT_OUT || '.tmp/ref'
const ROUTE = process.env.SHOT_ROUTE || '#/pages/index/index'
/**
 * 文件名前缀。
 * ⚠️ 必须显式给（或用 SHOT_ROUTE 推导）：早先写死成 `home-`，
 * 结果截详情页时**把首页的截图覆盖掉了** —— 一个"看起来成功了"的假证据。
 */
const NAME = process.env.SHOT_NAME || (ROUTE.includes('detail') ? 'detail' : 'home')

/** 要拍的视口：桌面三栏 / 桌面窄一点 / 移动单栏 */
const VIEWPORTS = [
  { name: 'desktop', width: 1920, height: 1080 },
  { name: 'laptop', width: 1280, height: 900 },
  { name: 'mobile', width: 420, height: 900 },
]

mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome' })
const errors = []

for (const vp of VIEWPORTS) {
  const page = await browser.newPage({ viewport: { width: vp.width, height: vp.height } })
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(`[${vp.name}] console: ${msg.text()}`)
  })
  page.on('pageerror', (err) => errors.push(`[${vp.name}] pageerror: ${err.message}`))
  // 记录 4xx/5xx 的**具体 URL**：只写"Failed to load resource"是查不出根因的
  page.on('response', (res) => {
    if (res.status() >= 400) errors.push(`[${vp.name}] http ${res.status()} ${res.url()}`)
  })

  await page.goto(`${BASE}/${ROUTE}`, { waitUntil: 'domcontentloaded' })
  // 等接口回来 + 首屏渲染稳定（信息流是 onShow 里请求的）
  await page.waitForTimeout(3500)
  await page.screenshot({ path: `${OUT}/${NAME}-${vp.name}.png`, fullPage: vp.name !== 'desktop' })
  console.log(`shot: ${OUT}/${NAME}-${vp.name}.png`)
  await page.close()
}

await browser.close()

if (errors.length) {
  console.log('--- 页面错误 ---')
  errors.forEach((e) => console.log(e))
} else {
  console.log('--- 无 console error / pageerror ---')
}
